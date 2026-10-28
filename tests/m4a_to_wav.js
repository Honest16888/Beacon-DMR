// m4a → 16 kHz 单声道 WAV（用 Chrome 解码，本机没有 ffmpeg）
//
//   node tests/m4a_to_wav.js <输入音频> <输出.wav> [采样率]
//
// 之所以要转：离线 ASR 模型都吃 16 kHz 单声道 float PCM，
// 而手机录音是 48 kHz 立体声 AAC。
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const FILE = process.argv[2], OUT = process.argv[3];
const RATE = Number(process.argv[4] || 16000);
if (!FILE || !OUT || !fs.existsSync(FILE)) { console.error('用法: node tests/m4a_to_wav.js <音频> <out.wav> [rate]'); process.exit(2); }

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROFILE = path.join(os.tmpdir(), 'beacon-wav-' + Date.now());
const PORT = 9461;
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
const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=9462',
  `--user-data-dir=${PROFILE}`, '--no-first-run', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', 'about:blank'], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try {
      const j = await (await fetch('http://127.0.0.1:9462/json/list')).json();
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
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 600));
  return r.result?.result?.value;
}
await send('Page.enable'); await send('Runtime.enable');
await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` });
await sleep(700);

const b64 = await ev(`(async()=>{
  const RATE=${RATE};
  const ac=new AudioContext();
  const ab=await ac.decodeAudioData(await (await fetch('/a')).arrayBuffer());
  // 单声道化
  const ch=ab.numberOfChannels, n=ab.length;
  const mono=new Float32Array(n);
  for(let c=0;c<ch;c++){ const d=ab.getChannelData(c); for(let i=0;i<n;i++)mono[i]+=d[i]/ch; }
  // 重采样到目标采样率（线性插值，够用）
  const oc=new OfflineAudioContext(1, Math.round(n*RATE/ab.sampleRate), RATE);
  const buf=oc.createBuffer(1,n,ab.sampleRate);
  buf.getChannelData(0).set(mono);
  const src=oc.createBufferSource(); src.buffer=buf;
  src.connect(oc.destination); src.start(0);
  const out=await oc.startRendering();
  const d=out.getChannelData(0);
  // 打包成 16 bit PCM WAV
  const nn=d.length, abuf=new ArrayBuffer(44+nn*2), v=new DataView(abuf);
  const ws=(o,s)=>{for(let i=0;i<s.length;i++)v.setUint8(o+i,s.charCodeAt(i));};
  ws(0,'RIFF'); v.setUint32(4,36+nn*2,true); ws(8,'WAVEfmt ');
  v.setUint32(16,16,true); v.setUint16(20,1,true); v.setUint16(22,1,true);
  v.setUint32(24,RATE,true); v.setUint32(28,RATE*2,true); v.setUint16(32,2,true);
  v.setUint16(34,16,true); ws(36,'data'); v.setUint32(40,nn*2,true);
  for(let i=0;i<nn;i++){ let s=Math.max(-1,Math.min(1,d[i])); v.setInt16(44+i*2, s<0?s*0x8000:s*0x7fff, true); }
  // 转 base64
  const u8=new Uint8Array(abuf); let bin='';
  const CH=0x8000;
  for(let i=0;i<u8.length;i+=CH) bin+=String.fromCharCode.apply(null,u8.subarray(i,i+CH));
  return btoa(bin);
})()`);

fs.writeFileSync(OUT, Buffer.from(b64, 'base64'));
console.log('已写出', OUT, fs.statSync(OUT).size, 'bytes', `(${RATE} Hz mono)`);
ws.close(); chrome.kill(); srv.close(); process.exit(0);
