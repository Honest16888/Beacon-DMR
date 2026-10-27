#!/usr/bin/env python3
"""发包节拍仿真：没有节拍控制时，DMRD 包会发得多密？

背景
----
一个 DMRD 包 = 27 字节 = 3 个 AMBE 帧 = **60 ms 音频**。
所以空口上必须严格每 60 ms 一个包，这是 MMDVMHost 的做法（定时器驱动）。

原来的实现是「采集驱动」：每读满一帧（20 ms）编一次码，攒满 3 帧就发。
这等于假设 `AudioRecord.read()` 一定每 20 ms 返回一次。

问题在于 AudioRecord 的 HAL **经常成块交付**：缓冲里攒够几帧就一次性给出来。
一旦缓冲里已经有积压（刚 startRecording、GC 停顿、系统抢占之后都会），
read 会连续几次立即返回 —— 于是几个包在 1~2 ms 内全发出去，然后静默。

空口上这就是「突发 + 静默」。对方听到的就是**断续**，
而网络统计里一个包都没丢，从外部完全看不出来。

这个脚本量化两件事：
  1. 突发能有多密（包间隔最小值）
  2. 加 60 ms 绝对节拍之后能不能压住

模型
----
* 墙上时钟按毫秒推进。
* 麦克风每 20 ms 产出 160 个采样（8 kHz），先落进 AudioRecord 缓冲。
* HAL 的交付策略可配置：
    - 'chunk:480'  每 60 ms 一次性交付 3 帧（最常见的成块交付）
    - 'burst:32'   一次性交付 32 帧（640 ms，模拟启动/卡顿后缓冲已满）
    - 'stream'     每 20 ms 交付 1 帧（理想情况）
* 消费者读一帧 → DSP+编码耗时 proc_ms → 攒 3 帧发一包。
* 缓冲有上限，超出就覆盖最旧的（这就是「丢样本」，AudioRecord 的真实行为）。

用法：python tests/tx_pacing_sim.py
"""

import statistics

FS = 8000
FRAME = 160            # 20 ms
PACKET_FRAMES = 3      # 27 字节 = 3 帧 = 60 ms
PKT_PERIOD = 60.0      # ms
BUF_FRAMES = 32        # 采集缓冲 = 32 帧 = 640 ms
PROC_MS = 2.5          # 每帧 DSP + 声码器编码耗时（实测量级）
DURATION_MS = 3000


class Mic:
    """模拟 AudioRecord：实时产出 + 有限缓冲 + 成块交付。"""

    def __init__(self, mode):
        self.mode = mode
        self.buf = []              # 已采集、尚未被读走的帧
        self.produced = 0          # 累计产出的帧数
        self.dropped = 0           # 因缓冲溢出被覆盖的帧数
        self.next_publish = 0.0    # 下一次交付的时刻
        self.pending = 0           # 已采集但 HAL 还没放出来的帧
        if mode.startswith('burst:'):
            # 启动瞬间缓冲里已经有这么多帧（startRecording / 卡顿之后）
            n = int(mode.split(':')[1])
            self.buf = list(range(n))
            self.produced = n
            self.next_publish = 1e9   # 之后按实时交付

    def tick(self, t):
        """时间推进到 t：产出的帧进 pending，按策略放出到 buf。"""
        target = int(t / 20.0)
        while self.produced < target:
            self.produced += 1
            if self.mode == 'stream' or self.mode.startswith('burst:'):
                # 逐帧交付；burst 模式只是初始时缓冲里已经攒了一批
                self.buf.append(self.produced)
            else:
                self.pending += 1
        if self.mode.startswith('chunk:'):
            chunk = int(self.mode.split(':')[1]) // FRAME
            if t >= self.next_publish:
                for _ in range(min(chunk, self.pending)):
                    self.buf.append(0)
                    self.pending -= 1
                self.next_publish += chunk * 20.0
        # 缓冲溢出：AudioRecord 覆盖最旧的
        while len(self.buf) > BUF_FRAMES:
            self.buf.pop(0)
            self.dropped += 1

    def avail(self):
        return len(self.buf)

    def take(self, n):
        out = self.buf[:n]
        del self.buf[:n]
        return out


