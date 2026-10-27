// 首页交互专项：默认收听 / 发射静音闸 / 记住上一通 / 折叠摘要 / 音质链 / 多话组互斥
//
//   node tests/smoke_ui.js [宽] [高]
//
// 需要一个支持 CDP 的 Chrome/Edge。找不到就设 CHROME_PATH 环境变量指过去。
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
const OUT = os.tmpdir();
const PROFILE = path.join(os.tmpdir(), 'beacon-dmr-ui-' + Date.now());
const PORT = 9342;
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
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } };
const send = (method, params = {}, sessionId) => new Promise(res => {
  const id = ++msgId; waiting.set(id, res);
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});
const t = (await send('Target.getTargets')).result.targetInfos.find(x => x.type === 'page');
const S = (await send('Target.attachToTarget', { targetId: t.targetId, flatten: true })).result.sessionId;
await send('Page.enable', {}, S); await send('Runtime.enable', {}, S);
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2, mobile: W < 800 }, S);
await send('Page.navigate', { url: PAGE }, S);
await sleep(2400);

let pass = 0, fail = 0;
const ok = (n, c, e) => { if (c) { pass++; console.log(`  ✓ ${n}`); } else { fail++; console.log(`  ✗ ${n}` + (e !== undefined ? '  → ' + JSON.stringify(e) : '')); } };
const ev = async expr => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true }, S);
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return r.result?.result?.value;
};

console.log('\n=== 1. 收听默认打开 ===');
const s1 = await ev(`return {on:state.audioOn, btn:document.getElementById('btnPlay').textContent,
  saved:localStorage.getItem('dmr.audioOn')};`);
ok('冷启动 audioOn === true', s1.on === true, s1);
ok('右上角显示「静音」', /静音/.test(s1.btn || ''), s1.btn);

const s2 = await ev(`document.getElementById('btnPlay').click();
  await new Promise(r=>setTimeout(r,120));
  return {on:state.audioOn, btn:document.getElementById('btnPlay').textContent,
    saved:localStorage.getItem('dmr.audioOn')};`);
ok('点一下变静音', s2.on === false, s2);
ok('静音状态存本地', String(s2.saved) === 'false', s2.saved);

const s3 = await ev(`document.getElementById('btnPlay').click();
  await new Promise(r=>setTimeout(r,120));
  return {on:state.audioOn, saved:localStorage.getItem('dmr.audioOn')};`);
ok('再点一下恢复收听', s3.on === true && String(s3.saved) === 'true', s3);

console.log('\n=== 2. 发射静音闸不污染用户意图 ===');
const m1 = await ev(`const before=state.audioOn;
  TX.txing=true; hdGate();
  await new Promise(r=>setTimeout(r,60));
  const o={muted:Audio_.muted, audioOn:state.audioOn, changed:state.audioOn!==before,
    gain:Audio_.gain?Audio_.gain.gain.value:null};
  TX.txing=false; hdGate();
  await new Promise(r=>setTimeout(r,80));
  o.afterMuted=Audio_.muted; o.afterAudioOn=state.audioOn;
  return o;`);
ok('发射时 Audio_.muted === true', m1.muted === true, m1);
ok('发射不改 state.audioOn（旧 bug）', m1.changed === false, m1);
ok('发射时 gain 压到 0', m1.gain !== null && m1.gain < 0.02, m1.gain);
ok('发射结束自动恢复', m1.afterMuted === false && m1.afterAudioOn === true, m1);

const m2 = await ev(`state.audioOn=false; renderPlayBtn();
  TX.txing=true; hdGate();
  await new Promise(r=>setTimeout(r,50));
  TX.txing=false; hdGate();
  await new Promise(r=>setTimeout(r,80));
  const o={audioOn:state.audioOn, muted:Audio_.muted};
  state.audioOn=true; renderPlayBtn();
  return o;`);
ok('用户先静音，发射结束后不会被强行打开', m2.audioOn === false && m2.muted === false, m2);

console.log('\n=== 3. 记住上一通呼号 ===');
const r1 = await ev(`/* 默认收听打开后会自动连 HOSE，真实通话会不停覆盖 lastRx —— 测试里先断掉 */
  try{ spotterClose(); }catch(e){}
  try{ rwClose(); }catch(e){}
  await new Promise(r=>setTimeout(r,200));
  TX.voc=true; TX.conn=true; TX.state='CONNECTED';
  state.current=null;
  state.lastRx={srcId:4601234,tg:91,call:'BG6ABC',name:'张三',at:Date.now()};
  txSync(); renderNow();
  await new Promise(r=>setTimeout(r,120));
  return {hint:document.getElementById('txHint').textContent,
    lastRx:state.lastRx,
    idle:document.getElementById('npIdle').textContent,
    idleShown:document.getElementById('npIdle').style.display!=='none',
    clickable:!!document.querySelector('#txHint [data-tg]')};`);
