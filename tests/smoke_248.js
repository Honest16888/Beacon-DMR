/* v2.4.8 新功能的界面冒烟测试。
 *
 * 覆盖：顶栏页签等宽 / 简洁模式 / 扫描面板折叠 / 话组静音 /
 *       长按拖动排序 / 条款弹窗 / 通联记录裁剪。
 *
 * 用法（需要 Chrome）：
 *   CHROME_PATH=... node tests/smoke_248.js [宽] [高]
 */
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
const PAGE = pathToFileURL(path.join(ROOT, 'web', 'index.html')).href;
const PROFILE = path.join(os.tmpdir(), 'beacon-dmr-248-' + Date.now());
const PORT = 9347;
const W = Number(process.argv[2] || 390), H = Number(process.argv[3] || 844);

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', `--window-size=${W},${H}`, 'about:blank',
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function getWs() {
  for (let i = 0; i < 40; i++) {
    try {
      const l = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const p = l.find(x => x.type === 'page');
      if (p) return p.webSocketDebuggerUrl;
    } catch {}
    await sleep(250);
  }
  throw new Error('CDP 未就绪');
}
const ws = new WebSocket(await getWs());
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let msgId = 0; const waiting = new Map();
const errors = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); return; }
  if (m.method === 'Runtime.exceptionThrown') {
    errors.push(m.params?.exceptionDetails?.exception?.description
      || m.params?.exceptionDetails?.text || 'unknown');
  }
  if (m.method === 'Runtime.consoleAPICalled' && m.params?.type === 'error') {
    errors.push((m.params.args || []).map(a => a.value ?? a.description).join(' '));
  }
};
const send = (method, params = {}, sessionId) => new Promise(res => {
  const id = ++msgId; waiting.set(id, res);
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});
const t = (await send('Target.getTargets')).result.targetInfos.find(x => x.type === 'page');
const S = (await send('Target.attachToTarget', { targetId: t.targetId, flatten: true })).result.sessionId;
await send('Page.enable', {}, S); await send('Runtime.enable', {}, S);
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2, mobile: W < 800 }, S);
await send('Page.navigate', { url: PAGE }, S);
await sleep(2600);

let pass = 0, fail = 0;
const ok = (n, c, e) => { if (c) { pass++; console.log(`  ✓ ${n}`); } else { fail++; console.log(`  ✗ ${n}` + (e !== undefined ? '  → ' + JSON.stringify(e) : '')); } };
const ev = async expr => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true }, S);
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return r.result?.result?.value;
};

/* 断掉两条收听链路：测试期间不要有真实通话来改 state.current */
await ev(`try{spotterClose();}catch(e){} try{rwClose();}catch(e){} await new Promise(r=>setTimeout(r,150));`);

console.log('\n=== 1. 顶栏三个页签 ===');
const v1 = await ev(`const bs=[...document.querySelectorAll('#viewTabs button')];
  return {n:bs.length, w:bs.map(b=>Math.round(b.getBoundingClientRect().width)),
    raw:bs.map(b=>b.textContent),
    /* 去掉图标 span 之后的可见文字（图标本身在手机端是 display:none） */
    labels:bs.map(b=>{const c=b.cloneNode(true);const i=c.querySelector('.vico');if(i)i.remove();return c.textContent;})};`);
ok('三个页签都在', v1.n === 3, v1);
ok('三个页签等宽（±1px）', Math.max(...v1.w) - Math.min(...v1.w) <= 1, v1.w);
ok('页签文字没有字面空格（间距交给 gap）', v1.raw.every(x => !/\s/.test(x)), v1.raw);
ok('可见文字都是两个字', v1.labels.every(x => x.length === 2), v1.labels);

console.log('\n=== 2. 扫描 / 录音 / 记录 面板折叠 ===');
const f1 = await ev(`const b=document.getElementById('tabFold'); b.click();
  await new Promise(r=>setTimeout(r,80));
  const o={folded:document.getElementById('tabPanel').classList.contains('folded'),
    body:getComputedStyle(document.getElementById('tabBody')).display,
    saved:localStorage.getItem('dmr.tabFolded'), aria:b.getAttribute('aria-expanded')};
  b.click(); await new Promise(r=>setTimeout(r,80));
  o.back=document.getElementById('tabPanel').classList.contains('folded');
  return o;`);
ok('点一下收起', f1.folded === true, f1);
ok('收起后正文 display:none', f1.body === 'none', f1.body);
ok('折叠状态存本地', String(f1.saved) === 'true', f1.saved);
ok('aria-expanded 跟着变', f1.aria === 'false', f1.aria);
ok('再点一下展开', f1.back === false, f1);

console.log('\n=== 3. 简洁模式 ===');
const sm1 = await ev(`const c=document.getElementById('setSimple'); c.checked=true;
  c.dispatchEvent(new Event('change',{bubbles:true}));
  await new Promise(r=>setTimeout(r,150));
  const dash=document.querySelector('#viewTabs button[data-view="dash"]');
  const mid=document.querySelector('.col-mid');
  return {simple:state.simple, cls:document.body.classList.contains('simple'),
    dash:getComputedStyle(dash).display, mid:getComputedStyle(mid).display,
    collapsed:document.querySelector('#viewListen .home-top').classList.contains('collapsed'),
    saved:localStorage.getItem('dmr.simple')};`);
