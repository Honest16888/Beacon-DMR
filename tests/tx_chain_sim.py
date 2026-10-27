"""发射前端链路仿真：旧链 vs 新链。

目的不是「证明好听」（这要上机通联才知道），而是确认三件事：
  1. 低频确实被压下去了，而且压的是「糊」的那一段，不是语音主体；
  2. 处理后不会削波 / 溢出；
  3. AGC 的稳态增益落在合理区间，不会把底噪放大。

纯 Python，不依赖 numpy。

用法：
    python tests/tx_chain_sim.py

输出里带「必须为 0」的那几项是硬性要求，其余是设计参考值。
"""
import math, random

random.seed(20261102)      # 固定谐波相位，保证每次跑结果一致

FS = 8000
DUR = 2.0
N = int(FS * DUR)

# ---------------------------------------------------------------- 信号生成

def biquad(b, a):
    """返回一个带状态的 biquad 处理函数（一阶自动补零）"""
    b = list(b) + [0.0]*(3-len(b))
    a = list(a) + [0.0]*(3-len(a))
    z1 = z2 = 0.0
    def run(x):
        nonlocal z1, z2
        y = b[0]*x + z1
        z1 = b[1]*x - a[1]*y + z2
        z2 = b[2]*x - a[2]*y
        return y
    return run

def design_lp(fc, q=0.7071, fs=FS):
    w0 = 2*math.pi*fc/fs
    al = math.sin(w0)/(2*q)
    cw = math.cos(w0)
    b0 = (1-cw)/2; b1 = 1-cw; b2 = (1-cw)/2
    a0 = 1+al; a1 = -2*cw; a2 = 1-al
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]

def design_bp(fc, q, fs=FS):
    w0 = 2*math.pi*fc/fs
    al = math.sin(w0)/(2*q)
    cw = math.cos(w0)
    b0 = al; b1 = 0.0; b2 = -al
    a0 = 1+al; a1 = -2*cw; a2 = 1-al
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]

def design_hp1(fc, fs=FS):
    a = 1.0/(1.0 + 2*math.pi*fc/fs)
    return [a, -a], [1.0, -a]

def design_hp2(fc, fs=FS):
    wc = math.tan(math.pi*fc/fs)
    wc2 = wc*wc
    s2 = math.sqrt(2.0)*wc
    k = 1.0/(1.0+s2+wc2)
    return [k, -2*k, k], [1.0, 2.0*(wc2-1.0)*k, (1.0-s2+wc2)*k]

def design_peak(f0, q, db, fs=FS):
    A = 10**(db/40.0)
    w0 = 2*math.pi*f0/fs
    al = math.sin(w0)/(2*q)
    cw = math.cos(w0)
    a0 = 1+al/A
    return [(1+al*A)/a0, -2*cw/a0, (1-al*A)/a0], [1.0, -2*cw/a0, (1-al/A)/a0]


