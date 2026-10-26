# DroidStar 二进制逆向分析报告

**分析对象**：`DroidStar64-build-f050489.exe`（DroidStar 上游的 Windows 发行版）
**文件信息**：45,934,080 字节（45.9 MB）· PE32+ 可执行文件 · Qt6 静态链接
**构建工具链**：**GCC 11.3.0 + MinGW-w64 v10.0.0**（非 MSVC）· Qt 6.5.x
**构建标识**：`f050489`（git 短哈希）· 项目页 `github.com/nostar/DroidStar`
**分析方式**：字符串池提取（ASCII 572,181 条 + UTF-16LE 26,005 条，去重 138,347）＋ 字节级检索
**分析目的**：判断其发射（通联）能力能否移植到「信标 DMR」App

---

## 一、总体结论（先说结果）

| 问题 | 答案 |
|---|---|
| 走的是 HOSE 吗？ | **不是**。走 MMDVM / Homebrew 协议（UDP） |
| 内置软件 AMBE+2 编码器吗？ | **没有**。只有插件接口，编码器要自己装 |
| 需要硬件热点吗？ | **不需要**。有 `MMDVM_DIRECT` 模式，纯 UDP 直连主站 |
| DMR 的 FEC 链是现成的吗？ | **是**，BPTC/Golay/Hamming/RS129 全部内置 |
| 支持硬件声码器吗？ | **是**，串口 AMBE3000（DVstick / ThumbDV 类） |

---

## 二、协议层：确认 MMDVM / Homebrew

### 2.1 协议魔数（已确认存在）

| 魔数 | 出现 | 含义 |
|---|---|---|
| `DMRD` | 4 次 | DMR 语音/数据帧头 |
| `RPTACK` | ✓ | 主站对客户端命令的确认 |
| `RPTC` | ✓ | 客户端上报配置 |
| `MSTCL` | ✓ | 主站关闭连接 |
| `MSTPONG` | 1 次 | 心跳应答 |
| `RPTP`… | 片段 | 推断为 `RPTPING`（心跳请求） |
| `RPTA`… | 片段 | 推断为 `RPTACK` |

> `RPTL` / `RPTK` / `RPTO` 的字节级检索为 0，但 `RPTP`、`RPTA`、`MSTC`、`MSTP`
> 以「长度前缀 + 片段」形式出现在字符串池中（如 `D$1RPTP`、`8RPTA`、`8MSTP`），
> 属于链接器字符串池合并的结果。**协议族判定为 Homebrew，这一点是确定的。**

### 2.2 关键发现：主机与端口**没有硬编码**

```
brandmeister        出现 0 次
62031               出现 0 次
```

说明主站地址与端口**全部由用户配置**，程序里没有可直接抄的默认值。
配置文件为：

- `DMRHosts.txt` —— 主站列表（对应 `process_dmr_hosts()`）
- `DMRIDs.dat` —— DMR ID 数据库（对应 `update_dmr_ids()` / `process_dmr_ids()`）

### 2.3 网络实现

`QUdpSocket`（含 `QNativeSocketEngine::writeDatagram()` / `readDatagram()` 符号）
—— **UDP 发射确认**，与浏览器 WebSocket 完全不同的通道。

### 2.4 DroidStar 的 DMR 类 API（符号表还原）

```
get_dmr_host()        set_dmrid()          dmr_tgid_changed(int)
get_dmrid()           set_dmrtgid()        dmrpc_state_changed(int)
get_dmrtgid()         set_dmr_options()    process_dmr_hosts()
get_dmr_options()     set_dmr_pc()         process_dmr_ids()
```

内部字段常量：`DMRHOST` `DMRID` `DMRTGID` `DMROPTS` `DMRSWID` `DMRPKGID`
`DMRDESC` `DMRFREQ` `DMRLOC` `DMRLAT` `DMRLONG` `DMRURL`

> `set_dmr_pc()` 中的 `pc` 疑似 Private Call（私呼）或 Parrot Channel，
> 仅从符号名无法确定，需要源码佐证。

