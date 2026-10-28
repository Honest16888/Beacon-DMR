# 信标 DMR · 纯 Java 版

`android/` 那份是 WebView 版（界面在 `web/index.html`，原生层只做 UDP + 声码器 + 桥）。
这一份是**全量重写**：界面、协议、音频全部在 Java 里，不加载 WebView。

两个工程互不影响，APK 可以同时装在手机上：

| | WebView 版 | 纯 Java 版 |
|---|---|---|
| applicationId | `net.beacon.dmr` | `net.beacon.dmr.java` |
| 界面 | 单文件 HTML（`web/index.html`） | 系统 View + XML |
| 收听 | JS WebSocket + WebAudio | `WsClient` + `AudioTrack` |
| 收发 | Java UDP + JNI（界面在 JS 里） | `MmdvmSession` + 同一条 `AudioTrack` |
| 出包 | `bash tools/build_apk.sh` | `bash tools/build_java.sh` |

applicationId 故意不同 —— 重写期间旧版还能正常用。等新版对齐后再改回
`net.beacon.dmr` 正式接班。

## 为什么值得重写

WebView 版把「同一个声音」实现了两遍：

| | Hoseline 收听 | MMDVM 收发 |
|---|---|---|
| 传输 | JS WebSocket | Java UDP 62031 |
| 解码 | JS μ-law + WebAudio | Java `DmrRx` |
| 输出 | AudioWorklet 环形缓冲 | AudioTrack（**另一条**） |

v2.4.x～v2.5.x 修的「噼啪」「两条路叠着放」「刷新后没声音」全都来自这个分裂。
纯 Java 版里，收听和收发共用同一条 `AudioTrack`，这些成因**根本不存在**：

- 没有 48 kHz 上下文里的逐帧重采样 → 没有 50 Hz 咔哒
- 没有 AudioWorklet 异步建不起来 → 没有「等 1.2 秒看门狗」
- 没有自动播放策略 → 不需要「轻触屏幕开始收听」
- 没有 DOM/JS 桥 → 可以放心开 R8
- **只有一个播放队列** → 两路音频同时出声在物理上做不到

体积 2.18 MB → **1.28 MB**。省的这 0.9 MB 全是前端资源（单文件 HTML 里
15,529 行呼号库数据岛、CSS、图标）；六份 .so 两边一样大，
`libc++_shared.so` 一个就占 2.4 MB（压缩后约 1 MB）。

## 目录

```
app/src/main/java/net/beacon/dmr/
├── App.java                 Application：崩溃堆栈落盘 filesDir/last_crash.txt
├── DmrTx.java                DMRD 帧组装（JNI；★ 必须在 net.beacon.dmr 包，见下）
├── KeepAliveService.java     前台服务：通知 + WakeLock + WifiLock
├── MainActivity.java        三页壳（收听 / 看板 / 设置）+ 三条链路调度 + 主页 chrome
├── core/
│   ├── MsgPack.java         MessagePack 编解码（零依赖，够用就好）
│   ├── MuLaw.java           G.711 μ-law 查表，与 WebView 版逐项一致
│   ├── Geo.java             话组号前三位 → 国家/地区（134 前缀 + 93 个 ISO）
│   ├── HostCheck.java       主站地址本地校验（空格/全角/域名格式，能猜回正确写法）
│   ├── Prefs.java           全部设置项的唯一读写入口（默认值都在这）
│   ├── Palette.java         主题解析 + 取语义色 + 混色
│   ├── Talkgroups.java      话组模型 + 订阅 / 自定义组持久化
│   └── TalkgroupRepo.java   话组表三级来源：内置 → 缓存 → 在线
├── hose/
│   ├── WsClient.java        RFC 6455 客户端（TLS + SNI + 分片 + ping/pong）
│   └── HoseClient.java      HOSE 协议：订阅、CALL_START/END/ALIAS/AUDIO
├── live/
│   ├── LhClient.java        BrandMeister LH 活动流（Socket.IO 套 Engine.IO）
│   ├── LiveStore.java       纯 Java 统计：活跃会话 / 走势 / Top10 / 通联记录
│   └── MasterRepo.java      主站表（40 条，7 天缓存）
├── mmdvm/                   ← Phase 3：从 android/ 搬过来（JNI 的两个类必须留在原包）
│   ├── MmdvmSession.java    UDP 62031 握手 / 心跳 / MSTNAK 重试（纯 java.net）
│   ├── DmrTransmitter.java  麦克风 → AGC → AMBE → DMRD（AudioRecord）
│   ├── DmrRx.java           DMRD 载荷 → 解交织 → AMBE → PCM（纯 Java）
│   ├── DmrDirectory.java    DMR ID ⇄ 呼号（database.radioid.net）
│   └── DmrLink.java         ★ 新写的接线层（替代 DmrEngine + DmrAudio）
├── vocoder/
│   └── Vocoder.java         声码器插件封装（JNI；★ 必须在 net.beacon.dmr.vocoder）
├── audio/
│   ├── AudioChain.java      软限幅 → 增益 → 高通180 → presence → 软削波
│   └── AudioOut.java        AudioTrack 8 kHz 直出 + 抖动缓冲
└── ui/
    ├── Ui.java              dp / 分段控件 / 排行行 / 时间格式
    ├── WaveView.java        RMS 柱状波形
    ├── LineChartView.java   Canvas 自绘走势折线
    ├── DashPage.java        看板：6 数字卡 + 走势 + 实时活动 + 4×Top10 + 记录
    └── SettingsPage.java    设置：外观 / 音频 / 扫描 / 收发 / 关于
```