ok('发射条显示「回 BG6ABC」', /回\s*BG6ABC/.test(r1.hint || ''), r1.hint);
ok('发射条显示话组号', /话组\s*91/.test(r1.hint || ''), r1.hint);
ok('呼号可点（带 data-tg）', r1.clickable === true, r1);
ok('空闲条显示「上一通 BG6ABC」', /上一通/.test(r1.idle || '') && /BG6ABC/.test(r1.idle || ''), r1.idle);
ok('空闲条可见', r1.idleShown === true, r1);

const r2 = await ev(`document.querySelector('#txHint [data-tg]').click();
  await new Promise(r=>setTimeout(r,100));
  return {tg:document.getElementById('txTg').value};`);
ok('点呼号把发射目标切到 91', String(r2.tg) === '91', r2);

console.log('\n=== 4. 折叠摘要条 ===');
const f1 = await ev(`state.htCollapsed=false; htFoldSet(false,false);
  document.getElementById('htFold').click();
  await new Promise(r=>setTimeout(r,120));
  const box=document.getElementById('htSum');
  const r=box.getBoundingClientRect();
  return {collapsed:document.querySelector('#viewListen .home-top').classList.contains('collapsed'),
    shown:getComputedStyle(box).display!=='none', h:Math.round(r.height),
    net:document.getElementById('hsNet').textContent,
    txt:document.getElementById('hsTxt').textContent,
    tg:document.getElementById('hsTg').textContent,
    htH:Math.round(document.querySelector('#viewListen .home-top').getBoundingClientRect().height)};`);
ok('收起后摘要条可见', f1.shown === true, f1);
ok('摘要条高度 ≤30px', f1.h > 0 && f1.h <= 30, f1.h);
ok('摘要显示网络', /BM|国内/.test(f1.net || ''), f1.net);
ok('摘要显示上一通呼号', /上一通/.test(f1.txt || ''), f1.txt);
ok('摘要显示发射话组', String(f1.tg) === '91', f1.tg);
ok('折叠总高明显变小', f1.htH < 150, f1.htH);

// 先把页面撑高：折叠后内容不够长就滚不动，测不出 sticky 行为
await ev(`const v=document.getElementById('viewListen');
  const sp=document.createElement('div'); sp.id='__sp'; sp.style.height='2400px';
  v.insertBefore(sp, v.querySelector('.foot')); return 1;`);
await sleep(200);

const f2 = await ev(`window.scrollTo(0,600);
  await new Promise(r=>setTimeout(r,450));
  const box=document.getElementById('htSum');
  const r=box.getBoundingClientRect();
  return {y:Math.round(window.scrollY), shown:getComputedStyle(box).display!=='none',
    top:Math.round(r.top), inView:r.top>=0&&r.bottom<=window.innerHeight,
    txt:document.getElementById('hsTxt').textContent};`);
ok('页面能滚动（撑高后 y>500）', f2.y > 500, f2);
ok('往下滑 600px 摘要条仍在视口内', f2.inView === true, f2);
ok('往下滑后仍能看到收听状态', /上一通|正在说|守听|发射/.test(f2.txt || ''), f2.txt);

const f3 = await ev(`Q.rxing=true; state.current={srcId:4601234,tg:91,alias:'BG6ABC 张三',start:Date.now()};
  qRender(); renderNow();
  await new Promise(r=>setTimeout(r,80));
  const o={txt:document.getElementById('hsTxt').textContent,
    live:document.getElementById('htSum').classList.contains('live')};
  Q.rxing=false; state.current=null; qRender(); renderNow();
  return o;`);
ok('有人说话时摘要显示「BG6ABC 正在说」', /BG6ABC 正在说/.test(f3.txt || ''), f3.txt);
ok('有人说话时指示灯亮', f3.live === true, f3);

const f4 = await ev(`const w=document.documentElement.scrollWidth, c=document.documentElement.clientWidth;
  return {w,c,over:w>c+1};`);
ok('折叠态无横向溢出', f4.over === false, f4);