### 2.5 支持的网络

除 BrandMeister 外，还内置：

- **TGIF**：`TGIFPASSWORD` / `tgifpwd` / `get_tgif_password()` / `set_tgif_password()`
- **XLX**：`XLX` 标识
- **DCS**：状态文案 `Connected to DCS`
- **DMRGateway**：`DMRGateway` 标识

---

## 三、声码器架构（本报告最重要的发现）

DroidStar 的声码器是**插件化**的，共四条路径：

### 3.1 硬件 AMBE（串口）—— 完整实现

```
SerialAMBE                    类名
ambe_connect_status(bool)     连接状态信号
ambedev_ready()               设备就绪信号
get_ambe()                    取帧接口
AMBE3000 Parity disabled      初始化步骤 1
AMBE3000 Rate set             初始化步骤 2
ERROR: AMBE3000 Parity not disabled
ERROR: AMBE3000 Rate not set
QSerialPort / Baud115200 / ModemBaud / baudRate
```

→ 这是 **AMBE3000 芯片通过 USB 转串口**的标准初始化序列，
即 **DVstick 30 / ThumbDV** 这一类硬件声码器。

### 3.2 软件声码器插件 —— **只有接口，没有实现**

```
/vocoder                      插件目录
Software vocoder              模式名
Vocoder plugin loaded         加载成功
Vocoder plugin not loaded     加载失败
get_vocoders()                枚举可用插件
load_vocoder_plugin()         加载
set_vocoder()                 选定
open_vocoder_dialog()         界面入口
```

### 3.3 `md380` 检索结果：**0 次**

```
md380    出现 0 次
md-380   出现 0 次
dynarmic 出现 0 次
```

→ **Windows 构建里完全没有内置 `md380_vocoder`。**
它是**独立下载的插件**，运行时从 `/vocoder` 目录动态加载。

### 3.4 内嵌的解码器

| 库 | 证据 | 用途 |
|---|---|---|
| **mbelib** | `ambe_fr c0: `、`mbelib: Error - uvquality must be within the range 1 - 64...` | **仅解码**（AMBE/AMBE+2） |
| **codec2** | `../DroidStar/codec2/codec2.cpp` 等 5 个文件 | M17 声码器 |

### 3.5 结论

> **DroidStar 自己没有任何软件 AMBE+2 编码器。**
> 它把「编码」这一环完全外置 —— 要么买硬件（串口 AMBE3000），
> 要么另外下载插件（md380_vocoder 等）。
>
> 这意味着「把 DroidStar 的发射功能搬过来」在**编码这一环搬不到东西**，
> 但**插件接口本身就是最大的可复用资产** —— 如果我们实现同样的接口，
> 就能直接吃下生态里所有现成的声码器插件。

---

## 四、内置的信道编码（FEC）—— 全部现成

编译期源码路径暴露了完整的依赖构成（共 102 条路径、7 个顶层项目）：

| 顶层项目 | 文件数 | 说明 |
|---|---|---|
| `jasper-version-4.0.0` | 37 | JPEG-2000 编解码（Qt 图像插件） |
| `harfbuzz-7.1.0` | 24 | 文字整形（**据此判定 Qt 版本为 6.5.x**） |
| `3rdparty`（Qt 内建） | 20 | double-conversion 等 |
| **`DroidStar`** | **17** | **本项目的编解码核心（见下表）** |
| `gcc-11.3.0.build_` | 1 | MinGW-w64 v10.0.0 winpthreads |

其中 `DroidStar/` 目录下的 17 个文件，就是 DMR / D-Star / YSF / M17 的编码链：

