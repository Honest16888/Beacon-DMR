#!/usr/bin/env python3
"""DeepCW 模型输入（频谱图）的参考实现 + 金标准。

数值部分逐行照抄 e04/deepcw-engine 的 examples/python/decode_morse.py：
    audio_to_spectrogram() / normalize_spectrogram()
两条常数都来自 model.onnx.json（3.2 kHz、FFT 256、hop 48、65 bin、q90 log1p）。

唯一的差别是重采样：官方例子用线性插值（作者自己注明「依赖最少」），
而模型的训练音频是 ffmpeg 重采样过的（带抗混叠滤波）。这里按 255 抽头
Blackman 窗 sinc、截止 1.4 kHz —— 落在目标奈奎斯特 1.6 kHz 之下，
又在模型关心的 1.2 kHz 之上。

用法：
    python tests/deepcw/spec_ref.py --out tests/out/deepcw

产出（全部是 f32 裸二进制，方便 Node 直接比）：
    cw48k.wav      合成的 48 kHz CW（16 bit PCM，单声道）
    cw3200.f32     重采样后的 3.2 kHz 波形
    spec.f32       归一化频谱，frames × 65，行优先
    meta.json      形状与常数
"""

from __future__ import annotations

import argparse
import json
import math
import os
import wave

import numpy as np

# ---- 与 model.onnx.json 一致 ----
SAMPLE_RATE = 3200
FFT_LENGTH = 256
HOP_LENGTH = 48
MIN_HZ = 400.0
MAX_HZ = 1200.0
BINS = 65
QUANTILE = 0.9
NOISE_LEVEL_MIN_RELATIVE_TO_PEAK = 1e-3
NOISE_LEVEL_MIN = 1e-5

RESAMPLE_TAPS = 255
RESAMPLE_CUTOFF_HZ = 1400.0

MORSE = {
    'A': '.-', 'B': '-...', 'C': '-.-.', 'D': '-..', 'E': '.', 'F': '..-.',
    'G': '--.', 'H': '....', 'I': '..', 'J': '.---', 'K': '-.-', 'L': '.-..',
    'M': '--', 'N': '-.', 'O': '---', 'P': '.--.', 'Q': '--.-', 'R': '.-.',
    'S': '...', 'T': '-', 'U': '..-', 'V': '...-', 'W': '.--', 'X': '-..-',
    'Y': '-.--', 'Z': '--..',
    '0': '-----', '1': '.----', '2': '..---', '3': '...--', '4': '....-',
    '5': '.....', '6': '-....', '7': '--...', '8': '---..', '9': '----.',
    '/': '-..-.', '?': '..--..', '.': '.-.-.-', ',': '--..--',
}


