#!/usr/bin/env python3
"""DeepCW 模型推理的**参考结果**：用 Python onnxruntime 跑金标准频谱。

产物（写成裸 f32，浏览器里直接读，不用再解析 onnx）：
    ref_logits.f32   模型输出 log_probs，形状 [1, T, 42]，行优先
    ref.json         形状 / 贪心解码文本 / 输入尺寸 / 模型哈希

用法：
    python tests/deepcw/poc/make_ref.py            # 默认读 tests/out/deepcw/spec.f32
    python tests/deepcw/poc/make_ref.py --spec X --out Y
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import numpy as np
import onnxruntime as ort

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
MODEL = os.path.join(ROOT, 'third_party', 'deepcw', 'model.onnx')
META = os.path.join(ROOT, 'third_party', 'deepcw', 'model.onnx.json')
SPEC = os.path.join(ROOT, 'tests', 'out', 'deepcw', 'spec.f32')
OUT = os.path.join(ROOT, 'tests', 'out', 'deepcw')


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def greedy(logits: np.ndarray, chars: list[str], blank: int) -> str:
    """CTC 贪心：逐帧 argmax → 合并相邻重复 → 去掉 blank。
    和官方 examples/python/decode_morse.py 的 collapse 逻辑一致（这里不涉及重叠窗加权）。"""
    idx = logits.argmax(axis=-1)
    out, prev = [], -1
    for i in idx:
        if i != prev and i != blank:
            out.append(chars[i])
        prev = i
    return ''.join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description='DeepCW 参考推理（Python onnxruntime）')
    ap.add_argument('--model', default=MODEL)
    ap.add_argument('--meta', default=META)
    ap.add_argument('--spec', default=SPEC)
    ap.add_argument('--out', default=OUT)
    args = ap.parse_args()

    for p in (args.model, args.meta, args.spec):
        if not os.path.exists(p):
            print('缺少 %s（模型用 tools/fetch_deepcw.py 取）' % p, file=sys.stderr)
            return 2

    meta = json.load(open(args.meta, encoding='utf-8'))
    frames = os.path.getsize(args.spec) // (4 * meta['spectrogram_frequency_bins'])
    spec = np.fromfile(args.spec, dtype=np.float32).reshape(frames, meta['spectrogram_frequency_bins'])
    x = spec[None, None, :, :]          # [batch, channel, time, frequency]
    assert x.shape[2] == frames

    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    sess = ort.InferenceSession(args.model, so, providers=['CPUExecutionProvider'])
    in_name = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[0].name
    logits = sess.run([out_name], {in_name: x.astype(np.float32)})[0]

    os.makedirs(args.out, exist_ok=True)
    logits.astype(np.float32).tofile(os.path.join(args.out, 'ref_logits.f32'))
    text = greedy(logits[0], meta['chars'], meta['blank_index'])

    info = {
        'model': os.path.basename(args.model),
        'model_sha256': sha256(args.model),
        'input_shape': list(x.shape),
        'output_shape': list(logits.shape),
        'logits_file': 'ref_logits.f32',
        'logits_sha256': hashlib.sha256(logits.astype(np.float32).tobytes()).hexdigest(),
        'logit_min': float(logits.min()),
        'logit_max': float(logits.max()),
        'greedy_text': text,
        'spec_frames': int(frames),
        'orts': ort.__version__,
    }
    with open(os.path.join(args.out, 'ref.json'), 'w', encoding='utf-8') as fh:
        json.dump(info, fh, indent=2, ensure_ascii=False)

    print('model   : %s (%d B, sha256 %s…)' % (info['model'], os.path.getsize(args.model),
                                              info['model_sha256'][:12]))
    print('input   : %s  (%.1f s @3.2k)' % (x.shape, frames / 66.6667))
    print('output  : %s  [1, T, %d]' % (logits.shape, meta['num_classes']))
    print('logits  : min %.4f  max %.4f' % (info['logit_min'], info['logit_max']))
    print('greedy  : %r' % text)
    print('out     : %s' % os.path.abspath(args.out))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