**零第三方依赖**：没有 androidx、没有 OkHttp。所有能力都用框架自带的。

## HOSE 协议要点

```
wss://hose.brandmeister.network/spotter/?token=test:test     子协议 "spotter"
报文 = MessagePack 数组，[0] 是类型：
   1  GROUP_JOIN   → [1, [话组...]]        空订阅发 3 (GROUP_RESET)
  11  CALL_START   ← [11, 0, srcId, tg]
  13  CALL_END     ← [13, 0, srcId, tg]
  20  CALL_AUDIO   ← [20, bin(160 字节 μ-law = 20 ms)]
  21  CALL_ALIAS   ← [21, "呼号 名字 位置"]
  22  CALL_METER   ← [22, ...]
```

⚠ **音频帧不带话组号** —— 归属只能靠最近一个 `CALL_START` 记住
（`HoseClient.attrTg`）。WebView 版在这一点上栽过好几次。

## 话组表：三级来源

| 级 | 位置 | 条数 | 作用 |
|---|---|---|---|
| 内置 | `assets/talkgroups.tsv` | 47 | 装完立刻能听；**国内组的中文名只在这份里** |
| 缓存 | `filesDir/talkgroups.cache.tsv` | ~1841 | 冷启动不用等网络；7 天 TTL |
| 在线 | `api.brandmeister.network/v2/talkgroup` | 1845 | 冷门组全靠它 |

**合并规则：内置优先。** 同一个 id 两边都有时保留内置的中文名
（91 是「世界组」不是 "World-wide"），在线表只补内置没有的。
最终约 **1873** 条 —— 比 WebView 版（1845）多，因为内置那 33 个国内组
官方表里根本没有（`86023` 查不到）。

界面上的计数会带来源标注：`1873 / 1873 · 订阅 2 · 在线`。
显示「内置 47」说明在线表没拉到，先查网络。

## 主题：为什么要绕一层 attr

设置里能强制浅色 / 深色，这就不能直接写 `@color/bg`：

`values-night/` 这个限定符**只认系统深色开关**，用户在设置里选了「深色」
而系统是浅色时，`@color/bg` 仍然解析成浅色那份。所以：

```
attrs.xml     13 个语义属性（cBg cPanel cText cAccent …）
colors.xml    l_* / d_* 两套色板，都放 values/（不用 -night）
themes.xml    BaseLight / BaseDark → AppTheme / AppTheme.Light / AppTheme.Dark
              <item name="cBg">@color/l_bg</item>  ← 挂 attr
布局里写      android:background="?attr/cBg"
```

