/* DeepCW 输入频谱 —— 与 e04/deepcw-engine 的 Python 参考实现逐值对齐。
 *
 * 链路：
 *   任意采样率 PCM
 *     → 255 抽头 Blackman 窗 sinc 重采样到 3200 Hz（截止 1.4 kHz）
 *     → reflect 填充 128 采样，周期 Hann(256)，hop 48
 *     → rFFT 取 400–1200 Hz 的 65 个频点（12.5 Hz 一格）
 *     → q90 log1p 归一化
 *   → [1, 1, frames, 65] float32，正是模型要的输入。
 *
 * 常数全部来自 model.onnx.json，不是选的。
 * 浏览器挂到 window.DeepCW，Node 走 module.exports —— 一份代码两处用，
 * 免得「网页里对、App 里不对」。
 */
(function (root) {
  'use strict';

  var META = {
    sample_rate: 3200,
    fft_length: 256,
    win_length: 256,
    hop_length: 48,
    output_time_stride: 2,
    min_hz: 400,
    max_hz: 1200,
    bins: 65,
    quantile: 0.9,
    // 归一化下限：噪声估计不能比峰值低三个数量级，也不能为 0
    NOISE_LEVEL_MIN_RELATIVE_TO_PEAK: 1e-3,
    NOISE_LEVEL_MIN: 1e-5
  };

  // 重采样：目标 3.2 kHz 的奈奎斯特是 1600 Hz。
  // 截止取 1400 —— 在奈奎斯特之下（不会折叠），又在模型关心的 1200 Hz 之上。
  var RESAMPLE_TAPS = 255;
  var RESAMPLE_CUTOFF_HZ = 1400;

  function sinc(x) {
    if (x === 0) return 1;
    var p = Math.PI * x;
    return Math.sin(p) / p;
  }

  /**
   * 带抗混叠的 sinc 重采样。
   * 只在线性插值上做算术会把它上边的东西全部折进 400–1200 Hz，
   * 这正是官方例子自己注明的「依赖最少」做法的代价。
   */
  function resampleSinc(input, srcRate, dstRate, cutoffHz) {
    dstRate = dstRate || META.sample_rate;
    cutoffHz = cutoffHz || RESAMPLE_CUTOFF_HZ;
    if (srcRate === dstRate) return Float32Array.from(input);

    var taps = RESAMPLE_TAPS;
    var half = (taps - 1) >> 1;
    var nOut = Math.round(input.length * dstRate / srcRate);
    var ratio = srcRate / dstRate;
    var fc = cutoffHz / srcRate;          // 以输入采样率为单位的截止频率

    // 对称 Blackman 窗：w[i] = .42 - .5cos(2πi/(N-1)) + .08cos(4πi/(N-1))
    var win = new Float64Array(taps);
    for (var i = 0; i < taps; i++) {
      var u = (i - half) / half;          // -1 .. 1
      win[i] = 0.42 - 0.5 * Math.cos(Math.PI * (u + 1))
             + 0.08 * Math.cos(2 * Math.PI * (u + 1));
    }

    var out = new Float32Array(nOut);
    for (var n = 0; n < nOut; n++) {
      var t = n * ratio;
      var base = Math.floor(t);
      var frac = t - base;
      var sum = 0, wsum = 0;
      for (var k = -half; k <= half; k++) {
        var idx = base + k;
        if (idx < 0 || idx >= input.length) continue;
        var z = k - frac;
        var s = sinc(2 * fc * z) * win[k + half];
        sum += input[idx] * s;
        wsum += s;
      }
      // 按实际用到的系数和归一，避免边沿掉电平
      out[n] = wsum !== 0 ? sum / wsum : 0;
    }
    return out;
  }

  /** 官方例子用的线性插值。只留着做对照，证明它确实会折叠。 */
  function resampleLinear(input, srcRate, dstRate) {
    dstRate = dstRate || META.sample_rate;
    if (srcRate === dstRate) return Float32Array.from(input);
    var nOut = Math.round(input.length * dstRate / srcRate);
    var out = new Float32Array(nOut);
    var ratio = srcRate / dstRate;
    for (var n = 0; n < nOut; n++) {
      var pos = n * ratio;
      var left = Math.floor(pos);
      var right = Math.min(left + 1, input.length - 1);
      var frac = pos - left;
      out[n] = input[left] * (1 - frac) + input[right] * frac;
    }
    return out;
  }

  // ---- FFT（长度固定 256，迭代基 2） ----
  function fft(re, im) {
    var n = re.length, i, j, k, m, half, tr, ti, cr, ci, wr, wi, ang, tmp;
    for (i = 1, j = 0; i < n; i++) {
      var bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        tmp = re[i]; re[i] = re[j]; re[j] = tmp;
        tmp = im[i]; im[i] = im[j]; im[j] = tmp;
      }
    }
    for (m = 2; m <= n; m <<= 1) {
      ang = -2 * Math.PI / m;
      wr = Math.cos(ang);
      wi = Math.sin(ang);
      half = m >> 1;
      for (k = 0; k < n; k += m) {
        cr = 1; ci = 0;
        for (i = 0; i < half; i++) {
          var a = k + i, b = k + i + half;
          tr = re[b] * cr - im[b] * ci;
          ti = re[b] * ci + im[b] * cr;
          re[b] = re[a] - tr; im[b] = im[a] - ti;
          re[a] += tr;        im[a] += ti;
          var ncr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = ncr;
        }
      }
    }
  }

  // 周期 Hann：numpy 的 hanning(N+1)[:-1] —— 不是 hanning(N)
  var HANN = (function () {
    var n = META.fft_length, w = new Float64Array(n);
    for (var i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
    return w;
  })();

  /**
   * numpy 的 reflect 填充：不重复边沿采样。
   * [1,2,3] 补 2 → [3,2,1,2,3,2,1]。写成「照抄边沿」就会每帧偏半个窗，
   * 每句开头丢一个字母，而且不会有任何报错。
   */
  function reflectPad(x, pad) {
    var n = x.length;
    var out = new Float32Array(n + 2 * pad);
    for (var i = 0; i < out.length; i++) {
      var j = i - pad;
      while (j < 0 || j >= n) {
        j = j < 0 ? -j : 2 * n - 2 - j;
      }
      out[i] = x[j];
    }
    return out;
  }

  /** 原始幅度谱：frames × 65，未归一化。 */
  function spectrogram(pcm) {
    var fftLen = META.fft_length, hop = META.hop_length;
    var binHz = META.sample_rate / fftLen;                 // 12.5 Hz
    var startBin = Math.ceil(META.min_hz / binHz);          // 32
    var stopBin = Math.floor(META.max_hz / binHz) + 1;      // 97（不含）
    var bins = stopBin - startBin;                          // 65

    if (pcm.length < fftLen) throw new Error('audio too short for fft_length=' + fftLen);

    var padded = reflectPad(pcm, fftLen >> 1);
    var frames = 1 + Math.floor((padded.length - fftLen) / hop);
    var data = new Float32Array(frames * bins);

    var re = new Float64Array(fftLen);
    var im = new Float64Array(fftLen);
    for (var f = 0; f < frames; f++) {
      var off = f * hop;
      for (var i = 0; i < fftLen; i++) {
        re[i] = padded[off + i] * HANN[i];
        im[i] = 0;
      }
      fft(re, im);
      for (var b = 0; b < bins; b++) {
        var k = startBin + b;
        data[f * bins + b] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
      }
    }
    return { frames: frames, bins: bins, data: data };
  }

  /**
   * noise_q90_log1p：用全窗幅度的 90 分位估计噪声底，再取 log1p。
   * 这个估计是整段一起算的，所以「换一段音频，同一句话的颜色会变」是正常的。
   */
  function normalizeQ90(data, quantile) {
    quantile = quantile == null ? META.quantile : quantile;
    var n = data.length;
    if (n === 0) return new Float32Array(0);

    var flat = Float32Array.from(data);
    flat.sort();                                   // TypedArray.sort 是数值序

    var rayleigh = Math.sqrt(-2 * Math.log(1 - quantile));
    var q90 = flat[Math.floor(quantile * (n - 1))];
    var peak = flat[n - 1];
    var noise = Math.max(
      q90 / rayleigh,
      peak * META.NOISE_LEVEL_MIN_RELATIVE_TO_PEAK,
      META.NOISE_LEVEL_MIN
    );

    var out = new Float32Array(n);
    for (var i = 0; i < n; i++) out[i] = Math.log1p(data[i] / noise);
    return out;
  }

  /** 完整前端：任意采样率 → 归一化频谱（模型输入）。 */
  function audioToInput(pcm, srcRate) {
    var pcm3200 = resampleSinc(pcm, srcRate, META.sample_rate);
    var spec = spectrogram(pcm3200);
    return {
      frames: spec.frames,
      bins: spec.bins,
      raw: spec.data,
      data: normalizeQ90(spec.data),
      pcm3200: pcm3200
    };
  }

  // ---- 合成 CW（给演示和测试用，不参与模型） ----
  var MORSE = {
    A: '.-', B: '-...', C: '-.-.', D: '-..', E: '.', F: '..-.', G: '--.',
    H: '....', I: '..', J: '.---', K: '-.-', L: '.-..', M: '--', N: '-.',
    O: '---', P: '.--.', Q: '--.-', R: '.-.', S: '...', T: '-', U: '..-',
    V: '...-', W: '.--', X: '-..-', Y: '-.--', Z: '--..',
    '0': '-----', '1': '.----', '2': '..---', '3': '...--', '4': '....-',
    '5': '.....', '6': '-....', '7': '--...', '8': '---..', '9': '----.',
    '/': '-..-.', '?': '..--..', '.': '.-.-.-', ',': '--..--'
  };

  function synthCw(text, opts) {
    opts = opts || {};
    var wpm = opts.wpm || 20;
    var tone = opts.toneHz || 700;
    var rate = opts.sampleRate || 48000;
    var noiseDb = opts.noiseDb == null ? null : opts.noiseDb;
    var seed = opts.seed || 1;

    var dot = 1.2 / wpm;                 // PARIS 标准：1.2 / WPM 秒
    var ramp = 0.005;                    // 5 ms 升余弦，避免键控咔哒
    var segs = [];                        // [秒数, 是否有音]

    var chars = text.toUpperCase().split('');
    for (var ci = 0; ci < chars.length; ci++) {
      var ch = chars[ci];
      if (ch === ' ') { segs.push([7 * dot, false]); continue; }
      var code = MORSE[ch];
      if (!code) continue;
      for (var si = 0; si < code.length; si++) {
        segs.push([(code[si] === '.' ? 1 : 3) * dot, true]);
        if (si < code.length - 1) segs.push([dot, false]);
      }
      segs.push([3 * dot, false]);        // 字符间隔
    }

    var total = 0;
    for (var s = 0; s < segs.length; s++) total += segs[s][0];
    var len = Math.max(1, Math.round(total * rate));
    var pcm = new Float32Array(len);

    var pos = 0;
    for (var g = 0; g < segs.length; g++) {
      var dur = Math.max(1, Math.round(segs[g][0] * rate));
      if (segs[g][1]) {
        var rampLen = Math.min(Math.round(ramp * rate), dur >> 1);
        for (var i = 0; i < dur && pos + i < len; i++) {
          var env = 1;
          if (rampLen > 0) {
            if (i < rampLen) env = 0.5 - 0.5 * Math.cos(Math.PI * i / rampLen);
            else if (i > dur - rampLen) env = 0.5 - 0.5 * Math.cos(Math.PI * (dur - i) / rampLen);
          }
          pcm[pos + i] = 0.5 * env * Math.sin(2 * Math.PI * tone * i / rate);
        }
      }
      pos += dur;
      if (pos >= len) break;
    }

    if (noiseDb != null) {
      // 简单 LCG，保证每次跑出来的噪声一样（测试要可复现）
      var state = seed >>> 0 || 1;
      var noise = new Float32Array(len);
      var energy = 0, count = 0;
      for (var q = 0; q < len; q++) {
        state = (state * 1664525 + 1013904223) >>> 0;
        var u1 = ((state >>> 8) & 0xffffff) / 0x1000000 + 1e-9;
        state = (state * 1664525 + 1013904223) >>> 0;
        var u2 = ((state >>> 8) & 0xffffff) / 0x1000000;
        var g1 = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        noise[q] = g1;
        if (pcm[q] !== 0) { energy += pcm[q] * pcm[q]; count++; }
      }
      var rms = count > 0 ? Math.sqrt(energy / count) : 0.5;
      var scale = rms / Math.pow(10, noiseDb / 20);
      for (var w = 0; w < len; w++) pcm[w] += noise[w] * scale;
    }
    return pcm;
  }

  var API = {
    META: META,
    resampleSinc: resampleSinc,
    resampleLinear: resampleLinear,
    spectrogram: spectrogram,
    normalizeQ90: normalizeQ90,
    audioToInput: audioToInput,
    synthCw: synthCw,
    reflectPad: reflectPad
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = API;
  root.DeepCW = API;
})(typeof globalThis !== 'undefined' ? globalThis : this);