console.log('\n=== 5. 滚回顶部自动展开 ===');
const f5 = await ev(`const before={y:Math.round(window.scrollY),leftTop:htLeftTop,
    sinceClick:Date.now()-htFoldAt,collapsed:state.htCollapsed};
  window.scrollTo(0,0);
  await new Promise(r=>setTimeout(r,700));
  const ht=document.querySelector('#viewListen .home-top');
  return {before,collapsed:ht.classList.contains('collapsed'),stateCollapsed:state.htCollapsed,
    y:Math.round(window.scrollY),leftTop:htLeftTop,
    sumShown:getComputedStyle(document.getElementById('htSum')).display!=='none'};`);
ok('滚回顶部自动展开', f5.collapsed === false, f5);
ok('展开后摘要条隐藏', f5.sumShown === false, f5);
await ev(`const sp=document.getElementById('__sp'); if(sp)sp.remove(); return 1;`);

// 截图：折叠态
await ev(`state.lastRx={srcId:4601234,tg:91,call:'BG6ABC',name:'张三',at:Date.now()};
  state.htCollapsed=true; htFoldSet(true,false); txSync(); renderNow();
  window.scrollTo(0,300); await new Promise(r=>setTimeout(r,300)); return 1;`);
await sleep(400);
const shot = await send('Page.captureScreenshot', { format: 'png' }, S);
if (shot.result?.data) fs.writeFileSync(path.join(OUT, 'beacon-dmr-ui-' + W + '.png'), Buffer.from(shot.result.data, 'base64'));

console.log('\n=== 6. 接收音质处理链 ===');
const q1 = await ev(`const ok0=Audio_.init();
  return {inited:ok0, hasPush:typeof Audio_.push==='function',
    hp:Audio_.hp?{type:Audio_.hp.type,f:Audio_.hp.frequency.value,q:Audio_.hp.Q.value}:null,
    pk:Audio_.pk?{type:Audio_.pk.type,f:Audio_.pk.frequency.value,g:Audio_.pk.gain.value}:null};`);
ok('音频上下文可用', q1.inited === true, q1);
ok('有 push 统一入口', q1.hasPush === true, q1);
ok('高通滤波器已串入（180Hz）', q1.hp && q1.hp.type === 'highpass' && q1.hp.f === 180, q1.hp);
ok('2.6kHz 提升已串入（+2.5dB）', q1.pk && q1.pk.type === 'peaking' && q1.pk.g === 2.5, q1.pk);

const q2 = await ev(`Audio_.silence();
  const f1=new Float32Array(160).fill(0.5);
  Audio_.push(f1);
  const f2=new Float32Array(160).fill(0.5);
  Audio_.push(f2);
  return {first:f1[0], firstMid:f1[80], second:f2[0], secondMid:f2[80]};`);
ok('断流后第一帧淡入（首样本≈0）', Math.abs(q2.first) < 0.01, q2);
ok('连续帧不再无条件淡变（第二帧首样本保持 0.5）', Math.abs(q2.second - 0.5) < 0.001, q2);
ok('帧中间不再被压（旧逻辑中段也会被衰减）', Math.abs(q2.firstMid - 0.5) < 0.001, q2);

const q3 = await ev(`const f=new Float32Array(160).fill(2.0);
  Audio_.push(f);
  let mx=0; for(const v of f) if(v>mx)mx=v;
  return {max:mx};`);
ok('软限幅把 2.0 压到 1.0 以内', q3.max <= 1.0001, q3);
ok('软限幅不硬切（仍大于 0.95，保留动态）', q3.max > 0.95, q3);

const q4 = await ev(`const f=new Float32Array(160).fill(0.3);
  Audio_.push(f);
  let same=true; for(const v of f) if(Math.abs(v-0.3)>1e-6){same=false;break;}
  return {same};`);
ok('正常电平不被软限幅改动', q4.same === true, q4);

console.log('\n=== 7. 多话组互斥（46001 在说时 91 插进来）===');
const g1 = await ev(`try{ spotterClose(); }catch(e){}
  state.subscribed=[46001,91]; state.solo=null;
  rxReset(); state.current=null; state.active.clear(); Audio_.silence();
  handleSpotter([T.CALL_START, 0, 4601001, 46001]);
  const first=state.current?state.current.tg:null;
  for(let i=0;i<3;i++) handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  const lockedBefore=RXG.tg;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  return {first, lockedBefore,
    after:state.current?state.current.tg:null,
    active91:state.active.has(91), active46001:state.active.has(46001),
    hold91:rxHold(91)};`);
ok('46001 开口后占住坑位', g1.first === 46001 && g1.lockedBefore === 46001, g1);
ok('91 插进来不打断（current 仍是 46001）', g1.after === 46001, g1);
ok('91 仍记进 active（列表里能看到）', g1.active91 === true, g1);
ok('46001 仍在 active', g1.active46001 === true, g1);
ok('rxHold(91) 返回占用的 46001', g1.hold91 === 46001, g1);

