"""v2.1 发射链专项仿真：AGC 的两个 bug 到底修没修好。

v2.0 之前 AGC 有两个问题，都不是「调参不对」，而是代码逻辑本身错了：

  Bug 1  agcGain / rmsAvg / agcFrames 挂在对象上，start() 里不重置。
         agcFrames 只增不减 → `agcFrames < AGC_FAST_FRAMES` 那段快速收敛
         **只有开机后第一次 PTT 才走到**。之后每次发射都从上一通残留的
         增益起步，按 1 秒的慢速收敛。

  Bug 2  电平检测器上升/下降共用 AGC_SLOW(0.02)。说一句话，中间的停顿
         让检测器按 1 秒的速率往下掉，下一个词回来时又要 1 秒才追上。
         听感是「每个词之间音量塌一下」。

这个脚本把两件事都量化出来，不靠形容词。

纯 Python，不依赖 numpy。用法：
    python tests/tx_agc_v21.py
"""
import math

FS = 8000
FRAME = 160                      # 20 ms

# ------------------------------------------------------------------ 信号


def make_word(level, dur_s, f0=120.0, seed=1):
    """一个「词」：谐波 + 共振峰包络 + 音节起伏，整体电平 = level（RMS）"""
    n = int(FS * dur_s)
    formants = [(650, 140, 1.0), (1150, 170, 0.6),
                (2400, 240, 0.30), (3200, 260, 0.12)]
    sig = [0.0] * n
    ph = 0.37
    for h in range(1, int(3400 / f0) + 1):
        f = h * f0
        env = 0.12
        for fc, bw, g in formants:
            env = max(env, g / (1.0 + ((f - fc) / (bw / 2)) ** 2))
        amp = env / (h ** 0.45)
        ph += 0.71 * seed
        for i in range(n):
            sig[i] += amp * math.sin(2 * math.pi * f * i / FS + ph)

    # 音节起伏：3.4 Hz，±3 dB
    for i in range(n):
        sig[i] *= 1.0 + 0.30 * math.sin(2 * math.pi * 3.4 * i / FS)

    r = math.sqrt(sum(x * x for x in sig) / n) or 1.0
    return [x * level / r for x in sig]


def make_sentence(words, gap_s=0.30):
    """把若干「词」用静音隔开，模拟一句话里的停顿。

    停顿里**必须留一点噪声**，不能是数字零 —— 真实麦克风永远有本底噪声，
    而 AGC 的行为恰恰由「停顿时的底噪电平」决定。用纯零会把 AGC 的
    词间行为测成假的（增益会一路冲上限）。
    """
    gap = [0.0] * int(FS * gap_s)
    out = []
    for w in words:
        out.extend(w)
        out.extend(gap)

    # 确定性伪随机噪声，RMS ≈ 6（约 −75 dBFS），模拟安静的手机麦克风
    s = 12345
    for i in range(len(out)):
        s = (s * 1103515245 + 12345) & 0x7FFFFFFF
        out[i] += ((s / 0x7FFFFFFF) * 2 - 1) * 6.0 * 1.732
    return out


# ------------------------------------------------------------------ AGC


class Agc:
    """逐行对应 DmrTransmitter.agc()。mode='old' / 'new'"""

    TARGET = {"old": 4000.0, "new": 5200.0}
    GATE_RMS = 100.0
    MAXG, MING = 8.0, 0.3
    FAST_FRAMES = 8
    FAST = 0.50
    SLOW = 0.02
    RISE = 0.25

    def __init__(self, mode, gain=1.0, rms_avg=0.0, frames=0):
        self.mode = mode
        self.target = self.TARGET[mode]
        # 允许外部注入「上一通残留」的状态
        self.gain = gain
        self.rms_avg = rms_avg
        self.frames = frames
        self.trace = []

    def reset_like_v21(self):
        """v2.1 在 start() 里做的事"""
        self.gain = 1.0
        self.rms_avg = 0.0
        self.frames = 0

    def process(self, pcm):
        n = len(pcm)
        rms = math.sqrt(sum(x * x for x in pcm) / n)

        if self.frames < 1:
            self.rms_avg = rms
        else:
            if self.mode == "old":
                a = self.FAST if self.frames < self.FAST_FRAMES else self.SLOW
            else:
                if self.frames < self.FAST_FRAMES:
                    a = self.FAST
                else:
                    a = self.RISE if rms > self.rms_avg else self.SLOW
            self.rms_avg += (rms - self.rms_avg) * a
        self.frames += 1

        want = 1.0
        if self.rms_avg > self.GATE_RMS:
            want = self.target / self.rms_avg
            want = min(max(want, self.MING), self.MAXG)

        if self.mode == "new" and self.frames == 1 and self.rms_avg > self.GATE_RMS:
            self.gain = want            # 首帧直接到位
        else:
            if self.mode == "old":
                k = 0.30 if want > self.gain else 0.06
            else:
                k = 0.35 if want < self.gain else 0.08   # 压快、提慢
            self.gain += (want - self.gain) * k

        out = [x * self.gain for x in pcm]
        self.trace.append(self.gain)
        return out


