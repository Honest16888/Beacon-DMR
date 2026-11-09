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

console.log('\n=== 1b. 注册失败的分诊（860 验证 / 重复 / 全角输入）===');
const wrongPwd = await ev(`
  AURA.logClear();
  const acc='860bad'+Date.now();
  try{
    await AURA.register(acc,'bad860','318',false);
    return {ok:true};
  }catch(e){
    return {ok:false, msg:e.message,
      log:(AURA.logLines||[]).join(' | ').slice(0,200)};
  }`);
ok('820 验证失败 → 明确说「860 账号或密码不对」',
  wrongPwd.ok === false && /860 账号或密码/.test(wrongPwd.msg || ''), wrongPwd);
ok('失败也有请求日志（状态码可见）', /401/.test(wrongPwd.log || ''), wrongPwd);

const dup = await ev(`
  try{ await AURA.register(${JSON.stringify(ACC)},'pass1234','318',false); return {ok:true}; }
  catch(e){ return {ok:false, msg:e.message, status:e.status}; }`);
ok('重复注册 → 提示去点「重新核验」',
  dup.ok === false && /重新核验/.test(dup.msg || ''), dup);

const fullwidth = await ev(`
  /* 账号里塞全角数字 + 前后空格、安全码用全角 —— 规范化之后必须能注册成功 */
  const acc='860fw'+Date.now();
  const r=await AURA.register('  '+acc+'  ','pass1234','３１８',false);
  return {ok:!!r.uid, code:r.enrollment_token?318:0};`);
ok('全角数字/空格被规范化后注册成功', fullwidth.ok === true, fullwidth);

const reverify = await ev(`
  const r=await AURA.register(${JSON.stringify(ACC)},'pass1234','318',true);
  const dev=await AURA.bindDevice(r.enrollment_token);
  /* reverify 会撤销该账号所有旧会话，这里必须重新登录，
     否则后面的用例拿到的是废 token（WS 永远连不上） */
  await AURA.login('318');
  return {ok:!!r.enrollment_token, dev:!!dev, tok:AURA.tokenValid()};`);
ok('重新核验拿到新的一次性令牌并能重新绑定',
  reverify.ok === true && reverify.dev === true && reverify.tok === true, reverify);

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

console.log('\n=== 4a. 没加入端点就按 PTT：自动加入并开口 ===');
const autoJoin = await ev(`
  AURA.leave();
  await new Promise(r=>setTimeout(r,250));
  state.subscribed=[460];
  const before=(await (await fetch(${JSON.stringify(HUB)} + '/__stats')).json()).stats;
  auraPttDown();                       /* 不 await：它要等 joined 再开口 */
  await new Promise(r=>setTimeout(r,1800));
  const after=(await (await fetch(${JSON.stringify(HUB)} + '/__stats')).json()).stats;
  const out={cur:AURA.cur&&AURA.cur.tg, talkBefore:before.talk_on, talkAfter:after.talk_on,
             txing:TX.txing===true, audioBefore:before.audio_in, audioAfter:after.audio_in};
  AURA.pendingTalk=false; auraPttUp();
  return out;`);
ok('按 PTT 自动加入端点（不用先点 ▶）', autoJoin.cur === 460, autoJoin);
ok('加入后自动 talk on', autoJoin.talkAfter > autoJoin.talkBefore, autoJoin);
ok('加入后麦克风真的开始发帧', autoJoin.audioAfter > autoJoin.audioBefore, autoJoin);
ok('TX 状态进了「发射中」', autoJoin.txing === true, autoJoin);

const rowJoin = await ev(`
  /* 行点击走的是 UI 分发器，里面带 net==='aura' 的判断 —— 先把网络切过去 */
  const prev=state.net;
  setNet('aura');
  await new Promise(r=>setTimeout(r,200));
  /* setNet 会从这个网络的 localStorage 重新载入订阅，先把它填回来 */
  state.subscribed=[460];
  store.set('subscribedAURA',[460]);
  renderMine();
  AURA.leave();
  await new Promise(r=>setTimeout(r,250));
  const row=document.querySelector('#mineList .row[data-id="460"]');
  if(row)row.click();
  await new Promise(r=>setTimeout(r,700));
  const out={cur:AURA.cur&&AURA.cur.tg, hasRow:!!row};
  setNet(prev==='aura'?'bm':prev);
  return out;`);
