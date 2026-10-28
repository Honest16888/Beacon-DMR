#!/usr/bin/env python3
"""v2.4.8 结构不变量检查。

为什么要有这个脚本
------------------
v2.4.8 这一批改的全是「不会崩、但会悄悄失效」的东西：

* **心跳超时判据的位置** —— 判据放在 send 之后，第 12 个 ping 刚发出去
  就被算成「没回」，等于凭空少容忍一个周期；放在 `send` 之前才对。
* **设置的第二份副本** —— 页面写 localStorage 的同时要写原生
  SharedPreferences，而且启动时必须**先整表回填**再读任何设置，
  顺序反了就等于没做。
* **通联记录的时间上限** —— 只有条数上限解决不了「打开记录全是昨天的」，
  必须同时有 24 小时的裁剪，而且启动时要裁一次。
* **话组静音的优先级** —— 静音判断必须排在「独听」之前，
  否则独听选中的组即使被静音也照样播。

这些都是「看起来写对了、跑起来也不报错」的错，只能靠结构检查守住。

用法：python tests/check_248.py
"""

import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..'))
JAVA_REPO = os.path.join(REPO, 'android', 'app', 'src', 'main', 'java', 'net', 'beacon', 'dmr')
JAVA_LIVE = r'F:\Users\ThinkPink\beacon-dmr-android\app\src\main\java\net\beacon\dmr'

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


def find_java(name):
    for d in (JAVA_REPO, JAVA_LIVE):
        p = os.path.join(d, name)
        if os.path.exists(p):
            return p
    return None


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


HTML = os.path.join(REPO, 'web', 'index.html')

# ============================================================
print('=== 1. 心跳超时判据（MmdvmSession.java）===')
mp = find_java('MmdvmSession.java')
ok('能定位到 MmdvmSession.java', mp is not None)
if mp:
    ms = strip_comments(read(mp))
    m = re.search(r'PONG_MISS_LIMIT\s*=\s*(\d+)', ms)
    limit = int(m.group(1)) if m else -1
    ok('PONG_MISS_LIMIT 提到 ≥ 10（原来 5 = 25 秒太急）', limit >= 10, f'实际 {limit}')
    ok('死常量 PONG_TIMEOUT_MS 已删除', 'PONG_TIMEOUT_MS' not in ms)
    # 判据必须在 send(ping) 之前
    i_loop = ms.find('private void pingLoop')
    if i_loop < 0:
        i_loop = ms.find('pingLoop')
    body = ms[i_loop:i_loop + 4000] if i_loop >= 0 else ''
    i_judge = body.find('pingsSincePong >= PONG_MISS_LIMIT')
    i_send = body.find('send(ping)')
    ok('存活判据在 pingLoop 里', i_judge > 0)
    ok('判据排在 send(ping) 之前（发之前判，不是发完就判）',
       0 <= i_judge < i_send if i_send > 0 else False,
       f'judge@{i_judge} send@{i_send}')
    ok('收到 DMRD 也清掉漏 pong 的账', 'pingsSincePong = 0' in ms)

# ============================================================
print('\n=== 2. 设置的原生副本（MainActivity.java）===')
ap = find_java('MainActivity.java')
ok('能定位到 MainActivity.java', ap is not None)
if ap:
    asrc = strip_comments(read(ap))
    ok('有 SharedPreferences 文件名常量', 'PREF_FILE' in asrc and 'dmr-settings' in asrc)
    ok('有 prefsAll()', 'public String prefsAll()' in asrc)
    ok('有 prefsGet()', 'public String prefsGet(String key)' in asrc)
    ok('有 prefsSet()', 'public boolean prefsSet(String key, String val)' in asrc)
    for fn in ('prefsAll', 'prefsGet', 'prefsSet'):
        i = asrc.find('public ' + ('String ' if fn != 'prefsSet' else 'boolean ') + fn)
        seg = asrc[max(0, i - 260):i] if i > 0 else ''
        ok(f'{fn} 带 @JavascriptInterface', '@JavascriptInterface' in seg)
    ok('写入用 commit() 而不是 apply()（进程被杀也不丢）',
       'ed.commit()' in asrc and '.apply()' not in asrc)
    ok('返回键先关条款弹窗', 'legalModal' in read(ap))