| 源文件 | 算法 | 在 DMR 中的位置 |
|---|---|---|
| `cbptc19696.cpp` | **BPTC(196,96)** | 主信道 FEC（语音帧） |
| `cgolay2087.cpp` | **Golay(20,8,7)** | 声码器帧内 FEC / EMB |
| `chamming.cpp` | **Hamming** | CACH 信道 |
| `crs129.cpp` | **RS(12,9)** | 帧终结符 |
| `CRCenc.cpp` | CRC | 校验 |
| `SHA256.cpp` | SHA-256 | 呼号哈希（M17 等） |
| `Golay24128.cpp` | Golay(24,12,8) | D-Star |
| `M17Convolution.cpp` / `YSFConvolution.cpp` / `YSFFICH.cpp` | 卷积码 / FICH | M17 / YSF |
| `m17.cpp` / `ysf.cpp` | 模式实现 | M17 / YSF |
| `codec2/*.cpp`（5 个） | **Codec2** | M17 声码器 |

> **DMR 的整条 FEC 链都有开源实现，这部分不需要我们自己写。**
> 这些文件源自 MMDVMHost 的 `Common/` 与 `DMR/` 目录。

---

## 五、调制解调器路径

```
Going MMDVM_DIRECT                        进入直连模式
No modem, cant do MMDVM_DIRECT            无调制解调器时的报错
SerialModem / ModemBaud                   串口调制解调器
MMDVM_MMDVM_HS_Hat                        支持的硬件型号
Received MMDVM_ACK / Received MMDVM_NAK   链路层确认
```

**两种工作方式：**

1. **`MMDVM_DIRECT`** —— 不需要任何硬件，**纯 UDP 直连主站**。这正是我们要走的路。
2. **串口调制解调器** —— 外接 MMDVM_HS Hat 等硬件板。

---

## 六、界面结构（QML）

```
main.qml
 └─ MainTab.qml
     ├─ HostsTab.qml        主站配置
     ├─ SettingsTab.qml     参数设置（呼号 / DMR ID / 声码器 / 调制解调器）
     ├─ LogTab.qml          日志
     └─ AboutTab.qml        关于
```

状态机文案：

```
Connecting to host failed:
Connected to host %1
Connected ping cnt:
Login failed:
Disconnected / ConnectingState / ConnectedState
transmit / TransmittedDataSignal
```

---

## 七、许可证状况

字符串池中存在 GPL 与 LGPL 的完整许可文本（`gnu.org/licenses/gpl-2.0.html`、
`gpl-3.0.html`、`lgpl-3.0.html`、`lgpl.html`），以及标准声明
`along with this program. If not, see <https://www.gnu.org/licenses/>.`

但这些文本**无法区分是 Qt 自身的许可头还是 DroidStar 的**（Qt6 静态链接会带入
大量 LGPL/GPL 文本）。README 仅声明 "for educational and development purposes only"。

> **判定：许可证不明确。** 若考虑复用其代码，需先向作者确认。

---

## 八、对「信标 DMR」项目的意义

### ✅ 好消息

1. **`MMDVM_DIRECT` 模式确认存在** → 我们的 App 也可以纯 UDP 直连主站，
   **用户不需要购买任何硬件热点**。
2. **DMR 的 FEC 链是开源现成的**（BPTC / Golay / Hamming / RS129），可直接移植。
3. **串口 AMBE3000 硬件路径确认可行** → Android 走 **USB OTG** 接
   DVstick 30 / ThumbDV，**音质最好且完全绕开 AMBE+2 专利问题**。
4. DroidStar 的类 API 与配置项命名是一份现成的**接口设计参考**。

### ⚠️ 坏消息（但必须知道）

1. **DroidStar 没有内置软件 AMBE+2 编码器** —— 这一环搬不到东西，
   仍须自行解决（OpenDMR 的 OP25 编码器 / md380_vocoder / 硬件）。
2. **`md380_vocoder` 不在此 exe 中**，需单独获取。
3. 主站地址与端口**全在用户配置里**，没有可直接复用的默认值。

### 🎯 建议的下一步

> **⚠️ 本节结论已被 §9 的后续发现部分推翻 —— 见 §9。**
> 声码器插件不仅接口公开，而且**已有预编译的 Android arm64 版本**，
> 且**自包含**（只依赖 libc/libm/libstdc++/libdl）。
> 也就是说「AMBE+2 编码」这个最大障碍，存在现成可用的二进制解法。