`Palette.apply(activity)` 读 `Prefs.theme()` 调 `setTheme()`，整棵树的
attr 一起换。**必须在 `super.onCreate()` 之前调** —— 窗口背景色在
`setContentView` 那一刻就定了，晚一步就会看到一闪的白底。

零依赖工程用不了 `AppCompatDelegate.setDefaultNightMode()`（那是 androidx），
这套 attr 方案是纯框架能做的等价物。

`values-night/themes.xml` 里只覆盖 `AppTheme`（跟随系统那份），
`.Light` / `.Dark` **故意不出现** —— 它们必须不受系统开关影响。

## 音频链路：顺序不能换

```java
// AudioChain.process() 逐样本
μ-law 解码 → 软限幅(0.96) → 音量 × 增强 → 高通 180Hz → presence(2.6k +2.5dB) → 软削波 → AudioTrack
```

和 WebView 版 `web/index.html` 里那条链**逐级对应**，这不是巧合：

- **软限幅在增益之前**是历史遗留，它挡不住出口；真正保证不硬削波的是末端软削波。
  末端 `pre(1/3) → WaveShaper(内部×3)` 在这里合成一步
  （`KNEE=0.7` 直通，`0.7~0.98` 走 `tanh` 渐近）。
- **不能用 `DynamicsCompressor` 当限幅器** —— 2 ms 起始时间抓不住单样本瞬态，
  实测出口仍有 1.05~1.21 的峰。
- **增益按样本爬坡**（`RAMP = 0.005`），presence 按帧爬 0.5 dB。
  跳变就是「啪」一声，正好是 v2.5.x 查了半天的那个声音。

改任何一级增益都要重跑 `tests/AudioChainTest.java`。

## 收发（Phase 3）：搬了什么、没搬什么

从 `android/` 搬过来的六个类**只改了 `package` 那一行**，一行逻辑没动：

| 类 | 依赖 | 说明 |
|---|---|---|
| `MmdvmSession` | 纯 `java.net` | UDP 62031 握手、心跳、MSTNAK 重试链 |
| `DmrTransmitter` | `AudioRecord` | 麦克风 → 高通/峰值/AGC/噪声门 → AMBE → DMRD |
| `DmrRx` | 纯 Java | DMRD 载荷 → 解交织 → AMBE → PCM |
| `DmrTx` | JNI | DMRD 帧组装（RS + BPTC + Golay + Hamming + CRC） |
| `DmrDirectory` | `HttpURLConnection` | DMR ID ⇄ 呼号 |
| `vocoder/Vocoder` | JNI | 声码器插件（dlopen + dlsym） |

**没搬 `DmrEngine` 和 `DmrAudio`。** 这是本轮最重要的决定：

```
WebView 版                        纯 Java 版
─────────────────────────         ─────────────────────────
HOSE   → WebAudio (48 kHz)        HOSE  μ-law ─┐
MMDVM  → DmrAudio (另一条 Track)  MMDVM AMBE  ─┴→ AudioOut（同一个队列）
```

`DmrAudio` 是**第二条 AudioTrack**。搬它进来就等于把「两条路叠着放」
那个问题的架构前提又装回去。所以只搬协议与 DSP，接线自己写
（`mmdvm/DmrLink.java`）：AMBE 解出来的 PCM 按 160 采样切帧，
喂进和 HOSE 完全相同的那个 `AudioOut`。于是：

- 队列只有一个 → **物理上不可能两路同时出声**
- 音量 / 高通 / presence / 软削波只有一份 → 不存在「哪条链的增益没跟上」
- 半双工闸门只剩一个动作：发射时 `audio.flush()`

`DmrLink` 保留的是 `DmrEngine` 里真正有信息量的部分：**过滤与去重策略**
（黑名单 / 静音话组 / 单流锁定 / 话组忙去重），把「拼 JSON 抛给 WebView」
换成类型化回调。

### HOSE 什么时候让位