# ============================================================
print('\n=== 3. 页面侧：存储副本与回填顺序 ===')
ok('能读到 web/index.html', os.path.exists(HTML))
html = read(HTML)
ok('store 有 hydrate()', 'hydrate()' in html)
ok('store 有 putRaw()（导入时两边都写）', 'putRaw(obj)' in html)
ok('store 有 clearAll()（恢复默认时副本一起清）', 'clearAll()' in html)
# boot 里 hydrate 必须在第一个 store.get 之前
i_boot = html.find('function boot(')
i_hyd = html.find('store.hydrate()', i_boot)
i_first_get = html.find('store.get(', i_boot)
ok('boot 里第一件事就是 hydrate',
   0 < i_hyd < i_first_get if i_first_get > 0 else False,
   f'hydrate@{i_hyd} firstGet@{i_first_get}')
ok('hydrate 只回填 localStorage 里缺的键（不覆盖用户刚改的值）',
   'cur==null' in html)
ok('导入走 putRaw（否则下次副本把旧值灌回来）', 'store.putRaw(keys)' in html)
ok('恢复默认走 clearAll', 'store.clearAll()' in html)

# ============================================================
print('\n=== 4. 通联记录：50 条 + 24 小时 ===')
m = re.search(r'CALLS_MAX\s*=\s*(\d+)', html)
ok('CALLS_MAX 是 50', m is not None and int(m.group(1)) == 50,
   m.group(1) if m else '缺')
ok('有 24 小时的 TTL 常量',
   re.search(r'CALLS_TTL_MS\s*=\s*24\s*\*\s*3600\s*\*\s*1000', html) is not None)
ok('旧的 120 条硬上限已移除', 'state.calls.length>120' not in html)
ok('写入时裁剪（logCall 里调 callsPrune）',
   'state.calls.unshift(' in html and
   html.find('callsPrune()', html.find('function logCall(')) > 0)
ok('启动时也裁一遍', html.find('callsPrune()', i_boot) > 0)

# ============================================================
print('\n=== 5. 简洁模式 ===')
ok('有 state.simple 字段', 'simple:false' in html)
ok('body.simple 隐藏看板入口', 'body.simple .viewtabs button[data-view="dash"]' in html)
ok('body.simple 隐藏实时活动栏', 'body.simple .col-mid' in html)
ok('有 applySimple()', 'function applySimple(' in html)
# ★ v2.4.9：操作区跟滚动彻底脱钩 —— 既不自动展开也不自动收起。
# v2.4.8 断言的是「简洁模式下滚回顶部不再自动展开」，v2.4.9 把整段
# scroll 监听删掉了（用户明确否掉两个方向的自动行为），所以改成
# 断言「没有任何滚动驱动的折叠调用」。
ok('没有任何滚动驱动的自动折叠/展开',
   'htFoldSet(false,true)' not in html
   and 'htFoldSet(true,false)' not in html
   and 'htFoldToggle' not in html[html.find("window.addEventListener('scroll'"):][:400]
   if "window.addEventListener('scroll'" in html else True)
ok('简洁模式仍然默认收起操作区',
   'if(state.simple)htFoldSet(true,true)' in html)
ok('设置页有简洁模式开关', 'id="setSimple"' in html)

# ============================================================
print('\n=== 6. 面板折叠 / 顶栏页签重排 ===')
ok('扫描录音面板有折叠钮', 'id="tabFold"' in html)
ok('有 tabFoldSet()', 'function tabFoldSet(' in html)
ok('折叠状态存本地', "store.set('tabFolded'" in html)
ok('三个页签是等宽分段控件（flex:1 + min-width）',
   re.search(r'\.viewtabs button\{[^}]*flex:1 1 0', html) is not None)
ok('页签文字与图标之间不留字面空格（靠 gap）',
   '<span class="vico">🎧</span>主页' in html)