def make_speech():
    """合成一段带「近讲效应」的元音，外加一个爆破音。

    谐波按共振峰包络加权，f0=120 Hz（成年男声）。
    近讲效应：把 300 Hz 以下抬 +13 dB —— 这正是手机贴嘴说话时发生的事。
    """
    f0 = 120.0
    formants = [(700, 130, 1.0), (1220, 160, 0.55),
                (2600, 220, 0.28), (3300, 260, 0.14)]
    sig = [0.0]*N
    for h in range(1, int(3400/f0)+1):
        f = h*f0
        env = 0.12                     # 谱底：基频不能是 0，真实语音基频很强
        for fc, bw, g in formants:
            env = max(env, g/(1.0 + ((f-fc)/(bw/2))**2))
        amp = env/(h**0.45)            # 声门源的自然倾斜
        ph = random.random()*2*math.pi
        for i in range(N):
            sig[i] += amp*math.sin(2*math.pi*f*i/FS + ph)

    def norm_peak(a, target=1.0):
        p = max(abs(x) for x in a) or 1.0
        return [x*target/p for x in a]

    sig = norm_peak(sig)                                   # 先把纯语音归一到 1

    # 幅度包络：模拟说话的音节起伏（约 20 dB 动态）
    for i in range(N):
        t = i/FS
        sig[i] *= 0.55 + 0.45*math.sin(2*math.pi*3.1*t)

    # 近讲效应：一阶低架 +13 dB @ 300 Hz
    lo = biquad(*design_lp(300, 0.6))
    lows = [lo(x) for x in sig]
    sig = [sig[i] + lows[i]*4.6 for i in range(N)]
    sig = norm_peak(sig)                                   # 归一化后再加爆破音

    # 爆破音：0.3 s 处 30 ms 的低频冲击，峰值取语音的 1.6 倍（p/b 的典型形态）
    st = int(0.3*FS)
    for i in range(st, min(st+int(0.03*FS), N)):
        k = (i-st)/(0.03*FS)
        sig[i] += 1.6*math.exp(-k*7.0)*math.sin(2*math.pi*95*(i-st)/FS)

    return [x*26000.0 for x in sig]


def band_rms(sig, fc, q):
    f = biquad(*design_bp(fc, q))
    acc = 0.0
    for x in sig:
        y = f(x)
        acc += y*y
    return math.sqrt(acc/len(sig))


def rms(sig):
    return math.sqrt(sum(x*x for x in sig)/len(sig))


def peak_of(sig):
    return max(abs(x) for x in sig)


def clip_count(sig):
    return sum(1 for x in sig if abs(x) > 32767)


# ---------------------------------------------------------------- 两条链

def chain_old(sig):
    hp = biquad(*design_hp1(120))
    out = []
    agc = 1.0
    gains = []
    for i in range(0, len(sig), 160):
        blk = sig[i:i+160]
        blk = [hp(x) for x in blk]
        pk = max(abs(x) for x in blk)
        if pk > 700:
            want = 20000.0/pk
            want = min(max(want, 1.0), 4.0)
        else:
            want = 1.0
        k = 0.35 if want > agc else 0.03
        agc += (want-agc)*k
        gains.append(agc)
        if agc >= 1.02:
            blk = [max(-32768, min(32767, x*agc)) for x in blk]
        out.extend(blk)
    return out, gains


def chain_new(sig):
    """与 DmrTransmitter.agc() 逐行对应"""
    hp = biquad(*design_hp2(280))
    pk = biquad(*design_peak(2600, 0.8, 2.0))
    out = []
    agc = 1.0
    rms_avg = 0.0
    frames = 0
    gains = []
    pre_rms = []
    for i in range(0, len(sig), 160):
        blk = [pk(hp(x)) for x in sig[i:i+160]]
        r = math.sqrt(sum(x*x for x in blk)/len(blk))
        pre_rms.append(r)
        if frames < 1:
            rms_avg = r
        else:
            a = 0.50 if frames < 8 else 0.02
            rms_avg += (r-rms_avg)*a
        frames += 1
        want = 1.0
        if rms_avg > 100.0:
            want = 4000.0/rms_avg
            want = min(max(want, 0.3), 8.0)
        k = 0.30 if want > agc else 0.06
        agc += (want-agc)*k
        gains.append(agc)
        if 0.99 <= agc <= 1.02:
            out.extend([max(-32768, min(32767, x)) for x in blk])
            continue
        nb = []
        for x in blk:
            y = x*agc
            a = abs(y)
            if a > 30146.0:
                over = (a-30146.0)/2622.0
                a = 30146.0 + 2622.0*(1.0-math.exp(-over))
                y = -a if y < 0 else a
            nb.append(max(-32768, min(32767, y)))
        out.extend(nb)
    return out, gains, pre_rms


# ---------------------------------------------------------------- 跑

src = make_speech()
old, old_gains = chain_old(src)
new, gains, pre_rms = chain_new(src)

