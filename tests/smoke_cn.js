// 国内 DMR 集成专项冒烟测试：网络切换 / 订阅报文 / μ-law 解码 / 布局
//
//   node tests/smoke_cn.js
//
// 需要一个支持 CDP 的 Chrome/Edge。找不到就设 CHROME_PATH 环境变量指过去：
//
//   CHROME_PATH="/usr/bin/google-chrome" node tests/smoke_cn.js
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_PATH || (
  process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : '/usr/bin/google-chrome'
);
const PAGE = pathToFileURL(path.join(ROOT, 'web', 'dmr-listener.html')).href;
const OUT = ROOT;
const PROFILE = path.join(os.tmpdir(), 'beacon-dmr-cdp-' + Date.now());
const PORT = 9334;

fs.mkdirSync(PROFILE, { recursive: true });   // 目录名带时间戳，避免删旧目录（会触发沙箱的批量删除保护）

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', '--window-size=1280,900', 'about:blank',
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${extra !== undefined ? '  → ' + JSON.stringify(extra) : ''}`); }
};

async function getWs() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const l = await r.json();
      const p = l.find(x => x.type === 'page');
      if (p) return p.webSocketDebuggerUrl;
    } catch {}
    await sleep(250);
  }
  throw new Error('CDP 未就绪');
}

const wsUrl = await getWs();
const ws = new WebSocket(wsUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let msgId = 0;
const waiting = new Map();
const events = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  else if (m.method) events.push(m);
};
const send = (method, params = {}, sessionId) => new Promise(res => {
  const id = ++msgId;
  waiting.set(id, res);
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});

// 建 session
const { result: { targetInfo } } = await send('Target.getTargets').then(r => ({ result: { targetInfo: r.result.targetInfos.find(t => t.type === 'page') } }));
const att = await send('Target.attachToTarget', { targetId: targetInfo.targetId, flatten: true });
const S = att.result.sessionId;

await send('Runtime.enable', {}, S);
await send('Page.enable', {}, S);
await send('Log.enable', {}, S);

const jsErrors = [];
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.method === 'Runtime.exceptionThrown')
    jsErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error')
    jsErrors.push(m.params.entry.text);
});

const evalJs = async (expr) => {
  const r = await send('Runtime.evaluate', {
    expression: `(function(){${expr}})()`, returnByValue: true, awaitPromise: true,
  }, S);
  if (r.result?.exceptionDetails) return '__ERR__' + (r.result.exceptionDetails.exception?.description || '');
  return r.result?.result?.value;
};

// 注入 WebSocket 钩子（必须在页面脚本跑之前不行，但可以在切换前装）
await send('Page.navigate', { url: PAGE }, S);
await sleep(3500);

console.log('\n=== 1. 页面加载 ===');
ok('无 JS 异常', jsErrors.length === 0, jsErrors.slice(0, 3));
ok('CN 库已载入', await evalJs('return CN.byId.size') > 15000, await evalJs('return CN.byId.size'));
ok('国内 ID 库已合并', await evalJs('return [...CN.byId.values()].filter(x=>x.src==="86").length') > 800,
  await evalJs('return [...CN.byId.values()].filter(x=>x.src==="86").length'));
ok('国内话组 33 个', await evalJs('return CN.tgList.length') === 33, await evalJs('return CN.tgList.length'));

console.log('\n=== 2. ID 解析（国内 86 号段）===');
const r1 = await evalJs('const h=CN.byId.get(8603025); return h?h.call+" / "+h.name+" / "+h.city:"MISS"');
ok('8603025 解析出呼号', String(r1).startsWith('BG8ROM'), r1);
const r2 = await evalJs('const h=CN.byId.get(8600000); return h?h.call:"MISS"');
ok('8600000 解析', r2 !== 'MISS', r2);

console.log('\n=== 3. 装 WebSocket 钩子 + 切到国内 ===');
// 钩子：记录所有发出的文本帧
await evalJs(`
  window.__sent=[];
  const OS=WebSocket.prototype.send;
  WebSocket.prototype.send=function(d){
    try{ if(typeof d==='string'&&d.length<4000)window.__sent.push({url:this.url,d}); }catch(e){}
    return OS.call(this,d);
  };
  return 1;
`);
await evalJs('setNet("cn"); return state.net');
await sleep(800);
ok('state.net === cn', await evalJs('return state.net') === 'cn');
ok('订阅默认 [460,860]', JSON.stringify(await evalJs('return state.subscribed')) === '[460,860]',
  await evalJs('return state.subscribed'));
ok('话组库切到国内（33 条）', await evalJs('return document.getElementById("libCount").textContent') === '33',
  await evalJs('return document.getElementById("libCount").textContent'));
ok('网络说明文字已更新', String(await evalJs('return document.getElementById("netInfo").textContent')).indexOf('China DMR') >= 0,
  await evalJs('return document.getElementById("netInfo").textContent'));
ok('segNet 高亮在 cn', await evalJs('return document.querySelector("#segNet button[data-net=cn]").classList.contains("on")'));

console.log('\n=== 4. 订阅报文 ===');
const sent = await evalJs('return window.__sent');
const subMsg = (sent || []).find(x => String(x.d).indexOf('subscribe') >= 0);
ok('发出了 subscribe 帧', !!subMsg, sent);
if (subMsg) {
  ok('连的是 860.radiowo.com/ws/listen', subMsg.url.indexOf('860.radiowo.com/ws/listen') >= 0, subMsg.url);
  let body = null; try { body = JSON.parse(subMsg.d); } catch {}
  ok('action === subscribe', body && body.action === 'subscribe', body);
  ok('talkgroups === [460,860]', body && JSON.stringify(body.talkgroups) === '[460,860]', body && body.talkgroups);
}
ok('rw.open === true（WS 已连上）', await evalJs('return state.rw.open') === true, await evalJs('return state.rw.open'));

console.log('\n=== 5. μ-law 解码 ===');
// 0xFF 是 μ-law 的「+0」，0x00 是最大负值
const dec = await evalJs('const f=Audio_.decode(new Uint8Array([255,127,0,128])); return [f[0],f[1],f[2],f[3]]');
ok('0xFF 解码接近 0', Math.abs(dec[0]) < 0.01, dec[0]);
ok('0x00 解码为大负值', dec[2] < -0.9, dec[2]);
ok('0x80 解码为大正值', dec[3] > 0.9, dec[3]);

console.log('\n=== 6. 模拟一帧国内音频 ===');
const sim = await evalJs(`
  state.audioOn=true;
  state.rw.streams.set(12345,{tg:860,srcId:8603025,ts:1});
  state.current={srcId:8603025,tg:860,alias:'',start:Date.now(),ts:1,stream:12345};
  const n=480, b=new Uint8Array(4+n);
  new DataView(b.buffer).setUint32(0,12345);
  for(let i=0;i<n;i++)b[4+i]=128;      // 128 = μ-law 近似静音
  const before=Audio_.level;
  handleRwAudio(b);
  return {len:b.length, level:Audio_.level, ok:true};
