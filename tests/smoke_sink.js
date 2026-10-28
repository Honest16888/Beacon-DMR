// 音频输出链路专项：8 kHz 上下文 + 环形缓冲
//
//   node tests/smoke_sink.js
//
// 这一版「噼啪」的根因是：48 kHz 上下文里每 20 ms 塞一个 8 kHz 的小 buffer，
// 每一帧都要单独重采样一次，边界必然出毛刺。官方 Hoseline 播放器的做法是
// `new AudioContext({sampleRate:8000})` + AudioWorklet 环形缓冲。
// 这里验证我们照做之后：
//   1. 上下文真的是 8000 Hz（否则环形缓冲会 6 倍速播放）
//   2. worklet 能加载、节点能建、帧能塞进去
//   3. 离线渲染同一个 processor，输出和输入**逐样本一致**（不是大概对）
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_PATH || (
  process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : '/usr/bin/google-chrome'
);
const PAGE = pathToFileURL(path.join(ROOT, 'web', 'index.html')).href;
const PROFILE = path.join(os.tmpdir(), 'beacon-sink-' + Date.now());
const PORT = 9472;
const sleep = ms => new Promise(r => setTimeout(r, ms));

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', '--window-size=390,844', 'about:blank',
], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try {
      const j = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const p = j.find(t => t.type === 'page'); if (p) return p.webSocketDebuggerUrl;
    } catch (e) {}
    await sleep(200);
  }
  throw new Error('连不上 Chrome');
}
const ws = new WebSocket(await wsUrl());
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const w = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && w.has(m.id)) { w.get(m.id)(m); w.delete(m.id); }
});
const send = (method, params = {}) => { const i = ++id; ws.send(JSON.stringify({ id: i, method, params })); return new Promise(r => w.set(i, r)); };
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 900));
  return r.result?.result?.value;
}
await send('Page.enable'); await send('Runtime.enable');
const errs = [];
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.exceptionThrown') errs.push(m.params?.exceptionDetails?.text || 'exception');
});
await send('Page.navigate', { url: PAGE });
await sleep(1800);

let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  console.log((cond ? '  ✓ ' : '  ✗ ') + name + (extra ? '  ' + extra : ''));
  cond ? pass++ : fail++;
};

console.log('=== 1. 上下文采样率 ===');
const base = await ev(`(async()=>{
  Audio_.resume();
  await new Promise(r=>setTimeout(r,600));
  return {
    sr: Audio_.ctx ? Audio_.ctx.sampleRate : -1,
    r8: !!Audio_.rate8000,
    hasWorklet: !!(Audio_.ctx && Audio_.ctx.audioWorklet),
    hasSink: !!Audio_.sink,
    pending: !!Audio_.sinkPending,
    srcLen: (Audio_.sinkSrc||'').length
  };
})()`);
ok('AudioContext 建在 8000 Hz', base.sr === 8000, `→ ${base.sr} Hz`);
ok('rate8000 标记为真', base.r8 === true);
ok('浏览器支持 AudioWorklet', base.hasWorklet === true);
ok('环形缓冲节点已建立', base.hasSink === true);
ok('没有卡在 pending 状态', base.pending === false);
ok('worklet 源码已留档（供离线验证）', base.srcLen > 500, `→ ${base.srcLen} 字符`);

console.log('\n=== 2. 喂帧不报错 ===');
const feed = await ev(`(()=>{
  try{
    for(let k=0;k<50;k++){
      const f=new Float32Array(160);
      for(let i=0;i<160;i++) f[i]=0.5*Math.sin(2*Math.PI*1000*(k*160+i)/8000);
      Audio_.push(f);
    }
    return { ok:true };
  }catch(e){ return { ok:false, err:String(e) }; }
})()`);
ok('连续喂 50 帧（1 秒）无异常', feed.ok === true, feed.err ? '→ ' + feed.err : '');