**优先做「声码器插件接口对齐」**，而不是先写协议栈。理由：

- DroidStar 的 `load_vocoder_plugin()` / `get_vocoders()` / `set_vocoder()`
  接口一旦被复现，就能直接吃下整个插件生态（含 md380_vocoder 与硬件后端）。
- 这比从零实现 AMBE+2 编码器**成本低一个数量级**，且规避了音质风险。

**需要补充获取的材料（按价值排序）：**

1. **`vocoder_plugin` 的接口定义** —— TTP 格式（`TTP1` / `SSTV` + 类型码）
   与导出函数签名。有源码或头文件最好。
2. **DroidStar 的 Android 构建**（若有）—— 可确认 Android 端的音频采集 API。
3. **`md380_vocoder` 插件本体** —— 判断能否在 Android ARM 上直接加载。

---

## 九、声码器插件接口（已破解）

> 本节是对 §3 的关键补充。§3 确认「DroidStar 自身不含软件编码器，只有插件接口」，
> 本节则把**那个接口的实际形态**从预编译二进制里逆了出来。

### 9.1 插件来源

| 项 | 值 |
|---|---|
| 仓库 | `github.com/wiblingen/droidstar_vocoder_plugin` |
| 下载站 | `pizzanbeer.net/plugins/` |
| 构成 | **mbelib（解码） + OP25 imbe_vocoder（编码）** |
| 加载方式 | `dlopen()`（Windows 为 `LoadLibrary`） |
| 授权 | **GPL-3.0-or-later**（已由官方 `vocoder_plugin.h` 版权头确认，见 §9.1.1） |

DroidStar 的加载方式（官方文档）：在界面 **Vocoder URL** 字段粘贴插件地址 →
按 **Download vocoder** → 程序下载后放进配置目录并 `dlopen`。
这也解释了 §3 里 `get_vocoders()` / `load_vocoder_plugin()` / `set_vocoder()`
三个符号的用途。

### 9.1.1 许可证详情（已核实）

**mbelib** —— 代码本身是宽松许可（源文件头为 ISC 式的
"Permission to use, copy, modify, …"），**但 README 顶部有一段显式的专利声明**，
原文如下：

> **PATENT NOTICE**
>
> This source code is provided for educational purposes only. It is
> a written description of how certain voice encoding/decoding
> algorythims could be implemented. Executable objects compiled or
> derived from this package may be covered by one or more patents.
> Readers are strongly advised to check for any patent restrictions or
> licencing requirements before compiling or using this source code.

**这段声明直接命中我们的场景** —— 它说的就是「由本包编译或派生的可执行对象
可能受一项或多项专利保护」。我们准备做的正是加载这样一个派生二进制。

**OP25**（osmocom / boatbod）为 GPL-2.0 系，`imbe_vocoder` 来自该项目。

**合并判定**：mbelib 宽松 + OP25 GPL-2.0 → 该插件作为整体**推定 GPL-2.0**。
若集成进「信标 DMR」并对外分发，需评估 GPL 传染；仅本机自用则风险不同。

> **实践建议**：把声码器插件做成**运行时可选下载**（就像 DroidStar 自己做的那样），
> 而不是打包进 APK 分发。这样主程序与插件保持分离，
> 既规避 GPL 传染，也符合 DroidStar 的既有做法。

### 9.2 可用平台（全部为预编译产物）

```
vocoder_plugin.linux.x86_64
vocoder_plugin.linux.arm          (RaspiOS/Raspbian)
vocoder_plugin.darwin.x86_64
vocoder_plugin.winnt.x86_64
vocoder_plugin.winnt.i386
vocoder_plugin.ios.arm
vocoder_plugin.ios.arm64
vocoder_plugin.android.arm        ← 已归档
vocoder_plugin.android.arm64      ← 已归档
```

