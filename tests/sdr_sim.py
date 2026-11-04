#!/usr/bin/env python3
"""模拟一台 SDR 的频谱画面：上半是峰线（panadapter），下半是瀑布。

用途是**观感**：给界面/文档/演示做一张「看起来像在守听一个 DMR 频段」的图。
和 `tests/deepcw/sim_spec.py` 不是一回事 —— 那个是给 DeepCW 模型吃的 65 bin CW 频谱，
这里是宽带的、以 dBm 为单位的 SDR 视图，信号是 DMR 话音那类**占带宽度的块**，不是点划。

画什么由数据决定，不是往图上贴图形：

- **噪声底**是 Rayleigh 幅度取对数后的样子（白噪声经 FFT 就是这个统计），
  再叠一层慢起伏（±1.5 dB）——真实的底噪不是一根直线，也不是逐点白噪声。
- **DMR 信号**顶是平的（12.5 kHz 占空），两侧是余弦滚降的裙边；功率用 dBm 相加
  （不是幅度相加），所以两个信号挨在一起时是「能量叠加」而不是「谁大听谁的」。
- **时间结构**：PTT 按 1.5~3 s 的突发开关，行与行之间保留一点抖动 ——
  瀑布上就会看到一段段有起止的竖条，而不是一条到底的柱子。

用法：
    python tests/sdr_sim.py                        # 默认：439.500 MHz / 400 kHz
    python tests/sdr_sim.py --center 438.500 --span 0.2 --out tests/out/sdr-430.png
    python tests/sdr_sim.py --bare                 # 只出瀑布纹理（没有坐标轴，适合平铺当底图）
"""

from __future__ import annotations

import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ------------------------------------------------------------------ 主题
BG = (10, 13, 17)
PANEL = (14, 18, 24)
GRID = (34, 44, 56)
GRID_DOT = (30, 38, 48)
TEXT = (198, 214, 230)
MUTED = (118, 136, 154)
DIM = (86, 100, 116)
TRACE = (86, 226, 255)
TRACE_FILL = (20, 96, 130)
ACCENT = (255, 196, 64)
OK = (110, 220, 140)

# 瀑布色标：黑 → 深蓝 → 青 → 绿 → 黄 → 橙 → 白（SDR 常用的那种）
WF_STOPS = [
    (0.00, (4, 6, 12)),
    (0.16, (8, 20, 62)),
    (0.34, (14, 62, 132)),
    (0.50, (18, 128, 156)),
    (0.64, (86, 178, 108)),
    (0.76, (214, 196, 66)),
    (0.88, (238, 128, 44)),
    (1.00, (255, 250, 236)),
]


def build_lut() -> np.ndarray:
    lut = np.zeros((256, 3), np.uint8)
    for i in range(256):
        t = i / 255.0
        for j in range(1, len(WF_STOPS)):
            if t <= WF_STOPS[j][0]:
                a, b = WF_STOPS[j - 1], WF_STOPS[j]
                k = (t - a[0]) / (b[0] - a[0] or 1)
                lut[i] = [round(a[1][c] + (b[1][c] - a[1][c]) * k) for c in range(3)]
                break
        else:
            lut[i] = WF_STOPS[-1][1]
    return lut


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    """字体只用 msyh（雅黑）一条：它同时有 latin 和中文。

    以前按 consola 优先，结果中文标签全是豆腐块 —— 字体挑错在图上是一眼可见的错误，
    但脚本本身不报错，很容易一路画到交付才发现。
    """
    for name in ('msyhbd.ttc' if bold else 'msyh.ttc', 'msyh.ttc', 'segoeui.ttf', 'arial.ttf'):
        path = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts', name)
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


# ------------------------------------------------------------------ 信号
def slow_profile(bins: int, rng: np.random.Generator) -> np.ndarray:
    """底噪的慢起伏形状（整幅图共用一条）。

    每一行都重新抽一次慢起伏的话，瀑布上会出现一条条**横向亮带** —— 真实的 SDR
    底噪是「同一条带内噪声曲线在一行行地抖」，不是每一行换一个形状。
    """
    x = np.arange(bins) / bins * 2 * np.pi
    return (1.6 * np.sin(1.7 * x + rng.uniform(0, 6.3))
            + 1.1 * np.sin(4.3 * x + rng.uniform(0, 6.3))
            + 0.7 * np.sin(0.6 * x + rng.uniform(0, 6.3)))


