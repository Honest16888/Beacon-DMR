/* 截图 + 自检：把「频谱」页签喂上合成的 CW，确认抽头 → 重采样 → 瀑布图整条链通了。
   只用于视觉/联调校对，不参与回归。用法：
     CHROME_PATH=... node tests/shot_cw.js [宽] [高] [输出文件]
*/
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_PATH;
const PAGE = pathToFileURL(path.join(ROOT, 'web', 'index.html')).href;
const PROFILE = path.join(os.tmpdir(), 'shot-cw-' + Date.now());
const PORT = 9357;
const W = Number(process.argv[2] || 390);
const H = Number(process.argv[3] || 844);
const OUT = process.argv[4] || path.join(ROOT, 'tests', 'out', 'shot-cw.png');

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--hide-scrollbars', 'about:blank',
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
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.exceptionThrown') {
    errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
  }
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
};
const send = (method, params = {}, sessionId) => new Promise(res => {
  const id = ++msgId; waiting.set(id, res);
  ws.send(JSON.stringify({ id, method, params, sessionId }));
});

const t = await send('Target.getTargets');
const page = t.result.targetInfos.find(x => x.type === 'page');
const att = await send('Target.attachToTarget', { targetId: page.targetId, flatten: true });
const S = att.result.sessionId;
await send('Page.enable', {}, S);
await send('Runtime.enable', {}, S);
await send('Emulation.setDeviceMetricsOverride',
  { width: W, height: H, deviceScaleFactor: 2, mobile: process.env.MOBILE !== '0' }, S);
await send('Page.navigate', { url: PAGE }, S);
await sleep(1800);

const ev = async expr => {
  const r = await send('Runtime.evaluate',
    { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true }, S);
  if (r.result?.exceptionDetails) {
    console.error('注入失败：', r.result.exceptionDetails.exception?.description);
  }
  return r.result?.result?.value;
};

/* 1. 打开频谱页签 */
const opened = await ev(`
  const b=document.querySelector('[data-tab="cw"]');
  if(!b) return {ok:false,why:'没有频谱页签'};
  b.click();
  await new Promise(r=>setTimeout(r,150));
  return {ok:!!document.getElementById('cwScope'),
          存在:!!document.getElementById('cwScope'),
          尺寸:document.getElementById('cwScope')
            ? [document.getElementById('cwScope').clientWidth,
               document.getElementById('cwScope').clientHeight] : null};
`);
console.log('页签：', JSON.stringify(opened));

/* 2. 合成 20 WPM / 700 Hz 的 CW，编码成 μ-law，走 Audio_.decode() 真正的那条路 */
const fed = await ev(`
  const sr=8000, wpm=20, tone=700, dot=1.2/wpm;
  const text='CQ CQ DE BH6RGQ K';
  const M={A:'.-',B:'-...',C:'-.-.',D:'-..',E:'.',F:'..-.',G:'--.',H:'....',I:'..',J:'.---',
    K:'-.-',L:'.-..',M:'--',N:'-.',O:'---',P:'.--.',Q:'--.-',R:'.-.',S:'...',T:'-',U:'..-',
    V:'...-',W:'.--',X:'-..-',Y:'-.--',Z:'--..',0:'-----',1:'.----',2:'..---',3:'...--',
    4:'....-',5:'.....',6:'-....',7:'--...',8:'---..',9:'----.'};
  const segs=[];
  for(const ch of text){ if(ch===' '){segs.push([7*dot,0]);continue;}
    const c=M[ch]; if(!c)continue;
    for(let i=0;i<c.length;i++){ segs.push([(c[i]==='.'?1:3)*dot,1]); if(i<c.length-1)segs.push([dot,0]); }
    segs.push([3*dot,0]); }
  let total=0; for(const s of segs) total+=s[0];
  const reps=Math.max(1, Math.ceil(6/total));
  const N=Math.round(total*reps*sr);
  const pcm=new Float32Array(N);
  let pos=0;
  for(let r=0;r<reps;r++) for(const [d,on] of segs){
    const n=Math.round(d*sr);
    if(on)for(let i=0;i<n&&pos+i<N;i++){
      const ramp=Math.min(40,n>>1);
      let env=1;
      if(i<ramp)env=i/ramp; else if(i>n-ramp)env=(n-i)/ramp;
      pcm[pos+i]+=0.35*env*Math.sin(2*Math.PI*tone*i/sr);
    }
    pos+=n;
  }
  /* 加点白噪声，让背景不是纯黑 */
  let seed=12345;
  for(let i=0;i<N;i++){ seed=(seed*1664525+1013904223)>>>0; pcm[i]+=((seed>>>8)/0x1000000-0.5)*0.03; }

  /* 标准 μ-law 编码（G.711） */
  const enc=s=>{
    const BIAS=0x84, CLIP=32635;
    let sign=s<0?0x80:0;
    let mag=Math.min(CLIP,Math.round(Math.abs(s)*32767))+BIAS;
    let exp=7;
    for(let mask=0x4000;(mag&mask)===0&&exp>0;exp--,mask>>=1);
    const mant=(mag>>(exp+3))&0x0F;
    return (~(sign|(exp<<4)|mant))&0xFF;
  };
  const before=CW.cn;
  const t0=performance.now();
  for(let i=0;i<N;i+=160){
    const n=Math.min(160,N-i);
    const u=new Uint8Array(n);
    for(let j=0;j<n;j++)u[j]=enc(pcm[i+j]);
    Audio_.decode(u);            // ← 真正的抽头就是这一行里调的
  }
  const ms=performance.now()-t0;
  return {before, after:CW.cn, samples:CW.total, noise:+CW.noise.toFixed(4),
          ms:+ms.toFixed(1), 每秒音频耗时ms:+(ms/(N/sr)).toFixed(1)};
`);
console.log('喂数据：', JSON.stringify(fed));

await sleep(600);
/* 文档用截图：PTT 悬浮钮会盖在瀑布图上，截之前先摘掉 */
await ev(`const p=document.getElementById('txPtt'); if(p)p.style.display='none'; return true;`);
/* 3. 原生那条路：伪造一条 rwaudio 事件，确认 base64 → Int16 → CW.feed 这段也通 */
const nativeEv = await ev(`
  const before=CW.cn;
  const n=1600, s=new Int16Array(n);
  for(let i=0;i<n;i++) s[i]=Math.round(6000*Math.sin(2*Math.PI*700*i/8000));
  const bytes=new Uint8Array(s.buffer);
  let bin=''; for(let i=0;i<bytes.length;i++) bin+=String.fromCharCode(bytes[i]);
  window.onDmrEvent(JSON.stringify({type:'rwaudio', b64:btoa(bin)}));
  await new Promise(r=>setTimeout(r,120));
  return {before, after:CW.cn};
`);
console.log('原生事件：', JSON.stringify(nativeEv));

const rect = await ev(`
  const el=document.getElementById('tabPanel');
  const b=el.getBoundingClientRect();
  return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height)};`);
console.log('面板：', JSON.stringify(rect));

const shot = await send('Page.captureScreenshot',
  { format: 'png', clip: { x: 0, y: 0, width: W, height: H, scale: 1 } }, S);
fs.writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log('已写入 ' + OUT);
if (errors.length) console.log('页面异常：' + JSON.stringify(errors));

chrome.kill();
process.exit(0);