const g2 = await ev(`/* 占用组失联超过 LOST_MS（CALL_END 丢了）才兜底让位 */
  const t0=Date.now();
  RXG.at=t0-RXG.LOST_MS-100;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  return {tg:state.current?state.current.tg:null};`);
ok('占用组失联超时后 91 可以接管', g2.tg === 91, g2);

const g3 = await ev(`/* CALL_END 按 tg 精确删除，并把坑位交给还在等的组 */
  rxReset(); state.current={srcId:4601001,tg:46001,alias:'',start:Date.now()};
  state.active.clear();
  state.active.set(46001,{tg:46001,srcId:4601001,alias:'',start:Date.now(),at:Date.now()});
  state.active.set(91,{tg:91,srcId:9100001,alias:'',start:Date.now(),at:Date.now()});
  RXG.tg=46001; RXG.at=Date.now();
  handleSpotter([T.CALL_END, 0, 4601001, 46001]);
  return {cur:state.current&&state.current.tg, a46001:state.active.has(46001)};`);
ok('CALL_END 按 tg 精确删除 46001', g3.a46001 === false, g3);
ok('CALL_END 后坑位交给还在等的 91', g3.cur === 91, g3);

const g4 = await ev(`/* 用户主动点某个组 = 立刻让路 */
  rxReset(); state.current={srcId:4601001,tg:46001,alias:'',start:Date.now()};
  RXG.tg=46001; RXG.at=Date.now();
  txSetTarget(91);
  return {locked:RXG.tg, tg:document.getElementById('txTg').value};`);
ok('点话组后立刻释放占用', g4.locked === 0, g4);

const g5 = await ev(`/* 独听切换也要释放 */
  RXG.tg=46001; RXG.at=Date.now();
  state.solo=null; toggleSolo(91);
  const o={locked:RXG.tg, solo:state.solo};
  toggleSolo(91); rxReset(); state.current=null; state.active.clear();
  return o;`);
ok('切独听后释放占用', g5.locked === 0, g5);

console.log('\n=== 8. 严格配对：46001 说话中 91 抢不走 ===');
const h1 = await ev(`try{ spotterClose(); }catch(e){}
  state.subscribed=[46001,91]; state.solo=null; state.audioOn=true;
  rxReset(); state.current=null; state.active.clear(); Audio_.silence();
  handleSpotter([T.CALL_START, 0, 4601001, 46001]);
  for(let i=0;i<5;i++) handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  /* 模拟换气停顿 5 秒：靠 hang 超时让位的旧逻辑会在这里把坑位交给 91，
     而 46001 的音频还在路上 —— 正是「声音是 46001、顶部却显示 91」的来源 */
  RXG.at=Date.now()-5000;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  return {tg:state.current&&state.current.tg, hold:rxHold(91), active91:state.active.has(91)};`);
ok('46001 停顿 5 秒，91 仍抢不走 current', h1.tg === 46001, h1);
ok('rxHold(91) 仍返回 46001', h1.hold === 46001, h1);
ok('91 仍记进 active（列表里能看到）', h1.active91 === true, h1);

const h2 = await ev(`/* 46001 真正收尾 → 91 交接 */
  handleSpotter([T.CALL_END, 0, 4601001, 46001]);
  return {cur:state.current&&state.current.tg, a46001:state.active.has(46001),
    muted:RXG.muteUntil>Date.now()};`);
ok('46001 收尾后 91 被交接为 current', h2.cur === 91, h2);
ok('交接时进入 250ms 静默（在途旧帧丢弃）', h2.muted === true, h2);

const h3 = await ev(`/* 交接静默期内音频必须被丢弃 */
  const atBefore=RXG.at;
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  return {kept:RXG.at===atBefore};`);
ok('静默期内音频被丢弃（不刷新占用时间）', h3.kept === true, h3);

const h4 = await ev(`/* 静默过后正常播放 */
  RXG.muteUntil=0;
  const atBefore=RXG.at;
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  return {refreshed:RXG.at>atBefore};`);
ok('静默期过后音频恢复', h4.refreshed === true, h4);

const h5 = await ev(`/* 91 收尾 → 干净 */
  handleSpotter([T.CALL_END, 0, 9100001, 91]);
  return {cur:state.current, a91:state.active.has(91)};`);
ok('91 收尾后 current 清空', h5.cur === null, h5);
ok('91 收尾后 active 无残留', h5.a91 === false, h5);