ok('点整行即加入（不是只有那颗 ▶）',
  rowJoin.hasRow === true && rowJoin.cur === 460, rowJoin);

console.log('\n=== 4d. 麦克风：App 内优先原生 / 权限被拒 ===');
const micNative = await ev(`
  const sent=[];
  window.Android = {
    hasMicPermission(){ return true; },
    requestMicPermission(){ window.onMicPermission(true); },
    auraMicStart(){ window.__micOn=true; return true; },
    auraMicStop(){ window.__micOn=false; },
    auraWsSendBin(b64){ sent.push(b64.length); },
    auraWsOpen(){ }, auraWsSendText(){ }, auraWsClose(){ },
  };
  AURA.nativeWs=true; AURA.wsOpen=true;
  const ok=await AURA.micStart();
  AURA.talkOn=true;
  const pcm=new Uint8Array(1+2560); pcm[0]=1;
  window.onAuraMic(btoa(String.fromCharCode.apply(null,pcm)));
  await new Promise(r=>setTimeout(r,60));
  const out={ok, native:AURA.nativeMic===true, started:window.__micOn===true, sent:sent.length};
  AURA.talkOn=false; AURA.micStop(); AURA.nativeWs=false;
  delete window.Android;
  return out;`);
ok('App 内优先用原生麦克风', micNative.ok === true && micNative.native === true, micNative);
ok('原生 PCM 被装进 WS 二进制帧', micNative.sent >= 1, micNative);

const micDenied = await ev(`
  window.Android = {
    hasMicPermission(){ return false; },
    requestMicPermission(){ window.onMicPermission(false); },
    auraMicStart(){ return true; }, auraMicStop(){ },
    auraWsSendBin(){ }, auraWsOpen(){ }, auraWsSendText(){ }, auraWsClose(){ },
  };
  const ok=await AURA.micStart();
  const out={ok, native:AURA.nativeMic===true};
  delete window.Android;
  return out;`);
ok('权限被拒时不开采集（并提示去系统设置）',
  micDenied.ok === false && micDenied.native === false, micDenied);

console.log('\n=== 4e. 会话归属：别抢另一台设备的登录 ===');
const sess = await ev(`
  const out={};
  /* ① 默认不开机自动连 */
  AURA.autoConnect=false;
  AURA.disconnect('测试');
  await new Promise(r=>setTimeout(r,150));
  out.afterDisc={open:AURA.wsOpen, tok:AURA.tokenValid(), hold:AURA.holdOff===true};
  /* ② autoConnect 关着时 ensureAura() 不该建会话 */
  ensureAura();
  await new Promise(r=>setTimeout(r,400));
  out.autoOff=AURA.wsOpen;
  /* ③ 手动连接可以恢复 */
  await AURA.ensure();
  out.reconnect=AURA.wsOpen;
  /* ④ 连续失败到上限就停手，不无限重连（scheduleReconnect 只在 aura 网络下跑） */
  const netKeep=state.net; state.net='aura';
  AURA.wsTries=5; AURA.holdOff=false;
  AURA.scheduleReconnect();
  state.net=netKeep;
  out.capped={hold:AURA.holdOff===true, timer:!!AURA.wsTimer};
  /* ⑤ 服务端说 unauthorized → 停手 + 清会话（不要在两边互相顶） */
  AURA.holdOff=false; AURA.wsTries=0;
  AURA.onText(JSON.stringify({type:'error',code:'unauthorized',message:'会话失效'}));
  out.unauth={hold:AURA.holdOff===true, tok:AURA.token};
  /* 收尾：重新登录，后面的用例还要用 */
  await AURA.login('318');
  await AURA.ensure();
  return out;`);
