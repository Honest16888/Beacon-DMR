#!/usr/bin/env python3
"""AGC「静音 → 开口」过冲仿真。

背景（2026-10-28）：用户给了一段 18.48 s 的发射录音（8 kHz，AMBE 解码输出），
里面**只有开头 400 ms 异常**：电平冲到 −5.1 dBFS，比后面正常段（中位 −24.8）
高约 20 dB，然后指数回落。后面 17 秒稳得很。

那个形状是 AGC 没收敛的典型特征。这个脚本把当时的参数复现一遍，
量化「静音期把增益顶到多高 → 开口时过冲多少 dB → 要多久才收回来」，
然后对比修复后的参数。

    python tests/tx_agc_overshoot.py

纯 Python，不依赖 numpy，跟其它 tx_*.py 保持一致。
"""

import math

TARGET = 4200.0      # v2.4.4 的目标 RMS
GATE = 100.0         # v2.4.4 的静音门限（−50 dBFS）
GMAX = 8.0           # v2.4.4 的最大提升（+18 dB）
GMIN = 0.22
FAST_FRAMES = 8
FAST = 0.50
SLOW = 0.02
RISE = 0.25


def db(v):
    return 20.0 * math.log10(max(v, 1e-9))


class Agc:
    """复刻 DmrTransmitter.agc() 的增益决策部分（不含 PCM 处理）。"""

    def __init__(self, gate=GATE, gmax=GMAX, jump_out=None, jump_ratio=None):
        self.gain = 1.0
        self.rms_avg = 0.0
        self.frames = 0
        self.gate = gate
        self.gmax = gmax
        # jump_out：输出超过目标的多少倍就复位（None = 关闭瞬态复位）
        self.jump_out = jump_out
        self.jump_ratio = jump_ratio
        self.trace = []

    def step(self, rms):
        prev = self.rms_avg
        if self.frames < 1:
            self.rms_avg = rms
        else:
            a = FAST if self.frames < FAST_FRAMES else (RISE if rms > self.rms_avg else SLOW)
            self.rms_avg += (rms - self.rms_avg) * a
        self.frames += 1

        want = 1.0
        if self.rms_avg > self.gate:
            want = TARGET / self.rms_avg
            want = min(max(want, GMIN), self.gmax)

        jump = False
        if self.frames == 1 and self.rms_avg > self.gate:
            jump = True
        elif self.jump_out is not None and rms > self.gate:
            if self.gain * rms > TARGET * self.jump_out:
                jump = True
                # 用**当前帧**的 rms 算目标，而不是平滑值 ——
                # 平滑值这时候还停在静音期的小数上，算出来仍然偏高
                want = min(max(TARGET / rms, GMIN), self.gmax)
        elif self.jump_ratio is not None and prev > 1.0 and rms > prev * self.jump_ratio \
                and rms > self.gate:
            jump = True
            want = min(max(TARGET / rms, GMIN), self.gmax)

        if jump:
            self.gain = want
        else:
            k = 0.35 if want < self.gain else 0.08
            self.gain += (want - self.gain) * k

        out = rms * self.gain
        self.trace.append((rms, self.gain, out))
        return out


def run(noise, speech, silence_frames, **kw):
    """静音 silence_frames 帧（底噪 noise）→ 开口 speech，持续 60 帧。"""
    agc = Agc(**kw)
    outs = []
    for _ in range(silence_frames):
        outs.append(agc.step(noise))
    pre_gain = agc.gain
    for _ in range(60):
        outs.append(agc.step(speech))
    steady = outs[-1]
    peak = max(outs[silence_frames:])
    # 过冲：第一帧相对稳态高出多少 dB
    first = outs[silence_frames]
    # 回落到稳态 3 dB 以内需要几帧
    settle = None
    for i in range(silence_frames, len(outs)):
        if db(outs[i]) <= db(steady) + 3.0:
            settle = i - silence_frames + 1
            break
    return dict(pre_gain=pre_gain, first=first, peak=peak, steady=steady,
                overshoot=db(first) - db(steady), settle=settle)


def report(title, **kw):
    print('\n' + title)
    print('  底噪  静音  开口前增益   首帧输出   稳态输出   过冲     回落')
    for noise in (120.0, 250.0, 500.0):
        for sil in (5, 50, 200):
            r = run(noise, 3000.0, sil, **kw)
            print('  %5.0f  %4d 帧  %5.2f×      %7.0f   %7.0f   %+5.1f dB  %s 帧'
                  % (noise, sil, r['pre_gain'], r['first'], r['steady'],
                     r['overshoot'],
                     ('%d' % r['settle']) if r['settle'] else '未回落'))


def main():
    print('目标 RMS %.0f（%.1f dBFS）· 语音 RMS 3000（%.1f dBFS）'
          % (TARGET, db(TARGET / 32768), db(3000 / 32768)))

    report('=== v2.4.4：门限 100（−50 dBFS）、最大提升 8×（+18 dB）===')
    report('=== 只把门限提到 260（−42 dBFS）===', gate=260.0)
    report('=== 只加瞬态复位（输出超目标 2 倍就复位）===', jump_out=2.0)
    report('=== 修复后：门限 260 + 最大 6× + 瞬态复位 ===',
           gate=260.0, gmax=6.0, jump_out=2.0)

    print('\n结论：')
    print('  · 门限 100 时，底噪只要超过 −50 dBFS，静音期增益就一路顶到 8×，')
    print('    开口首帧输出 = 3000 × 8 = 24000（目标 4200），过冲约 +15 dB；')
    print('  · 单靠提门限只能挡住安静环境，嘈杂环境（底噪 500）照样顶满；')
    print('  · 单靠复位能压住过冲，但静音期仍在白爬增益，白白浪费余量；')
    print('  · 三条一起上，首帧就落在目标附近，回落 1 帧完成。')


if __name__ == '__main__':
    main()