def noise_floor(bins: int, floor_dbm: float, rng: np.random.Generator,
                slow: np.ndarray | None = None) -> np.ndarray:
    """底噪：白噪声的频谱统计（Rayleigh，均值归到 0 dB）+ 一条共用的慢起伏。"""
    rayleigh = np.abs(rng.standard_normal(bins) + 1j * rng.standard_normal(bins))
    fast = 20 * np.log10(np.maximum(rayleigh, 1e-6)) - 1.05
    return floor_dbm + (slow if slow is not None else 0.0) + 0.55 * fast


def block_profile(bins: int, span_hz: float, center_hz: float, width_hz: float,
                  roll: float = 0.12) -> np.ndarray:
    """占带宽度的信号：平顶 + 余弦滚降裙边（0..1 的功率形状）。

    DMR 是 4-FSK、12.5 kHz 信道；在 SDR 上看就是一块平顶、边上滚下去的块，
    不是一根线。`roll` 是裙边占半带宽的比例 —— 0.12 出来的边和真机截图接近。
    """
    f = (np.arange(bins) - (bins - 1) / 2) / bins * span_hz     # 相对中心频率
    half = width_hz / 2
    edge = max(half * roll, 1e-6)
    d = np.abs(f - center_hz)
    shape = np.clip((half - d) / edge, 0.0, 1.0)
    return 0.5 - 0.5 * np.cos(np.pi * shape)      # 0 → 1 的平滑上升


def dbm_add(*arrays_db: np.ndarray) -> np.ndarray:
    """功率相加：dBm → mW 求和 → dBm。两个信号叠在一起时不会「取最大值」。"""
    total = np.zeros_like(arrays_db[0])
    for a in arrays_db:
        total += 10 ** (a / 10.0)
    return 10 * np.log10(np.maximum(total, 1e-30))


def dmr_burst_schedule(rows: int, row_s: float, rng: np.random.Generator,
                       on_s=(1.4, 3.2), gap_s=(1.6, 4.0), duty_target: float = 0.55):
    """PTT 突发的时间线：1.4~3.2 s 说话，1.6~4 s 静默，来回切换。"""
    gate = np.zeros(rows, np.float32)
    i = int(rng.integers(0, max(1, int(0.4 / row_s))))
    on = True
    while i < rows:
        span = (rng.uniform(*on_s) if on else rng.uniform(*gap_s)) / row_s
        j = min(rows, i + max(1, int(span)))
        if on:
            gate[i:j] = 1.0
        i = j
        on = not on
    return gate


def synth(rows: int, bins: int, span_hz: float, floor_dbm: float, seed: int,
          busy: bool = True, gate: np.ndarray | None = None) -> tuple[np.ndarray, list[dict]]:
    """返回 (rows × bins 的 dBm 矩阵, 信号清单)。

    信号清单用来画标记（峰值频率、电平、名字），不从图上反推。
    `gate` 是主台的 PTT 时间线（每行 0/1）；不给就当成一直在发。
    """
    rng = np.random.default_rng(seed)
    row_s = 0.062
    t = np.arange(rows) * row_s
    gate_arr = np.ones(rows, np.float32) if gate is None else gate.astype(np.float32)
    prof_cache = {}
    # 信号位置按 400 kHz 写死，换更宽的 span 时按比例摊开 ——
    # 否则 1 MHz 的图里所有信号会挤在中间，看着像同一个频段的复制品。
    spread = span_hz / 400e3

    def prof(center_hz: float, width_hz: float) -> np.ndarray:
        key = (round(center_hz, 1), round(width_hz, 1))
        if key not in prof_cache:
            prof_cache[key] = block_profile(bins, span_hz, center_hz, width_hz)
        return prof_cache[key]

    signals = [
        # 主台：DMR 中继输出，声音一通一通地来
        dict(name='DMR 话音', center=0.0, width=12500, level=-73.0, kind='dmr',
             drift=0.0, fade=1.2),
        # 邻近信道，弱，另一台机器
        dict(name='DMR', center=125000.0, width=12500, level=-88.0, kind='dmr',
             drift=0.0, fade=1.6),
        # 常发的窄带信标（细线）
        dict(name='信标', center=-158000.0, width=900, level=-79.0, kind='cw',
             drift=0.0, fade=0.6),
        # 左边挤进来一个强信号：裙边压着整段底噪，看着更像真频谱
        dict(name='邻频强信号', center=-198000.0, width=27000, level=-58.0, kind='fm',
             drift=0.0, fade=2.6),
        # 慢慢漂移的弱载波
        dict(name='漂移载波', center=68000.0, width=1400, level=-93.0, kind='cw',
             drift=2400.0, fade=1.0),
    ]
    if not busy:
        signals[0]['level'] = -96.0     # 守听中，主台上没人说话

    rows_db = np.empty((rows, bins), np.float32)
    slow = slow_profile(bins, rng)
    for r in range(rows):
        floor = noise_floor(bins, floor_dbm, rng, slow)
        layers = [floor]
        for s in signals:
            shape = prof(s['center'] * spread, s['width'])
            lvl = s['level']
            if s['kind'] == 'dmr' and s is signals[0]:
                g = float(gate_arr[r])
                if g <= 0.02:
                    continue                     # 没在发：这一行不叠这一层
                lvl = lvl + 20 * math.log10(g)
            # 缓慢起伏（QSB/AGC）+ 每个信号自己的相位
            lvl += s['fade'] * math.sin(2 * math.pi * 0.07 * t[r] + s['center'] * 1e-5)
            if s['kind'] == 'dmr':
                # 突发内部也不是死死的一个电平：TDMA 帧、功放包络、传播都会让行与行之间差几十分之一 dB
                lvl += float(rng.normal(0.0, 0.35))
            if s['drift']:
                shift = int(round(s['drift'] * math.sin(2 * math.pi * 0.05 * t[r])
                                  / (span_hz / bins)))
                shape = np.roll(shape, shift)
            layers.append(lvl + 10 * np.log10(np.maximum(shape, 1e-9)))
        rows_db[r] = dbm_add(*layers)
    return rows_db, signals


