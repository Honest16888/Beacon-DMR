# 离线 ASR 可行性验证：拿真实录音跑一遍中文模型，看「说完就出文字」能出什么
#
#   python tests/asr_probe.py <16k.wav> <模型目录> [--min-sil 0.35]
#
# 做两件事：
#   1. 整段识别一次，给总耗时（判断 RTF）
#   2. 按能量切句（模拟 PTT 松手），每句单独识别并计时 —— 这才是
#      「说完就出文字」的真实延迟：松手之后要等多久才看到字
#
# 模型：sherpa-onnx-paraformer-zh-small（model.int8.onnx + tokens.txt）
import argparse
import os
import sys
import time
import wave

import numpy as np


def read_wav(path):
    with wave.open(path, "rb") as f:
        assert f.getnchannels() == 1, "需要单声道"
        sr = f.getframerate()
        n = f.getnframes()
        raw = f.readframes(n)
    a = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return a, sr


def split_utterances(x, sr, min_sil=0.35, min_len=0.4, thresh_db=-38.0):
    """按能量切句。用 20ms 帧算 RMS，低于阈值的连续段当静音。"""
    hop = int(sr * 0.02)
    nf = len(x) // hop
    rms = np.array([np.sqrt(np.mean(x[i * hop:(i + 1) * hop] ** 2) + 1e-12) for i in range(nf)])
    db = 20 * np.log10(rms + 1e-12)
    voiced = db > thresh_db

    segs, start = [], None
    for i, v in enumerate(voiced):
        if v and start is None:
            start = i
        elif not v and start is not None:
            # 静音要够长才算断句
            j = i
            while j < nf and not voiced[j]:
                j += 1
            if (j - i) * 0.02 >= min_sil:
                segs.append((start * hop, i * hop))
                start = None
            else:
                i = j
    if start is not None:
        segs.append((start * hop, len(x)))
    # 太短的丢掉
    segs = [(a, b) for a, b in segs if (b - a) / sr >= min_len]
    return segs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav")
    ap.add_argument("model_dir")
    ap.add_argument("--min-sil", type=float, default=0.35)
    ap.add_argument("--threads", type=int, default=2)
    args = ap.parse_args()

    import sherpa_onnx

    m = args.model_dir
    model = os.path.join(m, "model.int8.onnx")
    if not os.path.exists(model):
        model = os.path.join(m, "model.onnx")
    tokens = os.path.join(m, "tokens.txt")
    for p in (model, tokens):
        if not os.path.exists(p):
            sys.exit("缺文件: " + p)
    print("模型 %s  %.1f MB" % (os.path.basename(model), os.path.getsize(model) / 1048576))

    x, sr = read_wav(args.wav)
    print("音频 %.2f 秒 @ %d Hz" % (len(x) / sr, sr))

    rec = sherpa_onnx.OfflineRecognizer.from_paraformer(
        paraformer=model, tokens=tokens, num_threads=args.threads,
        sample_rate=sr, feature_dim=80, decoding_method="greedy_search", debug=False,
    )

    # 1. 整段
    t0 = time.time()
    s = rec.create_stream()
    s.accept_waveform(sr, x)
    rec.decode_stream(s)
    full = s.result.text
    el = time.time() - t0
    print("\n=== 整段识别 ===")
    print("耗时 %.2fs  RTF %.3f  (%.1f 秒音频 / %.1f 秒计算)" % (el, el / (len(x) / sr), len(x) / sr, el))
    print("结果：%s" % (full or "(空)"))

    # 2. 按句
    segs = split_utterances(x, sr, args.min_sil)
    print("\n=== 切出 %d 句，逐句识别（模拟松手即转）===" % len(segs))
    total_audio = total_calc = 0.0
    for k, (a, b) in enumerate(segs, 1):
        seg = x[a:b]
        t = time.time()
        st = rec.create_stream()
        st.accept_waveform(sr, seg)
        rec.decode_stream(st)
        dt = time.time() - t
        total_audio += (b - a) / sr
        total_calc += dt
        print("  #%-2d %5.2fs 音频  识别 %5.0f ms   %s"
              % (k, (b - a) / sr, dt * 1000, st.result.text or "(空)"))

    if total_audio > 0:
        print("\n合计 %.1fs 音频 / %.2fs 计算，平均 RTF %.3f（越小越快）"
              % (total_audio, total_calc, total_calc / total_audio))
        print("注意：这是 PC 上 %d 线程的数字，手机 CPU 大概慢 2~4 倍" % args.threads)


if __name__ == "__main__":
    main()