# ============================================================
print('\n=== 7. 话组静音 + 长按拖动排序 ===')
ok('有 toggleMuteTg()', 'function toggleMuteTg(' in html)
ok('我的收听行里有静音按钮', 'data-act="mute"' in html)
i_aa = html.find('function audioAllowed(')
seg = html[i_aa:i_aa + 700] if i_aa > 0 else ''
i_mute = seg.find('rxMuteTg.indexOf(tg)')
i_solo = seg.find('state.solo!=null')
ok('静音判断排在独听之前', 0 <= i_mute < i_solo if i_solo > 0 else False,
   f'mute@{i_mute} solo@{i_solo}')
ok('静音的话组不抢收听坑位（CALL_START 提前返回）',
   'const muted=!!(state.rxMuteTg' in html)
ok('国内链路也做了同样的静音拦截', 'rxMuteTg.indexOf(tg)>=0' in html)
ok('长按 420ms 才进入拖动', re.search(r'},\s*420\)', html) is not None)
ok('有 dgStart / dgMoveTo / dgDrop',
   all(f'function {n}(' in html for n in ('dgStart', 'dgMoveTo', 'dgDrop')))
ok('拖动后写回 state.subscribed', 'state.subscribed=order' in html)
ok('长按后的 click 被拦掉（不会顺手设成发射目标）', 'DG.lastDrop<400' in html)
# ★ v2.4.9 拖动途中不许重绘：重绘会整体重写 innerHTML，正在拖的那一行脱落，
# 手势静默作废（用户划过去了，松手发现顺序没变）。
i_rm = html.find('function renderMine(')
seg_rm = html[i_rm:i_rm + 500] if i_rm > 0 else ''
ok('renderMine 在拖动途中让路', 'if(DG.on){ DG.pending=true; return; }' in seg_rm)
i_dg = html.find('function dgMoveTo(')
seg_dg = html[i_dg:i_dg + 500] if i_dg > 0 else ''
ok('dgMoveTo 能在行脱落时按 data-id 找回', 'row.dataset.id' in seg_dg)
# DG 必须先于 renderMine 声明（const 有 TDZ），而且只能有一处
ok('DG 只声明一次', html.count('const DG={') == 1)
ok('DG 声明在 renderMine 之前',
   0 < html.find('const DG={') < html.find('function renderMine('))

print('\n=== 7b. 静音真的挡得住声音（HOSE 音频归属）===')
# 第一版把静音写成「CALL_START 直接 return、不切 current」，
# 于是静音组的音频被当成「上一组说的」照常播出来 —— 静音失效。
# ★ v2.4.9：静音 / 自己回传 / 让位给原生，三种「不播」合并成一个 dropTg，
# 在 CALL_START 一次判定，整句从第一帧丢到 CALL_END。
ok('RXG 有 attrTg（音频归属覆盖）', 'attrTg:0' in html)
ok('RXG 有 dropTg / dropAt（整句丢弃标记）',
   'dropTg:0' in html and 'dropAt:0' in html)
i_start = html.find('function handleSpotter(')
i_mute = html.find('const muted=!!(state.rxMuteTg', i_start)
ok('静音判定在 CALL_START 里', i_mute > 0)
seg = html[i_mute:i_mute + 900] if i_mute > 0 else ''
ok('静音分支会立起 attrTg', 'RXG.attrTg=nTg' in seg, seg[:120])
ok('静音分支会立起 dropTg', 'RXG.dropTg=nTg' in seg)
ok('自己的回传也走同一条丢弃路径', 'const self=!!(selfId' in seg)
ok('让位给原生也走同一条丢弃路径', 'const yieldToNative=!hoseOwnsTg(nTg)' in seg)
i_audio = html.find('type===T.CALL_AUDIO', i_start)
seg2 = html[i_audio:i_audio + 1400] if i_audio > 0 else ''
ok('CALL_AUDIO 用 attrTg 判归属', 'RXG.attrTg||' in seg2 or 'RXG.attrTg ||' in seg2)
ok('CALL_AUDIO 先判 dropTg 整句丢弃', 'atg===RXG.dropTg' in seg2)
ok('归属未知时维持老行为（atg 为 0 不拦）', 'if(atg&&!audioAllowed(atg))' in seg2)
i_end = html.find('type===T.CALL_END', i_start)
seg3 = html[i_end:i_end + 800] if i_end > 0 else ''
ok('CALL_END 会撤掉 attrTg', 'RXG.attrTg===endTg' in seg3)
i_ho = html.find('function rxHandoff(')
seg4 = html[i_ho:i_ho + 900] if i_ho > 0 else ''
ok('rxHandoff 不把坑位交给静音组', 'rxMuteTg' in seg4)
ok('rxReset 一并清掉 attrTg', 'RXG.attrTg=0' in html)

