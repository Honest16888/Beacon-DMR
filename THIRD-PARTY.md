# 第三方组件与许可声明

本项目（信标 DMR / Beacon DMR）以 **GPL-3.0** 发布，因为它在实现上移植、链接或
分发了下列 GPL 组件。这些组件的著作权归各自作者所有，本项目的许可**不覆盖**它们。

| 组件 | 许可 | 在本项目中的形态 | 来源 |
|---|---|---|---|
| **DroidStar** | GPL-3.0-or-later | 发射帧组装逻辑的移植来源（`dmr.cpp` → `cpp/dmrtx_jni.cpp`） | <https://github.com/nostar/DroidStar-Android> |
| **MMDVMHost**（G4KLX） | GPL-2.0-or-later | FEC 编解码：RS(12,9) / BPTC(196,96) / Golay(20,8,7) / Hamming / CRC | <https://github.com/g4klx/MMDVMHost> |
| **droidstar_vocoder_plugin** | GPL-3.0-or-later | 预编译动态库 `libdsvocoder.so`（arm64-v8a / armeabi-v7a），通过 `dlopen` 加载 | <https://github.com/wiblingen/droidstar_vocoder_plugin> |
| **mbelib** | GPL-2.0 | AMBE 解码，间接经由上述声码器插件 | <https://github.com/szechyjs/mbelib> |

## 关于 `libdsvocoder.so`

`android/app/src/main/jniLibs/` 与 `third_party/droidstar-vocoder-plugin/` 下的
`.so` 是 **wiblingen/droidstar_vocoder_plugin 的预编译产物**，不是本仓库编译的，
仓库内也不包含它的源码。按 GPL-3.0，分发二进制时须能提供对应源码 —— 上游仓库即为
源码出处，请从上面的链接获取。

这两个文件是**运行时必需**的：没有它们，DMR 语音无法编解码，App 只能显示界面。
因此它们被纳入仓库而不是走 `.gitignore`。

`vocoder_plugin.h` / `vocoder_plugin_abi.h` 是插件的接口头文件，随上游一同分发。

## 专利声明（重要）

AMBE+2 编解码器涉及 **SoftBank / DVSI** 的专利。mbelib 的 README 明确写着：

> Executable objects compiled or derived from this package may be covered by one or more patents.

即编译产物可能落入专利覆盖范围。现状：

- **美国**：相关专利约 **2028 年**到期
- **欧洲、加拿大**：2024 年已到期
- **中国**：未申请
- **日本**：被驳回

DroidStar 上游在 2025-10-19 移除了插件系统，转向专利合规风险更低的
`USE_MD380_VOCODER` 方案，可见原作者对此持谨慎态度。

**结论**：个人自用一般无碍；**若要公开分发本项目的二进制（APK），请自行评估专利风险**。
本仓库只提供源码，不对由此产生的专利责任负责。

## 数据来源

| 数据 | 用途 | 性质 |
|---|---|---|
| BrandMeister HOSE | 国际网络语音流（`wss://hose.brandmeister.network/spotter/?token=test:test`） | 公开接口，固定 token |
| BrandMeister API | 实时活动、主站列表 | 公开接口 |
| RadioID.net | DMR ID ⇄ 呼号 对照、中国台站库 | 公开数据（CC BY-SA） |
| China DMR（radiowo） | 国内网络语音流 | 公开接口 |

话组库中内嵌的中国台站数据由 `tools/gen_cn_db.py` 从 RadioID.net 全量库抽取生成。
这些数据属于**公开的业余无线电执照信息**，不是隐私数据。

## 免责

本项目是业余无线电爱好者的第三方客户端，**与 BrandMeister、DroidStar、MMDVMHost、
RadioID.net 及任何设备厂商均无隶属关系**，也未获其背书。

发射功能需要你在 BrandMeister SelfCare 自行登记热点并设置密码，并且**必须持有有效的
业余无线电操作证书**。无证发射违反无线电管理条例。
