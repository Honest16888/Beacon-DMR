// 把录音画成「波形 + 声谱图」PNG，用来肉眼定位噼啪/断流
//
//   node tests/plot_m4a.js <音频> <输出png>
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const FILE = process.argv[2], OUT = process.argv[3] || 'ana.png';
const T0 = Number(process.argv[4] || 0), T1 = Number(process.argv[5] || 0);   // 可选：只画这一段
if (!FILE || !fs.existsSync(FILE)) { console.error('用法: node tests/plot_m4a.js <音频> <out.png>'); process.exit(2); }

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROFILE = path.join(os.tmpdir(), 'beacon-plot-' + Date.now());
const PORT = 9421;
const sleep = ms => new Promise(r => setTimeout(r, ms));

const srv = http.createServer((req, res) => {
  if (req.url.startsWith('/a')) {
    res.writeHead(200, { 'Content-Type': 'audio/mp4', 'Access-Control-Allow-Origin': '*' });
    fs.createReadStream(FILE).pipe(res); return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end('<!doctype html><meta charset="utf-8"><body></body>');
});
await new Promise(r => srv.listen(PORT, '127.0.0.1', r));

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', '--remote-debugging-port=9422', `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', '--window-size=1600,900', 'about:blank',
], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try {
      const j = await (await fetch('http://127.0.0.1:9422/json/list')).json();
      const p = j.find(t => t.type === 'page'); if (p) return p.webSocketDebuggerUrl;
    } catch (e) {}
    await sleep(200);
  }
  throw new Error('连不上 Chrome');
}
const ws = new WebSocket(await wsUrl());
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const waiters = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && waiters.has(m.id)) { waiters.get(m.id)(m); waiters.delete(m.id); }
});
const send = (method, params = {}) => {
  const myId = ++id; ws.send(JSON.stringify({ id: myId, method, params }));
  return new Promise(r => waiters.set(myId, r));
};
async function evalJs(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 800));
  return r.result?.result?.value;
}

