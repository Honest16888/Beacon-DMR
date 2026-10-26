/*
 * vocoder_plugin_abi.h —— DroidStar 声码器插件的 ABI 声明
 *
 * ══════════════════════════════════════════════════════════════════════
 * 本文件由两个独立来源交叉验证，结论一致：
 *
 *   来源 A（官方头文件）  nostar/DroidStar 的 vocoder_plugin.h
 *                        SHA256 前缀 9b1feed2e38d3de9，1788 字节
 *                        已归档于同目录 vocoder_plugin.h
 *
 *   来源 B（二进制实证）  vocoder_plugin.android.arm64 的 DWARF .debug_info
 *                        4235 个 DIE，还原出 VocoderPlugin 的完整定义
 *
 *   交叉验证结果：六个虚函数的**声明顺序完全一致**（见下），
 *   故 vtable 布局确定无疑。
 * ══════════════════════════════════════════════════════════════════════
 *
 * ⚠️ 许可证：GPL-3.0-or-later
 *    vocoder_plugin.h 头部版权声明为
 *      Copyright (C) 2019-2021 Doug McLain
 *      GNU General Public License v3 or later
 *    插件本体由 OP25（GPL-2.0 系）+ mbelib（宽松但带专利声明）构成。
 *    → 集成并分发需评估 GPL 传染。建议做成「运行时可选下载」，
 *      主程序与插件保持分离（DroidStar 原本就是这么做的）。
 *
 * ⚠️ 专利：mbelib 的 README 有显式 PATENT NOTICE，原文见
 *    DroidStar-逆向分析.md §9.1.1。AMBE+2 美国专利约 2028 到期。
 *
 * ⚠️ 上游已弃用此机制：DroidStar 在 2025-10-19 的提交
 *    "Remove vocoder plugin system and skip ASL web authentication"
 *    中移除了插件系统，改用编译期选项 USE_MD380_VOCODER（README 明确标注
 *    专利合规风险）与串口 AMBE 硬件。评估本方案时需考虑这一点。
 * ══════════════════════════════════════════════════════════════════════
 */

#ifndef DROIDSTAR_VOCODER_PLUGIN_ABI_H
#define DROIDSTAR_VOCODER_PLUGIN_ABI_H

#include <stdint.h>

/* ══════════════════════════════════════════════════════════════════════
 * 1. 官方接口原文（来自 vocoder_plugin.h，逐字复制）
 *
 *    class Vocoder {
 *    public:
 *        Vocoder() {}
 *        virtual ~Vocoder() {}
 *        virtual void decode_2400x1200(int16_t *pcm, uint8_t *codec) = 0;
 *        virtual void decode_2450x1150(int16_t *pcm, uint8_t *codec) = 0;
 *        virtual void decode_2450     (int16_t *pcm, uint8_t *codec) = 0;
 *        virtual void encode_2400x1200(int16_t *pcm, uint8_t *codec) = 0;
 *        virtual void encode_2450x1150(int16_t *pcm, uint8_t *codec) = 0;
 *        virtual void encode_2450     (int16_t *pcm, uint8_t *codec) = 0;
 *    };
 *
 *    typedef Vocoder* create_t();
 *    typedef void destry_t(Vocoder *);      // 注意：上游原文拼写就是 destry_t
 *
 *    ── 官方注释给出的确切语义（原文逐字）──
 *
 *      decode_2400x1200 : 提供指向 72 bits 码字的指针，
 *                         返回 160 个 S_16_BE PCM 帧到 pcm
 *      decode_2450x1150 : 同上，72 bits
 *      decode_2450      : 提供指向 **49 bits** 码字的指针，返回 160 个 PCM 帧
 *      encode_2400x1200 : 提供指向 160 个 S_16_BE PCM 帧的指针，返回 72 bits 码字
 *      encode_2450x1150 : 同上，72 bits
 *      encode_2450      : 同上，返回 **49 bits** 码字
 *
 *    → 72 bits = 9 字节 = DMR 的 20 ms 语音帧（2450 bps 话音 + 1150 bps FEC）
 *    → 49 bits = 2450 bps × 20 ms，不带 FEC 的裸话音位
 * ══════════════════════════════════════════════════════════════════════ */

typedef void *VocoderHandle;    /* 实际是 Vocoder*，此处当不透明指针 */

typedef VocoderHandle (*vocoder_create_fn)(void);
typedef void          (*vocoder_destroy_fn)(VocoderHandle);

