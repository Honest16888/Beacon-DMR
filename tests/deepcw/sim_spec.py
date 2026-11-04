#!/usr/bin/env python3
"""直接合成 DeepCW 口径的频谱图（不经过音频）。

和 `spec_ref.py` 的区别：

    spec_ref.py   合成 48 kHz CW → 重采样 3.2 kHz → STFT → 归一化   （真链路）
    sim_spec.py   直接在 65 bin × 15 ms 的网格上把「信号长什么样」算出来（模拟件）

输出格式与 `spec_ref.py` **完全一致**（`spec.f32` + `meta.json`），所以：
  - `render_png.py` 能直接画；
  - `poc/make_ref.py` 能直接喂模型 —— 解码对了，就说明这张模拟图对模型来说是「合法输入」。
  这一条是这个脚本唯一硬得起来的验证：图好不好看是主观的，模型读出来的字是不是原话是客观的。

为什么要模拟：真链路的每一个环节（重采样、加噪、键控包络、衰落）都在 48 kHz 上做，
想调「只改 SNR」「只改漂移」「两个信号同时在场」得反复过一遍重采样。
在频谱域里这些是各自独立的几个参数。

两个照真链路抄的细节（写错就「看着像、模型读不出」）：
  1. **窗的形状**：单个音调在 FFT 上不是一根线，而是 Hann 主瓣 ±2 bin、旁瓣 −31 dB。
     这里用「窗的解析响应」g(δ)=|Σ w[n]·e^{j2πδn/N}| 去卷积，不是往最近 bin 上怼一个尖峰。
  2. **帧积分的长度**：一帧是 256 点（80 ms），不是 15 ms。键控的 5 ms 升降沿经过这
     80 ms 的窗积分之后，起振会被摊开成两三帧 —— 少了这一步，模拟图上的点划边界
     会比真实图锐利得多，模型在边界上的行为也就跟真链路对不上了。

用法：
    python tests/deepcw/sim_spec.py --out tests/out/deepcw-sim
    python tests/deepcw/sim_spec.py --text "CQ CQ DE BH6RGQ K" --wpm 20 --tone 700 \
        --seconds 10 --snr 12 --png sim.png
    # 三个信号同时在场（模拟一个拥挤的频段）
    python tests/deepcw/sim_spec.py --multi "520:16:18,700:12:20,1010:9:25" \
        --seconds 12 --out tests/out/deepcw-sim-band
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from spec_ref import (BINS, FFT_LENGTH, HOP_LENGTH, MAX_HZ, MIN_HZ, MORSE,  # noqa: E402
                      SAMPLE_RATE, normalize_spectrogram)

HERE = os.path.dirname(os.path.abspath(__file__))
FRAME_S = HOP_LENGTH / SAMPLE_RATE            # 15 ms
WIN_S = FFT_LENGTH / SAMPLE_RATE              # 80 ms
BIN_HZ = SAMPLE_RATE / FFT_LENGTH             # 12.5 Hz


# ---------------------------------------------------------------- 窗的解析响应
def window_response() -> tuple[np.ndarray, np.ndarray]:
    """g(δ) = |Σ w[n]·e^{j2πδn/N}|：一个音调偏离 bin 中心 δ 个 bin 时，落在该 bin 上的幅度。

    δ 用 1/64 bin 的步长采样到 ±6 bin（Hann 主瓣 ±2，旁瓣到 −60 dB 以外基本不动了），
    调用侧线性插值。这样模拟图上的「一根线」宽度、旁瓣电平就和真 FFT 一致。
    """
    n = np.arange(FFT_LENGTH)
    w = np.hanning(FFT_LENGTH + 1)[:-1]       # 周期 Hann，与 spec_ref 一致
    deltas = np.arange(-6.0, 6.0 + 1e-9, 1.0 / 64)
    g = np.array([abs(np.sum(w * np.exp(2j * np.pi * d * n / FFT_LENGTH))) for d in deltas])
    return deltas, g / (w.sum() / 2.0)        # 归一化：δ=0 时 g=1


_DELTAS, _G = window_response()


def bin_response(delta_bins: np.ndarray | float) -> np.ndarray:
    return np.interp(delta_bins, _DELTAS, _G, left=0.0, right=0.0)


# ---------------------------------------------------------------- 键控包络
def morse_segments(text: str, wpm: float) -> list[tuple[float, bool]]:
    dot = 1.2 / wpm
    segs: list[tuple[float, bool]] = []
    for ch in text.upper():
        if ch == ' ':
            segs.append((7 * dot, False))
            continue
        code = MORSE.get(ch)
        if not code:
            continue
        for i, sym in enumerate(code):
            segs.append(((1 if sym == '.' else 3) * dot, True))
            if i < len(code) - 1:
                segs.append((dot, False))
        segs.append((3 * dot, False))
    return segs


def keying_envelope(text: str, wpm: float, seconds: float, t_res: float = 0.001,
                    ramp_s: float = 0.005) -> np.ndarray:
    """1 ms 分辨率、0/1 之间带 5 ms 升余弦沿的包络。文本不够长就整句重复。

    **只铺完整的句子**，最后一句话没铺满的部分留静音。差一个字符就截断的话，
    模型输在末尾会少一个字（第一版 `CQ CQ DE BH6RGQ K` 解出来没有 `K`，
    就是最后那个 `K` 的一半被数组边界切掉了）。
    """
    n = int(round(seconds / t_res))
    env = np.zeros(n, dtype=np.float64)
    segs = morse_segments(text, wpm)
    period = sum(d for d, _ in segs)
    if period <= 0:
        return env

    start = 0.0
    while start + period <= seconds:
        pos = start
        for dur, keyed in segs:
            i0 = int(round(pos / t_res))
            i1 = min(n, int(round((pos + dur) / t_res)))
            if keyed and i1 > i0:
                env[i0:i1] = 1.0
            pos += dur
        start += period

    # 一句话都装不下（比如只要 5 秒的窗口）→ 铺半句，别留一整片静音。
    # 留静音会让「结构对照」变成「拿噪声跟信号比」，相关系数直接掉到 0 附近。
    if start == 0.0:
        pos = 0.0
        for dur, keyed in segs:
            if pos >= seconds:
                break
            i0 = int(round(pos / t_res))
            i1 = min(n, int(round((pos + dur) / t_res)))
            if keyed and i1 > i0:
                env[i0:i1] = 1.0
            pos += dur
        start = pos

    # 5 ms 升余弦沿：对 0/1 方波做一次近似平滑
    k = max(1, int(round(ramp_s / t_res)))
    if k > 1:
        win = 0.5 - 0.5 * np.cos(np.pi * np.arange(k) / k)
        env = np.convolve(env, win / win.sum(), mode='same')
        # 卷积会把按键平台压到 1.0 以下，平台上把能量补回来
        peak = env.max() or 1.0
        env = np.clip(env / peak, 0.0, 1.0)
    return env


def frame_amplitudes(env: np.ndarray, frames: int, t_res: float = 0.001) -> np.ndarray:
    """把 1 ms 的包络折算成每帧的幅度。

    **两件事都不能省**：

    1. 窗口是 80 ms（FFT_LENGTH/3200），不是 15 ms 的 hop。这一条决定了点划边界
       在图上是「两三帧的软过渡」还是「一帧的硬边」。
    2. 窗内是**按 Hann 权重求线性均值**，不是平方平均。音调在窗内是相干的，
       |FFT| 正比于 Σ w[n]·env[n]，线性；换成 RMS 会把 60 ms 的空档抬到 0.6，
       符号之间的静默被抹平 → 模型看到的是一根不停的载波，输出全是乱码
       （第一版就是这么错的：解码 `OON0`）。
    """
    w = np.hanning(FFT_LENGTH + 1)[:-1]
    half = len(w) / 2
    # ★ 窗是 256 个**采样**（3.2 kHz 下 80 ms），不是 256 个毫秒槽。
    #   写成 taps=arange(256)-128 等于用 256 ms 的窗去积分 —— 60 ms 的点划会被抹成一根
    #   连续的线，只剩字符之间的空档还在（这就是第一版解码 `MMN0` 的原因）。
    taps = (np.arange(len(w)) - half) / SAMPLE_RATE / t_res
    wsum = w.sum()
    out = np.zeros(frames, dtype=np.float64)
    for f in range(frames):
        c = f * HOP_LENGTH / SAMPLE_RATE / t_res      # 帧中心的 1 ms 索引
        idx = np.clip(np.round(c + taps).astype(np.int64), 0, len(env) - 1)
        out[f] = float(np.dot(w, env[idx]) / wsum)
    return out


# ---------------------------------------------------------------- 合成功率谱
def synth_signal(tone_hz: float, amp: float, frames: int, env_amp: np.ndarray,
                 drift_hz: float = 0.0, drift_rate: float = 0.13,
                 fade_depth: float = 0.0, fade_rate: float = 0.07,
                 jitter: float = 0.0, rng: np.random.Generator | None = None) -> np.ndarray:
    """单个载波 → frames × BINS 的线性幅度（不是对数）。

    - drift：载波缓慢左右游走（发报机晶体温漂、手键节奏无关）
    - fade：QSB 衰落，整段幅度按慢正弦起伏
    - jitter：每帧的随机幅度扰动，模拟传播抖动
    """
    rng = rng or np.random.default_rng(0)
    t = np.arange(frames) * FRAME_S
    f = tone_hz + drift_hz * np.sin(2 * np.pi * drift_rate * t + 0.7)
    a = amp * env_amp
    if fade_depth:
        a = a * (1.0 - fade_depth * (0.5 - 0.5 * np.cos(2 * np.pi * fade_rate * t)))
    if jitter:
        a = a * (1.0 + jitter * rng.standard_normal(frames))

    centers = np.arange(BINS) * BIN_HZ + MIN_HZ        # 每格的中心频率
    # 音调偏离每格中心多少 bin —— 用窗的解析响应摊开
    delta = (f[:, None] - centers[None, :]) / BIN_HZ
    return a[:, None] * bin_response(delta)


def synth_noise(shape: tuple[int, int], sigma: float,
                rng: np.random.Generator) -> np.ndarray:
    """Rayleigh 分布的噪声幅度 —— |FFT(高斯白噪声)| 就是这个分布。

    用高斯而不是均匀/指数：真实噪声谱的每一格是「复数高斯取模」，Rayleigh 才对得上
    那个「一片沙沙、偶尔冒尖」的观感，也才对得上 q90 归一化的统计口径。
    """
    re = rng.standard_normal(shape) * sigma
    im = rng.standard_normal(shape) * sigma
    return np.hypot(re, im)


def build(frames: int, carriers: list[dict], noise_db: float,
          seed: int = 1) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    # 噪声底：σ 取 1，信号幅度按 SNR(dB) 折算到同样的功率口径
    sigma = 1.0
    noise = synth_noise((frames, BINS), sigma, rng)

    raw = noise
    for car in carriers:
        amp = math.sqrt(2.0 * sigma ** 2 * 10 ** (noise_db / 10.0)) * car.pop('amp', 1.0)
        raw = raw + synth_signal(car.pop('tone_hz'), amp, frames, car.pop('env_amp'),
                                 rng=rng, **car)
    return raw.astype(np.float32), normalize_spectrogram(raw.astype(np.float32))


def fit_text(text: str, wpm: float, seconds: float) -> str:
    """把报文缩到装得下 —— 整句塞不进去时逐词删，最短退到一个字母。

    多载波模式下这个坑很隐蔽：16 WPM 那台如果报文超过时长，`keying_envelope`
    一个完整句子都铺不进去（它只铺整句），结果是那条载波**整条消失**，
    图上只剩两台 —— 看着像画错了，其实是被自己「不留半句」的规则吃掉了。
    """
    words = text.split()
    while words and sum(d for d, _ in morse_segments(' '.join(words), wpm)) > seconds:
        words.pop()
    return ' '.join(words) if words else 'E'


def parse_multi(spec: str, frames: int, total_seconds: float, wpm_default: float,
                rng: np.random.Generator) -> list[dict]:
    """`500:16:18,700:12:20,1010:9:25` → 每个频点的频率:SNR:WPM。
    WPM 留空就用默认值；每个载波各自随机一段报文和起始相位，看起来才像多台同时工作。"""
    texts = ['CQ CQ DE BH6RGQ K', 'TEST DE BG5ABC/P', 'QRZ? DE JA1ABC',
             '5NN 73 TU', 'DE N0CALL K', 'VVV VVV DE BG7XYZ']
    out = []
    for i, item in enumerate(spec.split(',')):
        parts = item.strip().split(':')
        tone = float(parts[0])
        snr = float(parts[1]) if len(parts) > 1 and parts[1] else 12.0
        wpm = float(parts[2]) if len(parts) > 2 and parts[2] else wpm_default
        text = fit_text(texts[i % len(texts)], wpm, total_seconds)
        env = keying_envelope(text, wpm, total_seconds)
        # 随机相位：把包络循环平移一段，几台机器不会同时起振
        shift = int(rng.integers(0, max(1, len(env))))
        env = np.roll(env, shift)
        out.append(dict(tone_hz=tone, snr=snr, wpm=wpm, text=text, env=env))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description='直接合成 DeepCW 口径的频谱图')
    ap.add_argument('--text', default='CQ CQ DE BH6RGQ K')
    ap.add_argument('--wpm', type=float, default=20.0)
    ap.add_argument('--tone', type=float, default=700.0)
    ap.add_argument('--seconds', type=float, default=10.0)
    ap.add_argument('--snr', type=float, default=12.0, help='峰值信号 / 噪声底（dB）')
    ap.add_argument('--drift', type=float, default=0.0, help='载波游走幅度（Hz）')
    ap.add_argument('--fade', type=float, default=0.0, help='QSB 衰落深度 0~1')
    ap.add_argument('--jitter', type=float, default=0.0, help='每帧幅度抖动（相对）')
    ap.add_argument('--multi', default=None,
                    help='多载波："520:16:18,700:12:20,1010:9:25"（频率:SNR:WPM）')
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--out', default=os.path.join('tests', 'out', 'deepcw-sim'))
    ap.add_argument('--png', default=None)
    ap.add_argument('--decode', action='store_true',
                    help='顺手用模型读一遍（解码文本 = 原文本，才说明这张图是合法输入）')
    args = ap.parse_args()

    frames = int(round(args.seconds / FRAME_S))
    rng = np.random.default_rng(args.seed)

    # 一句话有多长：20 WPM 下的 `CQ CQ DE BH6RGQ K` 已经 10 s 出头。
    # 请求的时长装不下一整句时拉长到一句 —— 不然铺进去的是零个句子（全是静音），
    # 模型只会回空字符串，而且看起来像是「解码坏了」。
    if not args.multi:
        segs = morse_segments(args.text, args.wpm)
        period = sum(d for d, _ in segs)
        if period and period > args.seconds:
            args.seconds = period + 0.5
            frames = int(round(args.seconds / FRAME_S))
            print('时长不足一句（%.2f s），拉到 %.2f s' % (period, args.seconds))

    if args.multi:
        cars = parse_multi(args.multi, frames, args.seconds, args.wpm, rng)
        carriers = []
        for c in cars:
            carriers.append(dict(
                tone_hz=c['tone_hz'],
                amp=math.sqrt(10 ** ((c['snr'] - args.snr) / 10.0)),
                env_amp=frame_amplitudes(c['env'], frames),
                drift_hz=args.drift, fade_depth=args.fade, jitter=args.jitter,
            ))
        label = ' + '.join('%g Hz %g WPM' % (c['tone_hz'], c['wpm']) for c in cars)
        text_for_meta = ' / '.join(c['text'] for c in cars)
    else:
        env = keying_envelope(args.text, args.wpm, args.seconds)
        carriers = [dict(tone_hz=args.tone, env_amp=frame_amplitudes(env, frames),
                         drift_hz=args.drift, fade_depth=args.fade, jitter=args.jitter)]
        label = '%g Hz %g WPM' % (args.tone, args.wpm)
        text_for_meta = args.text

    raw, spec = build(frames, carriers, args.snr, args.seed)

    os.makedirs(args.out, exist_ok=True)
    spec.astype(np.float32).tofile(os.path.join(args.out, 'spec.f32'))
    meta = {
        'sample_rate': SAMPLE_RATE, 'fft_length': FFT_LENGTH, 'hop_length': HOP_LENGTH,
        'bins': BINS, 'frames': frames,
        'text': text_for_meta, 'wpm': args.wpm, 'tone_hz': args.tone,
        'noise_db': args.snr, 'source': 'sim', 'label': label,
        'drift_hz': args.drift, 'fade_depth': args.fade, 'jitter': args.jitter,
        'seed': args.seed,
        'spec_sha256': hashlib.sha256(spec.tobytes()).hexdigest(),
    }
    with open(os.path.join(args.out, 'meta.json'), 'w', encoding='utf-8') as fh:
        json.dump(meta, fh, indent=2, ensure_ascii=False)

    print('模拟件  : %s' % label)
    print('网格    : %d 帧 × %d bin（%.2f s @ %.1f 帧/s）'
          % (frames, BINS, frames * FRAME_S, 1 / FRAME_S))
    print('幅度    : min %.3f  max %.3f  mean %.3f（归一化后）'
          % (spec.min(), spec.max(), spec.mean()))
    print('落盘    : %s' % os.path.abspath(args.out))

    if args.png:
        subprocess.run([sys.executable, os.path.join(HERE, 'render_png.py'),
                        '--dir', args.out, '--png', args.png], check=False)

    if args.decode:
        model = os.path.join(HERE, '..', '..', 'third_party', 'deepcw', 'model.onnx')
        try:
            import onnxruntime as ort
        except ImportError:
            print('跳过解码：没有 onnxruntime')
            return 0
        if not os.path.exists(model):
            print('跳过解码：缺 model.onnx（python tools/fetch_deepcw.py）')
            return 0
        meta_path = os.path.join(HERE, '..', '..', 'third_party', 'deepcw', 'model.onnx.json')
        m = json.load(open(meta_path, encoding='utf-8'))
        x = spec[None, None, :, :].astype(np.float32)
        sess = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
        logits = sess.run(['log_probs'], {'spectrogram': x})[0][0]
        idx = logits.argmax(-1)
        out, prev = [], -1
        for i in idx:
            if i != prev and i != m['blank_index']:
                out.append(m['chars'][i])
            prev = i
        got = ''.join(out)
        print('模型解码: %r' % got)
        if not args.multi:
            want = args.text.upper()
            same = got.strip() == want.strip()
            print('期望    : %r  ->  %s' % (want, '一致' if same else '不一致'))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
