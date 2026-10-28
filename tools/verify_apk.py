# -*- coding: utf-8 -*-
"""核对 release APK：新类 / 新方法 / 新字符串是否真的进了 dex，assets 是否与源文件一致。

    python tools/verify_apk.py [APK 路径]

不给路径时依次找 dist/beacon-dmr-release.apk、
android/app/build/outputs/apk/release/app-release.apk。
"""
import zipfile, hashlib, io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CANDIDATES = [
    os.path.join(ROOT, 'dist', 'beacon-dmr-release.apk'),
    os.path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
]
APK = sys.argv[1] if len(sys.argv) > 1 else next((p for p in CANDIDATES if os.path.exists(p)), CANDIDATES[0])
# 只认 web/index.html —— 发布流程把工作区的 dmr-listener.html 同步成它。
# （以前这里指 web/dmr-listener.html，那个旧副本已删除，否则「一致」这项永远比错文件。）
SRC = os.path.join(ROOT, 'web', 'index.html')

if not os.path.exists(APK):
    print('APK 不存在:', APK); sys.exit(1)

z = zipfile.ZipFile(APK)
names = z.namelist()
dexes = [n for n in names if re.match(r'classes\d*\.dex$', n)]
print('APK = %.2f MB，dex 文件: %s' % (os.path.getsize(APK) / 1048576.0, dexes))

blob = b''
for d in dexes:
    blob += z.read(d)
print('dex 合计 %d 字节\n' % len(blob))

def has(s):
    return s.encode('utf-8') in blob

print('=== 1. 新增类 ===')
for c in ['Lnet/beacon/dmr/DmrRx;', 'Lnet/beacon/dmr/DmrAudio;',
          'Lnet/beacon/dmr/DmrAudio$Listener;', 'Lnet/beacon/dmr/MmdvmSession$Listener;']:
    print('  %s  %s' % ('OK ' if has(c) else 'MISS', c))

print('\n=== 2. 新增方法 ===')
meths = [
    ('DmrRx.deinterleave', 'deinterleave'),
    ('DmrRx.decode',       'decode'),
    ('DmrRx.selfTest',     'selfTest'),
    ('DmrAudio.feed',      'feed'),
    ('DmrAudio.setJitterMs', 'setJitterMs'),
    ('DmrAudio.start',     'start'),
    ('DmrEngine.setRxOn',  'setRxOn'),
    ('DmrEngine.setRxVolume', 'setRxVolume'),
    ('DmrEngine.setRxJitter', 'setRxJitter'),
    ('DmrEngine.rxSelfTest', 'rxSelfTest'),
    ('MainActivity.dmrSetRxOn', 'dmrSetRxOn'),
    ('MainActivity.dmrSetRxVolume', 'dmrSetRxVolume'),
    ('MainActivity.dmrSetRxJitter', 'dmrSetRxJitter'),
    ('MainActivity.dmrRxSelfTest', 'dmrRxSelfTest'),
    ('MmdvmSession.onDmrd(6参)', 'onDmrd'),
]
for label, s in meths:
    print('  %s  %s' % ('OK ' if has(s) else 'MISS', label))

print('\n=== 3. 原生侧字符串（dex 里是 MUTF-8）===')
# ⚠ 界面文案都在 assets/index.html 里，dex 里查不到 —— 这里只放真正由 Java 输出的那几句。
# （原来这一栏列了一堆 HTML 文案，于是永远 MISS，看着像坏了其实是在查错地方。）
strs = [
    '已连接主站 · 切后台不断',
    '守听中 · 切后台不断',
    '没有录音权限，无法发射',
    '加载失败：',
    '自检失败：',
    '引擎未初始化',
]
for s in strs:
    print('  %s  %s' % ('OK ' if has(s) else 'MISS', s))

print('\n=== 4. 旧文案应已清除 ===')
old = ['发射为实验性功能', '前往发射页', '信标 DMR 发射']
for s in old:
    print('  %s  %s' % ('已清除' if not has(s) else '!! 仍存在', s))

print('\n=== 4b. v2.4.8 原生侧（设置副本）===')
for s in ['prefsAll', 'prefsGet', 'prefsSet', 'dmr-settings']:
    print('  %s  dex 含 %r' % ('OK ' if has(s) else 'MISS', s))

print('\n=== 5. assets/index.html ===')
assets = [n for n in names if n.endswith('index.html')]
print('  APK 内:', assets)
src_h = hashlib.sha256(io.open(SRC, 'rb').read()).hexdigest()
if assets:
    a = z.read(assets[0])
    print('  源文件 sha256 =', src_h)
    print('  APK 内 sha256 =', hashlib.sha256(a).hexdigest())
    print('  一致:', hashlib.sha256(a).hexdigest() == src_h, '  大小:', len(a))
    txt = a.decode('utf-8', 'replace')
    # 挑几个「界面 / 逻辑 / 数据」各自的关键标识，确认打进去的是完整界面而不是空壳
    for k in ['txRxOn', 'txRxJit', 'txFoldBody', 'htFold', 'pttDrag', 'DmrRx', 'cnData']:
        print('  %s  assets 含 %r' % ('OK ' if k in txt else 'MISS', k))
    print('  ---- v2.4.8 ----')
    for k in ['hydrate()', 'setSimple', 'setAutoConn', 'tabFold', 'toggleMuteTg',
              'CALLS_MAX=50', 'LEGAL_VER', 'legalModal']:
        print('  %s  assets 含 %r' % ('OK ' if k in txt else 'MISS', k))
    print('  ---- v2.4.9 ----')
    for k in ['userMuted', 'applyGain()', 'setUserMute', 'nativeRxLive', 'hoseOwnsTg',
              'dropTg', 'state.rxOn&&state.audioOn', 'renderNowTx', 'nativeSeenTg',
              'recTg']:
        print('  %s  assets 含 %r' % ('OK ' if k in txt else 'MISS', k))
    print('  ---- v2.5.0 ----')
    for k in ['recCall', 'heldCall', 'selfRecGuard', 'logged=true']:
        print('  %s  assets 含 %r' % ('OK ' if k in txt else 'MISS', k))
    print('  ---- v2.5.1 ----')
    for k in ['np-row np-r1', 'state.rxDb', 'underAt', '2026.11.21']:
        print('  %s  assets 含 %r' % ('OK ' if k in txt else 'MISS', k))
else:
    print('  !! 没找到 index.html')

print('\n=== 6. native so ===')
for n in names:
    if n.endswith('.so'):
        print('  %-46s %8d' % (n, z.getinfo(n).file_size))

print('\nAPK sha256 =', hashlib.sha256(io.open(APK, 'rb').read()).hexdigest())
