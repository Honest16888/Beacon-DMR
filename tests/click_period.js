// 找「周期性」的咔哒：高频包络自相关
//
//   node tests/click_period.js <音频>
//
// 播放链路是「每帧建一个 AudioBuffer → start()」。若每帧拼接处有微小不连续，
// 就会以帧率（20ms / 45ms / 60ms）规律地咔一下，听感是「噼啪」。
// 这种周期性的唯一指纹是：高频能量包络在固定间隔上自相关出峰。
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const FILE = process.argv[2];
if (!FILE || !fs.existsSync(FILE)) { console.error('用法: node tests/click_period.js <音频>'); process.exit(2); }
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROFILE = path.join(os.tmpdir(), 'beacon-cp-' + Date.now());
const PORT = 9431;
const sleep = ms => new Promise(r => setTimeout(r, ms));

const srv = http.createServer((req, res) => {
  if (req.url.startsWith('/a')) {
    res.writeHead(200, { 'Content-Type': 'audio/mp4', 'Access-Control-Allow-Origin': '*' });
    fs.createReadStream(FILE).pipe(res); return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('<!doctype html><body>');
});
await new Promise(r => srv.listen(PORT, '127.0.0.1', r));
fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=9432',
  `--user-data-dir=${PROFILE}`, '--no-first-run', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', 'about:blank'], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try { const j = await (await fetch('http://127.0.0.1:9432/json/list')).json();
      const p = j.find(t => t.type === 'page'); if (p) return p.webSocketDebuggerUrl; } catch (e) {}
    await sleep(200);
  }
  throw new Error('no chrome');
}
const ws = new WebSocket(await wsUrl());
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const w = new Map();
ws.addEventListener('message', ev => { const m = JSON.parse(ev.data); if (m.id && w.has(m.id)) { w.get(m.id)(m); w.delete(m.id); } });
const send = (method, params = {}) => { const i = ++id; ws.send(JSON.stringify({ id: i, method, params })); return new Promise(r => w.set(i, r)); };
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 600));
  return r.result?.result?.value;
}
await send('Page.enable'); await send('Runtime.enable');
await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` });
await sleep(700);

const out = await ev(`(async()=>{
  const ac=new AudioContext();
  const ab=await ac.decodeAudioData(await (await fetch('/a')).arrayBuffer());
  const sr=ab.sampleRate, n=ab.length;
  const x=new Float32Array(n);
  for(let c=0;c<ab.numberOfChannels;c++){const d=ab.getChannelData(c);for(let i=0;i<n;i++)x[i]+=d[i]/ab.numberOfChannels;}
  let peak=0; for(let i=0;i<n;i++)peak=Math.max(peak,Math.abs(x[i]));
  const g=peak?1/peak:1;

  // 高频 = 二阶差分（≈ 6dB/oct 高通），对 4kHz 以上最敏感
  const d2=new Float32Array(n);
  for(let i=2;i<n;i++) d2[i]=Math.abs(x[i]*g - 2*x[i-1]*g + x[i-2]*g);

  const hop=Math.round(sr*0.002);          // 2ms
  const nf=Math.floor(n/hop);
  const env=new Float64Array(nf);
  for(let f=0;f<nf;f++){
    let s=0;
    for(let i=f*hop;i<Math.min(n,(f+1)*hop);i++) s+=d2[i]*d2[i];
    env[f]=Math.sqrt(s/hop);
  }
  // 只在有语音的地方算（高于中位数的帧），静音段的自相关没有意义
  const srt=Array.from(env).sort((a,b)=>a-b);
  const med=srt[srt.length>>1];
  const mean=env.reduce((a,b)=>a+b,0)/nf;
  const c=new Float64Array(nf);
  for(let f=0;f<nf;f++) c[f]=env[f]-mean;

  const res=[];
  for(let lag=3;lag<=60;lag++){          // 6ms .. 120ms
    let s=0, e1=0, e2=0;
    for(let f=0;f+lag<nf;f++){ s+=c[f]*c[f+lag]; e1+=c[f]*c[f]; e2+=c[f+lag]*c[f+lag]; }
    const r=(e1>0&&e2>0)? s/Math.sqrt(e1*e2) : 0;
    res.push({ ms:+(lag*hop/sr*1000).toFixed(1), r:+r.toFixed(4) });
  }
  res.sort((a,b)=>b.r-a.r);
  return { sr, dur:+(n/sr).toFixed(2), peak:+peak.toFixed(3), med:+med.toFixed(5), top:res.slice(0,8) };
})()`);
console.log(JSON.stringify(out, null, 1));
ws.close(); chrome.kill(); srv.close(); process.exit(0);