def run(agc, sig):
    """把整段信号按 20 ms 一帧过一遍 AGC"""
    out = []
    for i in range(0, len(sig) - FRAME + 1, FRAME):
        out.extend(agc.process(sig[i:i + FRAME]))
    return out


def rms(a):
    return math.sqrt(sum(x * x for x in a) / len(a)) if a else 0.0


def db(v):
    return 20 * math.log10(max(v, 1e-9))


def frame_rms_list(sig):
    return [rms(sig[i:i + FRAME]) for i in range(0, len(sig) - FRAME + 1, FRAME)]


# ------------------------------------------------------------------ 测试 1

print("=" * 72)
print("测试 1  跨发射残留：上一通大声、这一通轻声")
print("=" * 72)

LOUD = 6000.0
QUIET = 700.0
word_loud = make_word(LOUD, 1.2, seed=1)
word_quiet = make_word(QUIET, 1.2, seed=2)

# 上一通（大声）跑完，记下稳态增益
a_prev = Agc("new")
run(a_prev, word_loud)
resid_gain = a_prev.gain
resid_rms = a_prev.rms_avg
print(f"  上一通结束时残留状态：增益 {resid_gain:.2f}×   rmsAvg {resid_rms:.0f}   "
      f"frames {a_prev.frames}")

# 这一通（轻声）第一帧的正确增益应该是多少
first_frame_rms = rms(word_quiet[:FRAME])
ideal_gain = min(max(5200.0 / first_frame_rms, Agc.MING), Agc.MAXG)
print(f"  这一通首帧真实 RMS {first_frame_rms:.0f} → 理想增益 {ideal_gain:.2f}×")

for tag, reset in (("不重置（v2.0 行为）", False), ("重置（v2.1 行为）", True)):
    a = Agc("new", gain=resid_gain, rms_avg=resid_rms, frames=a_prev.frames)
    if reset:
        a.reset_like_v21()
    out = run(a, word_quiet)
    g0 = a.trace[0]
    # 前 200 ms（10 帧）的实际电平 vs 稳态电平
    early = rms(out[:FRAME * 10])
    steady = rms(out[FRAME * 40:FRAME * 50])
    err = db(early / steady) if steady > 0 else 0
    print(f"\n  {tag}")
    print(f"    首帧增益 {g0:.2f}×   （理想 {ideal_gain:.2f}×，偏差 {db(g0/ideal_gain):+.1f} dB）")
    print(f"    前 200 ms 电平相对稳态 {err:+.1f} dB")


# ------------------------------------------------------------------ 测试 2

print()
print("=" * 72)
print("测试 2  词间塌陷：一句话里三个词，词间停顿 300 ms")
print("=" * 72)

words = [make_word(3000.0, 0.80, seed=3),
         make_word(3000.0, 0.80, seed=4),
         make_word(3000.0, 0.80, seed=5)]
sentence = make_sentence(words, gap_s=0.30)
print(f"  信号 {len(sentence)/FS:.2f} s，{len(sentence)//FRAME} 帧")