const h6 = await ev(`/* 僵尸修复：被让路的组收到 CALL_END 也要能删掉 */
  rxReset(); state.current=null; state.active.clear();
  handleSpotter([T.CALL_START, 0, 4601001, 46001]);
  handleSpotter([T.CALL_AUDIO, new Uint8Array(160).fill(128)]);
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  const zombie=state.active.has(91);
  handleSpotter([T.CALL_END, 0, 9100001, 91]);
  return {before:zombie, after:state.active.has(91), cur:state.current&&state.current.tg};`);
ok('被让路的组也会进 active', h6.before === true, h6);
ok('它的 CALL_END 能删掉（原来变僵尸）', h6.after === false, h6);
ok('删掉它不影响正在听的 46001', h6.cur === 46001, h6);

const h7 = await ev(`/* 换组静默：current 从 46001 换到 91 时设 muteUntil */
  rxReset(); state.current={srcId:4601001,tg:46001,alias:'',start:Date.now()};
  state.active.clear(); RXG.tg=0; RXG.at=0;
  handleSpotter([T.CALL_START, 0, 9100001, 91]);
  return {tg:state.current&&state.current.tg, muted:RXG.muteUntil>Date.now()};`);
ok('换组时进入交接静默', h7.tg === 91 && h7.muted === true, h7);

console.log('\n=== 9. 个呼：收到、显示、能回 ===');
/* 这一节盯的是「个呼能收到但回不过去」。
 * 根因是个呼的 dstId 就是我自己，界面照直用它当「目标」，
 * 于是点一行 = 把发射目标设成自己。所有断言都围绕这一点。 */
const p1 = await ev(`
  rxReset(); state.current=null; state.active.clear();
  /* 模拟原生侧上报一个打给我的个呼：srcId=对方，dstId=我 */
  Q.rxing=true;
  Q.last={ srcId:4601001, dstId:4614187, slot:1, priv:true, at:Date.now() };
  renderNow();
  const c=qNative();
  return { tg:c&&c.tg, priv:c&&c.priv,
           lbl:document.getElementById('npTgLbl').textContent,
           tgTxt:document.getElementById('npTg').textContent,
           replyShown:document.getElementById('npReply').style.display !== 'none',
           replySrc:document.getElementById('npReply').dataset.src };`);
ok('个呼的目标 = 对方 ID（不是自己的 dstId）', p1.tg === 4601001, p1);
ok('个呼被标记为 priv', p1.priv === true, p1);
ok('顶部标签写「个呼」', p1.lbl === '个呼', p1);
ok('顶部显示对方 ID', p1.tgTxt === '4601001', p1);
ok('出现「回复」按钮', p1.replyShown === true, p1);
ok('回复按钮记住对方 ID', p1.replySrc === '4601001', p1);

const p2 = await ev(`
  /* 点「回复」→ 发射目标应切成对方 + 个呼 + 收到的时隙 */
  document.getElementById('txPriv').value='0';
  document.getElementById('txSlot').value='2';
  txReplyTo(4601001);
  return { tg:qCurTg(), priv:document.getElementById('txPriv').value,
           slot:document.getElementById('txSlot').value };`);
ok('回复把发射目标切成对方', p2.tg === 4601001, p2);
ok('回复把呼叫类型切成个呼', p2.priv === '1', p2);
ok('回复沿用收到的时隙 TS1', p2.slot === '1', p2);

const p3 = await ev(`
  /* 个呼时不能把「只收当前话组」的门限设成对方 ID，
     否则除了这个人以外的组呼全被滤掉 —— 同时开启就废了。 */
  txSetTarget(4601001, true);
  return { tg:qCurTg(), priv:document.getElementById('txPriv').value };`);
ok('个呼目标写进 txTg', p3.tg === 4601001, p3);
ok('txPriv 置 1', p3.priv === '1', p3);

const p4 = await ev(`
  /* 从个呼切回组呼，priv 必须清回 0 */
  txSetTarget(91, false);
  return { tg:qCurTg(), priv:document.getElementById('txPriv').value };`);
ok('切回组呼时 priv 清 0', p4.priv === '0' && p4.tg === 91, p4);

const p5 = await ev(`
  /* 实时活动里个呼行的 data-tg 必须是对方 ID，不是 dstId */
  state.active.clear();
  state.active.set('p1',{ key:'p1', tg:4614187, srcId:4601001, call:'BG7ABC', name:'',
    alias:'', start:Math.floor(Date.now()/1000), rssi:null, slot:1, master:'', types:[],
    priv:true, last:Date.now(), seen:Date.now() });
  renderLive(true);
  const row=document.querySelector('.live-row[data-k="p1"]');
  return { tg:row&&row.dataset.tg, priv:row&&row.dataset.priv,
           slot:row&&row.dataset.slot, pill:row&&row.querySelector('.tgpill').textContent };`);