await send('Page.enable'); await send('Runtime.enable');
await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` });
await sleep(700);

const CODE = `(async()=>{
  const ac=new AudioContext();
  const ab=await ac.decodeAudioData(await (await fetch('/a')).arrayBuffer());
  const sr=ab.sampleRate, n=ab.length;
  let x=new Float32Array(n);
  for(let c=0;c<ab.numberOfChannels;c++){const d=ab.getChannelData(c);for(let i=0;i<n;i++)x[i]+=d[i]/ab.numberOfChannels;}
  const T0=${T0}, T1=${T1};
  if(T1>T0){ const a=Math.floor(T0*sr), b=Math.min(n,Math.floor(T1*sr)); x=x.subarray(a,b); }
  const n2=x.length;
  let peak=0; for(let i=0;i<n2;i++){const a=Math.abs(x[i]); if(a>peak)peak=a;}
  const g=peak>0?1/peak:1;

  const W=1560, HW=200, SH=420, TOT=HW+SH+70;
  const cv=document.createElement('canvas'); cv.width=W; cv.height=TOT;
  const cx=cv.getContext('2d');
  cx.fillStyle='#0d1117'; cx.fillRect(0,0,W,TOT);

  // ---- 波形：每列取 min/max ----
  const per=n2/W;
  cx.strokeStyle='#3fb950'; cx.lineWidth=1;
  for(let px=0;px<W;px++){
    const a=Math.floor(px*per), b=Math.min(n2,Math.floor((px+1)*per));
    let mn=0,mx=0;
    for(let i=a;i<b;i++){const v=x[i]*g; if(v<mn)mn=v; if(v>mx)mx=v;}
    const mid=HW/2+20;
    cx.beginPath(); cx.moveTo(px+20, mid-mx*(HW/2-24)); cx.lineTo(px+20, mid-mn*(HW/2-24)); cx.stroke();
  }
  // 满刻度参考线
  cx.strokeStyle='#f85149'; cx.globalAlpha=.5;
  cx.beginPath(); cx.moveTo(20,HW/2+20-(HW/2-24)); cx.lineTo(W+20,HW/2+20-(HW/2-24)); cx.stroke();
  cx.beginPath(); cx.moveTo(20,HW/2+20+(HW/2-24)); cx.lineTo(W+20,HW/2+20+(HW/2-24)); cx.stroke();
  cx.globalAlpha=1;
  cx.fillStyle='#8b949e'; cx.font='12px monospace';
  cx.fillText('waveform (normalized, red = full scale)', 22, 14);

  // ---- 声谱图 ----
  const N=1024, hop=Math.max(1,Math.floor(n2/(W*1)));
  const cols=Math.min(W, Math.floor((n2-N)/hop)+1);
  const bins=N/2;
  function fft(re,im){
    const L=re.length;
    for(let i=1,j=0;i<L;i++){let bit=L>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;
      if(i<j){let t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
    for(let len=2;len<=L;len<<=1){
      const ang=-2*Math.PI/len, wr=Math.cos(ang), wi=Math.sin(ang);
      for(let i=0;i<L;i+=len){let cr=1,ci=0;
        for(let k=0;k<len/2;k++){
          const ur=re[i+k],ui=im[i+k];
          const vr=re[i+k+len/2]*cr-im[i+k+len/2]*ci;
          const vi=re[i+k+len/2]*ci+im[i+k+len/2]*cr;
          re[i+k]=ur+vr;im[i+k]=ui+vi;re[i+k+len/2]=ur-vr;im[i+k+len/2]=ui-vi;
          const nc=cr*wr-ci*wi;ci=cr*wi+ci*wr;cr=nc;}}}
  }
  const re=new Float64Array(N), im=new Float64Array(N);
  const img=cx.createImageData(cols,SH);
  const dbmin=-90, dbmax=-10;
  for(let c=0;c<cols;c++){
    const off=c*hop;
    for(let i=0;i<N;i++){re[i]=x[off+i]*g*(0.5-0.5*Math.cos(2*Math.PI*i/(N-1))); im[i]=0;}
    fft(re,im);
    for(let y=0;y<SH;y++){
      const f0=(T1>T0) ? (12000*(SH-1-y)/SH) : (40*Math.pow(20000/40,(SH-1-y)/SH));
      const k=Math.min(N/2-1,Math.max(1,Math.round(f0*N/sr)));
      const p=re[k]*re[k]+im[k]*im[k];
      let db=10*Math.log10(p+1e-20);
      let t=(db-dbmin)/(dbmax-dbmin); t=Math.max(0,Math.min(1,t));
      // 热力色
      const r=Math.round(255*Math.min(1,Math.max(0,1.6*t-0.6)));
      const gg=Math.round(255*Math.min(1,Math.max(0,1.6*t-0.15)*(1-Math.max(0,t-0.75))));
      const bb=Math.round(255*Math.min(1,Math.max(0,t<0.5?t*1.6:(1-t)*2.4)));
      const o=(y*cols+c)*4;
      img.data[o]=r; img.data[o+1]=gg; img.data[o+2]=bb; img.data[o+3]=255;
    }
  }
  cx.putImageData(img, 20, HW+40);
  cx.fillStyle='#8b949e';
  cx.fillText('spectrogram 40Hz-20kHz log', 22, HW+34);
  [200,500,1000,2000,4000,8000,16000].forEach(f=>{
    const y=(T1>T0) ? (HW+40+SH*(1-f/12000)) : (HW+40+SH*(1-Math.log(f/40)/Math.log(20000/40)));
    cx.fillStyle='#30363d'; cx.fillRect(20,y,W,1);
    cx.fillStyle='#8b949e'; cx.fillText(f>=1000?(f/1000)+'k':''+f, W+26, y+4);
  });
  return cv.toDataURL('image/png');
})()`;

const dataUrl = await evalJs(CODE);
fs.writeFileSync(OUT, Buffer.from(dataUrl.split(',')[1], 'base64'));
console.log('已写出', OUT, fs.statSync(OUT).size, 'bytes');
ws.close(); chrome.kill(); srv.close(); process.exit(0);