for mode, label in (("old", "v2.0：检测器上下对称（0.02）"),
                    ("new", "v2.1：上升 0.25 / 下降 0.02")):
    a = Agc(mode)
    out = run(a, sentence)
    fr = frame_rms_list(out)
    # 词的位置：每词 40 帧 + 15 帧间隔
    word_len = 40
    gap_len = 15
    print(f"\n  {label}")
    for wi in range(3):
        st = wi * (word_len + gap_len)
        # 词内前 5 帧 vs 词内后 20 帧（已稳态）
        head = rms(out[st * FRAME:(st + 5) * FRAME])
        tail = rms(out[(st + 20) * FRAME:(st + word_len) * FRAME])
        print(f"    第 {wi+1} 词：前 100 ms 相对稳态 {db(head/tail):+6.1f} dB")
    # 词间最低电平（塌陷深度）
    lows = []
    for wi in range(2):
        gs = (wi + 1) * (word_len + gap_len) - gap_len
        lows.append(min(fr[gs:gs + gap_len]))
    steady_all = rms(out[20 * FRAME:40 * FRAME])
    print(f"    词间最低电平 {db(min(lows)/steady_all):+6.1f} dB（相对稳态）")


# ------------------------------------------------------------------ 测试 3

print()
print("=" * 72)
print("测试 3  电平检测器上升速度（换人 / 换机器的场景）")
print("=" * 72)
print("  轻声说 1 s，紧接着大声说 1 s，看增益多久追上")

quiet_then_loud = make_word(900.0, 1.0, seed=6) + make_word(7000.0, 1.0, seed=7)
for mode, label in (("old", "v2.0 对称 0.02"), ("new", "v2.1 上升 0.25")):
    a = Agc(mode)
    out = run(a, quiet_then_loud)
    # 大声段起点在第 50 帧
    st = 50
    print(f"\n  {label}")
    for k in (5, 10, 25, 50):
        seg = rms(out[(st + k - 5) * FRAME:(st + k) * FRAME])
        ref = rms(out[(st + 45) * FRAME:(st + 50) * FRAME])
        print(f"    大声段第 {k*20:3d} ms 处电平相对稳态 {db(seg/ref):+6.1f} dB")


# ------------------------------------------------------------------ 测试 4

print()
print("=" * 72)
print("测试 4  presence EQ 频响：v2.0 (2600/Q0.8/+2) vs v2.1 (2000/Q0.9/+3)")
print("=" * 72)


def peak_resp(f0, q, gain_db, freqs, fs=FS):
    """RBJ peaking 的幅频响应（dB）"""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * math.pi * f0 / fs
    al = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    a0 = 1 + al / A
    b0, b1, b2 = (1 + al * A) / a0, -2 * cw / a0, (1 - al * A) / a0
    a1, a2 = -2 * cw / a0, (1 - al / A) / a0
    out = []
    for f in freqs:
        w = 2 * math.pi * f / fs
        z1 = complex(math.cos(-w), math.sin(-w))
        z2 = z1 * z1
        num = b0 + b1 * z1 + b2 * z2
        den = 1 + a1 * z1 + a2 * z2
        out.append(20 * math.log10(max(abs(num / den), 1e-12)))
    return out


freqs = [300, 500, 700, 900, 1200, 1500, 1800, 2000, 2400, 2800, 3200]
old = peak_resp(2600, 0.8, 2.0, freqs)
new = peak_resp(2000, 0.9, 3.0, freqs)
print(f"  {'频率':>8s} {'v2.0':>9s} {'v2.1':>9s} {'差':>9s}")
for f, o, n in zip(freqs, old, new):
    print(f"  {f:6d}Hz {o:+8.2f} {n:+8.2f} {n-o:+8.2f}")

core_old = sum(old[freqs.index(f)] for f in (900, 1200, 1500, 1800, 2000)) / 5
core_new = sum(new[freqs.index(f)] for f in (900, 1200, 1500, 1800, 2000)) / 5
sib_old = sum(old[freqs.index(f)] for f in (2400, 2800, 3200)) / 3
sib_new = sum(new[freqs.index(f)] for f in (2400, 2800, 3200)) / 3
print()
print(f"  可懂度核心 900–2000 Hz 平均：v2.0 {core_old:+.2f} dB → v2.1 {core_new:+.2f} dB")
print(f"  齿音区 2400–3200 Hz 平均：  v2.0 {sib_old:+.2f} dB → v2.1 {sib_new:+.2f} dB")
print(f"  核心/齿音 差：v2.0 {core_old-sib_old:+.2f} dB → v2.1 {core_new-sib_new:+.2f} dB"
      f"   （越大越偏可懂度）")


# ------------------------------------------------------------------ 测试 5

print()
print("=" * 72)
print("测试 5  噪声门：AGC 之后压掉底噪，同时不吃字")
print("=" * 72)