判据是 `DmrLink.isNativeRx()` = **最近 500 ms 收到过语音帧**，不是「已连接」。

按「已连接」掐掉 HOSE 会变成「连上主站反而什么都听不见」—— 手机当热点时
主站并不一定往这边推音频（MMDVM 的接收取决于主站路由）。
按「正在收」判断则两边都不丢：原生在放的时候 HOSE 让位（一个队列不能插两路），
原生没在放的时候 HOSE 照常工作。**任何时刻都不会出现全静音。**

### native 库从哪来

`app/src/main/jniLibs/` 下六份 .so（两个 ABI × `libbeaconvoc` /
`libdsvocoder` / `libc++_shared`），**从 v2.5.5 正式包里解出来的**，
逐字节相同。这样 native-java 保持「纯 Java + 预编译 .so」的形态，
出包不经过 CMake。

改了 `android/app/src/main/cpp/` 之后才需要：

```bash
bash tools/build_vocoder.sh     # 在 ASCII 镜像里重编，只拷 libbeaconvoc.so 回来
```

**`.so` 不许 strip**（`packaging { jniLibs { keepDebugSymbols += '**/libdsvocoder.so' } }`）。
`libdsvocoder.so` 是第三方预编译插件，我们既没有源码也没有它的构建脚本，
削它等于赌 dlsym 要的符号恰好都在 `.dynsym` 里 —— 赔率是「装完发不出去、
而且没有任何报错」。`useLegacyPackaging = true` 也一样，和 WebView 版逐项一致。

## 「连不上主站」到「连上了但一按就崩」

真机反馈走了两轮。第一轮是「连接主站连不上」，加了诊断按钮后拿到报告，
发现是两个**同时存在**的问题叠在一起；第二轮是「连上了一发射就崩溃」。
下面按发现顺序记，每一条都是「界面表现查不出来、代码里一眼能看出」的类型。

### 第一轮：连不上（alpha4 修）

MMDVM 登录失败有七八种成因，界面表现完全一样：停在「未连接」或弹一句 MSTNAK。
所以先给 `DmrLink.diagnose()` 加诊断按钮，让用户把一屏报告发回来 ——
**没有这一步，后面三条一条也查不出来。**

**① 主站地址写错了：`4602.ma4614ster.brandmeister.network`**

`4614`（DMR ID 的前四位）被插进了 `master` 中间。这种地址的唯一后果是 DNS 查不到，
而界面上只会写 `Load failed : Host not found` —— **一句话都不提「地址写错了」**。
用户只能得出「软件坏了」。

`core/HostCheck.java` 现在把能在本地判断的事全接过来：空格、全角字符、非法字符、
域名段边界、IPv4 越界、BrandMeister 域名格式。**能猜出正确写法时直接弹
「改好并连接」**——`guessBm()` 取第一个 4 位数字段作主站号，上面那串会被
准确猜回 `4602.master.brandmeister.network`。

顺带修了一个真 bug：`session.configure()` 传的是**原始** host，而空值检查用的是
`host.trim()`。粘贴时带了前后空格的话，`getAllByName(" 4602…")` 一样查不到。

**② JNI 符号全断：`UnsatisfiedLinkError: No implementation found for
...net.beacon.dmr.mmdvm.vocoder.Vocoder.nativeInit`**

Phase 3 搬家时「只改 package 行、逻辑一行没动」—— 而 JNI 的符号名由**全限定类名**
拼出来。`libbeaconvoc.so` 里导出的是 `Java_net_beacon_dmr_vocoder_Vocoder_nativeInit`，
包名一改就对不上。`DmrTx` 同样断，只是还没人按 PTT 所以没暴露。

那句报错极具误导性：`is the library loaded?` 读起来像「.so 没打进包」，
实际 .so 好好地在 `lib/arm64-v8a/` 里。现在两个类移回原包
（`net.beacon.dmr.DmrTx` / `net.beacon.dmr.vocoder.Vocoder`），
并加了 **`tools/check_jni.py`**：扫所有 `native` 声明，拼出期望符号，
逐个 ABI 比对 `.so` 的 `.dynsym`。接在 `build_java.sh` 里，编译前跑。

