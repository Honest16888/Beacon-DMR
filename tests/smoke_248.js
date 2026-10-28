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

const sm2 = await ev(`/* 滚回顶部不自动展开：先造出「刚才往下滚过」的条件 */
  htLeftTop=true; htFoldAt=0;
  window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const still=document.querySelector('#viewListen .home-top').classList.contains('collapsed');
  /* 关掉简洁模式，同样的条件应该会展开 —— 证明上一条不是巧合 */
  const c=document.getElementById('setSimple'); c.checked=false;
  c.dispatchEvent(new Event('change',{bubbles:true}));
  await new Promise(r=>setTimeout(r,120));
  htFoldSet(true,true); htLeftTop=true; htFoldAt=0;
  window.dispatchEvent(new Event('scroll'));
  await new Promise(r=>setTimeout(r,80));
  const opened=!document.querySelector('#viewListen .home-top').classList.contains('collapsed');
  return {still, opened};`);
ok('简洁模式下滚回顶部仍保持收起', sm2.still === true, sm2);
ok('关掉简洁模式后同样的条件会展开（对照）', sm2.opened === true, sm2);

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
  state.subscribed=[91,2621,91+0];
  state.subscribed=[91,2621,3100];
  store.set('subscribed',state.subscribed);
  renderMine(); await new Promise(r=>setTimeout(r,100));
  const before=[...document.querySelectorAll('#mineList .row[data-id]')].map(r=>Number(r.dataset.id));
  const rows=[...document.querySelectorAll('#mineList .row[data-id]')];
  const first=rows[0], last=rows[rows.length-1];
  const r0=first.getBoundingClientRect(), rl=last.getBoundingClientRect();
  const opt={bubbles:true, button:0, clientX:r0.left+40, clientY:r0.top+10};
  first.dispatchEvent(new PointerEvent('pointerdown', opt));
  await new Promise(r=>setTimeout(r,520));                 // 等过长按 420ms
  const lifted=first.classList.contains('lift');
  const list=document.getElementById('mineList');
  list.dispatchEvent(new PointerEvent('pointermove',
    {bubbles:true, clientX:r0.left+40, clientY:rl.top+rl.height-2}));
  await new Promise(r=>setTimeout(r,60));
  list.dispatchEvent(new PointerEvent('pointerup', {bubbles:true}));
  await new Promise(r=>setTimeout(r,180));
  const after=[...document.querySelectorAll('#mineList .row[data-id]')].map(r=>Number(r.dataset.id));
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

console.log('\n=== 9. 运行时错误 ===');
ok('页面没有未捕获异常 / console.error', errors.length === 0, errors.slice(0, 3));

console.log('\n' + '='.repeat(18) + `  ${pass} 通过 / ${fail} 失败  ` + '='.repeat(18));

try { chrome.kill(); } catch {}
/* Chrome 退出时还攥着 profile 里的几个文件，删不掉是正常的 ——
   临时目录，交给系统清理，别让收尾把测试结果搞成崩溃退出。 */
try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
process.exit(fail ? 1 : 0);