ok('state.simple 打开', sm1.simple === true, sm1);
ok('body 带 simple 类', sm1.cls === true, sm1);
ok('看板页签被藏起来', sm1.dash === 'none', sm1.dash);
ok('实时活动栏被藏起来', sm1.mid === 'none', sm1.mid);
ok('操作区自动收起', sm1.collapsed === true, sm1);
ok('设置存本地', String(sm1.saved) === 'true', sm1.saved);

const sm2 = await ev(`/* ★ v2.4.9：操作区跟滚动彻底脱钩。
   以前这里验的是「简洁模式不自动展开 / 正常模式会自动展开」两个方向，
   现在两个方向都没有了 —— 滚动是浏览列表的动作，不该顺带改布局。
   这里把「滚到很深再滚回顶部」走一遍，两种模式都必须保持原状。 */
  const ht=()=>document.querySelector('#viewListen .home-top').classList.contains('collapsed');
  const still=ht();                                  // 简洁模式：收起
  window.scrollTo(0,900); window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const deep=ht();
  window.scrollTo(0,0); window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const back=ht();
  /* 关掉简洁模式：同样滚一遍，也不许动 */
  const c=document.getElementById('setSimple'); c.checked=false;
  c.dispatchEvent(new Event('change',{bubbles:true}));
  await new Promise(r=>setTimeout(r,150));
  htFoldSet(false,true);                             // 先摆成展开
  const before=ht();
  window.scrollTo(0,900); window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const deep2=ht();
  window.scrollTo(0,0); window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const back2=ht();
  /* 手动那颗钮仍然要好使 */
  htFoldSet(false,true); document.getElementById('htFold').click();
  const manual=ht();
  document.getElementById('htFold').click();
  const manual2=ht();
  window.scrollTo(0,0);
  return {still, deep, back, before, deep2, back2, manual, manual2};`);
ok('简洁模式：滚到深处不展开', sm2.deep === true, sm2);
ok('简洁模式：滚回顶部也不展开', sm2.back === true, sm2);
ok('正常模式：摆成展开后滚到深处不自动收起', sm2.deep2 === false, sm2);
ok('正常模式：滚回顶部不会自己弹开', sm2.back2 === false, sm2);
ok('手动点折叠钮能收起', sm2.manual === true, sm2);
ok('再点一下能展开', sm2.manual2 === false, sm2);

console.log('\n=== 4. 话组单独静音 ===');
const mu1 = await ev(`if(!state.subscribed.length)addTg(91,true);
  await new Promise(r=>setTimeout(r,120));
  renderMine();
  const row=document.querySelector('#mineList .row[data-id]');
  const tg=Number(row.dataset.id);
  const btn=row.querySelector('[data-act="mute"]');
  const before={tg, has:!!btn, txt:btn&&btn.textContent, muted:state.rxMuteTg.slice(),
    eff:effSubs().slice()};
  btn.click(); await new Promise(r=>setTimeout(r,150));
  const after={list:state.rxMuteTg.slice(), saved:localStorage.getItem('dmr.rxMuteTg'),
    cls:document.querySelector('#mineList .row[data-id="'+tg+'"]').classList.contains('muted'),
    txt:document.querySelector('#mineList .row[data-id="'+tg+'"] [data-act="mute"]').textContent,
    eff:effSubs().slice()};
  return {before, after};`);
ok('每行有静音按钮', mu1.before?.has === true, mu1.before);
ok('默认是 🔊（未静音）', mu1.before?.txt === '🔊', mu1.before?.txt);
ok('静音前它在订阅列表里', mu1.before?.eff?.includes(mu1.before.tg) === true, mu1.before?.eff);
ok('点一下进了静音名单', mu1.after?.list?.includes(mu1.before.tg) === true, mu1.after);
ok('静音名单存本地', String(mu1.after?.saved || '').includes(String(mu1.before.tg)), mu1.after?.saved);
ok('静音后从订阅里摘掉（服务器不再推它的音频）',
  mu1.after?.eff?.includes(mu1.before.tg) === false, mu1.after?.eff);
ok('行变成 muted 样式', mu1.after?.cls === true, mu1.after);
ok('图标变成 🔇', mu1.after?.txt === '🔇', mu1.after?.txt);

const mu2 = await ev(`const tg=state.rxMuteTg[0];
  document.querySelector('#mineList .row[data-id="'+tg+'"] [data-act="mute"]').click();
  await new Promise(r=>setTimeout(r,150));
  return {list:state.rxMuteTg.slice(), heard:audioAllowed(tg), eff:effSubs().slice(), tg};`);
