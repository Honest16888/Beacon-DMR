"""噪声门会不会「吃字」—— 也就是对方听到的「断续」。

v2.1 最初的门有两个参数是错的，合起来造成每个词的开头被压掉一截：

  1. **保持窗只有 320 ms**。真实说话词与词之间常有 300~500 ms 的停顿，
     保持窗一过门就关。
  2. **开门是渐变的**（0.55/帧），从 −18 dB 回到满增益要 ~70 ms。
     而声母（b/p/d/t/g/k）本身就集中在头 30~60 ms —— 正好全被吃掉。
     声码器拿到一截几乎静音的起音，会判成无声帧，对方听到的就是「字头没了」。

这个脚本按停顿长度分别测量「下一个词起音被压多少 dB」，
对比 v2.1 初版参数和修正后的参数。

纯 Python。用法：python tests/tx_gate_chop.py
"""
import math

FS = 8000
FRAME = 160

TARGET, GATE_RMS = 5200.0, 100.0
MAXG, MING = 8.0, 0.3
FAST_FRAMES, FAST, SLOW, RISE = 8, 0.50, 0.02, 0.25

NG_THR_MULT = 2.8
CAP_RATIO = 0.30


def make_word(dur_s, level=1600.0, f0=120.0, seed=1):
    n = int(FS * dur_s)
    formants = [(650, 140, 1.0), (1150, 170, 0.6), (2400, 240, 0.30), (3200, 260, 0.12)]
    sig = [0.0] * n
    ph = 0.31
    for h in range(1, int(3400 / f0) + 1):
        f = h * f0
        env = 0.12
        for fc, bw, g in formants:
            env = max(env, g / (1.0 + ((f - fc) / (bw / 2)) ** 2))
        amp = env / (h ** 0.45)
        ph += 0.73 * seed
        for i in range(n):
            sig[i] += amp * math.sin(2 * math.pi * f * i / FS + ph)
    for i in range(n):
        sig[i] *= 1.0 + 0.30 * math.sin(2 * math.pi * 3.6 * i / FS)
    r = math.sqrt(sum(x * x for x in sig) / n) or 1.0
    return [x * level / r for x in sig]


def add_noise(sig, level):
    s = 424242
    out = list(sig)
    for i in range(len(out)):
        s = (s * 1103515245 + 12345) & 0x7FFFFFFF
        out[i] += ((s / 0x7FFFFFFF) * 2 - 1) * level * 1.732
    return out


def run(sig, hold_frames, snap_open, max_cut):
    """hold_frames / snap_open / max_cut 决定用哪一版参数"""
    rms_avg, agc, frames = 0.0, 1.0, 0
    floor, ng, hold = 0.0, 1.0, 0
    out, gtrace = [], []
    for i in range(0, len(sig) - FRAME + 1, FRAME):
        blk = sig[i:i + FRAME]
        r = math.sqrt(sum(x * x for x in blk) / len(blk))

        if frames < 1:
            rms_avg = r
        else:
            a = FAST if frames < FAST_FRAMES else (RISE if r > rms_avg else SLOW)
            rms_avg += (r - rms_avg) * a
        frames += 1
        want = 1.0
        if rms_avg > GATE_RMS:
            want = min(max(TARGET / rms_avg, MING), MAXG)
        if frames == 1 and rms_avg > GATE_RMS:
            agc = want
        else:
            k = 0.35 if want < agc else 0.08
            agc += (want - agc) * k
        y = [v * agc for v in blk]

        cap = max(rms_avg * CAP_RATIO, 1.0)
        if floor < 1.0:
            floor = max(r, 1.0)
        elif r < floor:
            floor += (r - floor) * 0.35
        else:
            floor += (r - floor) * 0.0004
        if floor > cap:
            floor = cap
        thr = floor * NG_THR_MULT

        g = 1.0
        if r >= thr:
            hold = hold_frames
        elif hold > 0:
            hold -= 1
        elif r > 1.0:
            cut = min(20 * math.log10(thr / r), max_cut)
            g = 10 ** (-cut / 20)

        if g > ng:
            if snap_open:
                ng = g
            else:
                ng += (g - ng) * 0.55
        else:
            ng += (g - ng) * (0.08 if not snap_open else 0.10)
        y = [v * ng for v in y]
        gtrace.append(ng)
        out.extend(y)
    return out, gtrace


def rms(a):
    return math.sqrt(sum(x * x for x in a) / len(a)) if a else 0.0


def db(v):
    return 20 * math.log10(max(v, 1e-9))


WARM, WORD, NOISE = 0.9, 0.9, 30.0        # 预热词 0.9s；待测词 0.9s；底噪 ≈ −61 dBFS
GAPS = [150, 250, 350, 450, 600, 900, 1500]

print("测试：一句话里，停顿 G 毫秒之后的下一个词，起音被压掉多少")
print(f"底噪 ≈ −61 dBFS，词电平 RMS 1600，停顿后接 {WORD*1000:.0f} ms 的词")
print()
print(f"{'停顿':>6s} | {'初版 320ms/渐变/18dB':>24s} | {'修正 600ms/硬开/14dB':>24s}")
print(f"{'':>6s} | {'起音':>10s} {'门最低增益':>11s} | {'起音':>10s} {'门最低增益':>11s}")

for gap_ms in GAPS:
    row = []
    for hold, snap, cut in ((16, False, 18.0), (30, True, 14.0)):
        sig = make_word(WARM, seed=1) + [0.0] * int(FS * gap_ms / 1000) + make_word(WORD, seed=2)
        sig = add_noise(sig, NOISE)
        out, g = run(sig, hold, snap, cut)
        onset = int(FS * WARM) + int(FS * gap_ms / 1000)
        head = rms(out[onset:onset + int(FS * 0.06)])
        body = rms(out[onset + int(FS * 0.30):onset + int(FS * 0.60)])
        gwin = g[onset // FRAME:(onset + int(FS * WORD)) // FRAME]
        row.append((db(head / body) if body > 0 else 0, min(gwin) if gwin else 1))
    a, b = row
    print(f"{gap_ms:5d}ms | {a[0]:+9.1f}dB {a[1]:11.3f} | {b[0]:+9.1f}dB {b[1]:11.3f}")

print()
print("  「起音」越接近 0 dB 越好。负得越多 = 字头被吃掉得越多。")
print("  −3 dB 以内基本听不出来；超过 −6 dB 就是明显的「断续」。")