print("=== 高通后、AGC 前的电平（决定 AGC 需要多少补偿）===")
print(f"  逐帧 RMS 中位数 {sorted(pre_rms)[len(pre_rms)//2]:.1f}   "
      f"最小 {min(pre_rms):.1f}   最大 {max(pre_rms):.1f}")

BANDS = [("低频 80-200", 130, 0.7),
         ("低频 200-300", 250, 1.2),
         ("中频 300-1.2k", 700, 0.9),
         ("高频 2-3.4k", 2600, 0.8)]

print("=== 输入信号 ===")
print(f"  RMS {rms(src):8.1f}   峰值 {peak_of(src):8.1f}")

for name, arr in (("旧链 (120Hz 一阶 + 峰值AGC)", old), ("新链 (280Hz 二阶 + presence + RMS AGC)", new)):
    print(f"\n=== {name} ===")
    print(f"  总 RMS {rms(arr):8.1f}   峰值 {peak_of(arr):8.1f}   削波 {clip_count(arr)}")
    for label, fc, q in BANDS:
        e = band_rms(arr, fc, q)
        print(f"  {label:16s} RMS {e:8.1f}")

print("\n=== 各频段相对电平变化（新 / 旧，dB）===")
for label, fc, q in BANDS:
    o = band_rms(old, fc, q)
    n = band_rms(new, fc, q)
    print(f"  {label:16s} {20*math.log10((n+1e-9)/(o+1e-9)):+7.2f} dB")

print("\n=== 关键结论 ===")
for lo_fc, lo_q, tag in ((130, 0.7, "80-200"), (250, 1.2, "200-300")):
    lo_o = band_rms(old, lo_fc, lo_q); lo_n = band_rms(new, lo_fc, lo_q)
    mid_o = band_rms(old, 700, 0.9); mid_n = band_rms(new, 700, 0.9)
    print(f"  {tag:8s} /中频 比：旧 {20*math.log10(lo_o/mid_o):+.1f} dB → 新 {20*math.log10(lo_n/mid_n):+.1f} dB")
hi_o = band_rms(old, 2600, 0.8); hi_n = band_rms(new, 2600, 0.8)
mid_o = band_rms(old, 700, 0.9); mid_n = band_rms(new, 700, 0.9)
print(f"  2-3.4k   /中频 比：旧 {20*math.log10(hi_o/mid_o):+.1f} dB → 新 {20*math.log10(hi_n/mid_n):+.1f} dB")

# AGC 抽气量化：稳态段（跳过前 25% 收敛期）增益的相对抖动
def swing(g):
    s = g[len(g)//4:]
    return min(s), max(s), 20*math.log10((max(s)+1e-9)/(min(s)+1e-9))

print("\n=== AGC 稳定性（跳过前 25% 收敛期）===")
for tag, g in (("旧链", old_gains), ("新链", gains)):
    lo, hi, pp = swing(g)
    print(f"  {tag}  增益 {lo:.2f}× ~ {hi:.2f}×   峰峰 {pp:5.1f} dB")
print("  （峰峰越小越不「抽气」；>3 dB 就能听出来）")

print("\n=== 不同输入电平下的行为（MIC 源各机差异能到 20 dB）===")
print("  输入增益   输出RMS   峰值   硬削波   AGC均值   摆动")
for g, label in [(0.1, "很轻"), (0.3, "偏轻"), (1.0, "正常"),
                 (2.5, "偏大"), (4.0, "很大")]:
    scaled = [x*g for x in src]
    o, gg, _ = chain_new(scaled)
    s = gg[len(gg)//4:]
    pp = 20*math.log10(max(s)/min(s)) if min(s) > 0 else 999
    hard = sum(1 for x in o if abs(x) >= 32760)
    print(f"  ×{g:<6}  {rms(o):8.0f} {peak_of(o):7.0f} {hard:8d}   "
          f"{sum(s)/len(s):6.2f}× {pp:6.1f} dB")

print(f"\n  新链硬削波样本数 {clip_count(new)}（必须为 0）")
