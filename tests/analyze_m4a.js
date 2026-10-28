// 录音取证：解码 m4a → 统计削波 / 不连续点 / 咔哒间隔
//
//   node tests/analyze_m4a.js "C:/path/to/x.m4a"
//
// 本机没有 ffmpeg，所以借 Chrome 的 decodeAudioData 解 AAC。
// 输出的是判断「噼啪」成因要用的几个量：
//   · 硬削波占比        → 增益过大 / 出口没限幅
//   · 最大相邻样本跳变  → 断流（缓冲挖坑）
//   · 咔哒间隔分布      → 等间隔(20/60ms) = buffer 边界；不规则 = 欠载
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const FILE = process.argv[2];
if (!FILE || !fs.existsSync(FILE)) { console.error('用法: node tests/analyze_m4a.js <音频文件>'); process.exit(2); }

const CHROME = process.env.CHROME_PATH || (
  process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : '/usr/bin/google-chrome'
);
const PROFILE = path.join(os.tmpdir(), 'beacon-ana-' + Date.now());
const PORT = 9411;
const sleep = ms => new Promise(r => setTimeout(r, ms));

const srv = http.createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/probe')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end('<!doctype html><meta charset="utf-8"><title>probe</title><body>probe</body>');
    return;
  }
  if (req.url.startsWith('/a')) {
    res.writeHead(200, { 'Content-Type': 'audio/mp4', 'Access-Control-Allow-Origin': '*' });
    fs.createReadStream(FILE).pipe(res);
    return;
  }
  res.writeHead(404); res.end();
});
await new Promise(r => srv.listen(PORT, '127.0.0.1', r));

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=9412`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', `--window-size=800,600`, 'about:blank',
], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch('http://127.0.0.1:9412/json/list');
      const j = await r.json();
      const p = j.find(t => t.type === 'page');
      if (p) return p.webSocketDebuggerUrl;
    } catch (e) { /* 还没起来 */ }
    await sleep(200);
  }
  throw new Error('连不上 Chrome');
}

const ws = new WebSocket(await wsUrl());
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0;
const waiters = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && waiters.has(m.id)) { waiters.get(m.id)(m); waiters.delete(m.id); }
});
function send(method, params = {}) {
  const myId = ++id;
  ws.send(JSON.stringify({ id: myId, method, params }));
  return new Promise(r => waiters.set(myId, r));
}
async function evalJs(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
  return r.result?.result?.value;
}

await send('Page.enable');
await send('Runtime.enable');
await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/probe` });
await sleep(700);

