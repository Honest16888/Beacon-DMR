#!/usr/bin/env python3
"""取 DeepCW 的模型权重（e04/deepcw-engine，MIT）。

模型有 15 MB，不进 git —— 二进制一旦进历史就永远留在那里。这里按 sha256 钉住版本：
上游换了权重（哪怕只是重新导出）会直接失败，而不是拿到一个「能跑但字全错」的模型。

    python tools/fetch_deepcw.py            # model.onnx + model.onnx.json（默认）
    python tools/fetch_deepcw.py --all      # 连 narrow 模型和官方参考实现一起取
    python tools/fetch_deepcw.py --check    # 只校验本地已有文件

落地到 third_party/deepcw/（已在 .gitignore 里）。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, 'third_party', 'deepcw')
BASE = 'https://raw.githubusercontent.com/e04/deepcw-engine/main/'

# 文件名 → (仓库内路径, sha256, 是否默认下载)
FILES = {
    'model.onnx':                 ('model.onnx', True,
        'cd5ee38106348ad86a8f2d40ef1ce7dccea0d7f7fbfd0f2dee0fd1a8a28773ae'),
    'model.onnx.json':            ('model.onnx.json', True,
        '4e7729dcc64c2a208deb28beac4ded55632d11cb70337a9c0ddce523e73bdd9c'),
    'model_narrow.onnx':          ('model_narrow.onnx', False,
        'fc80c4baec72d756af1dbce9b70357a731b0b1a6b8306815bcfc61b6403c46e5'),
    'model_narrow.onnx.json':     ('model_narrow.onnx.json', False,
        '83f0a2e9458c3e71e88652a6d257af4616e9c5a046e1305c32ce97cba9835d59'),
    'examples_python_decode_morse.py': ('examples/python/decode_morse.py', False,
        '89c1658eca4842b1500cfc9c66232f72101cd697c8463c35fd0e842dd5dbc6f9'),
    'examples_python_requirements.txt': ('examples/python/requirements.txt', False,
        '2ec7b315d4ba304cd1f2e0ec9aa26da24b6b36759ed0d5ff7957f152ea031530'),
}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch(local_name: str, remote: str, want: str) -> bool:
    out = os.path.join(DEST, local_name)
    if os.path.exists(out):
        got = sha256(out)
        ok = got == want
        print('%s %-34s %s' % ('OK  ' if ok else 'DIFF', local_name, got[:16] + '…'))
        if ok:
            return True
        print('     sha256 不符，重新下载')
    url = BASE + remote
    print('GET  %-34s ← %s' % (local_name, url))
    tmp = out + '.part'
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, 'wb') as fh:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
    got = sha256(tmp)
    if got != want:
        os.remove(tmp)
        print('FAIL %s sha256 %s ≠ 期望 %s' % (local_name, got, want), file=sys.stderr)
        return False
    os.replace(tmp, out)
    print('OK   %-34s %d B' % (local_name, os.path.getsize(out)))
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description='取 DeepCW 模型权重')
    ap.add_argument('--all', action='store_true', help='连 narrow 模型和官方参考实现一起取')
    ap.add_argument('--check', action='store_true', help='只校验，不下载')
    args = ap.parse_args()

    os.makedirs(DEST, exist_ok=True)
    names = [n for n, (_, core, _) in FILES.items() if args.all or core]

    if args.check:
        bad = 0
        for n in names:
            out = os.path.join(DEST, n)
            want = FILES[n][2]
            if not os.path.exists(out):
                print('MISS %s' % n)
                bad += 1
                continue
            got = sha256(out)
            print('%s %-34s %s' % ('OK  ' if got == want else 'DIFF', n, got[:16] + '…'))
            bad += got != want
        return 1 if bad else 0

    fails = 0
    for n in names:
        remote, _, want = FILES[n]
        if not fetch(n, remote, want):
            fails += 1
    if fails:
        print('\n%d 个文件失败' % fails, file=sys.stderr)
        return 1
    print('\n落地目录：%s' % os.path.abspath(DEST))
    print('下一步：python tests/deepcw/spec_ref.py && python tests/deepcw/poc/make_ref.py')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