### 9.3 插件入口点（`extern "C"`，`dlopen` 用）

从 `.dynsym` 逆出，**arm64 与 x86_64 两个构建完全一致**：

```c
extern "C" VocoderPlugin* create();
extern "C" void           destroy(VocoderPlugin*);
```

### 9.3.1 意外收获：插件带着完整 DWARF 调试信息

检查节表时发现该插件**没有被 strip**，带全套调试节：

```
.debug_info  .debug_abbrev  .debug_str  .debug_line  .debug_frame
.debug_loc   .debug_ranges  .symtab     .strtab
```

`.debug_info` 有 48,837 字节、4,235 个 DIE、7 个编译单元。
**这等于附赠了一份头文件** —— 签名、返回类型、成员布局、虚函数声明顺序
全部可以直接读出来，不需要反汇编、不需要猜。

**编译器与构建路径：**

```
Android (6454773 based on r365632c2) clang version 9.0.8 (LLVM 9.0.8svn)
/mnt/data/src/vocoder_plugin
```

**26 个源文件全部暴露**（即插件的真实构成）：

```
vocoder_plugin.cpp                  ← 插件本体（DroidStar 侧的封装）
imbe_vocoder.cc / imbe_vocoder_impl.cc
encode.cc / decode.cc / aux_sub.cc / basicop2.cc / math_sub.cc
ch_decode.cc / ch_encode.cc / dc_rmv.cc / dsp_sub.cc
pe_lpf.cc / pitch_est.cc / pitch_ref.cc / qnt_sub.cc / rand_gen.cc
sa_decode.cc / sa_encode.cc / sa_enh.cc / tbls.cc
uv_synt.cc / v_synt.cc / v_uv_det.cc
mbelib.c / ambe3600x2400.c / ambe3600x2450.c / ecc.c
```

> 前 25 个是 **OP25 的 `imbe_vocoder` 源码树**，后 4 个是 **mbelib**。
> 所以这个插件 = OP25 声码器 + mbelib 解码 + 一层封装。

### 9.4 `VocoderPlugin` 类接口（DWARF 精确还原）

**对象大小 193,664 字节。**

**数据成员（含偏移）：**

| 偏移 | 类型 | 名称 |
|---|---|---|
| 8 | `class imbe_vocoder` | `vocoder` |
| 16 | `struct mbelibParms *` | `m_mbelibParms` |
| 24 | `int` | `m_errs2` |
| 28 | `char[]` | `m_err_str` |
| 92 | `float[]` | `m_audio_out_temp_buf` |
| 736 | `float *` | `m_audio_out_temp_buf_p` |
| 744 | `float[]` | `m_aout_max_buf` |
| 1544 | `float *` | `m_aout_max_buf_p` |
| 1552 | `int` | `m_aout_max_buf_idx` |
| 1556 | `short[]` | `m_audio_out_buf` |
| 193560 | `short *` | `m_audio_out_buf_p` |
| 193568 | `int` | `m_audio_out_nb_samples` |
| 193572 | `int` | `m_audio_out_buf_size` |
| 193576–193600 | `const int *` | `w` `x` `y` `z` |
| 193608 | `char[]` | `ambe_d` |

**成员函数（按 DWARF 声明顺序，即虚函数 vtable 顺序）：**

```cpp
class VocoderPlugin {
public:
    VocoderPlugin();
    virtual ~VocoderPlugin();

    /* ---- 六个 virtual（走 vtable）---- */
    virtual void decode_2400x1200(int16_t *, uint8_t *);
    virtual void decode_2450x1150(int16_t *, uint8_t *);
    virtual void decode_2450     (int16_t *, uint8_t *);
    virtual void encode_2400x1200(int16_t *, uint8_t *);
    virtual void encode_2450x1150(int16_t *, uint8_t *);
    virtual void encode_2450     (int16_t *, uint8_t *);

    /* ---- 非 virtual（直接符号调用）---- */
    void   initMbeParms();
    void   process_2400x1200(unsigned char *);
    void   process_2450x1150(unsigned char *);
    void   process_2450     (unsigned char *);
    void   processData(char *);
    short *getAudio(int &);          // ← 返回 short*，不是 int
    void   resetAudio();
    void   processAudio();
};
```

