// DeepCW × onnxruntime-web 的 PoC 跑批：起一个本地静态服务，开无头 Chrome 跑页面，
// 取回 window.__poc（与 Python onnxruntime 的 logits 逐值对比结果）。
//
//   node tests/deepcw/poc/run_poc.js
//   CHROME_PATH=... node tests/deepcw/poc/run_poc.js
//
// 为什么不用 file:// —— wasm 和多线程都要正常的 http 源，file:// 是不透明源。
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..', '..');          // 仓库根
const CHROME = process.env.CHROME_PATH || (
  process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
      : '/usr/bin/google-chrome'
);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.onnx': 'application/octet-stream',
  '.f32': 'application/octet-stream',
};

const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '');
  const file = path.join(ROOT, rel);
  if (!file.startsWith(ROOT)) { res.writeHead(403).end(); return; }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404).end('not found: ' + rel); return; }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Content-Length': st.size,
      'Cross-Origin-Opener-Policy': 'same-origin',
      'Cross-Origin-Embedder-Policy': 'require-corp',
    });
    fs.createReadStream(file).pipe(res);
  });
});

const PORT = Number(process.env.POC_PORT || 9347);
await new Promise(r => server.listen(PORT, '127.0.0.1', r));
const QUERY = process.env.POC_QUERY || '';        // 例：POC_QUERY='?simd=0' 走标量 wasm
const PAGE = `http://127.0.0.1:${PORT}/tests/deepcw/poc/index.html${QUERY}`;
console.log('serve:', PAGE);

const PROFILE = path.join(os.tmpdir(), 'deepcw-poc-' + Date.now());
fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', '--remote-debugging-port=9348', `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', '--window-size=900,900', 'about:blank',
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));
let wsUrl;
for (let i = 0; i < 60 && !wsUrl; i++) {
  try {
    const l = await (await fetch('http://127.0.0.1:9348/json/list')).json();
    wsUrl = l.find(x => x.type === 'page')?.webSocketDebuggerUrl;
  } catch {}
  if (!wsUrl) await sleep(250);
}
if (!wsUrl) { console.error('CDP 未就绪'); chrome.kill(); server.close(); process.exit(2); }

const ws = new WebSocket(wsUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0; const waiting = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } };
const send = (method, params = {}, sessionId) => new Promise(res => { const i = ++id; waiting.set(i, res); ws.send(JSON.stringify({ id: i, method, params, sessionId })); });

const t = (await send('Target.getTargets')).result.targetInfos.find(x => x.type === 'page');
const S = (await send('Target.attachToTarget', { targetId: t.targetId, flatten: true })).result.sessionId;
await send('Runtime.enable', {}, S);
await send('Log.enable', {}, S);
const consoleLines = [];
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.method === 'Runtime.consoleAPICalled') {
    consoleLines.push(m.params.args.map(a => a.value ?? a.description ?? a.type).join(' '));
  }
  if (m.method === 'Runtime.exceptionThrown') {
    consoleLines.push('EXC ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
  }
});
await send('Page.enable', {}, S);
await send('Page.navigate', { url: PAGE }, S);

const deadline = Date.now() + 180000;
let result;
while (Date.now() < deadline) {
  const r = await send('Runtime.evaluate', {
    expression: 'JSON.stringify(window.__poc||null)', returnByValue: true,
  }, S);
  const v = r.result?.result?.value;
  if (v && v !== 'null') { result = JSON.parse(v); break; }
  await sleep(400);
}

ws.close(); chrome.kill(); server.close();

if (!result) {
  console.error('超时：页面没有产出 window.__poc');
  for (const l of consoleLines.slice(-15)) console.error('  |', l);
  process.exit(1);
}
if (consoleLines.length) {
  console.log('--- 页面日志 ---');
  for (const l of consoleLines.slice(-10)) console.log('  |', l);
}
console.log('--- 结果 ---');
console.log(JSON.stringify(result, null, 2));

if (!result.ok) { console.error('\nFAIL'); process.exit(1); }
console.log('\nPASS  wasm logits 与 Python 一致（max|Δ| = %s，解码文本相同）',
  result.maxD.toExponential(2));
