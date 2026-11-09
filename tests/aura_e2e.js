// AURA 端到端：真起一个 mock hub，真开无头 Chrome（带假麦克风），
// 走完 注册 → 绑定 → 登录 → bootstrap → WS join → talk → 收发音频。
//
//   python tests/aura_mock_hub.py --port 8099 &
//   CHROME_PATH=... node tests/aura_e2e.js
//
// 用假麦克风：--use-fake-device-for-media-stream 会给一个 440 Hz 的正弦，
// --use-fake-ui-for-media-stream 让 getUserMedia 不弹框。
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const CHROME = process.env.CHROME_PATH || (
  process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : '/usr/bin/google-chrome'
);
const HUB = process.env.AURA_HUB || 'http://127.0.0.1:8099';
const WEB_PORT = Number(process.env.E2E_PORT || 9355);
const CDP_PORT = Number(process.env.E2E_CDP || 9360);

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8' };
const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '');
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT)) { res.writeHead(403).end(); return; }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404).end('nf'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
});
await new Promise(r => server.listen(WEB_PORT, '127.0.0.1', r));

const PROFILE = path.join(os.tmpdir(), 'aura-e2e-' + Date.now());
fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  '--autoplay-policy=no-user-gesture-required', '--window-size=390,844', 'about:blank',
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));
let wsUrl;
for (let i = 0; i < 60 && !wsUrl; i++) {
  try {
    const l = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json();
    wsUrl = l.find(x => x.type === 'page')?.webSocketDebuggerUrl;
  } catch {}
  if (!wsUrl) await sleep(250);
}
if (!wsUrl) { console.error('CDP 没起来'); process.exit(2); }

const ws = new WebSocket(wsUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0; const waiting = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } };
const send = (method, params = {}, sessionId) => new Promise(res => { const i = ++id; waiting.set(i, res); ws.send(JSON.stringify({ id: i, method, params, sessionId })); });

const t = (await send('Target.getTargets')).result.targetInfos.find(x => x.type === 'page');
const S = (await send('Target.attachToTarget', { targetId: t.targetId, flatten: true })).result.sessionId;
await send('Runtime.enable', {}, S);
await send('Page.enable', {}, S);
await send('Page.navigate', { url: `http://127.0.0.1:${WEB_PORT}/web/index.html` }, S);
await sleep(2600);