**两处纠正**（来自 DWARF，此前按符号名推测有误）：

1. **`getAudio(int&)` 返回 `short *`**，不是 `int`。
   结合成员布局可确定语义：它返回 `m_audio_out_buf_p`，
   并通过引用参数写回 `m_audio_out_nb_samples`（采样数）。
2. **六个编解码方法都是 `virtual`**，返回类型均为 `void`
   （PCM 与码字都是原地读写，不靠返回值传递）。

> **`encode_2450x1150()` 就是 DMR 需要的那个 AMBE+2 编码器。**
> 它同时支持 `2450`（DMR/YSF/NXDN）与 `2400x1200`（IMBE/P25），编解码成对提供。
> 帧长：20 ms = 8 kHz 下 160 个 `int16` 采样 → 9 字节码字。

### 9.4.1 关键工程结论：用 `dlsym`，不要声明 C++ 类

六个方法是 `virtual`，调用会走 vtable。**如果我们自己声明的虚函数顺序与插件不一致，
就会静默调到错误的函数上** —— 不崩溃、不报错，只产生垃圾数据。

**但所有方法（含虚函数）在 `.dynsym` 里都是导出符号**，例如：

```
_ZN13VocoderPlugin16encode_2450x1150EPsPh    FUNC  WEAK
_ZN13VocoderPlugin11processDataEPc           FUNC  WEAK
_ZN13VocoderPlugin8getAudioERi               FUNC  WEAK
```

所以正确接法是：**不声明类，逐个 `dlsym` 取裸函数指针，把 `this` 当第一个参数传。**
这样完全绕开 vtable 布局问题。

已按此结论写出可直接使用的头文件：
**`vendor/droidstar-vocoder-plugin/vocoder_plugin_abi.h`**

### 9.4.2 官方头文件（第二来源，交叉验证通过）

DroidStar 仓库根目录就有接口头文件，已取得并归档：

```
vendor/droidstar-vocoder-plugin/vocoder_plugin.h
1788 字节 · sha256 前缀 9b1feed2e38d3de9
```

原文（逐字）：

```cpp
/*
	Copyright (C) 2019-2021 Doug McLain

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	...
*/

class Vocoder
{
public:
	Vocoder() {}
	virtual ~Vocoder() {}
	virtual void decode_2400x1200(int16_t *pcm, uint8_t *codec) = 0;	// Provide pointer to 72 bits as codec, returns 160 S_16_BE PCM frames as pcm
	virtual void decode_2450x1150(int16_t *pcm, uint8_t *codec) = 0;	// Provide pointer to 72 bits as codec, returns 160 S_16_BE PCM frames as pcm
	virtual void decode_2450(int16_t *pcm, uint8_t *codec) = 0;			// Provide pointer to 49 bits as codec, returns 160 S_16_BE PCM frames as pcm
	virtual void encode_2400x1200(int16_t *pcm, uint8_t *codec) = 0;	// Provide pointer to 160 S_16_BE PCM frames as pcm, returns pointer to 72 bits as codec
	virtual void encode_2450x1150(int16_t *pcm, uint8_t *codec) = 0;	// Provide pointer to 160 S_16_BE PCM frames as pcm, returns pointer to 72 bits as codec
	virtual void encode_2450(int16_t *pcm, uint8_t *codec) = 0;			// Provide pointer to 160 S_16_BE PCM frames as pcm, returns pointer to 49 bits as codec
};

typedef Vocoder* create_t();
typedef void destry_t(Vocoder *);
```

**交叉验证结果：六个虚函数的声明顺序，DWARF 与官方头文件完全一致。**