ok('个呼行的 data-tg = 对方 ID', p5.tg === '4601001', p5);
ok('个呼行带 data-priv=1', p5.priv === '1', p5);
ok('个呼行带时隙', p5.slot === '2', p5);
ok('个呼行的角标写「个呼」', p5.pill === '个呼', p5);

console.log('\n=== 10. 自定义主站 ===');
const c1 = await ev(`
  state.customHosts=[];
  const e1=customHostAdd('my.hotspot.example');
  const e2=customHostAdd('http://10.0.0.5:62031/');
  const e3=customHostAdd('   ');
  const e4=customHostAdd('a b c');
  return { e1,e2,e3,e4, list:state.customHosts.map(x=>x.host+':'+x.port),
           saved:JSON.parse(localStorage.getItem('dmr.customHosts')||'null') };`);
ok('普通域名能存', c1.e1 === '', c1);
ok('带协议和端口的地址被归一化', c1.e2 === '', c1);
ok('空地址被拒', c1.e3 !== '', c1);
ok('含空格被拒', c1.e4 !== '', c1);
ok('自定义主站带自己的端口', c1.list.includes('10.0.0.5:62031'), c1);
ok('已写入 localStorage', Array.isArray(c1.saved) && c1.saved.length === 2, c1);

const c2 = await ev(`
  const all=txHostData();
  const mine=all.filter(x=>x.group==='my');
  const hit=all.find(x=>x.host==='10.0.0.5');
  return { n:mine.length, port:hit&&hit.port, first:all[0]&&all[0].group };`);
ok('自定义主站进列表', c2.n === 2, c2);
ok('列表里保留自定义端口', c2.port === '62031', c2);
ok('自定义主站排在最前', c2.first === 'my', c2);

const c3 = await ev(`
  customHostDel('10.0.0.5');
  return { n:state.customHosts.length, still:state.customHosts.some(x=>x.host==='10.0.0.5') };`);
ok('能删除自定义主站', c3.n === 1 && c3.still === false, c3);

const c4 = await ev(`
  /* 选主站时端口要跟着条目走，不能一律写死 62031 */
  customHostAdd('alt.example:62099');
  const hit=txHostData().find(x=>x.host==='alt.example');
  txHostPick(hit.host, hit.port);
  return { host:document.getElementById('txHost').value,
           port:document.getElementById('txPort').value };`);
ok('选自定义主站时端口跟着走', c4.host === 'alt.example' && c4.port === '62099', c4);

/* v2.1 最初把「添加」按钮做成「把上面 #txHost 里填的地址存下来」，
 * 但列表只在面板打开 / 搜索框输入时重绘 —— 在地址框里打字不重绘，
 * 于是那个按钮永远不出现，用户看到的是「请先填地址」，功能等于没做。
 * v2.2 改成底部自带输入框，这两组用例锁死它。 */
const c5 = await ev(`
  state.customHosts=[];
  txHostPanel(true);
  const inp=document.getElementById('txHostNew');
  if(!inp) return { missing:true };
  inp.value='self.example:62100';
  const add=document.querySelector('#txHostBody [data-add]');
  if(!add) return { missing:'btn' };
  add.click();
  return { missing:false,
           host:document.getElementById('txHost').value,
           port:document.getElementById('txPort').value,
           n:state.customHosts.length,
           list:state.customHosts.map(x=>x.host+':'+x.port),
           hidden:document.getElementById('txHostList').hidden };`);
ok('面板底部有自带输入框', c5.missing === false, c5);
ok('点「添加」把地址填进主站框', c5.host === 'self.example', c5);
ok('端口用地址里写的那个', c5.port === '62100', c5);
ok('已存进「我的主站」', c5.n === 1 && c5.list[0] === 'self.example:62100', c5);
ok('添加后自动收起面板', c5.hidden === true, c5);

const c6 = await ev(`
  /* 手打地址的人打完就想回车，不该逼他去找按钮 */
  txHostPanel(true);
  const inp=document.getElementById('txHostNew');
  inp.value='enter.example';
  inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
  return { host:document.getElementById('txHost').value,
           port:document.getElementById('txPort').value,
           n:state.customHosts.length };`);
ok('回车也能添加', c6.host === 'enter.example', c6);
ok('没写端口时用默认 62031', c6.port === '62031', c6);
ok('回车添加也落库', c6.n === 2, c6);