ok('再点一下取消静音', !mu2.list.includes(mu1.before.tg), mu2);
ok('取消后重新订阅回去', mu2.eff.includes(mu2.tg) === true, mu2.eff);
ok('取消后 audioAllowed 放行', mu2.heard === true, mu2);

const mu3 = await ev(`/* 静音后 audioAllowed 必须挡住，且优先级高于独听 */
  state.rxMuteTg=[91]; state.solo=91; const blocked=audioAllowed(91);
  state.solo=null; state.rxMuteTg=[];
  return {blocked};`);
ok('静音优先于独听（独听选中的组被静音也不播）', mu3.blocked === false, mu3);

console.log('\n=== 5. 静音真的把声音挡住（HOSE 归属回归测试）===');
/* 这一组是回归测试：v2.4.8 第一版把静音写成「CALL_START 直接 return，不切
   state.current」，结果静音组的音频被当成「上一组说的」照常播出来 ——
   表现就是「静音了还在响」。下面按真实帧序跑一遍，数 Audio_.push 的次数。 */
const mu4 = await ev(`
  const origPush=Audio_.push.bind(Audio_);
  const origDec=Audio_.decode.bind(Audio_);
  let pushed=0;
  Audio_.push=()=>{ pushed++; };
  Audio_.decode=()=>new Float32Array(8);
  state.subscribed=[91,46001]; state.rxMuteTg=[46001];
  state.solo=null; state.audioOn=true; RXG.muteUntil=0;
  state.current=null; RXG.attrTg=0; RXG.tg=0; RXG.at=0;
  const AUDIO=[T.CALL_AUDIO, new Uint8Array(180)];

  handleSpotter([T.CALL_START, 0, 4614187, 91]);
  const c1=state.current?Number(state.current.tg):0;
  handleSpotter(AUDIO);
  const n1=pushed;                                  // 91 正常播

  handleSpotter([T.CALL_START, 0, 4615978, 46001]); // 被静音的那组开口
  const attr=RXG.attrTg;
  const c2=state.current?Number(state.current.tg):0;
  handleSpotter(AUDIO);
  const n2=pushed;                                  // 必须还是 1

  handleSpotter([T.CALL_END, 0, 4615978, 46001]);   // 它说完
  const attrAfter=RXG.attrTg;
  handleSpotter(AUDIO);
  const n3=pushed;                                  // 恢复正常，2

  Audio_.push=origPush; Audio_.decode=origDec;
  state.rxMuteTg=[]; state.current=null; RXG.attrTg=0;
  return {c1, n1, attr, c2, n2, attrAfter, n3};`);
ok('91 的 CALL_START 正常占坑', mu4.c1 === 91, mu4);
ok('91 的音频正常播放', mu4.n1 === 1, mu4);
ok('静音组开口时记下音频归属', mu4.attr === 46001, mu4);
ok('静音组不抢 state.current（不打断正在听的）', mu4.c2 === 91, mu4);
ok('静音组的音频被丢掉（这就是原来失效的那一步）', mu4.n2 === 1, mu4);
ok('它说完后归属覆盖被撤掉', mu4.attrAfter === 0, mu4);
ok('之后正常音频恢复播放', mu4.n3 === 2, mu4);

/* 用户实际遇到的那一幕：正在听某一组，点它的静音钮。
   期望：声音立刻停，并且后面已经在路上的帧也进不来。 */
const mu5 = await ev(`
  /* 留一个没静音的组，免得订阅被清空、连接被关掉（关掉会 rxReset 清 attrTg） */
  state.subscribed=[91,2621]; state.rxMuteTg=[];
  state.current={tg:91,srcId:1,alias:'',start:Date.now()};
  RXG.attrTg=0; RXG.tg=91; RXG.at=Date.now();
  const before={cur:!!state.current};
  toggleMuteTg(91);
  const o={before, cur:!!state.current, attr:RXG.attrTg,
    muted:state.rxMuteTg.slice(), eff:effSubs().slice()};
  toggleMuteTg(91);                    /* 复原 */
  state.current=null; RXG.attrTg=0; RXG.tg=0;
  return o;`);
ok('静音正在播的组：立刻停播（current 清空）', mu5.before?.cur === true && mu5.cur === false, mu5);
ok('并且立起 attrTg 挡住已经在路上的帧', mu5.attr === 91, mu5);
ok('同时把它从订阅里摘掉', mu5.eff.includes(91) === false, mu5.eff);