print('\n=== 7c. 静音第一层：不订阅 ===')
i_eff = html.find('function effSubs(')
seg_eff = html[i_eff:i_eff + 900] if i_eff > 0 else ''
ok('effSubs 把静音话组过滤掉', 'rxMuteTg' in seg_eff and 'filter(' in seg_eff, seg_eff[:120])
i_add = html.find('function rxMuteAdd(')
seg_add = html[i_add:i_add + 1600] if i_add > 0 else ''
ok('静音后重新订阅（syncSubs）', 'syncSubs()' in seg_add)
ok('静音后重连判定也跟着走（ensureSpotter）', 'ensureSpotter()' in seg_add)
i_del = html.find('function rxMuteDel(')
seg_del = html[i_del:i_del + 1200] if i_del > 0 else ''
ok('取消静音也重新订阅', 'syncSubs()' in seg_del)
ok('取消静音会撤掉 attrTg', 'RXG.attrTg===tg' in seg_del)

# ============================================================
print('\n=== 8. 条款与风险提示 ===')
for k in ('terms', 'privacy', 'risk'):
    ok(f'有 {k} 条款正文', f'{k}:{{' in html.replace(' ', ''))
ok('页脚有三个入口', html.count('data-legal=') >= 3)
ok('有条款版本号', 'LEGAL_VER' in html)
ok('有 openLegal / closeLegal', 'function openLegal(' in html and 'function closeLegal(' in html)
ok('弹窗复用 .modal 样式', 'id="legalModal"' in html and 'class="modal legal"' in html)
ok('风险提示里写了「没有执照不得发射」', '操作证书' in html and '执照' in html)
ok('隐私政策里写了「没有自己的服务器」', '没有自己的服务器' in html)

# ============================================================
print('\n=== 9. 启动自动登录主站 ===')
ok('有 state.autoConn', 'autoConn:true' in html)
ok('有 txAutoConnect()', 'function txAutoConnect(' in html)
i_ac = html.find('function txAutoConnect(')
seg = html[i_ac:i_ac + 1400] if i_ac > 0 else ''
ok('浏览器里不自动连', 'if(!ANDROID' in seg)
ok('被主站连续拒过不自动连', 'TX.blocked' in seg)
ok('参数不全不自动连', 'id<1000' in seg)
ok('boot 里会调用它', html.find('txAutoConnect,', i_boot) > 0)
ok('设置页有自动登录开关', 'id="setAutoConn"' in html)

# ============================================================
print('\n=== 10. 版本号 ===')
ok("PAGE_VER 是 2026.11.19", "PAGE_VER='2026.11.19'" in html)
gp = os.path.join(REPO, 'android', 'app', 'build.gradle')
if os.path.exists(gp):
    g = read(gp)
    ok('build.gradle versionName = 2.4.10', 'versionName "2.4.10"' in g)
    ok('build.gradle versionCode = 25', re.search(r'versionCode\s+25', g) is not None)
else:
    ok('能读到 android/app/build.gradle', False)

# ============================================================
print('\n=== 11. v2.4.9 收听/静音 + 单路归属 ===')
# 静音必须同时掐两条路：Hoseline(WebAudio) 和原生 MMDVM 接收(AudioTrack)。
# 只掐前者的话，MMDVM 连着时点了静音照样出声 —— 用户报的「右上角静音用不了」。
ok('Audio_ 有独立的 userMuted（和半双工闸分开）', 'userMuted:false' in html)
ok('Audio_ 有统一的 applyGain()', 'applyGain(){' in html)
ok('applyGain 两个闸任一关掉就是 0',
   '(this.muted||this.userMuted)?0:' in html)
