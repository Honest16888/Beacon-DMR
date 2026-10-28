// 冷启动音频探针：**故意不加 --autoplay-policy=no-user-gesture-required**
//
//   node tests/probe_audio_boot.js
//
// 为什么单独一支：其他冒烟测试都带着
//   --autoplay-policy=no-user-gesture-required
// 这个参数，于是「刷新之后没声音、要手动点一下」这类问题在测试里**永远复现不出来** ——
// 浏览器不挂起 AudioContext，代码里那些「等手势才 resume」的路径一次都不会被走到。
// 这支探针按真实用户的策略跑：没有手势之前，AudioContext 就是 suspended。
//
// 观察点：
//   · 页面加载 3 秒后 ctx.state 是什么（running 还是 suspended）
//   · 这期间喂进去的帧去了哪里（环形缓冲 / 攒帧 / 逐帧排期 / 被丢掉）
//   · 模拟一次真实点击之后，state 有没有变成 running、currentTime 有没有开始走
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
const PROFILE = path.join(os.tmpdir(), 'beacon-dmr-probe-' + Date.now());
const PORT = 9356;
const W = 390, H = 844;

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  /* ★ 这里**没有** --autoplay-policy=no-user-gesture-required —— 这就是全部意义 */
  `--window-size=${W},${H}`, 'about:blank',
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
const tgt = (await send('Target.getTargets')).result.targetInfos.find(x => x.type === 'page');
const S = (await send('Target.attachToTarget', { targetId: tgt.targetId, flatten: true })).result.sessionId;
await send('Page.enable', {}, S); await send('Runtime.enable', {}, S);
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2, mobile: true }, S);
await send('Page.navigate', { url: PAGE }, S);
await sleep(3200);

const ev = async expr => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true }, S);
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return r.result?.result?.value;
};

const snap = `const c=Audio_.ctx;
  return { state:c?c.state:'无', rate:c?c.sampleRate:0, now:c?+c.currentTime.toFixed(3):0,
    gain:Audio_.gain?+Audio_.gain.gain.value.toFixed(3):null,
    sink:!!Audio_.sink, abandoned:!!Audio_.sinkAbandoned, pending:(Audio_.pending||[]).length,
    muted:Audio_.muted, userMuted:Audio_.userMuted, audioOn:state.audioOn,
    vol:state.volume, g:state.gain, level:+Audio_.level.toFixed(3),
    under:Audio_.underSamples||0,
    tapOn:(function(){var e=document.getElementById('tapStart');return e?e.classList.contains('on'):null;})(),
    diag:(document.getElementById('audioDiag')||{}).textContent };`;

console.log('\n=== 1. 冷启动 3.2 秒后（还没有任何手势）===');
const a = await ev(snap);
console.log('   ', JSON.stringify(a));

/* 喂 1 秒的合成音频（50 帧 × 20 ms），看它走到哪条路 */
console.log('\n=== 2. 喂 1 秒音频（无手势）===');
const b = await ev(`
  window.__probe={pushed:0, played:0};
  const origPlay=Audio_.play.bind(Audio_);
  Audio_.play=function(f){ window.__probe.played++; return origPlay(f); };
  const origSend=Audio_.send.bind(Audio_);
  Audio_.send=function(f){ window.__probe.pushed++; return origSend(f); };
  for(let k=0;k<50;k++){
    const f=new Float32Array(160);
    for(let i=0;i<160;i++) f[i]=0.25*Math.sin(2*Math.PI*440*(k*160+i)/8000);
    Audio_.push(f);
    await new Promise(r=>setTimeout(r,20));
  }
  return {probe:window.__probe, state:Audio_.ctx?Audio_.ctx.state:'无',
          now:Audio_.ctx?+Audio_.ctx.currentTime.toFixed(3):0,
          sink:!!Audio_.sink, pending:(Audio_.pending||[]).length};`);
console.log('   ', JSON.stringify(b));

console.log('\n=== 3. 模拟一次真实点击（用户点屏幕任意位置）===');
await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 200, y: 400, button: 'left', clickCount: 1 }, S);
await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 200, y: 400, button: 'left', clickCount: 1 }, S);
await sleep(600);
const c1 = await ev(snap);
console.log('   ', JSON.stringify(c1));

/* currentTime 到底有没有在走 —— 这是「有没有真的在放」唯一可靠的判据。
   这一段放到最后再量，顺便避开点击后的 resume 过渡期。 */

console.log('\n=== 4. 再喂 0.6 秒，看出口 ==');
const d = await ev(`
  const before=window.__probe?window.__probe.played:0;
  for(let k=0;k<30;k++){
    const f=new Float32Array(160);
    for(let i=0;i<160;i++) f[i]=0.25*Math.sin(2*Math.PI*440*(k*160+i)/8000);
    Audio_.push(f);
    await new Promise(r=>setTimeout(r,20));
  }
  return {probe:window.__probe, sink:!!Audio_.sink,
          now:+Audio_.ctx.currentTime.toFixed(3)};`);
console.log('   ', JSON.stringify(d));

console.log('\n=== 5. 提示条与结论判读 ===');
const tap = await ev(`const el=document.getElementById('tapStart');
  return {has:!!el, on:el?el.classList.contains('on'):null,
          txt:el?el.textContent:''};`);
console.log('   提示条：', JSON.stringify(tap));

let pass = 0, fail = 0;
const ok = (n, c, e) => {
  if (c) { pass++; console.log(`  ✓ ${n}`); }
  else { fail++; console.log(`  ✗ ${n}` + (e !== undefined ? '  → ' + JSON.stringify(e) : '')); }
};
const tA = c1.now;
await sleep(700);
const c3 = await ev(snap);
console.log('   700ms 后 now =', c3.now, '（Δ', (c3.now - tA).toFixed(3), 's）');

ok('冷启动上下文是 suspended（浏览器自动播放策略）', a.state === 'suspended', a.state);
ok('冷启动就把「轻触开始收听」挂出来了', a.tapOn === true, a.tapOn);
ok('没有手势时 currentTime 冻结，环里攒的东西一个都放不出来',
  b.state === 'suspended' && b.now === 0 && b.probe.played === 0, b);
ok('帧没有被静默丢掉（建好了环形缓冲在等）', b.sink === true, b.sink);
ok('一次点击后上下文变 running', c1.state === 'running', c1.state);
ok('点击后提示条收起', c1.tapOn === false, c1.tapOn);
ok('currentTime 真的在走（不是假 running）', (c3.now - tA) > 0.3, c3.now - tA);
ok('恢复后帧继续进环形缓冲', d.probe.pushed > 50, d.probe);
ok('增益不是 0（不是「闸」的问题）', a.gain > 0, a.gain);
ok('提示条文案说清「轻触」', tap.has === true && tap.txt.indexOf('轻触') >= 0, tap.txt);

console.log(`\n================  ${pass} 通过 / ${fail} 失败  ================`);
ws.close(); chrome.kill();
await sleep(300);
try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch {}
process.exit(fail ? 1 : 0);