| 槽位 | 方法（两来源一致） |
|---|---|
| 0 | `decode_2400x1200` |
| 1 | `decode_2450x1150` |
| 2 | `decode_2450` |
| 3 | `encode_2400x1200` |
| 4 | **`encode_2450x1150`** ← DMR 需要的那个 |
| 5 | `encode_2450` |

**官方注释同时给出了确切语义**（此前只能推测）：

- `encode_2450x1150` / `decode_2450x1150` —— **72 bits（9 字节）** ↔ 160 个 PCM 帧
- `encode_2450` / `decode_2450` —— **49 bits**（= 2450 bps × 20 ms，不带 FEC 的裸话音位）

即 **72 bits = 9 字节 = DMR 的 20 ms 语音帧**（2450 bps 话音 + 1150 bps FEC），
与 §9.4 的推算一致。

**两处细节：**

- 基类名是 `Vocoder`，而插件实现类叫 `VocoderPlugin`（继承自 `Vocoder`）。
  这解释了为什么 DWARF 里只有 `VocoderPlugin` 的六个方法，
  而符号名带 `13VocoderPlugin` 前缀。
- 上游原文的 typedef 拼写是 **`destry_t`**（少了一个 o），是源码里的笔误，非本文转录错误。

### 9.4.3 ⚠️ 上游已弃用此机制

DroidStar 在 **2025-10-19** 的提交中移除了整个插件系统：

```
Remove vocoder plugin system and skip ASL web authentication when using IAX direct
```

涉及 `android` / `mbe` / `iax` / `p25` / `SettingsTab.qml` / `ysf` 等文件。

**当前 DroidStar 采用的替代方案：**

| 方案 | 说明 |
|---|---|
| **`USE_MD380_VOCODER`** | 编译期选项，需安装 `md380_vocoder` 库（arm / dynarmic）。**README 明确标注专利合规风险** |
| **串口 AMBE 硬件** | `serialambe.cpp`，支持 ThumbDV / DVstick 30 / DVSI 等 USB 设备 |
| **内置 imbe_vocoder** | 仓库内 `imbe_vocoder/` 目录，覆盖 IMBE 系（P25/NXDN） |
| **内置 codec2** | `codec2/` 目录，M17 用 |
| **内置 mbe** | `mbe/` 目录 |

> **对项目的启示**：上游自己放弃了「运行时下载插件」这条路，转向
> 「编译期集成 md380_vocoder + 硬件声码器」。评估本方案时应考虑：
> ① 插件机制虽可用，但已无上游维护；② `md380_vocoder` 那条路
> 上游是明知有专利风险仍保留的（README 里直接写了警告）。
>
> 但注意：**插件的二进制仍然可用**，接口也仍然有效 ——
> 只是要有「自己维护」的心理准备。

### 9.5 内部实现（可选参考）

除插件接口外，还导出了底层实现符号，可用于二次开发：

| 符号 | 说明 |
|---|---|
| `encode_ambe(IMBE_PARAM const*, int*, mbe_parameters*, mbe_parameters*, bool, float)` | AMBE 编码核心（6816 字节，最大函数） |
| `imbe_vocoder::imbe_encode/imbe_decode(short*, short*)` | OP25 IMBE 声码器 |
| `imbe_vocoder_impl::encode/decode(IMBE_PARAM*, short*, short*)` | 实际实现 |
| `imbe_vocoder_impl::{v_synt, uv_synt, dct, idct, fft, autocorr, pitch_ref, v_uv_det, sa_enh}` | 合成/分析各环节 |
| `mbe_dequantizeAmbeParms` / `mbe_dequantizeAmbe2250Parms` / `mbe_dequantizeAmbe2400Parms` | mbelib 解码 |
| `mbe_decodeAmbe2450Parms` / `mbe_decodeAmbe2400Parms` | mbelib 解码 |
| `mbe_printVersion` | 版本查询 |
| `AmbePlusLtable` / `AmbePlusPRBA24` / `AmbePlusPRBA58` / `AmbePlusVuv` / `AmbePlusHOCb5-8` / `AmbePlusDg` | **AMBE+ 量化表** |
| `AmbeLtable` / `AmbePRBA24` / `AmbePRBA58` / `AmbeVuv` / `AmbeHOCb5-8` / `AmbeDg` / `AmbeW0table` | 原版 AMBE 量化表 |