const c7 = await ev(`
  /* 空输入不能把当前主站冲掉 */
  txHostPanel(true);
  const before=document.getElementById('txHost').value;
  const inp=document.getElementById('txHostNew');
  inp.value='   ';
  document.querySelector('#txHostBody [data-add]').click();
  return { before, after:document.getElementById('txHost').value,
           n:state.customHosts.length };`);
ok('空输入不动当前主站', c7.after === c7.before && c7.n === 2, c7);

console.log('\n=== 11. 操作区话组改名 ===');
/* 「发射到 46001 世界组」里的名字来自话组表，用户要能按自己的叫法改。
 * 这一节确认：自定义名压过表里的名字、能清空恢复、只影响本机。 */
const n1 = await ev(`
  state.tgAlias={}; state.net='cn';
  state.tg['46001']='世界组';
  txSetTarget(46001, false);
  const before=document.getElementById('txTgName').textContent;
  tgRenameInline();
  const inp=document.getElementById('txTgName');
  const isInput=inp.tagName==='INPUT';
  inp.value='全国直呼';
  inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
  const after=document.getElementById('txTgName').textContent;
  return { before, isInput, after, tag:document.getElementById('txTgName').tagName,
           saved:JSON.parse(localStorage.getItem('dmr.tgAlias')||'null'),
           tgName:tgName(46001), label:tgLabel(46001) };`);
ok('改名前显示表里的名字', n1.before === '世界组', n1);
ok('点一下变成输入框', n1.isInput === true, n1);
ok('回车后显示新名字', n1.after === '全国直呼', n1);
ok('提交后换回 span（id 不变）', n1.tag === 'SPAN', n1);
ok('已写入 localStorage', n1.saved && n1.saved['46001'] === '全国直呼', n1);
ok('tgName 走自定义名', n1.tgName === '全国直呼', n1);
ok('tgLabel 也跟着变', n1.label === '全国直呼', n1);

const n2 = await ev(`
  /* 清空 → 恢复表里的名字 */
  tgRenameInline();
  const inp=document.getElementById('txTgName');
  inp.value='';
  inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
  return { txt:document.getElementById('txTgName').textContent,
           saved:JSON.parse(localStorage.getItem('dmr.tgAlias')||'{}'),
           name:tgName(46001) };`);
ok('清空后恢复表里的名字', n2.txt === '世界组' && n2.name === '世界组', n2);
ok('清空后别名被删掉', !('46001' in (n2.saved || {})), n2);

const n3 = await ev(`
  /* 个呼没有话组名可改，不能误触 */
  state.tgAlias={};
  txSetTarget(4601001, true);
  const before=document.getElementById('txTgName').textContent;
  tgRenameInline();
  return { editing:document.getElementById('txTgName').tagName, before };`);
ok('个呼时拒绝改名', n3.editing === 'SPAN', n3);

console.log('\n=== 12. 接收音频电平（dBFS） ===');
/* DMRD 包不带 RSSI，所以界面标「音频」而不是「信号」。
 * 这一节锁死三件事：读数保留一位小数、按电平分档变色、峰值按句重置。 */
const d1 = await ev(`
  window.onDmrEvent(JSON.stringify({type:'rx',on:true,srcId:4601001,dstId:91,slot:1,priv:false}));
  window.onDmrEvent(JSON.stringify({type:'rxlevel',v:0.4,db:-18.43}));
  const el=document.getElementById('npDb');
  return { txt:el&&el.textContent, lv:el&&el.dataset.lv, title:el&&el.title };`);
ok('dB 读数保留一位小数', d1.txt === '-18.4 dB', d1);
ok('正常说话档位是 ok', d1.lv === 'ok', d1);
ok('提示里写清「不是空口信号强度」', /RSSI/.test(d1.title || ''), d1);

const d2 = await ev(`
  window.onDmrEvent(JSON.stringify({type:'rxlevel',v:0.9,db:-6.2}));
  const a=document.getElementById('npDb');
  const hot=a.dataset.lv;
  window.onDmrEvent(JSON.stringify({type:'rxlevel',v:0.05,db:-45.0}));
  const b=document.getElementById('npDb');
  return { hot, low:b.dataset.lv, txt:b.textContent, title:b.title };`);
ok('接近满量程标 hot', d2.hot === 'hot', d2);
ok('底噪标 low', d2.low === 'low', d2);
ok('峰值按句累积（−6.2 被记住）', /-6\.2/.test(d2.title || ''), d2);

