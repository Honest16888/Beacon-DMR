# 信标 DMR · 纯 Java 版

`android/` 那份是 WebView 版（界面在 `web/index.html`，原生层只做 UDP + 声码器 + 桥）。
这一份是**全量重写**：界面、协议、音频全部在 Java 里，不加载 WebView。

两个工程互不影响，APK 可以同时装在手机上：

| | WebView 版 | 纯 Java 版 |
|---|---|---|
| applicationId | `net.beacon.dmr` | `net.beacon.dmr.java` |
| 界面 | 单文件 HTML（`web/index.html`） | 系统 View + XML |
| 收听 | JS WebSocket + WebAudio | `WsClient` + `AudioTrack` |
| 出包 | `bash tools/build_apk.sh` | `bash tools/build_java.sh` |

applicationId 故意不同 —— 重写期间旧版还能正常用。等新版对齐后再改回
`net.beacon.dmr` 正式接班。

## 为什么值得重写

WebView 版把「同一个声音」实现了两遍：

| | Hoseline 收听 | MMDVM 收发 |
|---|---|---|
| 传输 | JS WebSocket | Java UDP 62031 |
| 解码 | JS μ-law + WebAudio | Java `DmrRx` |
| 输出 | AudioWorklet 环形缓冲 | AudioTrack |

v2.4.x～v2.5.x 修的「噼啪」「两条路叠着放」「刷新后没声音」全都来自这个分裂。
纯 Java 版里，收听和收发共用同一条 `AudioTrack`，这些成因**根本不存在**：

- 没有 48 kHz 上下文里的逐帧重采样 → 没有 50 Hz 咔哒
- 没有 AudioWorklet 异步建不起来 → 没有「等 1.2 秒看门狗」
- 没有自动播放策略 → 不需要「轻触屏幕开始收听」
- 没有 DOM/JS 桥 → 可以放心开 R8

体积也从 2.18 MB 降到 **93 KB**（没有 WebView 资源、没有前端数据岛；
其中 15 KB 是两套色板和 134 条国家前缀表）。

## 目录

```
app/src/main/java/net/beacon/dmr/
├── App.java                 Application：崩溃堆栈落盘 filesDir/last_crash.txt
├── MainActivity.java        三页壳（收听 / 看板 / 设置）+ 两条链路调度
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
├── audio/
│   ├── AudioChain.java      软限幅 → 增益 → 高通180 → presence → 软削波
│   └── AudioOut.java        AudioTrack 8 kHz 直出 + 抖动缓冲
└── ui/
    ├── Ui.java              dp / 分段控件 / 排行行 / 时间格式
    ├── WaveView.java        RMS 柱状波形
    ├── LineChartView.java   Canvas 自绘走势折线
    ├── DashPage.java        看板：6 数字卡 + 走势 + 实时活动 + 4×Top10 + 记录
    └── SettingsPage.java    设置：外观 / 收听与音频 / 扫描 / 关于
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
bash native-java/tests/run.sh      # 105 项，桌面 JDK 直接跑，不需要设备
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

合计 **105 项**。

## 进度

- [x] **Phase 0** 工程骨架 + HOSE 收听 + AudioTrack + 波形 + 话组订阅
- [x] **Phase 0.5** 话组表三级来源（内置 47 / 缓存 / 在线 1845）+ 搜索 + 只看订阅 + 手动加组
- [x] **Phase 1** 看板（6 数字卡 / 走势 / 实时活动 / 4×Top10 / 通话构成 / 通联记录）
- [x] **Phase 2** 设置（外观三档 / 音量 / 增强 / 窄带 / 保持唤醒 / 扫描 / 音频自检 / 关于）
- [ ] Phase 3 收发（把 `android/` 里的 `MmdvmSession` / `DmrRx` / `DmrTransmitter`
      搬过来 —— 那几个类本来就是纯框架 Java，一行不用改；届时才需要 NDK 编声码器）
- [ ] Phase 4 录音 / 呼号库 / 国内 radiowo 链路
- [ ] Phase 5 对齐后改回 `net.beacon.dmr`，替代 WebView 版

## 已知的待办

- **没有前台服务**：切后台后 Activity 还在，音频能继续，但被系统回收就断。
  Phase 3 做收发时一并加 `DmrKeepAliveService`。
- **没有呼号解析**：现在显示 `ID 1234567`，Phase 4 接 `cnData` 那张表
  （`web/index.html` 里 15,529 行的那段）。
- 国内话组只有 33 个，取自 WebView 版 `cnTgData`（2026-10-26）。
  真正的实时源是 `860.radiowo.com/api/v1/talkgroups`，Phase 4 再接。
- **「自动录音」开关只存值，不生成文件** —— 录音本身归 Phase 4，设置页已标注。
- **窄带增强（presence）默认关，v2.5.5 默认开**。因为当前音频链路刚被验收
  「声音是对的」，默认加 2.6 kHz +2.5 dB 会改音色。要不要跟旧版一致，
  等真机试听后定。
- **这 900 多行 UI 一次都没在屏幕上跑过**（本机 SDK 没有 emulator，`adb devices` 空）。
  所以加了 `App.java` 崩溃捕获：堆栈落 `filesDir/last_crash.txt`，
  下次启动弹对话框。看到闪退请把那一屏截图发来。
