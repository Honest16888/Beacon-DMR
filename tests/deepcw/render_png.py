#!/usr/bin/env python3
"""把 spec.f32 画成一张瀑布图（不需要浏览器）。

Python 侧画图的好处是：图里的每个像素都直接来自参考实现算出的数值，
不是「网页大概长这样」——看图就等于看金标准。

用法：
    python tests/deepcw/spec_ref.py --out <目录>
    python tests/deepcw/render_png.py --dir <目录> --png <文件.png>
"""

from __future__ import annotations

import argparse
import json
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BG = (11, 15, 20)
PANEL = (10, 14, 19)
TEXT = (215, 227, 238)
MUTED = (125, 144, 163)
GRID = (30, 42, 54)

# 与 index.html 里同一组色标：深蓝 → 青 → 黄 → 白
STOPS = [
    (0.00, (5, 7, 10)),
    (0.18, (18, 54, 92)),
    (0.42, (31, 138, 156)),
    (0.68, (200, 170, 70)),
    (0.86, (240, 220, 120)),
    (1.00, (255, 255, 255)),
]


def build_lut() -> np.ndarray:
    lut = np.zeros((256, 3), dtype=np.uint8)
    for i in range(256):
        t = i / 255.0
        for j in range(1, len(STOPS)):
            if t <= STOPS[j][0]:
                a, b = STOPS[j - 1], STOPS[j]
                k = (t - a[0]) / (b[0] - a[0] or 1)
                lut[i] = [round(a[1][c] + (b[1][c] - a[1][c]) * k) for c in range(3)]
                break
        else:
            lut[i] = STOPS[-1][1]
    return lut


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for name in ('msyh.ttc', 'segoeui.ttf', 'arial.ttf'):
        path = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts', name)
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--dir', default=os.path.join('tests', 'out', 'deepcw'))
    parser.add_argument('--png', default=None)
    parser.add_argument('--gain', type=float, default=3.5)
    parser.add_argument('--scale', type=int, default=2)
    args = parser.parse_args()

    with open(os.path.join(args.dir, 'meta.json'), encoding='utf-8') as fh:
        meta = json.load(fh)
    frames = int(meta['frames'])
    bins = int(meta['bins'])
    data = np.fromfile(os.path.join(args.dir, 'spec.f32'), dtype='<f4').reshape(frames, bins)

    color_index = np.clip(data / args.gain * 255.0, 0, 255).astype(np.uint8)
    lut = build_lut()
    rgb = lut[color_index]                      # frames × bins × 3

    # 低频在下：数组是 frames × bins，转成「高 = bins、宽 = frames」，再把 bin 轴翻过来
    img = Image.fromarray(np.flip(rgb.transpose(1, 0, 2), axis=0))

    W, H = 1180, 470
    ml, mr, mt, mb = 66, 22, 54, 44
    pw, ph = W - ml - mr, H - mt - mb

    out = Image.new('RGB', (W, H), BG)
    scope = img.resize((pw, ph), Image.BILINEAR)
    out.paste(scope, (ml, mt))

    d = ImageDraw.Draw(out)
    f_small = load_font(12)
    f_title = load_font(15)

    # 网格 + 频率刻度
    for hz in (400, 600, 800, 1000, 1200):
        y = mt + ph - (hz - 400) / 800 * ph
        d.line([(ml, y), (ml + pw, y)], fill=GRID)
        d.text((ml - 10, y - 7), str(hz), font=f_small, fill=MUTED, anchor='ra')

    # 时间刻度
    seconds = frames * 0.015
    step = 2 if seconds > 12 else 1
    s = 0
    while s <= seconds:
        x = ml + (s / seconds) * pw
        d.line([(x, mt), (x, mt + ph)], fill=GRID)
        d.text((x, mt + ph + 8), f'{s:g}s', font=f_small, fill=MUTED, anchor='ma')
        s += step

    d.rectangle([ml, mt, ml + pw - 1, mt + ph - 1], outline=GRID)

    # 标题与角标（用 ASCII 画标题，避免字体缺字；中文说明放下面）
    d.text((ml, 16), 'DEEPCW  MODEL INPUT', font=f_title, fill=TEXT, anchor='la')
    d.text((ml + pw, 18), f'{frames} frames x {bins} bins  |  3200 Hz  |  hop 48 (15 ms)',
           font=f_small, fill=MUTED, anchor='ra')
    d.text((ml, H - 22), f'输入报文：{meta.get("text", "")}    '
                         f'{meta.get("wpm", "")} WPM   {meta.get("tone_hz", "")} Hz   '
                         f'SNR {meta.get("noise_db", "")} dB',
           font=f_small, fill=MUTED, anchor='la')
    d.text((ml + pw, H - 22), '400-1200 Hz  |  q90 log1p', font=f_small, fill=MUTED, anchor='ra')

    png = args.png or os.path.join(args.dir, 'deepcw-spectrogram.png')
    out.save(png)
    print('png :', os.path.abspath(png))
    print('size:', os.path.getsize(png), 'bytes')


if __name__ == '__main__':
    main()