**③ 声码器坏了会静默阻断登录**
`connect()` 开头写的是「声码器没就绪就 return」，于是点「连接主站」什么都不发生。
而登录（UDP 62031）和声码器（PCM ⇄ AMBE）是两件独立的事 —— **不发射就完全不需要声码器**。
现在声码器坏了也能登录，只是暂时不能按 PTT。

**④ 连接前申请麦克风权限**
登录和麦克风毫无关系。没给权限的用户点连接只看到权限框，随手点「拒绝」就永远连不上，
而界面上一个字都不提权限。麦克风现在只在 `startPtt()` 里申请。

### 第二轮：连上了，一按发射就崩（alpha4 修）

```
android.os.NetworkOnMainThreadException
  at BlockGuardOs.sendto
  at MmdvmSession.send(MmdvmSession.java:1717)
  at DmrTransmitter.start(DmrTransmitter.java:288)
  at DmrLink.ptt(DmrLink.java:514)
  at MainActivity.startPtt(MainActivity.java:395)
  at View.dispatchTouchEvent
```

连接那几条路早就移到后台线程了，**唯独 PTT 漏了** —— 因为它不是「点一下」，
而是 `OnTouchListener` 的 DOWN/UP，写的时候容易当成纯 UI 事件。
可 `DmrTransmitter.start()` 里第一件事就是发 LC 头帧，`stop()` 里要补终止帧，
两者都是 `DatagramSocket.send`。

现在 `DmrLink.ptt()` **绝不碰网络**：只做内存检查，然后把动作排进 `txSeq`。
用**单线程**执行器而不是每次 `new Thread`，因为按和放必须严格先后 ——
快速「点一下就松」时如果跑在并行线程上，`stop()` 可能先看到
`transmitting == false` 直接返回，然后 `start()` 才把发射打开，
**结果是松手之后一直在发射**。

异步启动带来的第二个问题：失败时不会有 `onTx(true)` 先到，而
`pttDown` 还停在 true —— 用户松手后再按就再也压不下去，屏幕上只看到按钮闪了一下。
所以 `onTx(false, …)` 里要复位 `pttDown`，并按「有没有真的开起来过」
分别显示「没发出去：…」和「发射中断：…」。

### 诊断报告长什么样

设置页底部一整行「诊断（连不上时点这个）」，`DmrLink.diagnose()` 分七段输出
【配置】【地址格式】【域名解析】【本机 UDP】【录音权限】【声码器】【当前会话】，
**不联网、不发包、不改状态**，随时可以点，文字可以长按复制。

配套的小修：
- 连接 / 断开 / 自检 / 诊断全部移到后台线程（`connect()` 第一件事是
  `InetAddress.getAllByName()`，DNS 卡住会冻界面数秒，用户会以为按钮没生效而反复点）。
- 连接按钮置忙（「正在登录…」），并加 30 s 看门狗 —— 握手超时 24 s + 断开后等待 2 s，
  最坏 26 s 才等到 ERROR；看门狗兜住「已经在连接中，忽略重复请求」那条静默 return。
- `onState(ERROR)` 弹一次 Toast；`updateTxDiag()` 改成「状态行 + 读数 + 最近三条日志」，
  状态行在 `onState` 里定死，不再被 4 秒一次的 tick 冲掉。

## 主页 1:1 复刻（alpha5）

上一版的主页只有一个话组列表、一条底部 PTT、一个「只看已订阅」复选框 ——
那是「把 WebView 版的功能挑几样搬过来」的做法，不是复刻。alpha5 按
`web/index.html` 的**手机端**规格（`@media(max-width:760px)` 那一档，那才是
真机看到的）整页重写。

### 结构对齐