#define VOCODER_SYM_CREATE   "create"
#define VOCODER_SYM_DESTROY  "destroy"

/* ══════════════════════════════════════════════════════════════════════
 * 2. 六个虚函数
 *
 *    声明顺序（两来源一致，即 vtable 槽位顺序）：
 *      0  decode_2400x1200
 *      1  decode_2450x1150
 *      2  decode_2450
 *      3  encode_2400x1200
 *      4  encode_2450x1150     ← DMR 需要的那个
 *      5  encode_2450
 * ══════════════════════════════════════════════════════════════════════ */

typedef void (*vocoder_codec_fn)(VocoderHandle self,
                                 int16_t *pcm,        /* 160 samples / 20 ms */
                                 uint8_t *codec);     /* 9 bytes  @2450x1150 */

#define VOCODER_SYM_ENCODE_2450x1150  "_ZN13VocoderPlugin16encode_2450x1150EPsPh"
#define VOCODER_SYM_DECODE_2450x1150  "_ZN13VocoderPlugin16decode_2450x1150EPsPh"
#define VOCODER_SYM_ENCODE_2450       "_ZN13VocoderPlugin11encode_2450EPsPh"
#define VOCODER_SYM_DECODE_2450       "_ZN13VocoderPlugin11decode_2450EPsPh"
#define VOCODER_SYM_ENCODE_2400x1200  "_ZN13VocoderPlugin16encode_2400x1200EPsPh"
#define VOCODER_SYM_DECODE_2400x1200  "_ZN13VocoderPlugin16decode_2400x1200EPsPh"

/* ══════════════════════════════════════════════════════════════════════
 * 3. 插件私有的流式接口（不在官方头文件里，是 wiblingen 插件额外加的）
 *
 *    DWARF 实证签名（注意 getAudio 返回 short*，不是 int）：
 *
 *      void   processData(char *code_word);
 *      void   processAudio();
 *      short *getAudio(int &nb_samples);
 *      void   resetAudio();
 *      void   initMbeParms();
 *      void   process_2450x1150(unsigned char *code_word);
 *      void   process_2450(unsigned char *code_word);
 *      void   process_2400x1200(unsigned char *code_word);
 *
 *    对应的成员变量（DWARF 给出偏移，仅供理解语义）：
 *      short  m_audio_out_buf[]        @1556     输出 PCM 缓冲
 *      short *m_audio_out_buf_p        @193560   当前写指针（getAudio 的返回值）
 *      int    m_audio_out_nb_samples   @193568   getAudio 通过引用写回采样数
 *      int    m_audio_out_buf_size     @193572
 *
 *    整个对象大小 193664 字节。
 * ══════════════════════════════════════════════════════════════════════ */

typedef void   (*vocoder_processData_fn)(VocoderHandle self, char *code_word);
typedef void   (*vocoder_processAudio_fn)(VocoderHandle self);
typedef short *(*vocoder_getAudio_fn)(VocoderHandle self, int *nb_samples);
typedef void   (*vocoder_void_fn)(VocoderHandle self);
typedef void   (*vocoder_processMode_fn)(VocoderHandle self, unsigned char *code_word);

#define VOCODER_SYM_PROCESS_DATA       "_ZN13VocoderPlugin11processDataEPc"
#define VOCODER_SYM_PROCESS_AUDIO      "_ZN13VocoderPlugin12processAudioEv"
#define VOCODER_SYM_GET_AUDIO          "_ZN13VocoderPlugin8getAudioERi"
#define VOCODER_SYM_RESET_AUDIO        "_ZN13VocoderPlugin10resetAudioEv"
#define VOCODER_SYM_INIT_MBE_PARMS     "_ZN13VocoderPlugin12initMbeParmsEv"
#define VOCODER_SYM_PROCESS_2450x1150  "_ZN13VocoderPlugin17process_2450x1150EPh"
#define VOCODER_SYM_PROCESS_2450       "_ZN13VocoderPlugin12process_2450EPh"
#define VOCODER_SYM_PROCESS_2400x1200  "_ZN13VocoderPlugin17process_2400x1200EPh"

/* ══════════════════════════════════════════════════════════════════════
 * 4. 接法：用 dlsym，不要声明 C++ 类
 *
 *    六个编解码方法是 virtual，调用会走 vtable。虽然现在已经通过两个来源
 *    确认了顺序，但**自己声明类仍有风险**（编译器差异、未来插件改版）。
 *
 *    好在所有方法（含虚函数）在 .dynsym 里都是导出符号，所以更稳的接法是：
 *    逐个 dlsym 取裸函数指针，把 this 当第一个参数传。完全绕开 vtable。
 * ══════════════════════════════════════════════════════════════════════ */

