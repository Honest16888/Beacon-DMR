import cmath, math

FS = 8000.0

def hp1(fc):
    """一阶高通（现在的 dcBlock）"""
    a = 1.0 / (1.0 + 2*math.pi*fc/FS)
    return [a, -a], [1.0, -a]

def hp2_butter(fc):
    """二阶 Butterworth 高通，双线性 + 预畸变"""
    wc = math.tan(math.pi*fc/FS)
    wc2 = wc*wc
    s2 = math.sqrt(2.0)*wc
    k = 1.0/(1.0 + s2 + wc2)
    b = [k, -2*k, k]
    a = [1.0, 2.0*(wc2-1.0)*k, (1.0-s2+wc2)*k]
    return b, a

def peaking(f0, Q, db):
    """RBJ peaking EQ"""
    A = 10**(db/40.0)
    w0 = 2*math.pi*f0/FS
    alpha = math.sin(w0)/(2*Q)
    b0 = 1+alpha*A; b1 = -2*math.cos(w0); b2 = 1-alpha*A
    a0 = 1+alpha/A; a1 = -2*math.cos(w0); a2 = 1-alpha/A
    return [b0/a0, b1/a0, b2/a0], [1.0, a1/a0, a2/a0]

def resp(b, a, f):
    z = cmath.exp(-1j*2*math.pi*f/FS)
    num = b[0] + b[1]*z + (b[2]*z*z if len(b) > 2 else 0)
    den = a[0] + a[1]*z + (a[2]*z*z if len(a) > 2 else 0)
    return num/den

def db(b, a, f):
    return 20*math.log10(abs(resp(b, a, f)) + 1e-12)

print("=== 现在的一阶 120 Hz 高通 ===")
b, a = hp1(120)
for f in [40, 60, 80, 100, 120, 150, 200, 280, 400, 1000, 3000]:
    print(f"  {f:5d} Hz  {db(b,a,f):+7.2f} dB")

print("\n=== 改成二阶 280 Hz Butterworth ===")
b2, a2 = hp2_butter(280)
for f in [40, 60, 80, 100, 120, 150, 200, 280, 400, 1000, 3000]:
    print(f"  {f:5d} Hz  {db(b2,a2,f):+7.2f} dB")

print("\n=== 改善量（新 - 旧）===")
for f in [40, 60, 80, 100, 120, 150, 200, 400, 1000]:
    print(f"  {f:5d} Hz  {db(b2,a2,f)-db(b,a,f):+7.2f} dB")

print("\n=== 直流 / Nyquist 检查（二阶）===")
print(f"  DC 增益   {db(b2,a2,0.001):+.2f} dB  (应趋于 -inf)")
print(f"  Nyq 增益  {db(b2,a2,3999):+.4f} dB  (应为 0)")

print("\n=== presence: 2600 Hz Q0.8 +2 dB ===")
bp, ap = peaking(2600, 0.8, 2.0)
for f in [300, 500, 1000, 1500, 2000, 2600, 3000, 3400, 3800]:
    print(f"  {f:5d} Hz  {db(bp,ap,f):+7.2f} dB")
print(f"  DC 增益   {db(bp,ap,0.001):+.4f} dB  (应为 0)")
print(f"  Nyq 增益  {db(bp,ap,3999):+.4f} dB  (应为 0)")

print("\n=== 级联总响应（高通 + presence）===")
for f in [60, 100, 150, 200, 280, 400, 1000, 2000, 2600, 3000, 3400]:
    t = db(b2, a2, f) + db(bp, ap, f)
    print(f"  {f:5d} Hz  {t:+7.2f} dB")

print("\n=== 抽头数 / 过渡带 ===")
for taps in (127, 159, 191):
    print(f"  {taps:4d} 抽头  过渡带 3.1*48000/{taps} = {3.1*48000/taps:7.1f} Hz")
print("\n  cutoff 3400, 127 抽头 -> 阻带边缘约", round(3400+3.1*48000/127/2), "Hz (>4000 混叠!)")
print("  cutoff 3200, 159 抽头 -> 阻带边缘约", round(3200+3.1*48000/159/2), "Hz (<4000 安全)")
