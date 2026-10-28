/* 临时截图脚本：把顶部收听区渲染成「正在接收」的状态再截屏。
   只用于视觉校对，不参与测试。用法：
     CHROME_PATH=... node tests/shot_nowbar.js [宽] [高] [输出文件]
*/
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_PATH;
const PAGE = pathToFileURL(path.join(ROOT, 'web', 'index.html')).href;
const PROFILE = path.join(os.tmpdir(), 'shot-' + Date.now());
const PORT = 9351;
const W = Number(process.argv[2] || 390);
const H = Number(process.argv[3] || 844);
const OUT = process.argv[4] || path.join(ROOT, 'dist', 'shot-nowbar.png');

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
ws.onmessage = e => {
  const m = JSON.parse(e.data);
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
  { width: W, height: H, deviceScaleFactor: 2, mobile: true }, S);
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

/* 造一个「BA7NCT 在 46001 说话、已说 6 秒、正在录音、原生接收」的稳定画面。
   必须把 Q.rxing / state.rxDb 也设上 —— 手机走的是原生 MMDVM，
   HOSE 不解码，Audio_.level 恒为 0，不设这两个波形就是一条平线。 */
await ev(`
  try{spotterClose();}catch(e){} try{rwClose();}catch(e){}
  await new Promise(r=>setTimeout(r,120));
  document.documentElement.dataset.theme='${process.env.THEME || 'light'}';
  state.net='bm';
  state.subscribed=[46001,91];
  state.audioOn=true;
  state.ham['BA7NCT']={call:'BA7NCT',name:'National Emergency Network Coordinator'};
  state.current={srcId:4602523,tg:46001,alias:'BA7NCT National Emergency Network Coordinator',start:Date.now()-6000};
  RXG.tg=46001; RXG.at=Date.now();
  Q.rxing=true; Q.last={srcId:4602523,dstId:46001,slot:1,priv:false};
  state.rxDb=-24.5;
  state.recOn=true; state.recActive=true;
  renderNow(); renderRecBtn();
  /* MODE=hose 模拟「只用 Hoseline 收听」：没有原生 rxlevel，
     dB 必须由 renderVu 用 Audio_.level 自己算出来。 */
  if('${process.env.MODE || ''}'==='hose'){
    Q.rxing=false; Q.last=null; state.rxDb=NaN; Audio_.level=0.05;
  }
  const db=document.getElementById('npDb');
  if(db&&'${process.env.MODE || ''}'!=='hose'){ db.textContent='−24.5 dB'; db.dataset.lv='ok'; }
  const t=document.getElementById('npTime'); if(t)t.textContent='6.0s';
  return true;`);
await sleep(500);

/* 顺便把关键节点的实际位置打出来，布局问题不靠肉眼猜 */
const rects = await ev(`
  const r=id=>{const e=document.getElementById(id);if(!e)return null;
    const b=e.getBoundingClientRect();return {x:Math.round(b.x),w:Math.round(b.width)};};
  const row=document.querySelector('.np-r1');
  return {rowW:row?Math.round(row.getBoundingClientRect().width):0,
          lamp:r('npLamp'), call:r('npCall'), vu:r('vu'),
          db:r('npDb'), time:r('npTime'),
          order:[...row.children].map(e=>e.id||e.className)};`);
console.log('布局：', JSON.stringify(rects));

const shot = await send('Page.captureScreenshot',
  { format: 'png', clip: { x: 0, y: 0, width: W, height: 430, scale: 1 } }, S);
fs.writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log('已写入 ' + OUT);

chrome.kill();
process.exit(0);