def main() -> None:
    ap = argparse.ArgumentParser(description='模拟 SDR 频谱画面（峰线 + 瀑布）')
    ap.add_argument('--center', type=float, default=439.5, help='中心频率 (MHz)')
    ap.add_argument('--span', type=float, default=0.4, help='带宽 (MHz)')
    ap.add_argument('--bins', type=int, default=1024)
    ap.add_argument('--rows', type=int, default=430)
    ap.add_argument('--floor', type=float, default=-104.0, help='底噪 (dBm)')
    ap.add_argument('--seed', type=int, default=7)
    ap.add_argument('--idle', action='store_true', help='主台上没人说话')
    ap.add_argument('--bare', action='store_true', help='只出瀑布纹理，无坐标轴/文字')
    ap.add_argument('--out', default=os.path.join('tests', 'out', 'sdr-sim.png'))
    args = ap.parse_args()

    span_hz = args.span * 1e6
    # 先把 PTT 时间线算出来，再按行取值
    gate = dmr_burst_schedule(args.rows, 0.062, np.random.default_rng(args.seed + 99))
    if args.idle:
        gate = np.zeros(args.rows, np.float32)

    rows_db, signals = synth(args.rows, args.bins, span_hz, args.floor, args.seed,
                             busy=not args.idle, gate=gate)

    # 归一化到 0..1 给色标：底噪是 0，最强信号是 1
    lo = np.percentile(rows_db, 5)
    hi = rows_db.max()
    norm = np.clip((rows_db - lo) / (hi - lo), 0, 1) ** 0.85

    lut = build_lut()
    wf = lut[(norm * 255).astype(np.uint8)]           # rows × bins × 3

    if args.bare:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        img = Image.fromarray(wf).resize((1400, 900), Image.BILINEAR)
        img.save(args.out)
        print('png :', os.path.abspath(args.out), '(bare)')
        return

    # ---------------------------------------------------------------- 版式
    W, MARGIN_H = 1560, 1000
    H = MARGIN_H
    ML, MR = 96, 28
    PT, PB = 74, 392                       # 峰线区
    WT, WCUT = 428, 916                    # 瀑布区（底下要留出频率轴的整行）
    PW = W - ML - MR

    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    f_title = font(17, True)
    f_big = font(21, True)
    f_small = font(12)
    f_tiny = font(11)

    # 顶栏
    d.rectangle([0, 0, W, 52], fill=PANEL)
    d.line([(0, 52), (W, 52)], fill=GRID)
    d.text((ML, 14), 'SDR 模拟', font=f_title, fill=TEXT)
    d.ellipse([ML + 98, 24, ML + 106, 32], fill=OK)
    d.text((ML + 114, 18), '守听中', font=f_small, fill=OK)
    d.text((ML + 320, 14), '%.6f MHz' % args.center, font=f_big, fill=ACCENT)
    d.text((ML + 540, 18), 'SPAN %.0f kHz' % (args.span * 1e3), font=f_small, fill=TEXT)
    d.text((ML + 680, 18), 'RBW %.0f Hz' % (span_hz / args.bins), font=f_small, fill=TEXT)
    d.text((ML + 820, 18), 'FFT %d' % args.bins, font=f_small, fill=TEXT)
    d.text((W - MR, 18), '瀑布 %.1f s · 模拟数据' % (args.rows * 0.062), font=f_small,
           fill=MUTED, anchor='ra')

    # ---------------------------------------------------------------- 峰线
    d.rectangle([ML, PT, ML + PW, PB], fill=PANEL)
    db_hi, db_lo = -40.0, -120.0

    def y_of(db: float) -> float:
        return PT + (db_hi - db) / (db_hi - db_lo) * (PB - PT)

    for db in range(int(db_lo), int(db_hi) + 1, 10):
        y = y_of(db)
        d.line([(ML, y), (ML + PW, y)], fill=GRID_DOT)
        d.text((ML - 10, y), '%d' % db, font=f_tiny, fill=DIM, anchor='rm')
    d.text((ML - 10, PT - 18), 'dBm', font=f_tiny, fill=MUTED, anchor='rm')

    # 取一帧「主台正在说话」的行当实时曲线：突发信号只在某几帧出现，
    # 用第 0 行（可能正好静默）当峰线，图上就会看不到主角。
    c0 = args.bins // 2
    r_show = int(np.argmax(rows_db[:, max(0, c0 - 8):c0 + 8].mean(axis=1)))
    trace = rows_db[r_show]                  # 当前这一帧
    trace_max = rows_db.max(axis=0)          # 峰值保持
    xs = np.linspace(ML, ML + PW, args.bins)
    ys = np.array([y_of(v) for v in trace])

    # 下面积：从曲线填到底部
    fill_layer = Image.new('RGB', (W, H), BG)
    fd = ImageDraw.Draw(fill_layer)
    pts = list(zip(xs.tolist(), ys.tolist()))
    fd.polygon(pts + [(xs[-1], PB), (xs[0], PB)], fill=TRACE_FILL)
    img = Image.blend(img, fill_layer, 0.30)
    d = ImageDraw.Draw(img)
    for db in range(int(db_lo), int(db_hi) + 1, 10):
        y = y_of(db)
        d.line([(ML, y), (ML + PW, y)], fill=GRID_DOT)
        d.text((ML - 10, y), '%d' % db, font=f_tiny, fill=DIM, anchor='rm')

    # 峰值保持线（暗）+ 当前线（亮）
    d.line([(x, y_of(v)) for x, v in zip(xs, trace_max)], fill=(40, 118, 142), width=1)
    d.line(pts, fill=TRACE, width=2)
    d.rectangle([ML, PT, ML + PW, PB], outline=GRID)

    # 峰值标记：从**峰值保持**里取每段信号的最高点，不从当前帧（当前帧可能正好在静默）
    spread = span_hz / 400e3
    marks = []
    for s in signals:
        c_hz = s['center'] * spread
        i0 = int(np.clip(args.bins / 2 + (c_hz - s['width'] / 2) / span_hz * args.bins,
                         0, args.bins - 1))
        i1 = int(np.clip(args.bins / 2 + (c_hz + s['width'] / 2) / span_hz * args.bins,
                         0, args.bins - 1))
        seg = trace_max[i0:max(i1, i0 + 1)]
        k = i0 + int(seg.argmax())
        db = float(trace_max[k])
        f_hz = args.center * 1e6 + (k - (args.bins - 1) / 2) / args.bins * span_hz
        marks.append(dict(name=s['name'], hz=f_hz, db=db, x=float(xs[k]), y=y_of(db)))

    used: list[tuple[float, float, float, float]] = []
    for mk in marks:
        if mk['db'] < args.floor + 6:          # 太弱的不标，免得图上一堆框
            continue
        d.line([(mk['x'], mk['y'] + 8), (mk['x'], PB)], fill=(64, 80, 96))
        tw = d.textlength(mk['name'], font=f_tiny)
        bw2, bh2 = tw + 10, 16
        bx = min(max(mk['x'] - bw2 / 2, ML + 4), ML + PW - bw2 - 4)
        by = max(PT + 6, mk['y'] - 30)
        for _ in range(8):                     # 简单避让：跟已有标签重叠就往下挪
            if not any(abs(by - y0) < bh2 + 3 and bx < x0 + w0 and bx + bw2 > x0
                       for x0, y0, w0, h0 in used):
                break
            by += bh2 + 4
        used.append((bx, by, bw2, bh2))
        d.rectangle([bx, by, bx + bw2, by + bh2], fill=(24, 32, 42), outline=(52, 66, 82))
        d.text((bx + 5, by + 1), mk['name'], font=f_tiny, fill=TEXT)
        d.text((mk['x'] + 5, mk['y'] + 14),
               '%+.1f kHz · %.0f dBm' % (mk['hz'] / 1e3 - args.center * 1e3, mk['db']),
               font=f_tiny, fill=ACCENT)

    # 中心游标 + 12.5 kHz 解调带宽（淡色，不要糊掉曲线）
    cx = ML + PW / 2
    bw = 12500 / span_hz * PW
    overlay = Image.new('RGB', (W, H), BG)
    od = ImageDraw.Draw(overlay)
    od.rectangle([cx - bw / 2, PT, cx + bw / 2, PB], fill=(26, 42, 28))
    img = Image.blend(img, overlay, 0.22)
    d = ImageDraw.Draw(img)
    d.line([(cx - bw / 2, PT), (cx - bw / 2, PB)], fill=(70, 120, 80))
    d.line([(cx + bw / 2, PT), (cx + bw / 2, PB)], fill=(70, 120, 80))
    d.line([(cx, PT), (cx, PB)], fill=(150, 230, 160), width=1)

    # ---------------------------------------------------------------- 瀑布
    wf_img = Image.fromarray(wf).resize((PW, WCUT - WT), Image.BILINEAR)
    img.paste(wf_img, (ML, WT))
    d = ImageDraw.Draw(img)
    d.rectangle([ML, WT, ML + PW, WCUT], outline=GRID)

    # 瀑布上的时间刻度（左）与信号竖线（穿过瀑布，帮助读「谁在哪」）
    for i in range(0, 31, 5):
        y = WT + (i / 30.0) * (WCUT - WT)
        d.text((ML - 10, y), '-%ds' % i, font=f_tiny, fill=DIM, anchor='rm')
        d.line([(ML, y), (ML + 6, y)], fill=DIM)
    for mk in marks:
        d.line([(mk['x'], WT), (mk['x'], WT + 18)], fill=(255, 255, 255, 60))

    # 频率轴
    ytick = WCUT + 5
    n_ticks = 9
    for i in range(n_ticks):
        t = i / (n_ticks - 1)
        x = ML + t * PW
        mhz = args.center + (t - 0.5) * args.span
        d.line([(x, WCUT), (x, WCUT + 4)], fill=MUTED)
        d.text((x, ytick + 4), '%.4f' % mhz, font=f_tiny, fill=TEXT, anchor='ma')
        if 0 < i < n_ticks - 1:
            xm = ML + (t - 0.5 / (n_ticks - 1)) * PW
            d.line([(xm, WCUT), (xm, WCUT + 2)], fill=GRID)
    d.text((ML + PW, ytick + 19), 'MHz', font=f_tiny, fill=MUTED, anchor='ra')

    # 底部状态条
    d.rectangle([0, H - 34, W, H], fill=PANEL)
    d.line([(0, H - 34), (W, H - 34)], fill=GRID)
    d.text((ML, H - 26), '底噪 %.1f dBm   占用带宽 12.5 kHz   AGC 自动   NF 4.2 dB'
           % args.floor, font=f_tiny, fill=MUTED)
    d.text((W - MR, H - 26), '示意数据，非真实接收', font=f_tiny, fill=DIM, anchor='ra')

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    img.save(args.out)
    print('png :', os.path.abspath(args.out))
    print('size:', os.path.getsize(args.out), 'bytes')
    for mk in marks:
        print('  %-8s %+9.1f kHz  %6.1f dBm' % (mk['name'], mk['hz'] / 1e3 - args.center * 1e3,
                                                mk['db']))


if __name__ == '__main__':
    main()