def run(mode, pace):
    """跑一次发射，返回 (包间隔列表, 丢帧数, 包数)。"""
    mic = Mic(mode)
    t = 0.0
    bundle = 0
    gaps = []
    last_pkt = None
    next_pkt_at = None
    pkts = 0
    early = 0

    while t < DURATION_MS:
        mic.tick(t)
        # 读满一帧（阻塞直到有数据）
        while mic.avail() < 1:
            t += 0.5
            mic.tick(t)
            if t >= DURATION_MS:
                break
        if t >= DURATION_MS:
            break
        mic.take(1)
        t += PROC_MS

        bundle += 1
        if bundle < PACKET_FRAMES:
            continue
        bundle = 0

        if pace:
            if next_pkt_at is None:
                next_pkt_at = t
            early_ns = next_pkt_at - t
            if early_ns > 0:
                early += 1
                if early_ns < PKT_PERIOD:
                    t = next_pkt_at
                    mic.tick(t)
                next_pkt_at += PKT_PERIOD
            elif early_ns < -2 * PKT_PERIOD:
                next_pkt_at = t + PKT_PERIOD
            else:
                next_pkt_at += PKT_PERIOD

        if last_pkt is not None:
            gaps.append(t - last_pkt)
        last_pkt = t
        pkts += 1

    return gaps, mic.dropped, pkts


def show(title, mode):
    print(f'\n=== {title} ===')
    print(f'{"":26} {"最小间隔":>9} {"中位":>8} {"最大":>8} {"<50ms 的包":>11} {"丢帧":>6} {"包数":>5}')
    for pace, label in ((False, '原实现（采集驱动）'), (True, 'v2.3（60 ms 节拍）')):
        gaps, dropped, pkts = run(mode, pace)
        if not gaps:
            print(f'  {label:24} 无数据')
            continue
        too_fast = sum(1 for g in gaps if g < 50)
        print(f'  {label:24} {min(gaps):8.1f}ms {statistics.median(gaps):7.1f}ms '
              f'{max(gaps):7.1f}ms {too_fast:6d}/{len(gaps):<4d} {dropped:6d} {pkts:5d}')


def main():
    print('发包节拍仿真  ——  DMRD 包应严格每 60 ms 一个（= 3 帧 × 20 ms 音频）')
    print(f'采集 {FS} Hz · 缓冲 {BUF_FRAMES} 帧 = {BUF_FRAMES*20} ms · '
          f'每帧处理 {PROC_MS} ms · 发射 {DURATION_MS/1000:.0f} 秒')

    show('HAL 成块交付：每 60 ms 一次给 3 帧', 'chunk:480')
    show('启动/卡顿后：缓冲里已有 32 帧（640 ms）', 'burst:32')
    show('理想情况：每 20 ms 给 1 帧', 'stream')

    print('\n读法：')
    print('  「最小间隔」远小于 60 ms = 包挤在一起发出去 → 空口突发 + 静默')
    print('  「<50ms 的包」越多，对方听到的断续越明显')
    print('  理想情况（stream）两者应该差不多 —— 说明节拍控制只在')
    print('  HAL 成块交付 / 缓冲有积压时才起作用，其余时候是零代价的。')

    # 结论判定
    g_old, _, _ = run('burst:32', False)
    g_new, _, _ = run('burst:32', True)
    print('\n---- 结论 ----')
    if g_old and min(g_old) < 10:
        print(f'  ✗ 原实现：缓冲有积压时，最小包间隔 {min(g_old):.1f} ms'
              f'（应为 60）→ 突发发送')
    if g_new and min(g_new) >= 50:
        print(f'  ✓ v2.3：同一场景下最小包间隔 {min(g_new):.1f} ms，节拍被压住了')
    if g_new and min(g_new) < 50:
        print(f'  ⚠ v2.3：仍有 {min(g_new):.1f} ms 的间隔，节拍没完全压住，需要复查')


if __name__ == '__main__':
    main()