console.log('\n=== 6. 长按拖动排序 ===');
const dg1 = await ev(`if(state.subscribed.length<3){ addTg(2621,true); addTg(91,true); }
  state.subscribed=[91,2621,3100];
  store.set('subscribed',state.subscribed);
  renderMine(); await new Promise(r=>setTimeout(r,120));
  const q=()=>[...document.querySelectorAll('#mineList .row[data-id]')];
  const before=q().map(r=>Number(r.dataset.id));
  const first=q()[0];
  /* 先把最后一行滚进视野再动手 —— 三行都在屏外时 rect 是虚的，
     按虚坐标算出来的落点会落在第一行和第二行之间，等于没拖动。 */
  q()[q().length-1].scrollIntoView({block:'center'});
  await new Promise(r=>setTimeout(r,150));
  const r0=first.getBoundingClientRect();
  const opt={bubbles:true, button:0, clientX:r0.left+40, clientY:r0.top+10};
  first.dispatchEvent(new PointerEvent('pointerdown', opt));
  await new Promise(r=>setTimeout(r,520));                 // 等过长按 420ms
  const lifted=first.classList.contains('lift');
  /* ★ 一次跳到目标位置会偶发不生效：真实拖动是连续多帧的，dgMoveTo 也按
     「当前兄弟的中点」逐次判断。这里补成 6 步，每步重新量一次最后一行的
     位置，布局有一点点位移也不会算错。 */
  const list=document.getElementById('mineList');
  for(let i=1;i<=6;i++){
    const rows=q(), last=rows[rows.length-1];
    const rl=last.getBoundingClientRect();
    const y=r0.top+10+((rl.top+rl.height-2)-(r0.top+10))*i/6;
    list.dispatchEvent(new PointerEvent('pointermove',
      {bubbles:true, clientX:r0.left+40, clientY:y}));
    await new Promise(r=>setTimeout(r,30));
  }
  list.dispatchEvent(new PointerEvent('pointerup', {bubbles:true}));
  await new Promise(r=>setTimeout(r,220));
  const after=q().map(r=>Number(r.dataset.id));
  return {before, lifted, after, saved:localStorage.getItem('dmr.subscribed')};`);
ok('准备了三行', dg1.before?.length === 3, dg1.before);
ok('长按后那一行被抬起', dg1.lifted === true, dg1);
ok('拖到末尾后顺序变了', JSON.stringify(dg1.after) !== JSON.stringify(dg1.before), dg1);
ok('新顺序写回 state / 存储',
  String(dg1.saved || '').replace(/\s/g, '').includes(String(dg1.after?.[0])), dg1.saved);

console.log("\n=== 7. 条款弹窗 ===");
const lg1 = await ev(`const b=document.querySelector('[data-legal="privacy"]');
  const has=!!b; b.click(); await new Promise(r=>setTimeout(r,120));
  const m=document.getElementById('legalModal');
  const o={has, show:m.classList.contains('show'),
    title:document.getElementById('lgTitle').textContent,
    body:document.getElementById('lgBody').textContent.slice(0,400),
    len:document.getElementById('lgBody').textContent.length};
  document.getElementById('lgClose').click();
  await new Promise(r=>setTimeout(r,80));
  o.closed=!m.classList.contains('show');
  return o;`);
ok('页脚有隐私政策入口', lg1.has === true, lg1);
ok('点开弹窗显示', lg1.show === true, lg1);
ok('标题是「隐私政策」', lg1.title === '隐私政策', lg1.title);
ok('正文确实有内容（>800 字）', lg1.len > 800, lg1.len);
ok('正文写明了「没有自己的服务器」', /没有自己的服务器/.test(lg1.body || ''), lg1.body?.slice(0, 60));
ok('点关闭能关掉', lg1.closed === true, lg1);

const lg2 = await ev(`openLegal('risk'); await new Promise(r=>setTimeout(r,80));
  const txt=document.getElementById('lgBody').textContent;
  const o={title:document.getElementById('lgTitle').textContent,
    lic:/操作证书/.test(txt) && /执照/.test(txt), ver:/条款版本/.test(txt)};
  closeLegal(); return o;`);
ok('风险提示标题正确', lg2.title === '风险提示', lg2.title);
ok('写明了必须先有操作证书与执照', lg2.lic === true, lg2);
ok('页脚带条款版本号', lg2.ver === true, lg2);

console.log('\n=== 8. 通联记录：50 条 + 24 小时 ===');
const cl1 = await ev(`const now=Date.now();
  state.calls=[];
  for(let i=0;i<60;i++)state.calls.push({tg:91,srcId:1000+i,call:'T'+i,name:'',t:now-i*1000,dur:1,priv:false});
  state.calls.push({tg:91,srcId:9,call:'OLD',name:'',t:now-25*3600*1000,dur:1,priv:false});
  const before=state.calls.length;
  const cut=callsPrune();
  return {before, cut, after:state.calls.length,
    hasOld:state.calls.some(x=>x.call==='OLD'), newest:state.calls[0]?.call};`);
ok('裁剪前 61 条', cl1.before === 61, cl1);
ok('裁掉 11 条（1 条过期 + 10 条超量）', cl1.cut === 11, cl1);
ok('裁剪后正好 50 条', cl1.after === 50, cl1);
ok('25 小时前那条被删掉', cl1.hasOld === false, cl1);
ok('最新的那条还在最前', cl1.newest === 'T0', cl1.newest);