> 注意：**量化表被完整导出**（`AmbePlus*` 系列）。
> 上一轮调研中「AMBE 编码的公开规范缺失、关键在量化表未公开」的说法，
> 在这个插件里不成立 —— 表就在二进制里。

### 9.6 外部依赖（关键：声码器自包含，但需自带 C++ 运行库）

`.dynsym` 的未定义符号只有 **34 个**，全部来自系统库：

```
libc    : malloc free memcpy memset printf fprintf sprintf puts strlen rand abort
libm    : cosf exp expf log log2f logf powf
libstdc++: _Znwm _ZdlPv __cxa_* __gxx_personality_v0
libdl   : dl_iterate_phdr
pthread : pthread_create pthread_mutex_lock/unlock pthread_once   (WEAK)
```

**没有对 `libmbe` / `libimbe_vocoder` 的外部依赖** —— 声码器全部静态链接进插件了。

**但 `DT_NEEDED` 实测（比未定义符号更权威）补充了一个重要细节：**

```
vocoder_plugin.android.arm / .arm64
  NEEDED  libc++_shared.so     ← ⚠️ NDK 的 C++ 运行库，不是系统库！
  NEEDED  libm.so
  NEEDED  libdl.so
  NEEDED  libc.so

vocoder_plugin.linux.x86_64
  NEEDED  libstdc++.so.6
  NEEDED  libm.so.6
  NEEDED  libgcc_s.so.1
  NEEDED  libc.so.6
```

> **修正结论**：声码器本身确实自包含，但**插件依赖 `libc++_shared.so`**，
> 而它不是 Android 系统库，必须由 APK 打包。
>
> 这意味着 NDK 配置必须用 `-DANDROID_STL=c++_shared`，
> **APK 会增大约 1–2 MB / ABI**（相对当前 606 KB 是明显增长）。
> 不能用 `c++_static` —— 插件与本体各持一份 C++ 运行库，
> `dlopen` 场景下易出 ODR / 异常表冲突，极难排查。

### 9.7 归档（本机）

```
vendor/droidstar-vocoder-plugin/
  vocoder_plugin.android.arm         353,248 B  ARM 32-bit   sha256:d0c2885e8ae61b92…
  vocoder_plugin.android.arm64       377,960 B  AArch64      sha256:669507d9494c88ec…
  vocoder_plugin.linux.x86_64        261,984 B  x86-64       sha256:73e6e1b840873e38…
```

### 9.8 对项目的意义（结论更新）

| 原判断 | 更新后 |
|---|---|
| AMBE+2 编码是最大障碍，只能自己实现或买硬件 | **已有现成的、自包含的 Android arm64 二进制**，`dlopen` 即可 |
| 需要评估 OpenDMR 的编码器质量 | 可先直接复用这个插件，质量与 DroidStar 用户听到的一致 |
| 需要从零写 JNI + NDK | 仍需一层 C++ shim（因为 `VocoderPlugin` 是 C++ 类，不是纯 C），但**不需要自己实现声码器** |

**新增的两个注意点：**

1. **许可证**：该插件推定 GPL（OP25 派生）。若要集成进「信标 DMR」并分发，
   需要评估是否接受 GPL 传染 —— 或者只做本地自用。
2. **专利**：AMBE+2 的美国专利仍未到期（§见前期调研，约 2028）。
   使用预编译二进制不改变专利风险，只改变了「技术可行性」。

> **剩下要做的**：写一层 C++ JNI shim 把 `create()` / `processData()` / `getAudio()`
> 暴露给 Java，再实现 MMDVM UDP 客户端。**声码器这一环不再是未知数。**

---

*报告生成：基于静态字符串与符号分析，未运行该程序，未使用网络请求。*