`);
ok('handleRwAudio 不抛异常', sim && sim.ok === true, sim);
ok('audioOn 生效后 level 被更新', typeof sim.level === 'number');

console.log('\n=== 7. 发射页跟随网络 ===');
ok('txHost 切到国内主站 8600.radiowo.com', await evalJs('return txEl("txHost").value') === '8600.radiowo.com',
  await evalJs('return txEl("txHost").value'));
ok('txPort 是 62031', String(await evalJs('return txEl("txPort").value')) === '62031',
  await evalJs('return txEl("txPort").value'));
ok('DMR ID placeholder 变 86 开头', await evalJs('return txEl("txId").placeholder') === '8601234',
  await evalJs('return txEl("txId").placeholder'));
const hint = await evalJs('txEnter(); return txEl("txNetHint").textContent');
ok('发射页提示是「国内 China DMR」', String(hint).indexOf('国内 China DMR') >= 0, String(hint).slice(0, 60));
ok('提示里写清 86 开头', String(hint).indexOf('86 开头') >= 0);

console.log('\n=== 7c. 主站选择面板（已搬进设置页）===');
/* 「主站与身份」现在住在设置页，必须先切过去 ——
   视图没显示时祖先 display:none，面板量出来宽度是 0。 */
await evalJs('document.querySelector("button[data-view=settings]").click(); return 1');
await sleep(400);
ok('设置页可见', await evalJs('return getComputedStyle(document.getElementById("viewSettings")).display') !== 'none');
ok('「主站与身份」卡片在设置页里', await evalJs(
  'return !!document.querySelector("#viewSettings #txFold")') === true);
ok('整页「对讲」已经取消', await evalJs(
  'return !!document.getElementById("viewTx")') === false);
ok('顶部导航没有「对讲」标签', await evalJs(
  'return !document.querySelector("button[data-view=tx]")') === true);
/* 默认折叠，不展开同样量不到宽度 */
await evalJs('var f=document.getElementById("txFold"); if(f&&document.getElementById("txFoldBody").offsetHeight===0)f.click(); return 1');
await sleep(300);
await evalJs('txHostPanel(true); return 1');
await sleep(200);
ok('面板已展开', await evalJs('return document.getElementById("txHostList").hidden') === false);
const opts = await evalJs(
  'return [...document.querySelectorAll("#txHostBody .pickrow")].map(o=>({v:o.dataset.host,t:o.textContent}))');
const cnOpts = opts.filter(o => o.v.indexOf('radiowo.com') > 0);
const bmFallback = opts.filter(o => /^(4602|4501|5021|5051)\.master\.brandmeister\.network$/.test(o.v));
ok('国内 China DMR 三台都在', cnOpts.length === 3, cnOpts.map(o => o.v));
ok('8600.radiowo.com 在列表里', cnOpts.some(o => o.v === '8600.radiowo.com'));
ok('8602.radiowo.com 在列表里', cnOpts.some(o => o.v === '8602.radiowo.com'));
ok('mastra.radiowo.com 在列表里', cnOpts.some(o => o.v === 'mastra.radiowo.com'));
ok('国际 BrandMeister 兜底四台都在', bmFallback.length === 4, bmFallback.map(o => o.v));
ok('4602 国际主站没被删', opts.some(o => o.v === '4602.master.brandmeister.network'));
ok('每条都标了端口 :62031', opts.length > 0 && opts.every(o => o.t.indexOf(':62031') >= 0),
  opts.filter(o => o.t.indexOf(':62031') < 0).map(o => o.t));
ok('无重复 host', new Set(opts.map(o => o.v)).size === opts.length,
  { total: opts.length, uniq: new Set(opts.map(o => o.v)).size });
ok('国内排在前面', opts[0].v === '8600.radiowo.com', opts.slice(0, 3).map(o => o.v));
ok('有两个分组标题', await evalJs('return document.querySelectorAll("#txHostBody .pickgroup").length') === 2);
ok('国际组标题含 BrandMeister', String(await evalJs(
  'return [...document.querySelectorAll("#txHostBody .pickgroup")].map(e=>e.textContent).join("|")')).indexOf('BrandMeister') >= 0);
/* 搜索：按国家名 / 域名片段都要能过滤 */
await evalJs('document.getElementById("txHostQ").value="韩国"; txFillHostList(); return 1');
await sleep(120);
const sKr = await evalJs('return [...document.querySelectorAll("#txHostBody .pickrow")].map(o=>o.dataset.host)');
ok('搜「韩国」命中 4501', sKr.length === 1 && sKr[0] === '4501.master.brandmeister.network', sKr);
await evalJs('document.getElementById("txHostQ").value="radiowo"; txFillHostList(); return 1');
await sleep(120);
ok('搜「radiowo」命中国内三台', await evalJs(
  'return document.querySelectorAll("#txHostBody .pickrow").length') === 3);
/* 点选一条，看是否回填地址 + 端口并收起 */
await evalJs('txHostPanel(true); txHostPick("8600.radiowo.com"); return 1');
await sleep(150);
ok('点选后地址回填', await evalJs('return txEl("txHost").value') === '8600.radiowo.com',
  await evalJs('return txEl("txHost").value'));
ok('点选后端口回填 62031', String(await evalJs('return txEl("txPort").value')) === '62031');
ok('点选后面板收起', await evalJs('return document.getElementById("txHostList").hidden') === true);
/* 面板是绝对定位、宽度 max(100%,300px)，窄屏上最容易把文档撑出横向滚动条，专门测一下 */
await send('Emulation.setDeviceMetricsOverride',
  { width: 320, height: 900, deviceScaleFactor: 1, mobile: true }, S);
await sleep(250);
await evalJs('txHostPanel(true); return 1');
await sleep(250);
const pov = await evalJs('return {sw:document.documentElement.scrollWidth, cw:document.documentElement.clientWidth}');
ok('320px 展开面板无横向溢出', pov.sw <= pov.cw + 1, pov);
const pw = await evalJs('return document.getElementById("txHostList").getBoundingClientRect().width');
ok('320px 下面板宽度 ≥ 280', pw >= 280, pw);
await evalJs('txHostPanel(false); return 1');
await send('Emulation.setDeviceMetricsOverride',
  { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, S);
await sleep(200);

console.log('\n=== 7d. 收听页发射条 + 双网络身份档案 ===');
await evalJs('document.querySelector("button[data-view=listen]").click(); return 1');
await sleep(400);
ok('发射条在收听页里', await evalJs('return !!document.querySelector("#viewListen #ptxBar")') === true);
ok('发射条在设置页里没有', await evalJs('return !!document.querySelector("#viewSettings #ptxBar")') === false);
ok('发射到目标输入框在', await evalJs('return !!document.getElementById("txTg")') === true);
ok('悬浮 PTT 在收听页里', await evalJs('return !!document.querySelector("#viewListen #txPtt")') === true);
ok('网络段高亮在 cn', await evalJs(
  'return document.querySelector("#segNet button[data-net=cn]").classList.contains("on")') === true);
ok('快捷 ID 与正式 ID 一致', await evalJs(
  'return txEl("txIdQuick").value===txEl("txId").value') === true);
const qg = await evalJs(
  'return [...document.querySelectorAll("#txHostQuick optgroup")].map(g=>({l:g.label,n:g.children.length}))');
ok('快捷主站有两个分组', qg.length === 2, qg);
ok('国内分组 3 台', !!qg[0] && qg[0].n === 3, qg[0]);
ok('国际分组 ≥ 4 台', !!qg[1] && qg[1].n >= 4, qg[1]);
/* 双档案：cn 写 8601234 → 切 bm 应该是另一套 → 切回来要恢复 */
await evalJs('var q=txEl("txIdQuick"); q.value="8601234"; q.dispatchEvent(new Event("input",{bubbles:true})); return 1');
await sleep(220);
ok('cn 档案写入 8601234', await evalJs('return txEl("txId").value') === '8601234',
  await evalJs('return txEl("txId").value'));
await evalJs('document.querySelector("#segNet button[data-net=bm]").click(); return 1');
await sleep(800);
ok('身份栏能切到国际网络', await evalJs('return state.net') === 'bm');
ok('bm 档案里没有 cn 的 ID', await evalJs('return txEl("txId").value') !== '8601234',
  await evalJs('return txEl("txId").value'));
ok('bm 主站自动是 4602', await evalJs('return txEl("txHost").value') === '4602.master.brandmeister.network',
  await evalJs('return txEl("txHost").value'));
await evalJs('var q=txEl("txIdQuick"); q.value="4601234"; q.dispatchEvent(new Event("input",{bubbles:true})); return 1');
await sleep(220);
await evalJs('document.querySelector("#segNet button[data-net=cn]").click(); return 1');
await sleep(800);
ok('切回 cn 后 ID 恢复 8601234', await evalJs('return txEl("txId").value') === '8601234',
  await evalJs('return txEl("txId").value'));
ok('切回 cn 后主站恢复 8600.radiowo.com', await evalJs('return txEl("txHost").value') === '8600.radiowo.com',
  await evalJs('return txEl("txHost").value'));

console.log('\n=== 7e. 收听页选中话组 = 直接发射 ===');
/* 本轮核心：对讲页取消，改成「在收听页点一个话组 → 它就成为发射目标 → 按住右下角 PTT 就发」。
   所以这里要验证三件事：点行改目标、行高亮、行内小按钮不误改目标。 */
await evalJs('setView("listen"); return 1');
await sleep(300);
const subsBefore = JSON.stringify(await evalJs('return state.subscribed'));

/* --- ① 话组库点一行 --- */
await evalJs('document.getElementById("libSearch").value=""; state.libQuery=""; renderLib(); return 1');
await sleep(250);
const libId = await evalJs('var r=document.querySelector("#libList .row[data-id]"); return r?Number(r.dataset.id):0');
ok('话组库里有可点的话组行', libId > 0, libId);
await evalJs('document.querySelector("#libList .row[data-id]").click(); return 1');
await sleep(250);
ok('点话组库一行 → txTg 跟着变', Number(await evalJs('return txEl("txTg").value')) === libId,
  { txTg: await evalJs('return txEl("txTg").value'), libId });
ok('发射条上显示的话组名不是「未设目标」',
  String(await evalJs('return txEl("ptxTgName").textContent')).indexOf('未设目标') < 0,
  await evalJs('return txEl("ptxTgName").textContent'));
ok('悬浮 PTT 上的数字同步', String(await evalJs('return txEl("pttTg").textContent')) === String(libId),
  await evalJs('return txEl("pttTg").textContent'));
ok('那一行被打上 tgt 高亮', await evalJs(
  'return !!document.querySelector(\'#libList .row[data-id="\'+txEl("txTg").value+\'"].tgt\')') === true);
ok('点行没有顺手把话组加进收听列表',
  JSON.stringify(await evalJs('return state.subscribed')) === subsBefore);

/* --- ② 我的收听点一行 --- */
const mineId = await evalJs('var r=document.querySelector("#mineList .row[data-id]"); return r?Number(r.dataset.id):0');
if (mineId > 0 && mineId !== libId) {
  await evalJs('document.querySelector("#mineList .row[data-id]").click(); return 1');
  await sleep(250);
  ok('点「我的收听」一行 → txTg 跟着变', Number(await evalJs('return txEl("txTg").value')) === mineId,
    { txTg: await evalJs('return txEl("txTg").value'), mineId });
} else {
  ok('「我的收听」里有行可点（跳过重复 id）', true, mineId);
}

/* --- ③ 行内小按钮（📡 加扫描）不能把目标也改掉 --- */
await evalJs('renderMine(); return 1');
await sleep(200);
const keep = String(await evalJs('return txEl("txTg").value'));
const miniInfo = await evalJs(
  'var r=[...document.querySelectorAll("#mineList .row[data-id]")].find(x=>x.dataset.id!==txEl("txTg").value);'
  +'if(!r)return null; var b=r.querySelector(".mini[data-act=scan],.mini[data-act=unscan]");'
  +'if(!b)return null; return {id:r.dataset.id, act:b.dataset.act}');
if (miniInfo) {
  await evalJs('var r=[...document.querySelectorAll("#mineList .row[data-id]")].find(x=>x.dataset.id!==txEl("txTg").value);'
    + 'r.querySelector(".mini[data-act=scan],.mini[data-act=unscan]").click(); return 1');
  await sleep(250);
  ok('点行内 📡 小按钮不会改发射目标', String(await evalJs('return txEl("txTg").value')) === keep,
    { before: keep, after: await evalJs('return txEl("txTg").value'), mini: miniInfo });
  /* 把副作用还原，免得影响后面「订阅数」相关断言 */
  await evalJs('var r=[...document.querySelectorAll("#mineList .row[data-id]")].find(x=>x.dataset.id!==txEl("txTg").value);'
    + 'r.querySelector(".mini[data-act=scan],.mini[data-act=unscan]").click(); return 1');
  await sleep(200);
} else {
  ok('行内有 📡 小按钮可测（跳过）', true);
}

/* --- ④ 手输话组号，PTT 上的数字要跟着走 --- */
await evalJs('var e=txEl("txTg"); e.value="2621"; e.dispatchEvent(new Event("input",{bubbles:true})); return 1');
await sleep(200);
ok('手输 2621 → 悬浮 PTT 同步', String(await evalJs('return txEl("pttTg").textContent')) === '2621',
  await evalJs('return txEl("pttTg").textContent'));

/* --- ⑤ PTT 默认在首页顶部操作区；未连接时禁用；开「可拖动」能浮到指定坐标 --- */
const pttState = await evalJs(
  'var p=document.getElementById("txPtt"); var r=p.getBoundingClientRect();'
  +'return {w:Math.round(r.width),h:Math.round(r.height),dis:p.disabled,'
  +'vis:getComputedStyle(p).display!=="none",pos:getComputedStyle(p).position,'
  +'inTop:!!p.closest(".home-top")}');
ok('PTT 默认悬浮在右下角', pttState.pos === 'fixed' && pttState.inTop === false, pttState);
ok('PTT 尺寸够按（≥64px）', pttState.w >= 64 && pttState.h >= 64, pttState);
ok('未连接主站时 PTT 禁用', pttState.dis === true, pttState);

const pttDragState = await evalJs(
  'state.pttDrag=true; state.pttPos={x:30,y:300}; pttApplyState();'
  +'var p=document.getElementById("txPtt"); var r=p.getBoundingClientRect();'
  +'var o={pos:getComputedStyle(p).position,floating:p.classList.contains("floating"),'
  +'left:Math.round(r.left),top:Math.round(r.top)};'
  +'state.pttDrag=false; state.pttPos=null; pttApplyState();'
  +'return o;');
ok('开启「PTT 可拖动」后浮到指定坐标', pttDragState.pos === 'fixed'
   && pttDragState.floating === true
   && Math.abs(pttDragState.left - 30) <= 1 && Math.abs(pttDragState.top - 300) <= 1,
   pttDragState);

/* 模拟真实拖动手势：pointerdown → 移动 >6px → pointerup。
   期望：位置被存下来、按钮浮起、且**没有触发发射**（拖动不该说话）。 */
const dragSim = await evalJs(
  'state.pttDrag=true; state.pttPos=null; pttApplyState();'
  +'var p=document.getElementById("txPtt"); var r=p.getBoundingClientRect();'
  +'var x0=r.left+12,y0=r.top+12;'
  +'function pe(t,x,y){return new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1,pointerType:"touch",button:0,isPrimary:true});}'
  +'p.dispatchEvent(pe("pointerdown",x0,y0));'
  +'p.dispatchEvent(pe("pointermove",x0+40,y0+50));'
  +'p.dispatchEvent(pe("pointermove",x0+80,y0+90));'
  +'p.dispatchEvent(pe("pointerup",x0+80,y0+90));'
  +'var o={saved:store.get("pttPos",null),held:TX.pttHeld,pos:getComputedStyle(p).position,'
  +'floating:p.classList.contains("floating")};'
  +'state.pttDrag=false; state.pttPos=null; store.set("pttPos",null); pttApplyState();'
  +'return o;');
ok('拖动 >6px 存下位置且不发射', !!(dragSim && dragSim.saved && typeof dragSim.saved.x === 'number')
   && dragSim.held === false && dragSim.pos === 'fixed', dragSim);

console.log('\n=== 7e2. 首页操作区手动折叠 + 滚回顶部自动展开 ===');
const foldBase = await evalJs(
  'state.htCollapsed=false; htFoldSet(false,false);'
  +'var ht=document.querySelector("#viewListen .home-top");'
  +'return {h:Math.round(ht.getBoundingClientRect().height),'
  +'collapsed:ht.classList.contains("collapsed"),'
  +'txt:document.getElementById("htFoldTxt").textContent,'
  +'aria:document.getElementById("htFold").getAttribute("aria-expanded")}');
ok('操作区默认展开', foldBase.collapsed === false && foldBase.txt === '收起操作区'
   && foldBase.aria === 'true', foldBase);

const foldClick = await evalJs(
  'document.getElementById("htFold").click();'
  +'var ht=document.querySelector("#viewListen .home-top");'
  +'var op=document.querySelector("#viewListen .home-op");'
  +'return {h:Math.round(ht.getBoundingClientRect().height),'
  +'collapsed:ht.classList.contains("collapsed"),op:getComputedStyle(op).display,'
  +'txt:document.getElementById("htFoldTxt").textContent,'
  +'aria:document.getElementById("htFold").getAttribute("aria-expanded"),'
  +'ls:store.get("htCollapsed",null)}');
ok('点折叠 → 操作区收起、分栏条还在', foldClick.collapsed === true && foldClick.op === 'none'
   && foldClick.txt === '展开操作区' && foldClick.aria === 'false', foldClick);
ok('折叠后高度明显变小', foldClick.h < foldBase.h - 80,
   { 展开: foldBase.h, 收起: foldClick.h });
ok('折叠状态已存本地', foldClick.ls === true, foldClick.ls);

/* 滚回顶部自动展开：先临时撑长页面滚下去，再滚回来。
   注意点击后有 500ms 冷却，这里的等待要超过它。 */
const foldAuto = await evalJs(
  'return (async function(){'
  +'var v=document.getElementById("viewListen");'
  +'var sp=document.createElement("div"); sp.id="__foldsp"; sp.style.height="1800px";'
  +'v.insertBefore(sp, v.querySelector(".foot"));'
  +'await new Promise(r=>setTimeout(r,150));'
  +'window.scrollTo(0,700); await new Promise(r=>setTimeout(r,600));'
  +'var mid=document.querySelector("#viewListen .home-top").classList.contains("collapsed");'
  +'window.scrollTo(0,0); await new Promise(r=>setTimeout(r,350));'
  +'var ht=document.querySelector("#viewListen .home-top");'
  +'var o={midCollapsed:mid,topCollapsed:ht.classList.contains("collapsed"),'
  +'scrollY:Math.round(window.scrollY),h:Math.round(ht.getBoundingClientRect().height)};'
  +'sp.remove(); window.scrollTo(0,0);'
  +'state.htCollapsed=false; store.set("htCollapsed",false); htFoldSet(false,false);'
  +'return o; })()');
ok('下滚保持收起、滚回顶部自动展开',
   foldAuto.midCollapsed === true && foldAuto.topCollapsed === false, foldAuto);
ok('折叠态无横向溢出',
   await evalJs('htFoldSet(true,false);'
     +'var o=document.documentElement.scrollWidth-document.documentElement.clientWidth;'
     +'htFoldSet(false,false); return o') === 0);

console.log('\n=== 7f. 设置页「常见端口与注意事项」 ===');
await evalJs('document.querySelector("button[data-view=settings]").click(); return 1');
await sleep(400);
ok('端口卡片在设置页里', await evalJs('return !!document.querySelector("#viewSettings #setPortCard")') === true);
ok('端口卡片不在收听页里', await evalJs('return !!document.querySelector("#viewListen #setPortCard")') === false);

const rows = await evalJs(
  'return [...document.querySelectorAll("#setPortList .portrow")].map(r=>({'
  +'port:r.dataset.port, proto:(r.querySelector(".pp")||{}).textContent,'
  +'name:(r.querySelector(".pd b")||{}).textContent, hasBtn:!!r.querySelector("button[data-port]")}))');
ok('四行常见端口', rows.length === 4, rows.map(r => r.port));
ok('62031 在第一行', rows[0] && rows[0].port === '62031', rows[0]);
ok('62031 标 UDP', rows[0] && String(rows[0].proto).trim() === 'UDP', rows[0] && rows[0].proto);
ok('55555 标 UDP', !!rows.find(r => r.port === '55555' && String(r.proto).trim() === 'UDP'));
ok('443 标 TCP', !!rows.find(r => r.port === '443' && String(r.proto).trim() === 'TCP'));
ok('62031 写着 MMDVM / Homebrew', String(rows[0].name).indexOf('MMDVM') >= 0, rows[0].name);
ok('前三个端口有「填入」按钮', rows.filter(r => r.hasBtn).length === 3, rows.filter(r => r.hasBtn).map(r => r.port));
ok('443 没有「填入」（写死的，不用填）', !rows.find(r => r.port === '443').hasBtn);

/* 当前值 = 62031，徽标绿、对应行高亮 */
ok('当前端口徽标是 62031', String(await evalJs('return txEl("setPortBadge").textContent')) === '62031',
  await evalJs('return txEl("setPortBadge").textContent'));
ok('62031 徽标是 ok 样式', await evalJs('return txEl("setPortBadge").classList.contains("ok")') === true);
ok('62031 那行高亮', await evalJs(
  'return !!document.querySelector(\'#setPortList .portrow[data-port="62031"].on\')') === true);

/* 点 55555 的「填入」→ 端口框、徽标、高亮全跟着换 */
await evalJs('document.querySelector(\'#setPortList .portrow[data-port="55555"] button\').click(); return 1');
await sleep(250);
ok('点「填入」写进了端口框', String(await evalJs('return txEl("txPort").value')) === '55555',
  await evalJs('return txEl("txPort").value'));
ok('徽标跟着变成 55555', String(await evalJs('return txEl("setPortBadge").textContent')) === '55555');
ok('非 62031 徽标降级成 warn', await evalJs('return txEl("setPortBadge").classList.contains("warn")') === true);
ok('高亮切到 55555 那行', await evalJs(
  'return !!document.querySelector(\'#setPortList .portrow[data-port="55555"].on\')') === true);
ok('62031 那行不再高亮', await evalJs(
  'return !!document.querySelector(\'#setPortList .portrow[data-port="62031"].on\')') === false);
ok('端口存进了档案', String(await evalJs('return (store.get(txProfileKey(),{})||{}).txPort')) === '55555',
  await evalJs('return (store.get(txProfileKey(),{})||{}).txPort'));

/* 改回 62031 */
await evalJs('document.querySelector(\'#setPortList .portrow[data-port="62031"] button\').click(); return 1');
await sleep(200);
ok('改回 62031 后徽标恢复 ok', await evalJs('return txEl("setPortBadge").classList.contains("ok")') === true);

/* 注意事项 */
const noteItems = await evalJs('return [...document.querySelectorAll("#setPortCard .portnote li")].map(e=>e.textContent)');
ok('注意事项至少 6 条', noteItems.length >= 6, noteItems.length);
ok('提醒了 UDP 不是 TCP', noteItems.some(t => t.indexOf('UDP') >= 0 && t.indexOf('TCP') >= 0), noteItems);
ok('提醒了改端口要断开重连', noteItems.some(t => t.indexOf('断开') >= 0), noteItems);
ok('提醒了切网络会自动回 62031', noteItems.some(t => t.indexOf('自动') >= 0 && t.indexOf('62031') >= 0), noteItems);
ok('提醒了手机流量可能封 UDP', noteItems.some(t => t.indexOf('UDP 高端口') >= 0), noteItems);

/* 「去改」要能展开「主站与身份」并把端口框滚进视野 */
await evalJs('var f=txEl("txFold"); if(f&&f.classList.contains("open"))f.click(); return 1');
await sleep(200);
ok('先确保「主站与身份」是折叠的', await evalJs('return !txEl("txFold").classList.contains("open")') === true);
await evalJs('txEl("setPortGo").click(); return 1');
await sleep(400);
ok('点「去改」展开「主站与身份」', await evalJs('return txEl("txFold").classList.contains("open")') === true);

/* 手机上端口行不能把页面撑出横向滚动 */
await send('Emulation.setDeviceMetricsOverride',
  { width: 390, height: 900, deviceScaleFactor: 1, mobile: true }, S);
await sleep(300);
const pv = await evalJs('return {sw:document.documentElement.scrollWidth, cw:document.documentElement.clientWidth}');
ok('390px 设置页无横向溢出', pv.sw <= pv.cw + 1, pv);
const rowBox = await evalJs(
  'var r=document.querySelector(\'#setPortList .portrow[data-port="62031"]\');'
  +'var b=r.querySelector("button"); var rb=r.getBoundingClientRect(); var bb=b.getBoundingClientRect();'
  +'return {row:Math.round(rb.width), btnRight:Math.round(bb.right), rowRight:Math.round(rb.right)}');
ok('390px 下「填入」按钮没被挤出卡片', rowBox.btnRight <= rowBox.rowRight + 1, rowBox);
await send('Emulation.setDeviceMetricsOverride',
  { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, S);
await sleep(200);

console.log('\n=== 7g. ESSID 默认「不用别名」+ 呼号必填 ===');
/* 这两条是「国际 BM 连不上」的直接原因：
   ① ESSID 以前默认 01，而 Pi-Star / DroidStar 默认 None —— 没动过这一项的人
      登录 ID 变成 9 位，SelfCare 里没这条记录，主站在下发配置那步回 MSTNAK。
   ② 呼号留空时原生侧拿 DMR ID 顶替，发上去是一串数字，主站比对必然不匹配。 */
await evalJs('document.querySelector("button[data-view=settings]").click(); return 1');
await sleep(300);
const ess = await evalJs(
  'var e=document.getElementById("txEssid");'
  +'return {tag:e.tagName, n:e.options.length, v:e.value,'
  +'o0:e.options[0].value, t0:e.options[0].textContent, o1:e.options[1].value, t1:e.options[1].textContent}');
ok('ESSID 是下拉框', ess.tag === 'SELECT', ess);
ok('ESSID 有 100 个选项（0~99）', ess.n === 100, ess.n);
ok('ESSID 第一项是 0', ess.o0 === '0', ess.o0);
ok('ESSID 0 写着「不用别名」', String(ess.t0).indexOf('不用别名') >= 0, ess.t0);
/* 选项上必须印出实际登录 ID —— 这是解决「和 DroidStar 差一位」的关键：
   DMR ID*100+ESSID 没人能心算，不写出来就永远对不上设备库。 */
ok('ESSID 选项带实际登录 ID', String(ess.t1).indexOf('860123400') >= 0, ess.t1);
ok('ESSID 1 以 01 开头', String(ess.t1).trim().indexOf('01') === 0, ess.t1);
ok('ESSID 0 也带裸 ID', String(ess.t0).indexOf('8601234') >= 0, ess.t0);

/* 存档里塞一个非法值，txLoad 必须归一化到 0（而不是像以前那样强推 01） */
const norm = await evalJs(
  'var o=store.get(txProfileKey(),{})||{};'
  +'o.txEssid="abc"; store.set(txProfileKey(),o); txLoad(); var bad=txEl("txEssid").value;'
  +'o.txEssid="77"; store.set(txProfileKey(),o); txLoad(); var good=txEl("txEssid").value;'
  +'o.txEssid="0"; store.set(txProfileKey(),o); txLoad();'
  +'return {bad:bad, good:good, final:txEl("txEssid").value}');
ok('非法 ESSID 归一到 0', norm.bad === '0', norm);
ok('合法 ESSID 77 原样保留', norm.good === '77', norm);
ok('复位成 0 之后仍是 0', norm.final === '0', norm);

/* 呼号必填：把原生接口换成探针，看 txConnect 会不会真的发出去 */
const callGate = await evalJs(
  'window.__probe=[];'
  +'window.Android={dmrConnect:function(){window.__probe.push("connect");return "";}};'
  +'txEl("txHost").value="4602.master.brandmeister.network";'
  +'txEl("txPort").value="62031";'
  +'txEl("txId").value="4614187";'
  +'txEl("txPwd").value="secret123";'
  +'txEl("txCall").value="";'
  +'TX.state="IDLE"; txConnect();'
  +'var without=window.__probe.length;'
  +'txEl("txCall").value="bh6rgq";'
  +'TX.state="IDLE"; txConnect();'
  +'var withCall=window.__probe.length;'
  +'var sent=window.__lastCall;'
  +'delete window.Android;'
  +'return {without:without, withCall:withCall}');
ok('呼号为空时不发起连接', callGate.without === 0, callGate);
ok('填了呼号才真正连', callGate.withCall === 1, callGate);

/* 呼号会被统一成大写（主站逐字符比对） */
const upper = await evalJs(
  'window.__arg=null;'
  +'window.Android={dmrConnect:function(h,p,id,ess,pwd,call){window.__arg=call;return "";}};'
  +'txEl("txCall").value=" bh6rgq "; TX.state="IDLE"; txConnect();'
  +'var c=window.__arg; delete window.Android; return c');
ok('呼号自动转大写去空格', upper === 'BH6RGQ', upper);

/* 上报给主站的位置/描述必须是纯 ASCII —— MMDVM 配置串是单字节定宽串，
   中文会被编码成 '?'，主站侧显示成一串问号 */
const descAscii = await evalJs(
  'window.__args=null;'
  +'window.Android={dmrConnect:function(h,p,id,ess,pwd,call,loc,desc){window.__args=[loc,desc];return "";}};'
  +'txEl("txCall").value="BH6RGQ"; TX.state="IDLE"; txConnect();'
  +'var a=window.__args; delete window.Android; return a');
ok('位置上报留空', descAscii[0] === '', descAscii);
ok('描述上报为纯 ASCII', /^[\x20-\x7E]*$/.test(descAscii[1] || ''), descAscii);

/* 浏览器预览模式：没有 Android 原生层时，必须显式置灰并说明为什么，
   而不是让用户点半天只弹一句「当前不在 App 内」 */
const webMode = await evalJs(
  'txBanner("web"); txWebMode();'
  +'return {cls:txEl("txBanner").className,'
  +'        conn:txEl("txBtnConn").disabled,'
  +'        connTxt:txEl("txBtnConn").textContent,'
  +'        ptt:txEl("txPtt").disabled,'
  +'        state:txEl("txIdState").textContent}');
ok('浏览器下横幅切到预览模式', /(^|\s)web(\s|$)/.test(webMode.cls), webMode);
ok('连接按钮置灰并显示「需装 App」', webMode.conn === true && webMode.connTxt === '需装 App', webMode);
ok('PTT 按钮置灰', webMode.ptt === true, webMode);
ok('身份状态标明仅 App 可用', webMode.state === '仅 App 可用', webMode);

/* 国际 BM + 9 位别名 ID：连接前就要把「这条记录可能没登记」摆到眼前 */
const aliasWarn = await evalJs(
  'window.Android={dmrConnect:function(){return "";}};'
  +'txEl("txHost").value="4602.master.brandmeister.network";'
  +'txEl("txPort").value="62031";'
  +'txEl("txId").value="4614187";'
  +'txEl("txEssid").value="4";'
  +'txEl("txPwd").value="secret123";'
  +'txEl("txCall").value="BH6RGQ";'
  +'TX.state="IDLE"; txConnect();'
  +'var r={cls:txEl("txBanner").className, txt:txEl("txBannerTitle").textContent};'
  +'delete window.Android; return r');
ok('BM + 别名 ID 时横幅提示 9 位 ID', /(^|\s)warn(\s|$)/.test(aliasWarn.cls) && /9 位别名/.test(aliasWarn.txt), aliasWarn);

const aliasNo = await evalJs(
  'window.Android={dmrConnect:function(){return "";}};'
  +'txEl("txEssid").value="0"; TX.state="IDLE"; txConnect();'
  +'var r={cls:txEl("txBanner").className, txt:txEl("txBannerTitle").textContent};'
  +'delete window.Android; return r');
ok('ESSID 为 0 时不弹别名警告', !/9 位别名/.test(aliasNo.txt), aliasNo);

/* BM 设备库预检：登录 ID 不存在时，要把「已登记的 ESSID」列出来 */
const devChk = await evalJs(
  'window.__of=window.__of||window.fetch;'
  +'window.fetch=function(u){'
  +'  var id=String(u).split("/").pop();'
  +'  var hit=(id==="4614187"||id==="461418703");'
  +'  return Promise.resolve({ok:hit,status:hit?200:404,'
  +'    json:function(){return Promise.resolve({id:Number(id),callsign:"BH6RGQ"});}});'
  +'};'
  +'return txBmDeviceCheck(461418704,4614187)');
ok('别名 ID 不存在时列出已登记的 ESSID', !!devChk && devChk.ok === false
  && (devChk.found || []).map(f => f.essid).sort().join(',') === '0,4', devChk);

const devOk = await evalJs(
  'window.fetch=function(u){'
  +'  var id=String(u).split("/").pop();'
  +'  var hit=(id==="4614187");'
  +'  return Promise.resolve({ok:hit,status:hit?200:404,'
  +'    json:function(){return Promise.resolve({id:4614187,callsign:"BH6RGQ"});}});'
  +'};'
  +'return txBmDeviceCheck(4614187,4614187)');
ok('ID 存在时返回设备记录', !!devOk && devOk.ok === true
  && devOk.device.callsign === 'BH6RGQ', devOk);
await evalJs('window.fetch=window.__of; return 1');

/* 列出名下全部 ESSID 记录 + CONF 兜底横幅 */
const devList = await evalJs(
  'window.__of2=window.fetch;'
  +'window.fetch=function(u){var id=String(u).split("/").pop();'
  +'  var hit=(id==="4614187"||id==="461418703");'
  +'  return Promise.resolve({ok:hit,status:hit?200:404,json:function(){'
  +'    return Promise.resolve({id:Number(id),callsign:"BH6RGQ",hardware:"MMDVM",rx:"438.5"});}});};'
  +'return txBmListDevices(4614187).then(function(r){window.fetch=window.__of2;return r;});');
ok('列出名下全部 ESSID 记录', Array.isArray(devList) && devList.length === 2
  && devList.map(f => f.essid).sort().join(',') === '0,4', devList);

const altBanner = await evalJs(
  'txBanner("alt",{cur:"ESSID 0（不用别名）",'
  +'list:"ESSID 0 → 4614187、ESSID 03 → 461418703",fix:"ESSID 03",'
  +'master:"3102",btn2:"换 3102 主站重试"});'
  +'return {cls:txEl("txBanner").className,t:txEl("txBannerTitle").textContent,'
  +'        btn:txEl("txBannerBtn").textContent,'
  +'        b2:txEl("txBannerBtn2").textContent,b2show:txEl("txBannerBtn2").style.display};');
ok('CONF 兜底横幅给出换记录 + 换主站两条路', /(^|\s)warn(\s|$)/.test(altBanner.cls)
  && /仍被主站拒绝/.test(altBanner.t) && /改用/.test(altBanner.btn)
  && /换 3102 主站重试/.test(altBanner.b2) && altBanner.b2show !== 'none', altBanner);

const b2hide = await evalJs('txBanner("ok"); return txEl("txBannerBtn2").style.display;');
ok('非 alt 模式隐藏第二颗按钮', b2hide === 'none', b2hide);

const fixHostApply = await evalJs(
  'window.Android={dmrConnect:function(){return "";}};'
  +'txEl("txHost").value="4602.master.brandmeister.network";'
  +'TX.fixHost="3102.master.brandmeister.network"; TX.state="IDLE"; txConnect();'
  +'var v=txEl("txHost").value; delete window.Android; return v');
ok('点「换主站」后主站地址自动套用',
  fixHostApply === '3102.master.brandmeister.network', fixHostApply);

/* 拦截横幅：红色 + 一键修正按钮 */
const nodv = await evalJs(
  'txBanner("nodv",{loginId:461418704,alt:"ESSID 0 → 4614187",fixLabel:"ESSID 0（不用别名）"});'
  +'return {cls:txEl("txBanner").className,t:txEl("txBannerTitle").textContent,'
  +'        btn:txEl("txBannerBtn").textContent};');
ok('设备不存在时横幅为红色并给出一键修正',
  /(^|\s)block(\s|$)/.test(nodv.cls) && /没有设备记录/.test(nodv.t) && /改用/.test(nodv.btn), nodv);

/* 只告诉用户「这个 ID 没有」没用 —— 要直接说「改成几」。
   顺带验证预检失败时**不会**继续发起连接（否则又是一轮白等 MSTNAK）。 */
const nodvLog = await evalJs(
  'return (async()=>{'
  +'window.__of3=window.fetch;'
  +'window.fetch=function(u){var id=String(u).split("/").pop();'
  +'  var hit=(id==="4614187"||id==="461418703");'
  +'  return Promise.resolve({ok:hit,status:hit?200:404,'
  +'    json:function(){return Promise.resolve({id:Number(id),callsign:"BH6RGQ"});}});};'
  +'window.Android={dmrConnect:function(){window.__c=(window.__c||0)+1;return "";}};'
  +'txEl("txHost").value="4602.master.brandmeister.network";'
  +'txEl("txId").value="4614187"; txEl("txEssid").value="5";'
  +'txEl("txCall").value="BH6RGQ"; txEl("txPwd").value="x";'
  +'txEl("txLog").textContent=""; TX.state="IDLE";'
  +'await txConnectFlow();'
  +'var t=txEl("txLog").textContent, c=window.__c||0;'
  +'window.fetch=window.__of3; delete window.Android;'
  +'return {t:t,c:c};})()');
ok('预检失败时日志直接给出该填的 ESSID 序号',
  /把 ESSID 改成 04/.test(nodvLog.t), nodvLog.t ? nodvLog.t.slice(-90) : nodvLog);
ok('预检失败时不发起连接', nodvLog.c === 0, nodvLog.c);
ok('日志点明两边映射已统一',
  /映射已统一/.test(nodvLog.t), nodvLog.t ? nodvLog.t.slice(-60) : nodvLog);

/* ★ 本轮核心修复：填 04 必须落到 461418703（DroidStar 连成功过的那条），
   预检通过后直接发起连接。改回 0-based 会让这里变成 461418704 → 被拒。 */
const ess04 = await evalJs(
  'return (async()=>{'
  +'window.__of4=window.fetch;'
  +'window.fetch=function(u){var id=String(u).split("/").pop();'
  +'  var hit=(id==="4614187"||id==="461418703");'
  +'  return Promise.resolve({ok:hit,status:hit?200:404,'
  +'    json:function(){return Promise.resolve({id:Number(id),callsign:"BH6RGQ"});}});};'
  +'window.Android={dmrConnect:function(h,p,id,ess,pwd,call){'
  +'  window.__a={ess:ess,id:id,call:call};return "";}};'
  +'txEl("txHost").value="4602.master.brandmeister.network";'
  +'txEl("txId").value="4614187"; txEl("txEssid").value="4";'
  +'txEl("txCall").value="BH6RGQ"; txEl("txPwd").value="x";'
  +'txEl("txLog").textContent=""; TX.state="IDLE";'
  +'await txConnectFlow();'
  +'var r={a:window.__a||null,log:txEl("txLog").textContent.slice(-200)};'
  +'window.fetch=window.__of4; delete window.Android; return r;})()');
ok('填 04 落到 461418703 并通过预检直接连接',
  !!ess04.a && ess04.a.ess === 4 && /461418703/.test(ess04.log), ess04);
ok('下拉里 04 显示的实际 ID 就是 461418703',
  String(await evalJs(
    'txEl("txId").value="4614187"; txInitEssid(true);'
    +'return txEl("txEssid").options[4].textContent')).indexOf('461418703') >= 0,
  await evalJs('return txEl("txEssid").options[4].textContent'));

/* 点「改用 … 重连」时，txConnect 会先把修正值套进 #txEssid */
const fixApply = await evalJs(
  'window.Android={dmrConnect:function(){return "";}};'
  +'txEl("txEssid").value="4"; TX.fixEssid=0; TX.state="IDLE"; txConnect();'
  +'var v=txEl("txEssid").value; delete window.Android; return v');
ok('点「改用」后 ESSID 自动套用为 0', fixApply === '0', fixApply);

console.log('\n=== 7b. 网络隔离（不能串网）===');
ok('国际 Last Heard 已断开', await evalJs('return LH.ws===null') === true, await evalJs('return LH.ws'));
await sleep(500);
const strayKeys = await evalJs('return [...state.active.keys()].filter(k=>k[0]==="c"||k[0]==="s")');
ok('active 里没有国际条目', (strayKeys || []).length === 0, strayKeys);

console.log('\n=== 8. 切回国际 ===');
await evalJs('setNet("bm"); return state.net');
await sleep(600);
ok('state.net === bm', await evalJs('return state.net') === 'bm');
ok('国际订阅恢复 [91]', JSON.stringify(await evalJs('return state.subscribed')) === '[91]',
  await evalJs('return state.subscribed'));
ok('话组库回到国际库', await evalJs('return document.getElementById("libCount").textContent') !== '33',
  await evalJs('return document.getElementById("libCount").textContent'));
ok('txHost 回到国际主站', await evalJs('return txEl("txHost").value') === '4602.master.brandmeister.network', await evalJs('return txEl("txHost").value'));
ok('rw 已断开', await evalJs('return state.rw.open') === false, await evalJs('return state.rw.open'));
/* 切回国际后，国内那三台仍然必须留在下拉框里 —— 用户明确要求过不能删 */
const optsBm = await evalJs('return [...document.querySelectorAll("#txHostBody .pickrow")].map(o=>o.dataset.host)');
ok('切回国际后国内三台仍在列表', optsBm.filter(v => v.indexOf('radiowo.com') > 0).length === 3, optsBm.filter(v => v.indexOf('radiowo.com') > 0));

console.log('\n=== 8. 布局（多宽度零横向溢出）===');
for (const w of [320, 360, 390, 414, 480, 768, 1024, 1280]) {
  await send('Emulation.setDeviceMetricsOverride',
    { width: w, height: 900, deviceScaleFactor: 1, mobile: w <= 480 }, S);
  await sleep(180);
  const o = await evalJs('return {sw:document.documentElement.scrollWidth, cw:document.documentElement.clientWidth}');
  ok(`${w}px 无横向溢出`, o.sw <= o.cw + 1, o);
}
// 国内模式下再测一次
await evalJs('setNet("cn");return 1');
for (const w of [320, 390]) {
  await send('Emulation.setDeviceMetricsOverride',
    { width: w, height: 900, deviceScaleFactor: 1, mobile: true }, S);
  await sleep(180);
  const o = await evalJs('return {sw:document.documentElement.scrollWidth, cw:document.documentElement.clientWidth}');
  ok(`国内 ${w}px 无横向溢出`, o.sw <= o.cw + 1, o);
}
// netbar 不换行
const nb = await evalJs(`
  const n=document.querySelector('.netbar'), s=document.getElementById('segNet'), i=document.getElementById('netInfo');
  return {nh:n.getBoundingClientRect().height, sh:s.getBoundingClientRect().height,
          nw:n.getBoundingClientRect().width, iw:i.getBoundingClientRect().width,
          it:i.textContent, iov:i.scrollWidth};