console.log('\n=== 3. 离线渲染：输出是否逐样本还原 ===');
const rt = await ev(`(async()=>{
  const SR=8000, N=SR;                       // 1 秒
  const oc=new OfflineAudioContext(1, N, SR);
  // 和线上一致：data URL（file:// 下 blob 会被拒）
  const url='data:text/javascript;utf8,'+encodeURIComponent(Audio_.sinkSrc);
  await oc.audioWorklet.addModule(url);
  const node=new AudioWorkletNode(oc,'dmr-sink',
    {numberOfInputs:0,numberOfOutputs:1,outputChannelCount:[1]});
  node.connect(oc.destination);
  // 造一段 1 kHz 正弦，Int16 量化后送进去（和线上路径一致）
  const d=new Int16Array(N);
  for(let i=0;i<N;i++) d[i]=Math.round(0.5*32767*Math.sin(2*Math.PI*1000*i/SR));
  node.port.postMessage({action:'play',data:d},[d.buffer]);
  const out=await oc.startRendering();
  const y=out.getChannelData(0);
  // 头一个渲染量子可能还没收到消息（消息是异步投递的），从 128 开始比
  const skip=256;
  let maxErr=0, sumSq=0, cnt=0;
  for(let i=skip;i<N;i++){
    const want=d[i]/32768;
    const e=Math.abs(y[i]-want);
    if(e>maxErr)maxErr=e;
    sumSq+=y[i]*y[i]; cnt++;
  }
  return { maxErr:+maxErr.toFixed(6), rms:+Math.sqrt(sumSq/cnt).toFixed(4),
           head:+y[0].toFixed(4), tail:+y[N-1].toFixed(4) };
})()`);
ok('输出与输入逐样本一致（误差 < 1e-4）', rt.maxErr < 1e-4, `→ 最大误差 ${rt.maxErr}`);
ok('输出确实有信号（不是全零）', rt.rms > 0.3, `→ RMS ${rt.rms}`);

console.log('\n=== 4. 静音 / 清空 ===');
const sil = await ev(`(()=>{
  try{
    Audio_.setUserMute(true);
    const m=Audio_.userMuted;
    Audio_.setUserMute(false);
    Audio_.silence();
    return { ok:true, muted:m };
  }catch(e){ return { ok:false, err:String(e) }; }
})()`);
ok('静音 → 解除 → 清空 全程无异常', sil.ok === true, sil.err ? '→ ' + sil.err : '');
ok('用户静音状态被记住', sil.muted === true);

console.log('\n=== 5. 看门狗：攒帧溢出要认输，不能吃帧 ===');
/* 「第一次进软件偶尔没声音，重启才好」的形态：
   addModule 的 Promise 迟迟不 settle，sinkPending 一直挂着 true，
   而 push() 在 pending 期间的策略是「先攒着」—— 于是每一帧都被吃掉。
   现在攒到 1.5 秒就认输，改走逐帧排期。 */
const wd = await ev(`(()=>{
  const bak={sink:Audio_.sink, pending:Audio_.pending, pendingFlag:Audio_.sinkPending};
  Audio_.sink=null; Audio_.sinkPending=true; Audio_.sinkAbandoned=false; Audio_.pending=[];
  let played=0;
  const origPlay=Audio_.play.bind(Audio_);
  Audio_.play=function(f){ played++; return origPlay(f); };
  for(let k=0;k<90;k++){
    const f=new Float32Array(160); f.fill(0.05);
    Audio_.push(f);
  }
  const r={abandoned:!!Audio_.sinkAbandoned, left:(Audio_.pending||[]).length, played};
  Audio_.play=origPlay;
  Audio_.sink=bak.sink; Audio_.pending=bak.pending||[]; Audio_.sinkPending=false;
  return r;
})()`);
ok('攒帧溢出后认输（sinkAbandoned）', wd.abandoned === true);
ok('攒下的帧交给了逐帧排期，没被吃掉', wd.played > 70, `→ ${wd.played} 帧`);
ok('pending 已清空', wd.left === 0);

console.log('\n=== 6. 运行时错误 ===');
ok('页面没有未捕获异常', errs.length === 0, errs.length ? '→ ' + errs.slice(0, 3).join(' | ') : '');

console.log('\n' + '='.repeat(18) + `  ${pass} 通过 / ${fail} 失败  ` + '='.repeat(18));
ws.close(); chrome.kill();
process.exit(fail ? 1 : 0);