ok('断开后保留设备绑定（token 还在）', sess.afterDisc.open === false && sess.afterDisc.tok === true, sess);
ok('autoConnect 关着时 ensureAura() 不建会话', sess.autoOff === false, sess);
ok('手动连接能恢复', sess.reconnect === true, sess);
ok('重连有上限（到顶就停手）', sess.capped.hold === true, sess);
ok('unauthorized 后停手并清掉会话',
  sess.unauth.hold === true && !sess.unauth.tok, sess);

console.log('\n=== 4f. 释放设备（换回原 App 的唯一干净做法）===');
const rel = await ev(`
  const before={tok:AURA.tokenValid(), dev:!!AURA.device};
  const r=await AURA.releaseDevice();
  const after={tok:AURA.tokenValid(), dev:AURA.device, ls:String(localStorage.getItem('dmr.auraDevice')||'')};
  /* 释放之后要能重新走核验把绑定拿回来（协议 3.3） */
  const again=await AURA.register(${JSON.stringify(ACC)},'pass1234','318',true);
  await AURA.bindDevice(again.enrollment_token);
  await AURA.login('318');
  return {before, after, r, back:{dev:!!AURA.device, tok:AURA.tokenValid()}};`);
ok('释放前有 token 且已绑定', rel.before.tok === true && rel.before.dev === true, rel);
ok('释放后会话与 device_credential 都清掉',
  rel.after.tok === false && rel.after.dev === '' && (rel.after.ls === '' || rel.after.ls === '""'), rel);
ok('释放后可重新核验并绑回来', rel.back.dev === true && rel.back.tok === true, rel);

console.log('\n=== 4b. 连接诊断 + 原生 HTTP 桥 ===');
const diag = await ev(`
  const r = await AURA.testConn();
  return {ok:r.ok, steps:r.steps.map(x=>x.k+'='+x.v+' ['+(x.s||'-')+']')};`);
ok('诊断：REST 与 WebSocket 都通', diag.ok === true, diag);
ok('诊断把通道写成「页面 fetch」（浏览器里没有原生桥）',
  (diag.steps || []).some(x => x.startsWith('通道=页面 fetch')), diag);

const bridge = await ev(`
  /* 装一个假的 Android.auraHttp：验证 JS 会优先走原生桥，并正确解析回调 */
  const calls = [];
  window.Android = {
    auraHttp(id, method, url, headersJson, bodyJson) {
      calls.push({method, url, headers:JSON.parse(headersJson||'{}'), body:bodyJson});
      fetch(url, {method, headers:JSON.parse(headersJson||'{}'),
                  body: bodyJson == null ? undefined : bodyJson})
        .then(r => r.text().then(t => window.onAuraHttp(id, JSON.stringify({status:r.status, body:t, error:''}))))
        .catch(e => window.onAuraHttp(id, JSON.stringify({status:0, body:'', error:String(e)})));
    }
  };
  const r = await AURA.api('/api/public/overview', undefined, {noAuth:true});
  const usedNative = calls.length === 1 && calls[0].url.indexOf('/api/public/overview') > 0;
  delete window.Android;
  return {usedNative, calls:calls.length, ok:!!r.ok};`);
ok('有原生桥时 REST 走原生（不再用 fetch）', bridge.usedNative === true, bridge);
ok('原生桥的回调被正确解析', bridge.ok === true, bridge);