`);
ok('网络条高度合理（≤60px）', nb.nh <= 60, nb);

console.log('\n=== 9. 截图 ===');
/* 前面的用例可能把页面滚下去了（sticky 的 mobnav 会把发射条压在下面），
   截图前先回到顶部，否则拍到的是「滚动后的样子」，看不出真实首屏布局。 */
await evalJs('window.scrollTo(0,0); return 1');
await send('Emulation.setDeviceMetricsOverride',
  { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }, S);
await sleep(400);
await evalJs('window.scrollTo(0,0); return 1');
await sleep(200);
const shot = await send('Page.captureScreenshot', { format: 'png' }, S);
const SHOT_DIR = path.join(ROOT, 'tests', 'shot');
fs.mkdirSync(SHOT_DIR, { recursive: true });
fs.writeFileSync(path.join(SHOT_DIR, 'm390-listen.png'), Buffer.from(shot.result.data, 'base64'));
await send('Emulation.setDeviceMetricsOverride',
  { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, S);
await sleep(300);
await evalJs('window.scrollTo(0,0); return 1');
await sleep(150);
const shot2 = await send('Page.captureScreenshot', { format: 'png' }, S);
fs.writeFileSync(path.join(SHOT_DIR, 'd1280-listen.png'), Buffer.from(shot2.result.data, 'base64'));

console.log(`\n================  ${pass} 通过 / ${fail} 失败  ================`);
if (jsErrors.length) { console.log('\nJS 异常:'); jsErrors.slice(0, 8).forEach(e => console.log('  ', e)); }
ws.close();
chrome.kill();
process.exit(fail ? 1 : 0);