/* ══════════════════════════════════════════════════════════════════════
 * 5. 依赖：声码器自包含，但**必须自带 libc++_shared.so**
 *
 *    DT_NEEDED 实测（权威，比未定义符号更准）：
 *
 *      vocoder_plugin.android.arm / .arm64
 *        NEEDED  libc++_shared.so     ← ⚠️ 关键！NDK 的 C++ 运行库
 *        NEEDED  libm.so
 *        NEEDED  libdl.so
 *        NEEDED  libc.so
 *
 *      vocoder_plugin.linux.x86_64
 *        NEEDED  libstdc++.so.6
 *        NEEDED  libm.so.6
 *        NEEDED  libgcc_s.so.1
 *        NEEDED  libc.so.6
 *
 *    ✅ 好消息：**没有 libmbe / libimbe_vocoder 等声码器库依赖**
 *       —— 声码器已全部静态链进插件，这部分确实自包含。
 *
 *    ⚠️ 但注意：**`libc++_shared.so` 不是 Android 系统库**，
 *       它是 NDK 提供的 C++ 运行库，必须由 APK 自己打包。
 *
 *    → 在 app/build.gradle 的 NDK 配置里用 `c++_shared` STL：
 *
 *        android {
 *            defaultConfig {
 *                externalNativeBuild { cmake { cppFlags "-std=c++17" } }
 *            }
 *            externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }
 *            packagingOptions { jniLibs { useLegacyPackaging = false } }
 *        }
 *
 *      并且确保 NDK 的 STL 设为 shared：
 *        -DANDROID_STL=c++_shared
 *
 *    → 代价：APK 会增大约 1–2 MB / ABI（libc++_shared.so 的体积）。
 *      对当前 606 KB 的 APK 来说是明显增长，但不可避免。
 *
 *    → **不要**改用 c++_static：插件与本体会各持一份 C++ 运行库，
 *      dlopen 场景下容易出现 ODR / 异常表冲突，很难排查。
 * ══════════════════════════════════════════════════════════════════════ */

/* ══════════════════════════════════════════════════════════════════════
 * 6. 用法骨架
 * ══════════════════════════════════════════════════════════════════════
 *
 *   void *h = dlopen("/data/data/<pkg>/files/vocoder_plugin.android.arm64",
 *                    RTLD_NOW | RTLD_LOCAL);
 *   if (!h) { LOGE("dlopen: %s", dlerror()); return; }
 *
 *   vocoder_create_fn  create  = (vocoder_create_fn) dlsym(h, VOCODER_SYM_CREATE);
 *   vocoder_destroy_fn destroy = (vocoder_destroy_fn)dlsym(h, VOCODER_SYM_DESTROY);
 *   vocoder_codec_fn   enc     = (vocoder_codec_fn)  dlsym(h, VOCODER_SYM_ENCODE_2450x1150);
 *   vocoder_codec_fn   dec     = (vocoder_codec_fn)  dlsym(h, VOCODER_SYM_DECODE_2450x1150);
 *
 *   VocoderHandle v = create();
 *
 *   // 发射：160 个采样 → 9 字节 AMBE+2
 *   int16_t pcm[160];
 *   uint8_t frame[9];
 *   enc(v, pcm, frame);
 *
 *   // 接收：9 字节 → 160 个采样
 *   uint8_t rx[9];
 *   int16_t out[160];
 *   dec(v, out, rx);
 *
 *   destroy(v);
 *   dlclose(h);
 *
 * ══════════════════════════════════════════════════════════════════════ */

/* ══════════════════════════════════════════════════════════════════════
 * 7. 待实测确认的两点
 *
 *    ① **字节序**：官方注释写的是 "S_16_BE"（signed 16-bit big-endian），
 *       但参数类型是 int16_t*（宿主序）。两者矛盾，需在真机上喂一段
 *       已知 PCM 实测确认是否需要 byte-swap。
 *
 *    ② **采样率**：DMR 语音是 8 kHz。160 samples / 20 ms 正好对应 8 kHz，
 *       与注释自洽，但仍建议实测确认（部分实现内部用 16 kHz 中间率）。
 * ══════════════════════════════════════════════════════════════════════ */

#endif /* DROIDSTAR_VOCODER_PLUGIN_ABI_H */