# 一段「说话 - 停顿 - 说话 - 长停顿 - 说话」，底噪 −62 dBFS
NOISE_RMS = 26.0          # 32768 * 10^(-62/20) ≈ 26
words = [make_word(1800.0, 0.70, seed=11),
         make_word(1800.0, 0.70, seed=12),
         make_word(1800.0, 0.70, seed=13)]
sig = []
sig.extend(words[0]); sig.extend([0.0] * int(FS * 0.25))
sig.extend(words[1]); sig.extend([0.0] * int(FS * 1.20))   # 长停顿，门必须关
sig.extend(words[2]); sig.extend([0.0] * int(FS * 0.40))

s = 99991
for i in range(len(sig)):
    s = (s * 1103515245 + 12345) & 0x7FFFFFFF
    sig[i] += ((s / 0x7FFFFFFF) * 2 - 1) * NOISE_RMS * 1.732

# 说话段 / 长停顿段的下标
SP = [(0, len(words[0])), (len(words[0]) + int(FS*0.25), len(words[0]) + int(FS*0.25) + len(words[1]))]
GAP = (len(words[0]) + int(FS*0.25) + len(words[1]), len(words[0]) + int(FS*0.25) + len(words[1]) + int(FS*1.20))


class Gate:
    """逐行对应 DmrTransmitter.noiseGate()"""
    THR_MULT = 2.8
    HOLD = 16
    OPEN = 0.55
    CLOSE = 0.08
    MAX_CUT = 18.0

    def __init__(self):
        self.floor = 0.0
        self.gain = 1.0
        self.hold = 0
        self.closed = 0          # 处于关闭状态的帧数，用于统计

    def process(self, rms_pre, rms_avg):
        cap = max(rms_avg * 0.30, 1.0)
        if self.floor < 1.0:
            self.floor = max(rms_pre, 1.0)
        elif rms_pre < self.floor:
            self.floor += (rms_pre - self.floor) * 0.35
        else:
            self.floor += (rms_pre - self.floor) * 0.0004
        if self.floor > cap:
            self.floor = cap

        thr = self.floor * self.THR_MULT
        if rms_pre >= thr:
            self.hold = self.HOLD
        elif self.hold > 0:
            self.hold -= 1

        want = 1.0
        if self.hold <= 0 and 1.0 < rms_pre < thr:
            cut = 20 * math.log10(thr / rms_pre)
            cut = min(cut, self.MAX_CUT)
            want = 10 ** (-cut / 20)
            self.closed += 1

        k = self.OPEN if want > self.gain else self.CLOSE
        self.gain += (want - self.gain) * k
        return self.gain


def chain_v21(sig, use_gate):
    a = Agc("new")
    g = Gate() if use_gate else None
    out = []
    for i in range(0, len(sig) - FRAME + 1, FRAME):
        blk = sig[i:i + FRAME]
        pre = math.sqrt(sum(x * x for x in blk) / len(blk))
        # AGC 内部会更新 rms_avg，先算一遍拿到更新后的值
        y = a.process(blk)
        if g is not None:
            gg = g.process(pre, a.rms_avg)
            y = [v * gg for v in y]
        out.extend(y)
    return out, (g.closed if g else 0)


for tag, use in (("无噪声门（v2.0）", False), ("有噪声门（v2.1）", True)):
    out, closed = chain_v21(sig, use)
    sp = []
    for a0, b0 in SP:
        sp.append(rms(out[a0:b0]))
    speech = sum(sp) / len(sp)
    gap = rms(out[GAP[0]:GAP[1]])
    # 只看长停顿的后半段（前半段还在保持窗里）
    gap_tail = rms(out[GAP[0] + int(FS*0.6):GAP[1]])
    print(f"\n  {tag}")
    print(f"    说话段 RMS        {speech:8.0f}  ({db(speech/32768):+.1f} dBFS)")
    print(f"    长停顿 RMS        {gap:8.0f}  ({db(gap/32768):+.1f} dBFS)")
    print(f"    长停顿后半段 RMS  {gap_tail:8.0f}  ({db(gap_tail/32768):+.1f} dBFS)")
    print(f"    语音/底噪 比      {db(speech/gap_tail):+8.1f} dB")

print()
print("  参考：AMBE+2 在语音/底噪比低于 25 dB 时，噪声会明显分走比特，")
print("        听感从「清楚」滑向「糊」；30 dB 以上基本听不出底噪。")