const STATS = `(async()=>{
  const ac=new AudioContext();
  const buf=await (await fetch('/a.m4a')).arrayBuffer();
  const ab=await ac.decodeAudioData(buf);
  const sr=ab.sampleRate;
  // 单声道化
  const n=ab.length, ch=ab.numberOfChannels;
  const x=new Float32Array(n);
  for(let c=0;c<ch;c++){ const d=ab.getChannelData(c); for(let i=0;i<n;i++)x[i]+=d[i]/ch; }

  let peak=0, sum=0, clip=0, nan=0;
  for(let i=0;i<n;i++){
    const v=x[i];
    if(!isFinite(v)){ nan++; continue; }
    const a=Math.abs(v);
    if(a>peak)peak=a;
    sum+=v*v;
    if(a>=0.999)clip++;
  }
  const rms=Math.sqrt(sum/n);
  // 每秒削波数，用来定位「什么时候炸」
  const perSec=[];
  for(let s=0;s* sr<n;s++){
    let c=0;
    for(let i=s*sr;i<Math.min(n,(s+1)*sr);i++) if(Math.abs(x[i])>=0.999)c++;
    perSec.push(c);
  }

  // 相邻样本跳变。语音本身 8k 采样时相邻跳变也有，但不会接近满刻度。
  // 断流/拼接处的跳变会远大于局部能量。
  //
  // ★ 录音是拿手机喇叭对着录的，整体电平很低（peak 0.34），
  // 所以阈值不能按满刻度设，先归一化到 1.0 再判。
  const g = peak > 1e-6 ? 1 / peak : 1;
  for (let i = 0; i < n; i++) x[i] *= g;
  const d=new Float32Array(n);
  for(let i=1;i<n;i++)d[i]=Math.abs(x[i]-x[i-1]);

  // 短时高频能量：咔哒/噼啪在 4kHz 以上，语音主体在 3kHz 以下。
  // 用一阶差分的局部 RMS 当高频能量的廉价近似。
  const hop=Math.max(1,Math.round(sr*0.005));      // 5ms
  const nf=Math.floor(n/hop);
  const hf=new Float32Array(nf);
  for(let f=0;f<nf;f++){
    let s=0;
    for(let i=f*hop;i<(f+1)*hop&&i<n;i++)s+=d[i]*d[i];
    hf[f]=Math.sqrt(s/hop);
  }
  // 每个 5ms 帧和它前后各 40ms 的中位数比，突出来的就是咔哒
  const spikes=[];
  for(let f=0;f<nf;f++){
    const a=Math.max(0,f-8), b=Math.min(nf-1,f+8);
    const seg=Array.from(hf.slice(a,b+1)).sort((p,q)=>p-q);
    const med=seg[seg.length>>1]||0;
    if(hf[f]>0.05 && hf[f]>med*4) spikes.push({t:+(f*hop/sr).toFixed(3), r:+(hf[f]/(med||1e-9)).toFixed(1)});
  }
  // 合并同一处
  const merged=[];
  for(const c of spikes){
    const last=merged[merged.length-1];
    if(last&&c.t-last.t<0.012){ if(c.r>last.r)last.r=c.r; }
    else merged.push({...c});
  }

  const gaps=[];
  for(let i=1;i<merged.length;i++)gaps.push(+((merged[i].t-merged[i-1].t)*1000).toFixed(1));
  const hist={};
  for(const gp of gaps){ const k=gp<25?'<25':gp<80?'25-80':gp<150?'80-150':gp<300?'150-300':'>=300'; hist[k]=(hist[k]||0)+1; }
  // 能量包络（每 50ms 一个数），用来看断流/空洞
  const env=[];
  const eh=Math.round(sr*0.05);
  for(let i=0;i+eh<=n;i+=eh){
    let s=0; for(let k=i;k<i+eh;k++)s+=x[k]*x[k];
    env.push(+Math.sqrt(s/eh).toFixed(3));
  }

  let zc=0;
  for(let i=1;i<n;i++) if((x[i-1]<0)!==(x[i]<0)) zc++;

  /* ---- 频谱：平均功率谱 + 高频占比随时间 ---- */
  const N=1024, HOP2=512;
  function fft(re,im){
    const L=re.length;
    for(let i=1,j=0;i<L;i++){
      let bit=L>>1;
      for(;j&bit;bit>>=1)j^=bit;
      j^=bit;
      if(i<j){ let t=re[i];re[i]=re[j];re[j]=t; t=im[i];im[i]=im[j];im[j]=t; }
    }
    for(let len=2;len<=L;len<<=1){
      const ang=-2*Math.PI/len, wr=Math.cos(ang), wi=Math.sin(ang);
      for(let i=0;i<L;i+=len){
        let cr=1,ci=0;
        for(let k=0;k<len/2;k++){
          const ur=re[i+k], ui=im[i+k];
          const vr=re[i+k+len/2]*cr-im[i+k+len/2]*ci;
          const vi=re[i+k+len/2]*ci+im[i+k+len/2]*cr;
          re[i+k]=ur+vr; im[i+k]=ui+vi;
          re[i+k+len/2]=ur-vr; im[i+k+len/2]=ui-vi;
          const ncr=cr*wr-ci*wi; ci=cr*wi+ci*wr; cr=ncr;
        }
      }
    }
  }
  const bins=N/2;
  const avg=new Float64Array(bins);
  let frames=0;
  const hfSeries=[];
  const re=new Float64Array(N), im=new Float64Array(N);
  for(let off=0;off+N<=n;off+=HOP2){
    for(let i=0;i<N;i++){ re[i]=x[off+i]*(0.5-0.5*Math.cos(2*Math.PI*i/(N-1))); im[i]=0; }
    fft(re,im);
    let lo=0, hi=0, all=0;
    for(let k=1;k<bins;k++){
      const p=re[k]*re[k]+im[k]*im[k];
      avg[k]+=p; all+=p;
      const f=k*sr/N;
      if(f<3000)lo+=p; else if(f<16000)hi+=p;
    }
    frames++;
    hfSeries.push({ t:+(off/sr).toFixed(3), r:+(all>1e-12?hi/all:0).toFixed(3), all:+all.toFixed(2) });
  }
  for(let k=0;k<bins;k++)avg[k]/=Math.max(1,frames);

  // 32 个对数频段的相对 dB
  const bands=[];
  for(let b=0;b<32;b++){
    const f0=40*Math.pow(20000/40,b/32), f1=40*Math.pow(20000/40,(b+1)/32);
    let s=0,c=0;
    for(let k=1;k<bins;k++){ const f=k*sr/N; if(f>=f0&&f<f1){ s+=avg[k]; c++; } }
    bands.push({ hz:Math.round((f0+f1)/2), db:+(10*Math.log10((s/Math.max(1,c))+1e-20)).toFixed(1) });
  }
  const mx=Math.max(...bands.map(b=>b.db));

  // 高频占比异常帧（咔哒/噪声爆发）
  const sorted=hfSeries.map(o=>o.r).sort((a,b)=>a-b);
  const med=sorted[sorted.length>>1]||0;
  const hfHot=hfSeries.filter(o=>o.r>med*2.2&&o.all>0.5).slice(0,25);

  // 高频包络自相关：等间隔重复 = buffer 边界 / 周期性欠载
  const env2=hfSeries.map(o=>o.r);
  const lagMs=[];
  for(let lag=2;lag<=40;lag++){          // 2*512/48k ≈ 21ms 步长 → 覆盖到 ~0.4s
    let s=0,c=0;
    for(let i=0;i+lag<env2.length;i++){ s+=env2[i]*env2[i+lag]; c++; }
    lagMs.push({ ms:+(lag*HOP2/sr*1000).toFixed(1), v:+(c?s/c:0).toFixed(4) });
  }
  const acMax=Math.max(...lagMs.map(o=>o.v));
  const acTop=lagMs.filter(o=>o.v>acMax*0.75).slice(0,5);

  return {sr, ch, dur:+(n/sr).toFixed(2), peak:+peak.toFixed(4), rms:+rms.toFixed(4),
          clip, clipPct:+(clip/n*100).toFixed(3), nan,
          perSec, clicks:merged.slice(0,30), nClicks:merged.length,
          gaps:gaps.slice(0,30), hist, env,
          zcr:+(zc/n*sr/2).toFixed(0),
          bands:bands.map(b=>({hz:b.hz, db:+(b.db-mx).toFixed(1)})),
          hfMed:+med.toFixed(3), hfHot, acTop};
})()`;

const st = await evalJs(STATS);
console.log(JSON.stringify(st, null, 1));

ws.close();
chrome.kill();
srv.close();
process.exit(0);
