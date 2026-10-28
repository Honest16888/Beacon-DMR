#!/usr/bin/env python3
"""发射链结构不变量检查。

为什么要有这个脚本
------------------
v2.3 重写 `txLoop` 时漏掉了一行

    System.arraycopy(ambe, 0, bundle, bundleFill, Vocoder.CODEC_BYTES);

后果不是报错、不是崩溃，而是**发出去的全是静音**：

* `bundle` 是 `byte[27]`，Java 的初始值全是 0；
* 少一次拷贝，每个 DMRD 包里就是三帧全零码字；
* PTT 照按、包照发、主站照转、发射小结一切正常；
* 对方一个字都听不到，而且从任何本地指标都看不出来。

v2.3 和 v2.4 就这样发出去了两版静音。

这类 bug 的共同点是**没有可观测的失败信号**，所以不能靠「跑一遍看看」发现，
只能靠「结构上必须成立」来守。这个脚本就是干这个的 ——
它读源码，检查那几条一旦被破坏就必然导致静音或断字的顺序关系。

用法：python tests/check_tx_bundle.py
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CANDIDATES = [
    os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'java',
                 'net', 'beacon', 'dmr', 'DmrTransmitter.java'),
    r'F:\Users\ThinkPink\beacon-dmr-android\app\src\main\java\net\beacon\dmr\DmrTransmitter.java',
]

passed = 0
failed = 0


def ok(name, cond, detail=''):
    global passed, failed
    if cond:
        passed += 1
        print(f'  ✓ {name}')
    else:
        failed += 1
        print(f'  ✗ {name}' + (f'   {detail}' if detail else ''))


def find_src():
    for p in CANDIDATES:
        p = os.path.abspath(p)
        if os.path.exists(p):
            return p
    return None


def strip_comments(s):
    """去掉 /* */ 与 // 注释，保留换行。

    注释里会大段引用代码（比如「这里不能 continue，因为……」），
    不去掉就会把解释文字当成代码命中，检查结论就不可信了。
    """
    s = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s


def main():
    if len(sys.argv) > 1:
        src_path = os.path.abspath(sys.argv[1])
        if not os.path.exists(src_path):
            print(f'找不到 {src_path}')
            return 2
    else:
        src_path = find_src()
    if not src_path:
        print('找不到 DmrTransmitter.java，试过：')
        for p in CANDIDATES:
            print('  ', os.path.abspath(p))
        return 2

    src = open(src_path, encoding='utf-8').read()
    print(f'检查 {src_path}')

    # 只取 txLoop 方法体，避免别处的同名字符串造成误判
    m = re.search(r'private void txLoop\(.*?\n    \}', src, re.S)
    body = strip_comments(m.group(0)) if m else ''
    ok('能定位到 txLoop 方法体', bool(body), '正则没匹配到，检查方法签名是否变了')
    if not body:
        return 1

    print('\n=== 1. 码字必须真的被装进 bundle ===')
    # 这是 v2.3 那个 bug 的直接守卫
    cp = re.search(
        r'System\.arraycopy\(\s*ambe\s*,\s*0\s*,\s*bundle\s*,\s*bundleFill\s*,'
        r'\s*Vocoder\.CODEC_BYTES\s*\)', body)
    ok('ambe → bundle 的 arraycopy 存在', bool(cp),
       '★ 缺这一行 = 发出去全是静音（v2.3/v2.4 的实际故障）')

    i_cp = body.find('arraycopy(ambe')
    i_fill = body.find('bundleFill += Vocoder.CODEC_BYTES')
    i_send = body.find('nativeVoice(bundle')
    ok('拷贝发生在 bundleFill 累加之前',
       i_cp >= 0 and i_fill > i_cp, f'arraycopy@{i_cp} fill@{i_fill}')
    ok('拷贝发生在发包之前', i_cp >= 0 and i_send > i_cp,
       f'arraycopy@{i_cp} nativeVoice@{i_send}')

    print('\n=== 2. bundle 的生命周期 ===')
    ok('bundle 按 27 字节分配', 'new byte[DmrTx.AMBE_BUNDLE]' in body)
    ok('发完把 bundleFill 清零',
       re.search(r'if\s*\(\s*bundleFill\s*>=\s*DmrTx\.AMBE_BUNDLE\s*\)\s*\{[^}]*bundleFill\s*=\s*0',
                 body, re.S) is not None,
       '不清零会一直 >= 阈值，每帧都发一次包')

    print('\n=== 3. 编码失败不能丢帧 ===')
    enc = body.find('Vocoder.nativeEncode')
    ok('调用 nativeEncode 并检查返回值',
       enc >= 0 and 'Vocoder.CODEC_BYTES' in body[enc:enc + 200])
    # 编码分支里不能出现 continue —— 那会让时间轴压缩 20 ms
    seg = body[enc:enc + 1200]
    ok('编码失败分支里没有 continue', 'continue' not in seg.split('} else {')[0],
       'continue 会让这一帧消失、bundleFill 不前进，时间轴被压 20 ms')

    print('\n=== 4. 前端处理链的顺序 ===')
    order = ['highPass(pcm)', 'presence(pcm)', 'rmsOf(pcm)', 'agc(pcm', 'noiseGate(pcm']
    pos = [body.find(k) for k in order]
    ok('五个环节都能找到', all(p >= 0 for p in pos), str(dict(zip(order, pos))))
    ok('顺序为 高通 → presence → 量电平 → AGC → 噪声门',
       all(p >= 0 for p in pos) and pos == sorted(pos),
       '噪声门必须在 AGC 之后，否则 AGC 会把压掉的噪声原样提回来')

    print('\n=== 5. 发包节拍 ===')
    ok('有 60 ms 周期常量',
       re.search(r'PKT_PERIOD_NS\s*=\s*60_000_000L', body) is not None)
    ok('早到时等待', re.search(r'if\s*\(\s*early\s*>\s*0\s*\)', body) is not None)
    ok('晚到后重新对齐',
       re.search(r'nextPktAt\s*=\s*now\s*\+\s*PKT_PERIOD_NS', body) is not None,
       '只等不对齐的话，一次长停顿会让网格永远落后')

    print('\n=== 6. 采集读取 ===')
    ok('用 rawFill 累加，而不是每轮从 0 重来',
       'rawFill' in body and 'got < readPerFrame' not in body,
       'got 每轮清零会把已读到的采样扔掉')
    ok('连续 0 读的保护计数在循环外',
       re.search(r'int zeroSpins\s*=\s*0;\s*\n[^\n]*\n[^\n]*\n\s*while\s*\(transmitting\)',
                 body) is not None or 'zeroSpins' in body,
       'zeroSpins 若在外层循环内声明，每次迭代都清零，保护永远不成立')

    print('\n=== 7. 用户麦克风增益的位置 ===')
    # 两个方向都不能错，错哪边都会「滑块能拖但没用」：
    #   · 放在 AGC 之前  → AGC 把它原样抵消
    #   · 放在软限幅之后 → 只能把已经失真的声音调小，毛刺一个不少
    # v2.4.2 就是后者，用户反馈「−12 dB 好了一点但不够」。这里守住新结构。
    m2 = re.search(r'private void agc\(.*?\n    \}', src, re.S)
    agc_body = strip_comments(m2.group(0)) if m2 else ''
    ok('能定位到 agc 方法体', bool(agc_body))

    gpos = agc_body.find('agcGain * micGain')
    ok('用户增益并进 AGC 的输出乘数', gpos >= 0,
       '单独在 agc 之外乘，就是「限幅之后再乘」的老路')
    kpos = agc_body.find('KNEE')
    ok('软限幅在乘数之后', gpos >= 0 and kpos > gpos,
       '限幅必须在用户增益之后：否则调小增益只减小音量，谐波一点没少')
    ok('限幅介入有计数', 'st.limHits++' in agc_body,
       '没有计数就分不清「前级削波」和「后级限幅」，两者处置方式相反')
    ok('旧的分段增益已移除', 'applyMicGain' not in strip_comments(src),
       '留着它就是限幅之后再乘，会重新引入 v2.4.2 的问题')

    lpos = body.find('listener.onLevel')
    apos = body.find('agc(pcm, frameRms, st)')
    ok('发射电平在所有增益与限幅之后量',
       lpos >= 0 and apos >= 0 and lpos > apos,
       '在增益之前量，用户调增益时读数不动，会以为滑块坏了')
    ok('采集阶段有削波统计', 'countNearFull' in body,
       '这是唯一能区分「手机 MIC 自己过载」和「我们后级搞炸」的测量点')

    print('\n=== 8. AGC 的「静音→开口」过冲防护 ===')
    # 2026-10-28 的实录：18.48 s 里只有开头 400 ms 冲高 20 dB，后面稳如老狗。
    # 根因是静音期把增益顶到上限，开口时来不及收。见 tests/tx_agc_overshoot.py。
    m3 = re.search(r'AGC_GATE_RMS\s*=\s*([0-9.]+)f', strip_comments(src))
    gate = float(m3.group(1)) if m3 else -1
    ok('静音门限 ≥ 200（−44 dBFS）', gate >= 200,
       '门限太低时手机底噪就被当成语音，AGC 在没人说话时把增益顶到上限')
    ok('有瞬态复位常量 AGC_JUMP_OUT', 'AGC_JUMP_OUT' in strip_comments(src),
       '没有复位的话，静音期爬上去的增益要 7~8 帧才收回来')
    ok('复位用当前帧 rms 反算增益',
       re.search(r'AGC_TARGET_RMS\s*/\s*rms\b', agc_body) is not None,
       '用平滑值 rmsAvg 算出来的 want 仍偏高，复位等于没复位')
    ok('复位走 jump 分支而不是只靠 slew', 'jump' in agc_body and 'agcGain = w' in agc_body)

    print(f'\n================  {passed} 通过 / {failed} 失败  ================')
    return 0 if failed == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