| 区块 | WebView 选择器 | 说明 |
|---|---|---|
| 顶栏两行 | `.topbar` / `.tbrow` / `.viewtabs` | 页签**从底栏搬到顶栏**，底栏整个删掉 |
| 警告条 | `.warnbar` | 常驻容器，默认 gone；声码器坏了这类半残状态挂这里 |
| 播报条 | `.nowbar` / `.np-*` | **从页内提到页外**，三个页面共享 |
| 可收起操作区 | `.netbar` / `.ptx` / `.htsum` / `.htfold` | 网络切换 + 三行发射条 + 折叠摘要 |
| 三栏列表 | `.mobnav` / `.grid > .col` | 我的收听 / 实时活动 / 话组库，手机上一次一栏 |
| 悬浮 PTT | `.pttf` | 74dp 圆钮，右下 16/20，**不是底部条** |

### 几个必须照着做的细节

**顶栏是两行，不是一行。** 桌面端用 `.tbrow{display:contents}` 把两行 DOM
「摊平」回一行；手机端 `.tbrow{display:flex}` 才真正分成两行。我们只做手机，
所以直接两行：第 1 行 `📻 + 页签 + 开始收听`，第 2 行 `音量 + 两个状态灯`。
`.brand .btxt / .sub` 在手机端是 `display:none`，只留 14sp 的 📻。

**播报条在页外。** 它在 WebView 的 DOM 里是 `body` 的直接子级，所以切到看板页
也还看得见「谁在说话」。放进 `page_listen` 里就做不到这一点。

**左边那条 3dp 竖线用一个独立 View 顶住左墙。** Android 的 shape 不能只画左边，
而 `.nowbar.live` / `.txing` 只换这条线的颜色。用独立 View 之后宽度恒为 3dp，
换色时布局不会抖。

**PTT 是悬浮圆钮，而且三态。** 未连接灰渐变（`.ptt:disabled`）、正在接收
MMDVM 那一路染绿（`.ptt.rx`）、其余红渐变。绿色那档很重要 —— 它让
「现在有人在说话」在拇指下面就能看见，不用回头看播报条。
按下时只做 `scale(.94)`，圆钮上的字一个都不换（`.ptt.live` 就是这么做的）。

**「只看已订阅」复选框删掉了。** 上一版把全部 1873 个话组塞进一个列表，
靠一个复选框筛 —— 那是「一个列表兼两用」。WebView 版本来就分两栏：
「我的收听」= 订阅，「话组库」= 全部。现在两个列表各管一件事，
`TgAdapter` 带一个 `data` 参数，两栏共用一个类。

### 折叠只省屏幕，不藏状态

`.home-top.collapsed .home-op{display:none}` + `.htsum{display:flex}`。
收起后只剩 26dp 的摘要行：网络缩写 + 状态点 + 谁在说 + 当前发射目标。
**往下滑看记录时还要能一眼看到有没有人在说话、上一通是谁** ——
所以摘要行不是「缩小版操作区」，是另一套只读信息。

折叠状态存本地（`Prefs.htCollapsed`），箭头转 180°，虚线和负边距一起收掉。

### 新增的色板与 drawable

WebView 的 `--tx` / `--tx2` / `--border2` 在原生侧原来没有，补成
`cTx` / `cTx2` / `cBorder2` 三个主题属性（`Palette` 里也有对应字段）。
新增 14 个 drawable：分段容器与选中项、输入框、计数胶囊、话组胶囊、
回复 / 录音按钮、悬浮 PTT 三态、虚线、摘要组号胶囊。

**踩到的坑：XML 注释里不能出现 `--`。** 写注释时直接抄 CSS 变量名
（`background:var(--bg)`）会让 `aapt` 报「注释中有不允许的字符串 “--”」，
而且错误信息指向 `attrs.xml:37`，跟真正出问题的 drawable 差了十万八千里。

### 静态检查

没有 emulator，`findViewById` 返回 null 只会在真机上 NPE。所以加了
`tools/check_layout.py`，接在出包流程里：Java 里 `R.id.x` 引用的 id 必须在
某个 layout 里存在、同一 layout 内不能有重复 id、`@drawable/@style/@color/@string`
引用都要解析得到。当前 134 个 id / 8 个 layout 全部对得上。

