#!/usr/bin/env python3
"""校验「模拟频谱」和「真链路算出来的频谱」是不是同一个东西。

`sim_spec.py` 在频谱域直接合成，省掉了重采样；省掉的东西会不会改变图的形状，
靠这个脚本回答，而不是靠肉眼看两张图「差不多」。

比的是**结构**（同一个报文、同一个音调、都不加噪声）：
    - 逐帧峰值包络的相关系数（点划节奏对不对）
    - 峰值 bin 是否都落在 700 Hz
    - 每帧的最强 bin 索引是否一致

噪声不加：噪声是随机的，两次独立抽样的逐格相关必然低，比它没有意义。

用法：
    python tests/deepcw/check_sim.py             # 只看数字，退出码即结论
    python tests/deepcw/check_sim.py --png x.png # 顺手出并排对照图
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim_spec  # noqa: E402
import spec_ref  # noqa: E402

TEXT, WPM, TONE = 'CQ CQ DE BH6RGQ K', 20.0, 700.0


def real_spec(seconds: float) -> np.ndarray:
    pcm = spec_ref.synth_cw(TEXT, WPM, TONE, 48000, None)
    while len(pcm) < seconds * 48000:
        pcm = np.concatenate([pcm, spec_ref.synth_cw(TEXT, WPM, TONE, 48000, None)])
    pcm = pcm[: int(seconds * 48000)]
    _, spec = spec_ref.audio_to_spectrogram(spec_ref.resample_sinc(pcm, 48000))
    return spec


def sim_spectrum(seconds: float, snr_db: float = 60.0) -> np.ndarray:
    frames = int(round(seconds / sim_spec.FRAME_S))
    env = sim_spec.keying_envelope(TEXT, WPM, seconds)
    carriers = [dict(tone_hz=TONE, env_amp=sim_spec.frame_amplitudes(env, frames))]
    _, spec = sim_spec.build(frames, carriers, snr_db, seed=1)
    return spec


def plot(real: np.ndarray, sim: np.ndarray, out: str, n: int) -> None:
    from PIL import Image, ImageDraw, ImageFont

    stops = [(0.0, (5, 7, 10)), (0.2, (18, 54, 92)), (0.45, (31, 138, 156)),
             (0.7, (200, 170, 70)), (1.0, (255, 255, 255))]
    lut = np.zeros((256, 3), np.uint8)
    for i in range(256):
        t = i / 255.0
        for j in range(1, len(stops)):
            if t <= stops[j][0]:
                u, v = stops[j - 1], stops[j]
                k = (t - u[0]) / (v[0] - u[0] or 1)
                lut[i] = [round(u[1][c] + (v[1][c] - u[1][c]) * k) for c in range(3)]
                break
    else:
        lut[i] = stops[-1][1]

    def band(x: np.ndarray) -> Image.Image:
        idx = np.clip(x / 2.8 * 255, 0, 255).astype(np.uint8)
        rgb = lut[idx.transpose(1, 0)]                     # bins × frames × 3
        return Image.fromarray(np.flip(rgb, axis=0)).resize((900, 240), Image.BILINEAR)

    W, H = 1000, 640
    img = Image.new('RGB', (W, H), (11, 15, 20))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)
    img.paste(band(real), (50, 34))
    d.text((50, 12), '真链路 48k → 3.2k → STFT（无噪声）', font=font, fill=(215, 227, 238))
    img.paste(band(sim), (50, 344))
    d.text((50, 322), '模拟件 频谱域直接合成（无噪声）', font=font, fill=(215, 227, 238))
    secs = n * 0.015
    for i in range(int(secs) + 1):
        x = 50 + i / secs * 900
        d.text((x - 6, 280), '%ds' % i, font=font, fill=(120, 140, 160))
        d.text((x - 6, 590), '%ds' % i, font=font, fill=(120, 140, 160))
    img.save(out)


def main() -> int:
    ap = argparse.ArgumentParser(description='模拟频谱 vs 真链路频谱')
    ap.add_argument('--seconds', type=float, default=5.0)
    ap.add_argument('--png', default=None)
    args = ap.parse_args()

    real = real_spec(args.seconds)
    sim = sim_spectrum(args.seconds)
    n = min(len(real), len(sim))
    a, b = real[:n].astype(np.float64), sim[:n].astype(np.float64)

    corr = float(np.corrcoef(a.ravel(), b.ravel())[0, 1])
    la, lb = a.max(1), b.max(1)
    corr_peak = float(np.corrcoef(la, lb)[0, 1])
    pa, pb = a.argmax(1), b.argmax(1)
    # 只在「两台都认为有信号」的帧上比 argmax：静默帧里 argmax 是噪声抽出来的，
    # 比它没有意义（两边的噪声还是不同种子）。
    on = (la > 0.5 * la.max()) & (lb > 0.5 * lb.max())
    bin_same = float((pa[on] == pb[on]).mean()) if on.any() else 0.0
    tone_bin = int(round((TONE - spec_ref.MIN_HZ) / sim_spec.BIN_HZ))
    on_bins = float((pa[on] == tone_bin).mean()) > 0.95 and float((pb[on] == tone_bin).mean()) > 0.95

    print('帧数        : real %d / sim %d' % (len(real), len(sim)))
    print('整体相关    : %.4f   （逐格比，含窗泄漏的细节）' % corr)
    print('逐帧峰值相关: %.4f   ← 点划节奏，这条最该看' % corr_peak)
    print('有声帧      : %d / %d' % (int(on.sum()), n))
    print('有声帧最强 bin 一致: %.1f%%（都在 %g Hz？%s）'
          % (bin_same * 100, spec_ref.MIN_HZ + tone_bin * sim_spec.BIN_HZ,
             '是' if on_bins else '否'))

    ok = corr_peak > 0.97 and bin_same > 0.95 and on_bins
    print('结论        :', 'PASS —— 模拟图与真链路结构一致' if ok else 'FAIL')

    if args.png:
        plot(real, sim, args.png, n)
        print('对照图      :', os.path.abspath(args.png))
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