const cl2 = await ev(`/* logCall 里也要裁：塞满再记一通 */
  const now=Date.now();
  state.calls=[];
  for(let i=0;i<50;i++)state.calls.push({tg:91,srcId:1000+i,call:'X'+i,name:'',t:now-i*1000,dur:1,priv:false});
  state.lastLogKey='';
  logCall({tg:91,srcId:4614187,alias:'BH6RGQ Test',start:now-2000,priv:false});
  return {n:state.calls.length, first:state.calls[0]?.call};`);
ok('logCall 之后仍是 50 条', cl2.n === 50, cl2);
ok('新记录在最前', cl2.first === 'BH6RGQ', cl2.first);

console.log('\n=== 9. v2.4.9 右上角收听/静音 ===');
/* 用户报「右上角收听和静音用不了」。查下来是两个洞：
   ① setPlay(false) 只拦新帧，已经排进 WebAudio 队列的 buffer 撤不回来；
   ② 原生 MMDVM 接收走 AudioTrack，页面压根没管 —— 点了静音照样出声。
   下面把两条都按住。 */
const p1 = await ev(`const b=document.getElementById('btnPlay');
  const g0=Audio_.ctx?Audio_.ctx.state:'none';
  setPlay(false,true);
  const off={on:state.audioOn, um:Audio_.userMuted, txt:b.textContent,
             saved:localStorage.getItem('dmr.audioOn')};
  setPlay(true,true);
  const on={on:state.audioOn, um:Audio_.userMuted, txt:b.textContent};
  return {g0,off,on};`);
ok('静音后 audioOn=false', p1.off.on === false, p1.off);
ok('静音后 Audio_.userMuted=true', p1.off.um === true, p1.off);
ok('静音后按钮文字变「听」', /听/.test(p1.off.txt || '') && !/静音/.test(p1.off.txt || ''), p1.off.txt);
ok('静音写进本地存储', String(p1.off.saved) === 'false', p1.off.saved);
ok('恢复后 userMuted=false', p1.on.um === false, p1.on);
ok('恢复后按钮文字变「静音」', /静音/.test(p1.on.txt || ''), p1.on.txt);

const p2 = await ev(`/* 静音期间拖音量滑块不能把声音「拖」回来 */
  setPlay(false,true);
  applyVolume(120);
  /* 增益是 8ms 斜坡（setTargetAtTime），等它落下来再读 ——
     直接读会读到斜坡中间的值，不是「被滑块写回」 */
  await new Promise(r=>setTimeout(r,120));
  const g=Audio_.gain?Audio_.gain.gain.value:null;
  const mut=Audio_.userMuted;
  applyVolume(80);
  setPlay(true,true);
  return {g, mut};`);
ok('静音时拖音量滑块不改变静音状态', p2.mut === true, p2);
ok('静音时增益被压在 0（不是被滑块写回）', p2.g === null || p2.g < 0.02, p2.g);

const p3 = await ev(`/* 静音时连 buffer 都不建：静音期间来的帧不进播放队列 */
  setPlay(false,true);
  const before=Audio_.nextTime;
  const f=new Float32Array(160).fill(0.1);
  Audio_.push(f);
  const after=Audio_.nextTime;
  setPlay(true,true);
  return {before,after};`);
ok('静音期间 push 不排播放队列', p3.after === 0, p3);

console.log('\n=== 9b. v2.4.9 发射时顶部显示自己的呼号 ===');
/* 顶部主显示以前只接接收侧：原生那条路自己在发（接收被 transmitter.isTransmitting()
   挡住），HOSE 又被 hdGate 静音 —— 两者都是空的，于是自己正在说话，
   顶部一路挂着「暂无通话」。这一节盯住发射分支。 */
const t1 = await ev(`document.getElementById('txCall').value='BG6ABC';
  document.getElementById('txId').value='4601234';
  document.getElementById('txTg').value='91';
  document.getElementById('txPriv').value='0';
  state.current=null; Q.rxing=false; Q.last=null;
  TX.txing=true; TX.startAt=Date.now();
  renderNow();
  const bar=document.getElementById('nowbar');
  const o={call:document.getElementById('npCall').textContent,
    name:document.getElementById('npName').textContent,
    tg:document.getElementById('npTg').textContent,
    tgLbl:document.getElementById('npTgLbl').textContent,
    tgName:document.getElementById('npTgName').textContent,
    idle:document.getElementById('npIdle').style.display,
    main:document.getElementById('npMain').style.display,
    txcls:bar.classList.contains('txing'),
    live:bar.classList.contains('live'),
    reply:document.getElementById('npReply').style.display,
    time:document.getElementById('npTime').textContent,
    sum:document.getElementById('hsTxt').textContent};
  return o;`);
ok('发射时顶部显示自己的呼号', t1.call === 'BG6ABC', t1);
ok('发射时不显示「暂无通话」', t1.idle === 'none' && t1.main === '', t1);
ok('发射时显示目标话组', t1.tg === '91', t1);
ok('发射时标签是「话组」', t1.tgLbl === '话组', t1.tgLbl);
ok('发射时带出话组名', (t1.tgName || '').length > 0, t1.tgName);
ok('发射时整条加 txing 类（左侧红杠）', t1.txcls === true && t1.live === true, t1);
ok('发射时收起「回复」按钮', t1.reply === 'none', t1);
ok('发射时计时器在走', /^0\.\ds$/.test(t1.time || ''), t1.time);
ok('折叠摘要也带自己的呼号', /BG6ABC/.test(t1.sum || ''), t1.sum);