## 看板口径

数字必须和 WebView 版 `dashData()` 对得上（用户两个版本来回切）：

| 项 | 口径 |
|---|---|
| 活跃会话 | 30 分钟窗口内，未收到 `Session-Stop` 的 |
| 走势 | 窗口随运行时长自适应：< 10 分钟看 10 分钟，之后逐级放宽到 30 分钟 |
| 人数 | 按呼号去重，不按 ID |
| 自动跟进 | 只有 `tg > 90` 才进候选池（排除 91 世界组之外的杂组） |
| 通联记录 | 50 条 / 24 小时 |
| 实时活动 | 12 条，可按「只看订阅」筛 |

LH 实测 **9 条/秒**。`onEvent` 回调在 WebSocket 读线程上，
**只往 `LiveStore` 里丢，绝不碰 View、也不 post 到主线程** ——
`LiveStore` 内部 `synchronized`，界面靠 500 ms 的 tick 自己去取快照。

`LiveStore` 刻意**不认识 JSON**：`LhClient.parse()` 把报文转成
`LiveStore.Event` 再送进来。这样 Store 是纯 Java，桌面能直接跑测试。

## 测试

```bash
bash native-java/tests/run.sh      # 125 项，桌面 JDK 直接跑，不需要设备
```

- **第一组 37 项**（`CoreSelfTest`）只覆盖不依赖 Android 的两个类
  （`MsgPack` / `MuLaw`）—— 它们错了的表现是「连上了但没声音」，
  在手机上排查极贵。其中一条是**逐项比对 WebView 版那张 μ-law 表**：
  只要有一项不同，两个版本音色就不一样，而这种回归最难被接受。
- **第二组 12 项**（`TgMergeTest`）测话组表合并规则。需要 `android.jar`
  才编得过（`TalkgroupRepo` 里有 `Context`），找不到就自动跳过。
  ⚠ 注意 `run.sh` 里 classpath 用 `;` 不用 `:` —— Windows 的 `java.exe`
  只认分号，写成冒号会把整串当成一个路径，报「找不到主类」，
  看起来像编译没产出 class，其实编译是好的。
- **`GeoTest` 18 项** —— 前缀解析、未知话组、中文名优先、边界（199/900/两位数字）
- **`AudioChainTest` 8 项** —— 满幅输入出口不超过 0.98、直通区线性、
  增益爬坡不跳变、presence 开关不影响直流
- **`StatsTest` 30 项** —— `LiveStore` 的 30 分钟窗口、去重、Top10 排序、
  通联记录裁剪、走势自适应
- **`DmrRxTest` 20 项** —— 交织 / 解交织**位级**往返。测试里用 Java 抄了一份
  `dmrtx_jni.cpp` 的 `interleave_ambe()`，逐字对应。这一组最重要的一条是
  「同步码 + 嵌入信令在场的 64 轮往返全部一致」：同步码写在 p[13] 低半字节和
  p[19] 高半字节，正好和 AMBE 抢同一批位 —— 取错半字节的表现是
  「每 6 帧（360 ms）坏一次」，而不是报错
- **`HostCheckTest` 45 项** —— 主站地址校验。每一条都对应一种真实写法，
  而每一种在界面上都只表现成 `Load failed : Host not found`，
  看不出是哪个字错了。其中一条钉的就是真机上那串
  `4602.ma4614ster.brandmeister.network` → 必须猜回正确地址

合计 **182 项**。

### 另外还有两道静态检查

`tools/check_jni.py` 和 `tools/check_layout.py` 不在上面这 170 项里，
但同样挂在出包流程上（编译之前跑）。

**`check_layout.py`** 查布局与代码的 id 对应关系 —— `findViewById` 拿不到控件
不会编译报错，只会在运行时返回 null，往下走一步就是 NPE。本工程没有 emulator，
这是唯一能在桌面上拦住它的地方。

**`check_jni.py`** 查 JNI 符号：
扫所有 `native` 方法声明，按 JNI 规则拼出符号名，逐个 ABI 比对 `.so` 的 `.dynsym`。
对不上就直接失败。

