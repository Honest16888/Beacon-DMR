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
├── MainActivity.java        三页壳（收听 / 看板 / 设置）+ 三条链路调度
├── core/
│   ├── MsgPack.java         MessagePack 编解码（零依赖，够用就好）
│   ├── MuLaw.java           G.711 μ-law 查表，与 WebView 版逐项一致
│   ├── Geo.java             话组号前三位 → 国家/地区（134 前缀 + 93 个 ISO）
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
├── mmdvm/                   ← Phase 3：从 android/ 搬过来的，只改了 package 行
│   ├── MmdvmSession.java    UDP 62031 握手 / 心跳 / MSTNAK 重试（纯 java.net）
│   ├── DmrTransmitter.java  麦克风 → AGC → AMBE → DMRD（AudioRecord）
│   ├── DmrRx.java           DMRD 载荷 → 解交织 → AMBE → PCM（纯 Java）
│   ├── DmrTx.java           DMRD 帧组装（JNI 到 dmrtx_jni.cpp 的 FEC 链）
│   ├── DmrDirectory.java    DMR ID ⇄ 呼号（database.radioid.net）
│   ├── DmrLink.java         ★ 新写的接线层（替代 DmrEngine + DmrAudio）
│   └── vocoder/Vocoder.java 声码器插件封装（JNI 到 libbeaconvoc.so）
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

合计 **125 项**。

## 进度

- [x] **Phase 0** 工程骨架 + HOSE 收听 + AudioTrack + 波形 + 话组订阅
- [x] **Phase 0.5** 话组表三级来源（内置 47 / 缓存 / 在线 1845）+ 搜索 + 只看订阅 + 手动加组
- [x] **Phase 1** 看板（6 数字卡 / 走势 / 实时活动 / 4×Top10 / 通话构成 / 通联记录）
- [x] **Phase 2** 设置（外观三档 / 音量 / 增强 / 窄带 / 保持唤醒 / 扫描 / 音频自检 / 关于）
- [x] **Phase 3** 收发（MMDVM 会话 + 声码器 + FEC 链 + 按住说话 + 收发设置卡 + 三份自检）
- [ ] Phase 4 录音 / 呼号库 / 国内 radiowo 链路
- [ ] Phase 5 对齐后改回 `net.beacon.dmr`，替代 WebView 版

## 已知的待办

- **没有前台服务**：切后台后 Activity 还在，音频能继续，但被系统回收就断。
  发射中尤其不能被回收 —— 下一个包做 `DmrKeepAliveService`。
- **没有呼号解析**：现在显示 `ID 1234567`，Phase 4 接 `cnData` 那张表
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