const t2 = await ev(`/* 发射结束 → 顶部回到接收/空闲状态 */
  TX.txing=false; TX.startAt=0; state.current=null;
  Q.rxing=false; Q.last=null;
  renderNow();
  const bar=document.getElementById('nowbar');
  return {txcls:bar.classList.contains('txing'),
    idle:document.getElementById('npIdle').style.display,
    main:document.getElementById('npMain').style.display};`);
ok('发射结束后去掉 txing 类', t2.txcls === false, t2);
ok('发射结束后回到空闲显示', t2.idle === '' && t2.main === 'none', t2);

const t3 = await ev(`/* 个呼模式：标签变「个呼」，呼号照旧是自己 */
  document.getElementById('txPriv').value='1';
  document.getElementById('txTg').value='4601234';
  TX.txing=true; TX.startAt=Date.now(); renderNow();
  const o={lbl:document.getElementById('npTgLbl').textContent,
    tg:document.getElementById('npTg').textContent,
    call:document.getElementById('npCall').textContent};
  TX.txing=false; TX.startAt=0; document.getElementById('txPriv').value='0';
  renderNow();
  return o;`);
ok('个呼发射时标签是「个呼」', t3.lbl === '个呼', t3);
ok('个呼发射时目标显示对方 ID', t3.tg === '4601234', t3);
ok('个呼发射时呼号仍显示自己', t3.call === 'BG6ABC', t3);

console.log('\n=== 10. v2.4.9 一句话只归一条路（见过才让）===');
/* 两条路径同时放同一句 = 「听两遍」；中途压增益 = 「重叠压制」。
   但让位必须「见过才让」—— 按配置推断会让 HOSE 把用户真正在听的组
   整句丢掉，表现就是「没声音、顶部也没呼号」。这一节把两种情况都按住。 */
const o1 = await ev(`rxReset(); state.current=null; state.active.clear();
  state.audioOn=true; Audio_.setUserMute(false);
  state.rxOnlyWatchTg=true; TX.conn=true; TX.voc=true;
  Q.seenTg={}; Q.rxing=false; Q.last=null;
  document.getElementById('txTg').value='91';
  return {live:nativeRxLive(), owns91:hoseOwnsTg(91), owns46001:hoseOwnsTg(46001),
          seen91:nativeSeenTg(91)};`);
ok('MMDVM 连着时 nativeRxLive() 为真', o1.live === true, o1);
ok('没有证据时 HOSE 照播 91（不能按配置瞎推断）', o1.owns91 === true, o1);
ok('没有证据时 HOSE 照播 46001', o1.owns46001 === true, o1);
ok('nativeSeenTg 默认 false', o1.seen91 === false, o1);

const o2 = await ev(`/* 原生真的在 91 上收到过 → 这一组才让位 */
  Q.seenTg={}; Q.seenTg[91]=Date.now();
  return {seen:nativeSeenTg(91), owns91:hoseOwnsTg(91), owns46001:hoseOwnsTg(46001)};`);
ok('原生在 91 上收到过 → 认定它会收', o2.seen === true, o2);
ok('原生收到过的 91 让位', o2.owns91 === false, o2);
ok('没收到过的 46001 仍然 HOSE 播', o2.owns46001 === true, o2);

const o3 = await ev(`/* 证据会过期：超过 NATIVE_TG_TTL_MS 就重新由 HOSE 播 */
  Q.seenTg={}; Q.seenTg[91]=Date.now()-NATIVE_TG_TTL_MS-1000;
  return {seen:nativeSeenTg(91), owns:hoseOwnsTg(91)};`);
ok('过期证据不算数', o3.seen === false, o3);
ok('过期后 HOSE 重新接管', o3.owns === true, o3);

const o4 = await ev(`/* 让位的那一句：不播、不占坑位、不切 current，但**照录** */
  Q.seenTg={}; Q.seenTg[91]=Date.now();
  state.current=null; state.active.clear(); rxReset(); Audio_.silence();
  state.recOn=true; state.recs=[]; state.recActive=false;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  const afterStart={cur:state.current?state.current.tg:null, drop:RXG.dropTg,
                    attr:RXG.attrTg, rec:RXG.recTg, recOn:state.recActive};
  const n0=Audio_.nextTime, len0=Rec.len;
  for(let i=0;i<5;i++) handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  const afterAudio={next:Audio_.nextTime, slot:RXG.tg, recLen:Rec.len-len0};
  handleSpotter([T.CALL_END, 0, 9100001, 91]);
  return {afterStart, afterAudio, dropEnd:RXG.dropTg, recEnd:RXG.recTg};`);