def synth_cw(text: str, wpm: float = 20.0, tone_hz: float = 700.0,
             sample_rate: int = 48000, noise_db: float | None = None,
             seed: int = 1) -> np.ndarray:
    """按 PARIS 标准合成 CW：点 = 1.2/WPM 秒，5 ms 升余弦键控。"""
    dot = 1.2 / wpm
    ramp = 0.005
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

    total = sum(d for d, _ in segs)
    length = max(1, int(round(total * sample_rate)))
    pcm = np.zeros(length, dtype=np.float64)

    pos = 0
    for duration, keyed in segs:
        n = max(1, int(round(duration * sample_rate)))
        if keyed:
            idx = np.arange(n, dtype=np.float64)
            env = np.ones(n)
            ramp_n = min(int(round(ramp * sample_rate)), n // 2)
            if ramp_n > 0:
                rise = 0.5 - 0.5 * np.cos(np.pi * np.arange(ramp_n) / ramp_n)
                env[:ramp_n] = rise
                env[n - ramp_n:] = rise[::-1]
            tone = 0.5 * env * np.sin(2 * np.pi * tone_hz * idx / sample_rate)
            end = min(pos + n, length)
            pcm[pos:end] += tone[: end - pos]
        pos += n
        if pos >= length:
            break

    if noise_db is not None:
        rng = np.random.default_rng(seed)
        gauss = rng.standard_normal(length)
        keyed = pcm != 0
        rms = float(np.sqrt(np.mean(pcm[keyed] ** 2))) if keyed.any() else 0.5
        pcm += gauss * (rms / (10 ** (noise_db / 20.0)))

    return pcm.astype(np.float32)


def write_wav(path: str, pcm: np.ndarray, sample_rate: int) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = np.clip(pcm, -1.0, 1.0)
    with wave.open(path, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes((data * 32767.0).astype('<i2').tobytes())


def read_wav_16(path: str) -> np.ndarray:
    """读回 16 bit PCM 单声道 —— 金标准要建立在「文件里真正写下的采样」上，
    否则 JS 侧读 WAV 时会多出一层量化差，对比就没意义了。"""
    with wave.open(path, 'rb') as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 2, 'expect mono 16-bit'
        data = wav.readframes(wav.getnframes())
    return (np.frombuffer(data, dtype='<i2').astype(np.float32) / 32768.0)


def resample_sinc(x: np.ndarray, src: int, dst: int = SAMPLE_RATE,
                  cutoff: float = RESAMPLE_CUTOFF_HZ) -> np.ndarray:
    """与 dsp.js 的 resampleSinc 逐式对应（同样的窗、同样的边沿处理）。"""
    if src == dst:
        return x.astype(np.float32, copy=True)

    taps = RESAMPLE_TAPS
    half = (taps - 1) // 2
    n_out = int(round(len(x) * dst / src))
    ratio = src / dst
    fc = cutoff / src

    i = np.arange(taps, dtype=np.float64)
    u = (i - half) / half
    win = 0.42 - 0.5 * np.cos(np.pi * (u + 1)) + 0.08 * np.cos(2 * np.pi * (u + 1))
    k = np.arange(-half, half + 1, dtype=np.int64)

    out = np.zeros(n_out, dtype=np.float64)
    chunk = 4096
    for start in range(0, n_out, chunk):
        stop = min(start + chunk, n_out)
        t = np.arange(start, stop, dtype=np.float64) * ratio
        base = np.floor(t).astype(np.int64)
        frac = t - base
        idx = base[:, None] + k[None, :]
        z = k[None, :].astype(np.float64) - frac[:, None]
        s = np.sinc(2 * fc * z) * win[None, :]
        valid = (idx >= 0) & (idx < len(x))
        idx_clipped = np.clip(idx, 0, len(x) - 1)
        num = np.sum(np.where(valid, x[idx_clipped] * s, 0.0), axis=1)
        den = np.sum(np.where(valid, s, 0.0), axis=1)
        with np.errstate(divide='ignore', invalid='ignore'):
            out[start:stop] = np.where(den != 0, num / den, 0.0)
    return out.astype(np.float32)


def frequency_bin_range(sample_rate: int, fft_length: int,
                        min_hz: float, max_hz: float) -> tuple[int, int]:
    bin_hz = sample_rate / fft_length
    return int(math.ceil(min_hz / bin_hz)), int(math.floor(max_hz / bin_hz)) + 1


def normalize_spectrogram(spectrogram: np.ndarray, quantile: float = QUANTILE) -> np.ndarray:
    """照抄 decode_morse.py 的 normalize_spectrogram（noise_q90_log1p）。"""
    flat = np.sort(spectrogram.reshape(-1))
    if len(flat) == 0:
        return spectrogram.astype(np.float32, copy=False)
    rayleigh_factor = math.sqrt(-2.0 * math.log(1.0 - quantile))
    noise_level = max(
        float(flat[math.floor(quantile * (len(flat) - 1))]) / rayleigh_factor,
        float(flat[-1]) * NOISE_LEVEL_MIN_RELATIVE_TO_PEAK,
        NOISE_LEVEL_MIN,
    )
    return np.log1p(spectrogram / np.float32(noise_level)).astype(np.float32, copy=False)


def audio_to_spectrogram(audio: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """照抄 decode_morse.py 的 audio_to_spectrogram，返回 (原始幅度, 归一化)。"""
    if len(audio) < FFT_LENGTH:
        raise ValueError(f'audio is too short for fft_length={FFT_LENGTH}')

    start_bin, stop_bin = frequency_bin_range(SAMPLE_RATE, FFT_LENGTH, MIN_HZ, MAX_HZ)
    if stop_bin - start_bin != BINS:
        raise ValueError(f'metadata expects {BINS} bins, got {stop_bin - start_bin}')

    padded = np.pad(audio, (FFT_LENGTH // 2, FFT_LENGTH // 2), mode='reflect')
    window = np.hanning(FFT_LENGTH + 1)[:-1].astype(np.float32)
    frames = 1 + (len(padded) - FFT_LENGTH) // HOP_LENGTH
    raw = np.empty((frames, BINS), dtype=np.float32)

    for f in range(frames):
        start = f * HOP_LENGTH
        frame = padded[start:start + FFT_LENGTH] * window
        spectrum = np.fft.rfft(frame, n=FFT_LENGTH)
        raw[f] = np.abs(spectrum[start_bin:stop_bin]).astype(np.float32)

    return raw, normalize_spectrogram(raw)


def main() -> None:
    parser = argparse.ArgumentParser(description='DeepCW 输入频谱的参考实现（金标准）')
    parser.add_argument('--out', default=os.path.join('tests', 'out', 'deepcw'))
    parser.add_argument('--text', default='CQ CQ DE BH6RGQ K')
    parser.add_argument('--wpm', type=float, default=20.0)
    parser.add_argument('--tone', type=float, default=700.0)
    parser.add_argument('--noise-db', type=float, default=0.0)
    parser.add_argument('--seconds', type=float, default=6.0)
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)

    # 时间不够就重复文本，直到凑够 --seconds
    text = args.text
    pcm = synth_cw(text, args.wpm, args.tone, 48000, args.noise_db)
    while len(pcm) < args.seconds * 48000:
        pcm = np.concatenate([pcm, synth_cw(text, args.wpm, args.tone, 48000, args.noise_db)])

    wav_path = os.path.join(args.out, 'cw48k.wav')
    write_wav(wav_path, pcm, 48000)
    pcm = read_wav_16(wav_path)           # 以后都用「文件里的采样」

    pcm3200 = resample_sinc(pcm, 48000)
    raw, spec = audio_to_spectrogram(pcm3200)

    pcm3200.tofile(os.path.join(args.out, 'cw3200.f32'))
    spec.astype(np.float32).tofile(os.path.join(args.out, 'spec.f32'))

    meta = {
        'sample_rate': SAMPLE_RATE,
        'fft_length': FFT_LENGTH,
        'hop_length': HOP_LENGTH,
        'bins': BINS,
        'frames': int(spec.shape[0]),
        'source_rate': 48000,
        'source_samples': int(len(pcm)),
        'text': args.text,
        'wpm': args.wpm,
        'tone_hz': args.tone,
        'noise_db': args.noise_db,
    }
    with open(os.path.join(args.out, 'meta.json'), 'w', encoding='utf-8') as fh:
        json.dump(meta, fh, indent=2)

    secs = len(pcm) / 48000.0
    print('audio   : %.2f s @48k -> %d samples @3.2k' % (secs, len(pcm3200)))
    print('frames  : %d  (%.1f frames/s)' % (spec.shape[0], spec.shape[0] / secs))
    print('bins    : %d' % spec.shape[1])
    print('spec    : min %.3f  max %.3f  mean %.3f' % (spec.min(), spec.max(), spec.mean()))
    print('out     : %s' % os.path.abspath(args.out))

    # 顺手验证：整段幅度谱里 700 Hz 那格应该是每帧最强的
    bin_hz = SAMPLE_RATE / FFT_LENGTH
    tone_bin = int(round((args.tone - MIN_HZ) / bin_hz))
    peak_bins = raw.argmax(axis=1)
    hit = float(np.mean(peak_bins == tone_bin))
    print('tone bin: %d (%.0f Hz), 每帧最强占比 %.1f%%' % (tone_bin, MIN_HZ + tone_bin * bin_hz, hit * 100))


if __name__ == '__main__':
    main()
