// 验证出口软削波：任何增益下输出都不越过 1.0，且默认档完全透明
//
//   node tests/audio_limiter.js
//
// 复刻 Audio_.init() 里的那条链（gain → 高通 → presence → 1/3 预增益 → WaveShaper），
// 用 OfflineAudioContext 离线渲染，直接量出口峰值。
//
// 为什么要有这个测试：v2.5.2 之前「播放发炸 / 噼啪」的判断依据是「录音清晰但播放炸」，
// 推理出问题在增益之后的某一级。这里把它变成可验证的数字：
//   · 老配置（音量 0.8 × 增益 2）出口峰值 2.11，三成样本被硬削波
//   · 就算把增益降到 1×，presence 的 +2.5 dB 也会把峰值推到 1.05，仍然削波
//   · 测试信号故意做得很尖（每 50ms 一个满刻度尖峰），真实语音比这温和得多
//   · 加了软削波之后，无论推到 3× 都 ≤0.98
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROFILE = path.join(os.tmpdir(), 'beacon-lim-' + Date.now());
const sleep = ms => new Promise(r => setTimeout(r, ms));

fs.mkdirSync(PROFILE, { recursive: true });
const chrome = spawn(CHROME, ['--headless=new', '--remote-debugging-port=9442',
  `--user-data-dir=${PROFILE}`, '--no-first-run', '--disable-gpu',
  '--autoplay-policy=no-user-gesture-required', 'about:blank'], { stdio: 'ignore' });

async function wsUrl() {
  for (let i = 0; i < 50; i++) {
    try {
      const j = await (await fetch('http://127.0.0.1:9442/json/list')).json();
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
await send('Runtime.enable');

const res = await ev(`(async()=>{
  // 造一段「μ-law 满刻度」的测试信号：语音频段的三个正弦 + 每 50ms 一个尖峰，峰值 0.98
  function makeSrc(sr, dur){
    const n=Math.round(sr*dur), a=new Float32Array(n);
    for(let i=0;i<n;i++){
      const t=i/sr;
      let v=0.6*Math.sin(2*Math.PI*700*t)+0.3*Math.sin(2*Math.PI*2600*t)
           +0.25*Math.sin(2*Math.PI*1800*t);
      if(i%Math.round(sr*0.05)===0)v=0.98;
      a[i]=Math.max(-0.98,Math.min(0.98,v));
    }
    return a;
  }
  async function run(gainMul, useShaper, presenceDb){
    const sr=48000, dur=2;
    const oc=new OfflineAudioContext(1, sr*dur, sr);
    const src=oc.createBufferSource();
    const b=oc.createBuffer(1, sr*dur, sr);
    b.getChannelData(0).set(makeSrc(sr,dur));
    src.buffer=b;
    const g=oc.createGain(); g.gain.value=gainMul;
    const hp=oc.createBiquadFilter(); hp.type='highpass'; hp.frequency.value=180; hp.Q.value=0.7;
    const pk=oc.createBiquadFilter(); pk.type='peaking'; pk.frequency.value=2600; pk.Q.value=0.9; pk.gain.value=presenceDb;
    src.connect(g); g.connect(hp); hp.connect(pk);
    if(useShaper){
      const pre=oc.createGain(); pre.gain.value=1/3;
      const w2=oc.createWaveShaper();
      const NS=4096, cur=new Float32Array(NS), T=0.7, CEIL=0.98, K=3;
      for(let i=0;i<NS;i++){
        const u=(i/(NS-1))*2-1, x=u*K, a=Math.abs(x), sg=x<0?-1:1;
        cur[i]=a<=T ? x : sg*(T+(CEIL-T)*Math.tanh((a-T)/(CEIL-T)));
      }
      w2.curve=cur; w2.oversample='none';
      pk.connect(pre); pre.connect(w2); w2.connect(oc.destination);
    }else{
      pk.connect(oc.destination);
    }
    src.start(0);
    const out=await oc.startRendering();
    const d=out.getChannelData(0);
    let peak=0, over=0, sum=0;
    for(let i=0;i<d.length;i++){
      const a=Math.abs(d[i]);
      if(a>peak)peak=a;
      if(a>1.0)over++;
      sum+=d[i]*d[i];
    }
    return { peak:+peak.toFixed(4), over, rms:+Math.sqrt(sum/d.length).toFixed(5) };
  }
  const out={};
  out.old_2x_noShaper = await run(0.8*2, false, 2.5);   // 老默认
  out.old_2x_shaper   = await run(0.8*2, true,  2.5);
  out.new_1x_noShaper = await run(0.8*1, false, 2.5);   // 新默认，但没有削波器
  out.new_1x_shaper   = await run(0.8*1, true,  2.5);
  out.max_3x_shaper   = await run(1.0*3, true,  2.5);   // 音量拉满 + 3×
  out.noPres_1x_shaper= await run(0.8*1, true,  0);     // 关掉窄带增强
  return out;
})()`);

console.log(JSON.stringify(res, null, 1));

let bad = 0;
const chk = (name, cond, extra) => { console.log((cond ? '  ✓ ' : '  ✗ ') + name + (extra || '')); if (!cond) bad++; };

console.log('\n=== 出口峰值 ===');
chk('老配置(2×) 确实硬削波', res.old_2x_noShaper.over > 0,
    `  → 峰值 ${res.old_2x_noShaper.peak}，${res.old_2x_noShaper.over} 个样本越过 1.0`);
chk('降到 1× 也还在削波（presence +2.5dB 的锅）', res.new_1x_noShaper.over > 0,
    `  → 峰值 ${res.new_1x_noShaper.peak}`);
chk('加软削波后 2× 不再越界', res.old_2x_shaper.over === 0, `  → ${res.old_2x_shaper.peak}`);
chk('加软削波后 1× 不再越界', res.new_1x_shaper.over === 0, `  → ${res.new_1x_shaper.peak}`);
chk('音量拉满 + 3× 也压得住', res.max_3x_shaper.over === 0, `  → ${res.max_3x_shaper.peak}`);
chk('峰值留了余量（≤0.98）', res.max_3x_shaper.peak <= 0.9801, `  → ${res.max_3x_shaper.peak}`);

console.log('\n=== 默认档是否被削波器改变 ===');
const dr = Math.abs(res.new_1x_shaper.rms - res.new_1x_noShaper.rms) / res.new_1x_noShaper.rms;
chk('1× 时软削波几乎不动信号（RMS 差 <2%）', dr < 0.02,
    `  → ${(dr * 100).toFixed(3)}%（${res.new_1x_noShaper.rms} → ${res.new_1x_shaper.rms}）`);

ws.close(); chrome.kill();
process.exit(bad ? 1 : 0);