ok('让位的话组不切 current', o4.afterStart.cur === null, o4.afterStart);
ok('让位的话组立起 dropTg', o4.afterStart.drop === 91, o4.afterStart);
ok('让位的话组立起 recTg（仍要录音）', o4.afterStart.rec === 91, o4.afterStart);
ok('让位的话组开了录音', o4.afterStart.recOn === true, o4.afterStart);
ok('让位的话组音频一帧都不播', o4.afterAudio.next === 0, o4.afterAudio);
ok('让位的话组不占收听坑位', o4.afterAudio.slot === 0, o4.afterAudio);
ok('让位的话组照样进了录音缓冲', o4.afterAudio.recLen > 0, o4.afterAudio);
ok('CALL_END 撤掉 dropTg', o4.dropEnd === 0, o4);
ok('CALL_END 撤掉 recTg', o4.recEnd === 0, o4);

const o5 = await ev(`/* 自己的回传：不出声，但要留档 —— 录音 + 通联记录 + 顶部「上一通」。
   以前这里是「录了也是自己，干脆不录」，结果用户自己的发言在录音里、
   通联记录里、顶部「上一通」和就绪行的「回 xxx」里全都找不到。 */
  document.getElementById('txId').value='4601234';
  TX.conn=false; TX.voc=false;
  state.current=null; state.active.clear(); state.calls=[]; state.lastRx=null;
  rxReset(); Audio_.silence();
  state.recActive=false; state.recs=[];
  handleSpotter([T.CALL_START, 0, 4601234, 91]);
  const cur=state.current?state.current.tg:null, drop=RXG.dropTg, rec=RXG.recTg;
  const len0=Rec.len;
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  const recLen=Rec.len-len0, nxt=Audio_.nextTime;
  handleSpotter([T.CALL_END, 0, 4601234, 91]);
  return {cur, drop, rec, next:nxt, recLen,
          calls:state.calls.length,
          lastRxCall:state.lastRx?state.lastRx.call:null,
          lastRxTg:state.lastRx?state.lastRx.tg:null,
          recAfter:state.recActive};`);
ok('自己的回传不切 current', o5.cur === null, o5);
ok('自己的回传立起 dropTg', o5.drop === 91, o5);
ok('自己的回传也录音', o5.rec === 91 && o5.recLen > 0, o5);
ok('自己的回传不播', o5.next === 0, o5);
ok('自己的回传进通联记录', o5.calls === 1, o5);
ok('自己的回传设了「上一通」', o5.lastRxTg === 91 && !!o5.lastRxCall, o5);
ok('自己的回传 CALL_END 收掉录音', o5.recAfter === false, o5);

const o5b = await ev(`/* 松手兜底：有些网络只回音频、不回 CALL_END。
   不收尾的话录音会一直挂着等下一句 CALL_START 顶掉，记录也永远不落地。 */
  TX.conn=false; TX.voc=false;
  state.current=null; state.active.clear(); state.calls=[]; state.lastRx=null;
  state.recActive=false; state.recs=[];
  document.getElementById('txId').value='4601234';
  handleSpotter([T.CALL_START, 0, 4601234, 91]);
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  const before={recTg:RXG.recTg, calls:state.calls.length};
  selfRecGuard();                       /* 模拟松手 */
  await new Promise(r=>setTimeout(r,2800));
  return {before, recTg:RXG.recTg, calls:state.calls.length,
          lastRx:state.lastRx?state.lastRx.tg:null};`);
ok('松手兜底前还在录、还没记', o5b.before.recTg === 91 && o5b.before.calls === 0, o5b);
ok('松手兜底后 recTg 归零', o5b.recTg === 0, o5b);
ok('松手兜底补上通联记录', o5b.calls === 1, o5b);
ok('松手兜底设了「上一通」', o5b.lastRx === 91, o5b);

const o5c = await ev(`/* CALL_END 先到 → 兜底不该再记一遍 */
  TX.conn=false; TX.voc=false;
  state.current=null; state.active.clear(); state.calls=[]; state.lastRx=null;
  state.recActive=false; state.recs=[];
  document.getElementById('txId').value='4601234';
  handleSpotter([T.CALL_START, 0, 4601234, 91]);
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  handleSpotter([T.CALL_END, 0, 4601234, 91]);   /* 真实的 CALL_END 先到 */
  selfRecGuard();
  await new Promise(r=>setTimeout(r,2800));
  return {calls:state.calls.length};`);
ok('CALL_END 先到时不重复记录', o5c.calls === 1, o5c);

