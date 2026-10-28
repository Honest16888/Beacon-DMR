import re, sys, io, os, subprocess, json, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 只认 web/index.html —— 发布流程把工作区的 dmr-listener.html 同步成它。
# 以前这里还有一份 web/dmr-listener.html 的旧副本，测试跑的是三版之前的代码。
P = os.path.join(ROOT, 'web', 'index.html')
html = io.open(P, encoding='utf-8').read()

# ---- 1. 抽出所有 <script> 块 ----
blocks = []
for m in re.finditer(r'<script\b([^>]*)>(.*?)</script>', html, re.S):
    attrs, body = m.group(1), m.group(2)
    blocks.append((attrs.strip(), body, m.start()))

print('script 块数 =', len(blocks))
for i, (a, b, off) in enumerate(blocks):
    print('  [%d] attrs=%r  长度=%d' % (i, a[:70], len(b)))

# ---- 2. 只对最后一个块做 node --check ----
js = blocks[-1][1]
tmp = os.path.join(ROOT, 'tests', 'shot', '_last_script_block.js')
os.makedirs(os.path.dirname(tmp), exist_ok=True)
io.open(tmp, 'w', encoding='utf-8').write(js)
node = os.environ.get('NODE_BIN') or 'node'
r = subprocess.run([node, '--check', tmp], capture_output=True, text=True, encoding='utf-8', errors='replace')
print('\n=== node --check (最后一个 script 块, %d 字节) ===' % len(js))
print('exit =', r.returncode)
if r.stdout.strip(): print('stdout:', r.stdout[:2000])
if r.stderr.strip(): print('stderr:', r.stderr[:2000])

# ---- 3. ID 对账 ----
ids = collections.Counter(re.findall(r'\bid\s*=\s*"([^"]+)"', html))
dups = {k: v for k, v in ids.items() if v > 1}
print('\n=== 重复 id ===')
print('  ', dups if dups else '（无）')

defined = set(ids)
# JS 里所有 getElementById('x') / txEl('x') / q('x')
refs = set(re.findall(r"getElementById\(\s*'([^']+)'", js))
refs |= set(re.findall(r"\btxEl\(\s*'([^']+)'", js))
refs |= set(re.findall(r"\bq\(\s*'([^']+)'", js))
refs |= set(re.findall(r"\$\(\s*'#([A-Za-z0-9_-]+)'", js))
missing = sorted(x for x in refs if x not in defined)
print('\n=== JS 引用但 HTML 里没有的 id ===')
print('  ', missing if missing else '（无，%d 个引用全部命中）' % len(refs))

# ---- 4. CSS 类对账（粗略：脚本/HTML 里用到的 class 是否有规则） ----
css = ''.join(re.findall(r'<style[^>]*>(.*?)</style>', html, re.S))
css_classes = set(re.findall(r'\.([A-Za-z][A-Za-z0-9_-]*)', css))
html_classes = set()
for m in re.finditer(r'class\s*=\s*"([^"]+)"', html):
    for c in m.group(1).split():
        html_classes.add(c)
js_classes = set()
for m in re.finditer(r"classList\.(?:add|toggle|remove)\(\s*'([^']+)'", js):
    js_classes.add(m.group(1))
for m in re.finditer(r"className\s*=\s*'([^']*)'", js):
    for c in m.group(1).split():
        js_classes.add(c.strip("+'\" "))
used = (html_classes | js_classes)
unknown = sorted(c for c in used if c and c not in css_classes)
print('\n=== 用到但 CSS 里没有规则的 class ===')
print('  ', unknown if unknown else '（无）')

# ---- 5. 关键新元素是否都在 ----
must = ['txRxOn','txRxVol','txRxVolVal','txRxJit','txRxJitVal','txBtnRxTest',
        'txFold','txFoldBody','txTg','txPriv','txSlot','txCc','txPtt','txLog','txEssid','txCall',
        'txBtnConn','txBtnDisc','txBtnVoc','txBtnFec','txBtnMic','txBtnCopy','txBtnCopyLog','txBtnClr',
        # 国内 DMR 集成
        'segNet','netInfo','cn86Data','cnTgData','txNetHint','setNetName',
        # 收听页发射条（对讲页已取消）
        'viewListen','ptxBar','txIdState','txTgName','txIdQuick','txHostQuick',
        'pttTg','pttLbl','txHint',
        # 设置页「常见端口与注意事项」
        'setPortCard','setPortBadge','setPortGo','setPortList']
miss2 = [x for x in must if x not in defined]
print('\n=== 关键元素 ===')
print('  ', '缺: ' + str(miss2) if miss2 else '全部存在（%d 个）' % len(must))

# ---- 5b. 国内数据块完整性 ----
print('\n=== 国内数据块 ===')
for bid, min_n, label in [('cn86Data', 1500, '86 号段 ID 库'), ('cnTgData', 33, '国内话组表')]:
    m = re.search(r'<script id="%s"[^>]*>(.*?)</script>' % bid, html, re.S)
    if not m:
        print('  !! 缺少 #%s' % bid); continue
    rows = [x for x in m.group(1).strip().split('\n') if x.strip()]
    flag = '✓' if len(rows) >= min_n else '!!'
    print('  %s %-16s %5d 条（下限 %d）' % (flag, label, len(rows), min_n))
    if bid == 'cn86Data':
        n86 = sum(1 for r in rows if r.startswith('86'))
        print('     其中 86 开头 %d 条，46 开头 %d 条' % (n86, sum(1 for r in rows if r.startswith('46'))))

# ---- 5c. 国内链路函数是否齐全 ----
print('\n=== 国内链路函数 ===')
need_fn = ['setNet', 'rwConnect', 'rwClose', 'rwSendSubs', 'syncRwSubs', 'handleRw',
           'handleRwAudio', 'ensureRw', 'rwLiveStart', 'rwLivePoll', 'ensureLive',
           'lhClose', 'renderLibCN', 'renderNetInfo', 'txNetHint', 'syncTxHostForNet', 'commonIds']
miss3 = [f for f in need_fn if not re.search(r'function\s+%s\s*\(' % f, js)]
print('  ', '缺: ' + str(miss3) if miss3 else '全部存在（%d 个）' % len(need_fn))

# ---- 6. 不能重复的按钮 id ----
for x in ['txBtnConn','txBtnDisc','txTg','txPriv','txSlot','txCc']:
    n = ids.get(x, 0)
    if n != 1:
        print('  !! %s 出现 %d 次' % (x, n))

print('\n总行数 =', html.count('\n') + 1, ' 字节 =', len(html.encode('utf-8')))
