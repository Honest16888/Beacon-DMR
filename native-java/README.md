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

体积也从 2.18 MB 降到 **41 KB**（没有 WebView 资源、没有前端数据岛）。

## 目录

```
app/src/main/java/net/beacon/dmr/
├── MainActivity.java        主页（收听）—— Phase 0 唯一一屏
├── core/
│   ├── MsgPack.java         MessagePack 编解码（零依赖，够用就好）
│   ├── MuLaw.java           G.711 μ-law 查表，与 WebView 版逐项一致
│   ├── Talkgroups.java      话组模型 + 订阅 / 自定义组持久化
│   └── TalkgroupRepo.java   话组表三级来源：内置 → 缓存 → 在线
├── hose/
│   ├── WsClient.java        RFC 6455 客户端（TLS + SNI + 分片 + ping/pong）
│   └── HoseClient.java      HOSE 协议：订阅、CALL_START/END/ALIAS/AUDIO
├── audio/
│   └── AudioOut.java        AudioTrack 8 kHz 直出 + 抖动缓冲 + 增益
└── ui/
    └── WaveView.java        RMS 柱状波形
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

## 测试

```bash
bash native-java/tests/run.sh      # 49 项，桌面 JDK 直接跑，不需要设备
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

## 进度

- [x] **Phase 0** 工程骨架 + HOSE 收听 + AudioTrack + 波形 + 话组订阅
- [x] **Phase 0.5** 话组表三级来源（内置 47 / 缓存 / 在线 1845）+ 搜索 + 只看订阅 + 手动加组
- [ ] Phase 1 看板（实时活动 / 通联记录 / 话组库）
- [ ] Phase 2 设置（音频链、扫描、自动录音）
- [ ] Phase 3 收发（把 `android/` 里的 `MmdvmSession` / `DmrRx` / `DmrTransmitter`
      搬过来 —— 那几个类本来就是纯框架 Java，一行不用改；届时才需要 NDK 编声码器）
- [ ] Phase 4 录音 / 呼号库 / 国内 radiowo 链路
- [ ] Phase 5 对齐后改回 `net.beacon.dmr`，替代 WebView 版

## 已知的待办

- 没有前台服务：切后台后 Activity 还在，音频能继续，但被系统回收就断。
  Phase 3 做收发时一并加 `DmrKeepAliveService`。
- 没有呼号解析：现在显示 `ID 1234567`，Phase 4 接 `cnData` 那张表
  （`web/index.html` 里 15,529 行的那段）。
- 国内话组只有 33 个，取自 WebView 版 `cnTgData`（2026-10-26）。
  真正的实时源是 `860.radiowo.com/api/v1/talkgroups`，Phase 4 再接。
