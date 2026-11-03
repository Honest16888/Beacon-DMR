#!/usr/bin/env node
/* 把 dsp.js 和 Python 参考实现逐值对一遍。
 *
 * 这一层是「改 DSP 时的第一道检查」：窗口、padding、频段、归一化里任何一处
 * 偏了，模型都会很有信心地输出错字，而不是报错。
 *
 * 用法：
 *   python tests/deepcw/spec_ref.py --out <目录>
 *   node   tests/deepcw/verify.js --dir <目录>
 */
'use strict';

const fs = require('fs');
const path = require('path');
const dsp = require('./dsp.js');

function parseArgs(argv) {
  const args = { dir: path.join('tests', 'out', 'deepcw'), tolerance: null };
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--dir') args.dir = argv[++i];
    else if (argv[i] === '--tolerance') args.tolerance = parseFloat(argv[++i]);
  }
  return args;
}

function readWav(file) {
  const buf = fs.readFileSync(file);
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error('not a WAV file: ' + file);
  }
  let offset = 12;
  let fmt = null;
  let data = null;
  while (offset + 8 <= buf.length) {
    const id = buf.toString('ascii', offset, offset + 4);
    const size = buf.readUInt32LE(offset + 4);
    const body = offset + 8;
    if (id === 'fmt ') {
      fmt = {
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bits: buf.readUInt16LE(body + 14)
      };
    } else if (id === 'data') {
      data = buf.subarray(body, body + size);
    }
    offset = body + size + (size & 1);
  }
  if (!fmt || !data) throw new Error('WAV missing fmt/data chunk');
  if (fmt.channels !== 1 || fmt.bits !== 16) {
    throw new Error('expect mono 16-bit, got ' + fmt.channels + 'ch/' + fmt.bits + 'bit');
  }
  const out = new Float32Array(data.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = data.readInt16LE(i * 2) / 32768;
  return { sampleRate: fmt.sampleRate, pcm: out };
}

function readF32(file) {
  const buf = fs.readFileSync(file);
  return new Float32Array(buf.buffer, buf.byteOffset, buf.length >> 2);
}

function compare(name, actual, expected, tolerance) {
  if (actual.length !== expected.length) {
    return {
      name,
      ok: false,
      detail: `长度不一致：js=${actual.length} ref=${expected.length}`
    };
  }
  let maxAbs = 0;
  let sumSq = 0;
  let maxIndex = -1;
  for (let i = 0; i < expected.length; i++) {
    const d = Math.abs(actual[i] - expected[i]);
    if (d > maxAbs) { maxAbs = d; maxIndex = i; }
    sumSq += d * d;
  }
  const rms = Math.sqrt(sumSq / Math.max(1, expected.length));
  return {
    name,
    ok: maxAbs <= tolerance,
    detail: `max|Δ|=${maxAbs.toExponential(2)} rms|Δ|=${rms.toExponential(2)} ` +
            `(第 ${maxIndex} 个) 容差=${tolerance}`,
    maxAbs
  };
}

function main() {
  const args = parseArgs(process.argv);
  const dir = path.resolve(args.dir);
  const metaPath = path.join(dir, 'meta.json');
  if (!fs.existsSync(metaPath)) {
    console.error('找不到 ' + metaPath + '\n先跑：python tests/deepcw/spec_ref.py --out ' + args.dir);
    process.exit(2);
  }
  const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));

  const { sampleRate, pcm } = readWav(path.join(dir, 'cw48k.wav'));
  const refPcm3200 = readF32(path.join(dir, 'cw3200.f32'));
  const refSpec = readF32(path.join(dir, 'spec.f32'));

  console.log(`源音频 : ${sampleRate} Hz, ${pcm.length} 采样`);
  console.log(`金标准 : ${meta.frames} 帧 × ${meta.bins} bin\n`);

  const results = [];

  // ① 重采样
  const pcm3200 = dsp.resampleSinc(pcm, sampleRate);
  results.push(compare('重采样 48k→3.2k', pcm3200, refPcm3200, 1e-5));

  // ② 频谱 + 归一化
  const spec = dsp.spectrogram(pcm3200);
  const norm = dsp.normalizeQ90(spec.data);
  results.push(compare('频谱（归一化）', norm, refSpec, 2e-4));

  let allOk = true;
  for (const r of results) {
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}`);
    console.log('      ' + r.detail);
    if (!r.ok) allOk = false;
  }

  // ③ 附带：原样把最有信息量的几行打出来，方便肉眼确认不是「全零也算过」
  const frames = spec.frames;
  const bins = spec.bins;
  const mid = Math.floor(frames / 2);
  let bestBin = 0;
  for (let b = 1; b < bins; b++) {
    if (norm[mid * bins + b] > norm[mid * bins + bestBin]) bestBin = b;
  }
  console.log(`\n中间帧最强 bin = ${bestBin}（${(400 + bestBin * 12.5).toFixed(1)} Hz），` +
              `值 ${norm[mid * bins + bestBin].toFixed(3)}`);
  console.log(`归一化范围 ${Math.min(...norm).toFixed(3)} … ${Math.max(...norm).toFixed(3)}`);

  process.exit(allOk ? 0 : 1);
}

main();