ok('有 setUserMute()', 'setUserMute(on){' in html)
ok('setVol 走 applyGain（拖音量拖不回静音）',
   'setVol(v){ this.applyGain(); }' in html)
ok('play() 两个闸都判', 'if(this.muted||this.userMuted)' in html)
i_sp = html.find('function setPlay(')
seg = html[i_sp:i_sp + 1600] if i_sp > 0 else ''
ok('setPlay 会设 userMuted', 'Audio_.setUserMute(!on)' in seg)
ok('setPlay 会下发原生接收开关', 'rxApply()' in seg)
ok('setPlay 给 toast 回执', "toast(on?'🔊 开始收听'" in seg)
ok('冷启动那次不弹 toast', 'setPlay(store.get(\'audioOn\',true)!==false, true)' in html)
ok('rxApply 用 rxOn && audioOn',
   'Android.dmrSetRxOn(!!(state.rxOn&&state.audioOn))' in html)

# 单路归属：一句话只能归一条路，从 CALL_START 定死，不在句子中间切
ok('有 nativeRxLive()', 'function nativeRxLive(){' in html)
ok('有 hoseOwnsTg()', 'function hoseOwnsTg(tg){' in html)
seg = html[html.find('function hoseOwnsTg('):][:800]
ok('原生没在收时 HOSE 才播', 'if(!nativeRxLive())return true;' in seg)
# ★ 让位必须「见过才让」：按 txTg / rxOnlyWatchTg 推断会让 HOSE 把用户
# 真正在听的组整句丢掉，表现是「没声音、顶部连呼号都不显示」。
ok('让位依据是「原生真在这个组上收到过」', 'return !nativeSeenTg(tg);' in seg)
ok('不再按 rxOnlyWatchTg 推断', 'state.rxOnlyWatchTg' not in seg)
ok('不再按 qCurTg 推断', 'qCurTg()' not in seg)
ok('有 nativeSeenTg()', 'function nativeSeenTg(' in html)
ok('证据有有效期', 'const NATIVE_TG_TTL_MS=' in html)
ok('原生收到时记进 Q.seenTg', 'Q.seenTg[Q.last.dstId]=Date.now()' in html)
# 反向：不能再有「先播半句再压增益」的中途切换
ok('CALL_START 里不再调 hdGate 中途压增益',
   'hdGate()' not in html[html.find('function handleSpotter('):][:4000])
i_hd = html.find('function hdGate(')
seg_hd = html[i_hd:i_hd + 1100] if i_hd > 0 else ''
# hdGate 只在「原生收的正好是 HOSE 正在播的那个话组」时才让 HOSE 闭嘴。
# 以前判据是 TX.txing||Q.rxing —— 两条路在收不同话组时，HOSE 播的是一句
# 完全正常的话，被压掉就是纯粹的截断。
ok('hdGate 按「同一话组」判定',
   'Number(Q.last.dstId)===Number(cur.tg)' in seg_hd)
ok('hdGate 发射时一律静音', 'if(TX.txing){ txMuteAll(true); return; }' in seg_hd)

# ★ v2.4.9 发射时顶部显示自己的呼号
ok('有 renderNowTx()', 'function renderNowTx(){' in html)
ok('renderNow 先判发射态', 'if(TX.txing){ renderNowTx(); return; }' in html)
ok('有 selfCall()', 'function selfCall(){' in html)
i_txev = html.find("case 'tx':")
ok('tx 事件会刷顶部', 'renderNow();' in html[i_txev:i_txev + 1400])
ok('tx 事件记下发射起始时刻', 'TX.startAt=ev.on?Date.now():0;' in html)
ok('发射中计时器也走秒', 'if(TX.txing){' in html[html.find('function renderNowTimer('):][:300])
ok('折叠摘要带自己的呼号', "s='发射中'+(sc?' · '+sc:'')" in html)

print('\n' + '=' * 18 + f'  {passed} 通过 / {failed} 失败  ' + '=' * 18)
raise SystemExit(1 if failed else 0)