console.log('\n=== 4c. 原生 WebSocket 传输（ws:// 在 https 页面里的唯一出路）===');
const nativeWs = await ev(`
  /* 装一个假的 Android.auraWs*：验证 useNativeWs() 的判断、文本发送、
     二进制 base64 发送、以及回调分发（open/text/bin/close）。 */
  const sent = {text:[], bin:[]};
  let opened = false;
  window.Android = {
    auraWsOpen(url, trustAll){ window.__wsUrl = url; window.__wsTrust = trustAll;
      setTimeout(()=>window.onAuraWs(JSON.stringify({t:'open'})), 30); },
    auraWsSendText(s){ sent.text.push(s); },
    auraWsSendBin(b64){ sent.bin.push(b64.length); },
    auraWsClose(){ },
  };
  /* http Hub → 必须走原生 */
  AURA.hub='http://127.0.0.1:8099';
  const choseNative = AURA.useNativeWs();
  /* https Hub + 不信任自签名 → 用浏览器 */
  AURA.hub='https://hub.example.com';
  const choseBrowser = AURA.useNativeWs();
  AURA.hub='http://127.0.0.1:8099';

  /* 手动驱动一次连接，看回调是否被正确分发 */
  AURA.token='tok_unit';
  AURA.connectWs();
  await new Promise(r=>setTimeout(r,120));
  const openedOk = AURA.wsOpen === true;

  /* 文本：join/talk 都该走 wsSendText */
  AURA.cur={channelId:12, tgId:34, tg:460, name:'x'};
  AURA.join(AURA.cur);
  AURA.talk(true);

  /* 二进制：4 KB PCM → base64 */
  const u8=new Uint8Array(4096); u8[0]=1;
  AURA.wsSendBin(u8);

  /* 下行：原生回一帧 base64 PCM，应该进 onBinary（不报错即可） */
  const sdrBefore=SDR.cn;
  const pcm=new Uint8Array(1+160*2); pcm[0]=1;
  window.onAuraWs(JSON.stringify({t:'bin', data: btoa(String.fromCharCode.apply(null,pcm))}));
  await new Promise(r=>setTimeout(r,60));

  /* 关闭 */
  window.onAuraWs(JSON.stringify({t:'close', data:'测试关闭'}));
  delete window.Android;
  return {choseNative, choseBrowser, openedOk, texts:sent.text.length, bins:sent.bin.length,
          pcmIn:sent.bin[0]||0, closed:AURA.wsOpen===false};`);
ok('http Hub → 选原生 WS（浏览器必拒 ws://）', nativeWs.choseNative === true, nativeWs);
ok('https Hub 且不信任自签名 → 仍用浏览器 WS', nativeWs.choseBrowser === false, nativeWs);
ok('原生 open 回调把 wsOpen 置起来', nativeWs.openedOk === true, nativeWs);
ok('join / talk 走原生文本帧', nativeWs.texts >= 2, nativeWs);
ok('PCM 走原生二进制帧（base64）', nativeWs.bins >= 1, nativeWs);
ok('close 回调复位状态', nativeWs.closed === true, nativeWs);

console.log('\n=== 5. 网络切换不串线 ===');
const sw = await ev(`
  setNet('aura');
  await new Promise(r=>setTimeout(r,300));
  /* 2.10.6 起切网络不再自动建会话（避免抢另一台设备的登录），这里显式连 */
  if(!AURA.wsOpen)await AURA.ensure();
  const openBefore = AURA.wsOpen;
  setNet('bm');
  await new Promise(r=>setTimeout(r,150));
  const auraOpen = AURA.wsOpen;
  const net1 = state.net;
  setNet('aura');
  await new Promise(r=>setTimeout(r,200));
  const autoRe = AURA.wsOpen;          /* 不自动连：应为 false */
  if(!AURA.wsOpen)await AURA.ensure(); /* 显式连 */
  return {openBefore, net1, auraOpenAfterLeave:auraOpen, net2:state.net,
          autoRe, wsRe:AURA.wsOpen, hub:AURA.hub};`);
ok('切到 BM 会关掉 AURA 的 WS',
  sw.openBefore === true && sw.net1 === 'bm' && sw.auraOpenAfterLeave === false, sw);
ok('切回 AURA 不自动抢会话（要手动连）', sw.autoRe === false, sw);
ok('显式点连接后能重新连上', sw.net2 === 'aura' && sw.wsRe === true, sw);

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