const ev = async expr => {
  const r = await send('Runtime.evaluate', { expression: `(async()=>{${expr}})()`, awaitPromise: true, returnByValue: true }, S);
  if (r.result?.exceptionDetails) return { err: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
  return r.result?.result?.value;
};

let pass = 0, fail = 0;
const ok = (n, c, e) => { if (c) { pass++; console.log('  ✓', n); } else { fail++; console.log('  ✗', n, e !== undefined ? '→ ' + JSON.stringify(e) : ''); } };

console.log('\n=== 1. 注册 → 绑定 → 登录 ===');
const ACC = '860e2e' + Date.now();
const reg = await ev(`
  store.set('auraHub', ${JSON.stringify(HUB)});
  AURA.load();
  const r = await AURA.register(${JSON.stringify(ACC)},'pass1234','318',false);
  const bound = await AURA.bindDevice(r.enrollment_token);
  const lg = await AURA.login('318');
  return {uid:r.uid, callsign:r.callsign, dev:!!bound, tok:!!lg.token,
          valid:AURA.loggedIn(), cs:AURA.callsign};`);
ok('注册返回 uid / 呼号', !!reg.uid && !!reg.callsign, reg);
ok('设备绑定拿到 device_credential', reg.dev === true, reg);
ok('登录拿到 PTT token 且判为有效', reg.tok === true && reg.valid === true, reg);

const badCode = await ev(`
  try{ await AURA.login('999'); return {ok:true}; }
  catch(e){ return {ok:false, msg:e.message, status:e.status}; }`);
ok('错安全码被拒（401），且提示统一', badCode.ok === false && badCode.status === 401, badCode);

const reuse = await ev(`
  /* 同一个 temporary_key 重放必须失败：先手动领一次，再用两次 */
  const k = await AURA.api('/api/auth/issue-ptt-key',{device_credential:AURA.device},{noAuth:true});
  let first=true, second=false;
  try{ await AURA.api('/api/auth/login',{temporary_key:k.temporary_key,security_code:'318'},{noAuth:true}); }
  catch(e){ first=false; }
  try{ await AURA.api('/api/auth/login',{temporary_key:k.temporary_key,security_code:'318'},{noAuth:true}); second=true; }
  catch(e){ second=false; }
  return {first, second};`);
ok('临时凭据只能登录一次（第二次被拒）', reuse.first === true && reuse.second === false, reuse);

console.log('\n=== 2. 频道发现 ===');
const boot = await ev(`
  const ends = await AURA.bootstrap();
  await AURA.ensure();
  return {n:ends.length, tgs:ends.map(e=>e.tg), names:ends.map(e=>e.name),
          wsOpen:AURA.wsOpen, hub:AURA.hub};`);
ok('bootstrap 拉到 4 个端点', boot.n === 4, boot);
ok('端点带 channel_id/tg_id/tg 三件套',
  Array.isArray(boot.tgs) && boot.tgs.includes(460) && boot.tgs.includes(91), boot);
ok('WS 已连上', boot.wsOpen === true, boot);

console.log('\n=== 3. 加入端点 + 收音频 ===');
const join = await ev(`
  const sdrBefore = SDR.cn;
  const e = AURA.endByTg.get(460);
  AURA.join(e);
  await new Promise(r=>setTimeout(r,600));
  return {cur:AURA.cur&&AURA.cur.tg, members:AURA.members.size,
          sdrBefore, sdrAfter:SDR.cn, talker:!!state.lastRx};`);
ok('joined 事件把当前端点定下来', join.cur === 460, join);
ok('成员列表收到（含对端呼号）', join.members >= 2, join);
ok('下行 PCM 帧进了频谱（feedSpectrum 被调用）', join.sdrAfter > join.sdrBefore, join);
ok('talker 事件更新了「谁在说」', join.talker === true, join);

console.log('\n=== 4. 发射：talk on + 麦克风 PCM ===');
const tx = await ev(`
  const before = await (await fetch(${JSON.stringify(HUB)} + '/__stats')).json();
  AURA.talk(true);
  const mic = await AURA.micStart();
  await new Promise(r=>setTimeout(r,900));
  const after = await (await fetch(${JSON.stringify(HUB)} + '/__stats')).json();
  AURA.talk(false); AURA.micStop();
  await new Promise(r=>setTimeout(r,300));
  const after2 = await (await fetch(${JSON.stringify(HUB)} + '/__stats')).json();
  return {mic, before:before.stats, after:after.stats, after2:after2.stats};`);
ok('麦克风打开成功', tx.mic === true, tx);
ok('talk on 到达 Hub', tx.after.talk_on > tx.before.talk_on, tx.after);
ok('音频帧到达 Hub（0x01 PCM）', tx.after.audio_in > tx.before.audio_in,
  { in: tx.after.audio_in, bytes: tx.after.audio_bytes });
ok('talk off 到达 Hub', tx.after2.talk_off > tx.before.talk_off, tx.after2);

console.log('\n=== 5. 网络切换不串线 ===');
const sw = await ev(`
  setNet('aura');
  await new Promise(r=>setTimeout(r,600));
  const openBefore = AURA.wsOpen;
  setNet('bm');
  await new Promise(r=>setTimeout(r,150));
  const auraOpen = AURA.wsOpen;
  const net1 = state.net;
  setNet('aura');
  await new Promise(r=>setTimeout(r,400));
  return {openBefore, net1, auraOpenAfterLeave:auraOpen, net2:state.net, wsRe:AURA.wsOpen, hub:AURA.hub};`);
ok('切到 BM 会关掉 AURA 的 WS',
  sw.openBefore === true && sw.net1 === 'bm' && sw.auraOpenAfterLeave === false, sw);
ok('切回 AURA 能重新连上', sw.net2 === 'aura' && sw.wsRe === true, sw);

console.log('\n=== 6. UI 接线 ===');
const ui = await ev(`
  document.querySelector('[data-net="aura"]').click();
  await new Promise(r=>setTimeout(r,300));
  const seg=[...document.querySelectorAll('#segNet button')].map(b=>b.dataset.net);
  const on=[...document.querySelectorAll('#segNet button.on')].map(b=>b.dataset.net);
  const card=!!document.getElementById('auraCard');
  const st=document.getElementById('auraState').textContent;
  return {seg, on, card, st, mine:document.getElementById('mineList').textContent.slice(0,20)};`);
ok('网络选择器有三挡且 AURA 高亮', ui.seg.length === 3 && ui.on[0] === 'aura', ui);
ok('设置页有 AURA 卡片且状态已更新', ui.card === true && /登录|连接/.test(ui.st), ui);

ws.close(); chrome.kill(); server.close();
console.log(`\n================  ${pass} 通过 / ${fail} 失败  ================`);
process.exit(fail ? 1 : 0);