const d3 = await ev(`
  /* 新的一句开始 → 峰值重新起算 */
  window.onDmrEvent(JSON.stringify({type:'rx',on:true,srcId:4601001,dstId:91,slot:1,priv:false}));
  window.onDmrEvent(JSON.stringify({type:'rxlevel',v:0.3,db:-22.0}));
  const t=document.getElementById('npDb').title;
  window.onDmrEvent(JSON.stringify({type:'rx',on:false}));
  return { title:t, lv:document.getElementById('npDb').dataset.lv };`);
ok('新句子峰值不继承上一句', !/-6\.2/.test(d3.title || ''), d3);
ok('一句结束后读数变暗', d3.lv === 'idle', d3);

console.log('\n=== 13. 麦克风增益（发射侧） ===');
/* 增益必须作用在 AGC 之后 —— 这是功能能不能被感知的前提，测不到音频，
 * 所以这里锁的是「UI 有没有把值真的送出去、有没有夹住、读数跟不跟手」。 */
const mg1 = await ev(`
  const s=document.getElementById('txMicGain');
  return { has:!!s, min:s&&s.min, max:s&&s.max, step:s&&s.step, val:s&&s.value,
           lbl:document.getElementById('txMicGainVal').textContent,
           lvl:document.getElementById('txLevelDb').textContent };`);
ok('增益滑块存在且范围 −12 ~ +12', mg1.has && mg1.min === '-12' && mg1.max === '12', mg1);
ok('默认 0 dB 且读数显示 0 dB', mg1.val === '0' && mg1.lbl === '0 dB', mg1);
ok('发射电平初始为 —', mg1.lvl === '—', mg1);

const mg2 = await ev(`
  const s=document.getElementById('txMicGain');
  s.value='6'; s.dispatchEvent(new Event('input',{bubbles:true}));
  return { v:state.micGain, lbl:document.getElementById('txMicGainVal').textContent,
           on:document.getElementById('txMicGainVal').classList.contains('on'),
           saved:Number(localStorage.getItem('dmr.micGain')) };`);
ok('拖动后 state 更新为 +6', mg2.v === 6, mg2);
ok('读数带正号：+6 dB', mg2.lbl === '+6 dB', mg2);
ok('非 0 时读数点亮', mg2.on === true, mg2);
ok('值写进本地存储', mg2.saved === 6, mg2);

const mg3 = await ev(`
  const s=document.getElementById('txMicGain');
  s.value='-9'; s.dispatchEvent(new Event('input',{bubbles:true}));
  const neg={ lbl:document.getElementById('txMicGainVal').textContent, v:state.micGain };
  s.value='0'; s.dispatchEvent(new Event('input',{bubbles:true}));
  return { neg, zero:document.getElementById('txMicGainVal').classList.contains('on'),
           lbl:document.getElementById('txMicGainVal').textContent };`);
ok('负增益显示 −9 dB（不带正号）', mg3.neg.lbl === '-9 dB' && mg3.neg.v === -9, mg3);
ok('回到 0 后熄灭且显示 0 dB', mg3.zero === false && mg3.lbl === '0 dB', mg3);

const mg4 = await ev(`
  /* 越界值必须被夹住，不能把 30 原样送进原生 */
  state.micGain=99; micGainSyncUI(); const hi=state.micGain;
  state.micGain=0; micGainSyncUI();
  const s=document.getElementById('txMicGain');
  s.value='30'; s.dispatchEvent(new Event('input',{bubbles:true}));
  return { hi, clamped:state.micGain };`);
ok('越界值被夹到 +12', mg4.clamped === 12, mg4);

const mg5 = await ev(`
  /* level 事件带 db 字段 → 发射电平读数跟手 */
  window.onDmrEvent(JSON.stringify({type:'tx',on:true,dstId:91}));
  window.onDmrEvent(JSON.stringify({type:'level',v:62,db:-13.27}));
  const d=document.getElementById('txLevelDb');
  const on={ txt:d.textContent, hot:d.classList.contains('on') };
  window.onDmrEvent(JSON.stringify({type:'tx',on:false,reason:'松开 PTT'}));
  const off={ txt:d.textContent, dim:d.classList.contains('off') };
  return { on, off };`);
ok('发射电平保留一位小数：−13.3 dB', mg5.on.txt === '-13.3 dB', mg5);
ok('发射中读数点亮', mg5.on.hot === true, mg5);
ok('松手后变暗但保留读数', mg5.off.dim === true && mg5.off.txt === '-13.3 dB', mg5);

console.log(`\n================  ${pass} 通过 / ${fail} 失败  ================`);
ws.close(); chrome.kill();
await sleep(300);
try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
process.exit(fail ? 1 : 0);