const o10 = await ev(`/* 让路的那一句也要进通联记录。
   以前它不进 state.current，CALL_END 到的时候 cur 是「正在听的组」，
   认不到它 → 不播也不记，静默消失。多组同时有呼叫时就会漏。 */
  TX.conn=false; TX.voc=false;
  state.current=null; state.active.clear(); state.calls=[]; state.lastRx=null;
  rxReset(); Audio_.silence(); state.recActive=false;
  state.subscribed=[91,46001];
  /* A 组先占住坑位 */
  handleSpotter([T.CALL_START,0,9100001,91]);
  handleSpotter([T.CALL_AUDIO,new Uint8Array(160).fill(128)]);
  const aCur=state.current?state.current.tg:null;
  /* B 组来呼叫 → 让路 */
  handleSpotter([T.CALL_START,0,4601001,46001]);
  const heldTg=Object.keys(RXG.heldCall).map(Number);
  const curAfter=state.current?state.current.tg:null;
  const callsMid=state.calls.length;
  /* B 组说完 */
  handleSpotter([T.CALL_END,0,4601001,46001]);
  return {aCur, heldTg, curAfter, callsMid,
          calls:state.calls.length,
          lastTg:state.calls[0]?state.calls[0].tg:null,
          lastSrc:state.calls[0]?state.calls[0].srcId:null,
          left:Object.keys(RXG.heldCall).length};`);
ok('让路前 A 组占着', o10.aCur === 91, o10);
ok('让路不切 current', o10.curAfter === 91, o10);
ok('让路记进 heldCall', o10.heldTg.includes(46001), o10);
ok('让路时不急着记', o10.callsMid === 0, o10);
ok('让路的 CALL_END 补上记录', o10.calls === 1, o10);
ok('补的正是让路那一组', o10.lastTg === 46001 && o10.lastSrc === 4601001, o10);
ok('补完清掉 heldCall', o10.left === 0, o10);

const o11 = await ev(`/* 同组接续：上一句的 CALL_END 丢了，新 CALL_START 到了也要补记 */
  TX.conn=false; TX.voc=false;
  state.current=null; state.active.clear(); state.calls=[]; state.lastRx=null;
  rxReset(); Audio_.silence(); state.recActive=false;
  handleSpotter([T.CALL_START,0,9100001,91]);
  handleSpotter([T.CALL_AUDIO,new Uint8Array(160).fill(128)]);
  /* 故意不送 CALL_END，同组直接来第二个人 */
  handleSpotter([T.CALL_START,0,9100002,91]);
  return {calls:state.calls.length,
          first:state.calls[0]?state.calls[0].srcId:null,
          cur:state.current?state.current.srcId:null};`);
ok('同组接续补记上一句', o11.calls === 1, o11);
ok('补记的是上一个人', o11.first === 9100001, o11);
ok('current 换成新的', o11.cur === 9100002, o11);

const o6 = await ev(`/* 静音的话组：整句丢弃，同样不录 */
  TX.conn=false; TX.voc=false;
  state.rxMuteTg=[91]; state.current=null; state.active.clear(); rxReset(); Audio_.silence();
  state.recActive=false;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  const r={drop:RXG.dropTg, rec:RXG.recTg};
  state.rxMuteTg=[];
  return r;`);
ok('静音的话组立起 dropTg', o6.drop === 91, o6);
ok('静音的话组不录音', o6.rec === 0, o6);

const o7 = await ev(`/* hdGate：只有「同一话组」才让 HOSE 闭嘴 */
  TX.conn=true; TX.voc=true; state.rxOnlyWatchTg=true;
  document.getElementById('txTg').value='91';
  state.subscribed=[46001,91]; state.current=null; state.active.clear(); rxReset();
  Audio_.setMute(false); Q.seenTg={};
  /* ① HOSE 在播 46001，原生在收 91 → 不同话组，不许掐 */
  handleSpotter([T.CALL_START,0,4601001,46001]);
  Q.rxing=true; Q.last={srcId:9100001,dstId:91,slot:1,priv:false};
  hdGate();
  const diffMuted=Audio_.muted;
  /* ② 原生收的正好是 HOSE 正在播的 46001 → 让位 */
  Q.last={srcId:4601001,dstId:46001,slot:1,priv:false};
  hdGate();
  const sameMuted=Audio_.muted;
  /* ③ 自己在发射 → 一律静音 */
  Q.rxing=false; hdGate(); TX.txing=true; hdGate();
  const txMuted=Audio_.muted;
  TX.txing=false; hdGate(); Audio_.setMute(false);
  state.current=null; rxReset(); Q.rxing=false; Q.last=null;
  return {diffMuted, sameMuted, txMuted};`);
ok('不同话组时 HOSE 不被掐', o7.diffMuted === false, o7);
ok('同一话组时 HOSE 让位', o7.sameMuted === true, o7);
ok('发射时一律静音', o7.txMuted === true, o7);
await ev(`TX.conn=false; TX.voc=false; state.rxOnlyWatchTg=true; return 1;`);

console.log('\n=== 11. 运行时错误 ===');
ok('页面没有未捕获异常 / console.error', errors.length === 0, errors.slice(0, 3));

console.log('\n' + '='.repeat(18) + `  ${pass} 通过 / ${fail} 失败  ` + '='.repeat(18));

try { chrome.kill(); } catch {}
/* Chrome 退出时还攥着 profile 里的几个文件，删不掉是正常的 ——
   临时目录，交给系统清理，别让收尾把测试结果搞成崩溃退出。 */
try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
process.exit(fail ? 1 : 0);