它防的是一个**只在运行时才暴露**的错误：改一个 `package` 行，所有 native 调用静默全断，
报错是英文的 `No implementation found for … is the library loaded?` ——
读起来像「.so 没打进包」，实际是符号名还带着旧包名。这个坑踩过一次（见上一节 ②）。

## 进度

- [x] **Phase 0** 工程骨架 + HOSE 收听 + AudioTrack + 波形 + 话组订阅
- [x] **Phase 0.5** 话组表三级来源（内置 47 / 缓存 / 在线 1845）+ 搜索 + 只看订阅 + 手动加组
- [x] **Phase 1** 看板（6 数字卡 / 走势 / 实时活动 / 4×Top10 / 通话构成 / 通联记录）
- [x] **Phase 2** 设置（外观三档 / 音量 / 增强 / 窄带 / 保持唤醒 / 扫描 / 音频自检 / 关于）
- [x] **Phase 3** 收发（MMDVM 会话 + 声码器 + FEC 链 + 按住说话 + 收发设置卡 + 三份自检）
- [x] **Phase 3.5** 主页 1:1 复刻（顶栏两行 / 播报条 / 可收起操作区 / 三栏列表 / 悬浮 PTT）
- [ ] Phase 4 录音 / 呼号库 / 国内 radiowo 链路
- [ ] Phase 5 对齐后改回 `net.beacon.dmr`，替代 WebView 版

## 已知的待办

- ~~**没有前台服务**~~ → 已做 `KeepAliveService`（alpha3）。三件事必须一起做：
  `startForeground()` + Partial WakeLock + WifiLock(HIGH_PERF)。
  **只有 WakeLock 的表现是「熄屏能听、切后台会断」** —— 它只锁 CPU，锁不住进程优先级。
  服务类型用 `mediaPlayback` 而不是 `dataSync`（Android 14 对 `dataSync` 有每天累计
  6 小时上限，守听会撞上）；发射时再加 `microphone`。`START_NOT_STICKY` 而不是
  `STICKY`：进程被杀后重建的是空壳（服务活着、通知挂着、锁也持着，但 Activity /
  WebSocket / UDP 会话全没了），用户看到「守听中」却收不到东西，比通知消失更糟。
  待真机验证：切后台 / 熄屏后链路是否还在、通知是否显示。
- **「保持屏幕常亮」用 `FLAG_KEEP_SCREEN_ON` 而不是 PARTIAL_WAKE_LOCK** ——
  前者跟随窗口生命周期、不需权限、不会忘记释放耗一整晚电。- **没有呼号解析**：现在显示 `ID 1234567`，Phase 4 接 `cnData` 那张表
  （`web/index.html` 里 15,529 行的那段）。`DmrDirectory` 已经搬过来了，
  但只在「添加屏蔽名单」时才该调它（radioid.net 建议控制频率）。
- 国内话组只有 33 个，取自 WebView 版 `cnTgData`（2026-10-26）。
  真正的实时源是 `860.radiowo.com/api/v1/talkgroups`，Phase 4 再接。
- **「自动录音」开关只存值，不生成文件** —— 录音本身归 Phase 4，设置页已标注。
- **窄带增强（presence）默认关，v2.5.5 默认开**。因为当前音频链路刚被验收
  「声音是对的」，默认加 2.6 kHz +2.5 dB 会改音色。要不要跟旧版一致，
  等真机试听后定。
- **原生接收只做「正在收时 HOSE 让位」**，不做 per-TG 归属（v2.5.5 的
  `RXG.dropTg`）。共用一个队列之后 per-TG 归属没有意义了 —— 同一时刻只能放一路。
- **这 1300 多行 UI + 9000 行搬过来的协议代码一次都没在屏幕上跑过**
  （本机 SDK 没有 emulator，`adb devices` 空）。所以加了 `App.java` 崩溃捕获：
  堆栈落 `filesDir/last_crash.txt`，下次启动弹对话框。看到闪退请截图发来。
