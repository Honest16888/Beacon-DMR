/*
 * vocoder_jni.cpp —— 声码器插件的 JNI 封装 + 自检（冒烟测试）
 *
 * 设计要点（依据 vocoder_plugin_abi.h 的结论）：
 *   1. 全部通过 dlsym 取函数指针，**不声明 C++ 类** —— 插件的方法是 virtual，
 *      自己声明类的虚函数顺序一旦不一致，就会静默调到错误的函数上。
 *   2. 用 RTLD_LOCAL，避免插件的符号污染宿主命名空间。
 *   3. 声码器对象有状态（m_audio_out_buf 等），全程用互斥锁保护。
 *   4. **编码与解码各持一个实例**。上游 MBEVocoder 的 m_cur_mp / m_prev_mp
 *      被编码器的谱预测和解码器同时读写；共用一个实例会让「刚收完就发」
 *      时编码器从对端的参数状态起步，头几帧失真。分开后互不干扰。
 *      代价是每个实例约 190 KB 内存，可接受。
 *
 * 自检（probe）要回答：
 *   Q1 dlopen 能否成功？
 *   Q2 四个符号能否解析、create() 是否返回有效指针？
 *   Q3 编码是否只写 9 字节（不越界）？
 *   Q4 测试信号是什么？
 *   Q5 编码器是否真的读取输入？（静音/语音/噪声三种输入码字是否不同）
 *   Q6 解码器是否产出声音？（静音码字 RMS≈0，有声码字 RMS 显著大于 0）
 *   Q7 包络相关性 + 编解码延迟 + 字节序交叉验证
 *   Q8 解码输出的主频是否保留（Goertzel）
 *   Q9 编码是否确定（同输入同初态跑两遍）
 */

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <pthread.h>

#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "vocoder_plugin_abi.h"

/* M_PI 是 POSIX 扩展，部分工具链不默认提供 */
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "BeaconVoc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* DMR: 20 ms @ 8 kHz = 160 samples → 9 bytes (72 bits) */
static const int PCM_FRAME_SAMPLES = 160;
static const int CODEC_FRAME_BYTES = 9;

/* 插件在 APK 里的 soname。重命名是必须的：
 * Android 只解压 lib 目录下、以 "lib" 开头且以 ".so" 结尾的文件，
 * 原名 vocoder_plugin.android.arm64 不含 lib 前缀，不会被打进包。 */
static const char *VOCODER_LIB_SONAME = "libdsvocoder.so";

namespace {

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ★ 编码、解码各一把锁，不共用。
 *
 * 原来两者共用 g_lock —— 等于把「本来已经分开的两个实例」又串行化了。
 * 文件头注释第 4 条专门说了「编码与解码各持一个实例，分开后互不干扰」，
 * 但一把全局锁让这个设计白做了。
 *
 * 后果是实打实的：发射线程每 20 ms 要 encode 一次；接收线程每收一个包要
 * decode **3 次**（DmrRx.decode 里的 3 帧循环），包是 60 ms 一个，
 * 同时收多个话组还要乘上话组数。接收一解码，发射就被挡住 ——
 * 发射线程追不上音频时钟，AudioRecord 的缓冲开始堆积，
 * 追上时又把几帧连着发出去。对方听到的就是「话音断续」。
 *
 * 两个实例没有共享状态（见注释 4），各用一把锁是安全的，也不会死锁：
 * 任何路径都不会同时持有两把。 */
pthread_mutex_t g_encLock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_decLock = PTHREAD_MUTEX_INITIALIZER;

void          *g_handle  = nullptr;   /* dlopen 句柄 */
VocoderHandle  g_enc     = nullptr;   /* 编码专用实例 */
VocoderHandle  g_dec     = nullptr;   /* 解码专用实例 */

vocoder_create_fn  g_create  = nullptr;
vocoder_destroy_fn g_destroy = nullptr;
vocoder_codec_fn   g_encode  = nullptr;
vocoder_codec_fn   g_decode  = nullptr;

/* ---- 小工具 ---- */

std::string fmt(const char *f, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof(buf), f, ap);
    va_end(ap);
    return std::string(buf);
}

/* 16 位字节序翻转（只用于自检里的字节序交叉验证） */
int16_t swap16(int16_t v) {
    return (int16_t)(((v & 0x00FF) << 8) | ((v >> 8) & 0x00FF));
}

/* 生成一段正弦。freqHz=0 时生成静音。 */
void makeSine(int16_t *out, int n, int sampleRate, int freqHz, int amp) {
    for (int i = 0; i < n; i++) {
        double v = (freqHz <= 0) ? 0.0
                                 : std::sin(2.0 * M_PI * freqHz * i / sampleRate) * amp;
        out[i] = (int16_t)v;
    }
}

std::string hexDump(const uint8_t *p, int n) {
    std::string s;
    char b[8];
    for (int i = 0; i < n; i++) {
        snprintf(b, sizeof(b), "%02X ", p[i]);
        s += b;
    }
    if (!s.empty() && s.back() == ' ') s.pop_back();
    return s;
}

/* 均方根，用来判断某段输出到底"有没有声音" */
double rmsOf(const int16_t *p, int n) {
    if (n <= 0) return 0.0;
    double s = 0;
    for (int i = 0; i < n; i++) s += (double) p[i] * (double) p[i];
    return std::sqrt(s / n);
}

/* 两个等长码字的比特差异数（汉明距离） */
int hammingBytes(const uint8_t *a, const uint8_t *b, int n) {
    int d = 0;
    for (int i = 0; i < n; i++) {
        uint8_t x = (uint8_t) (a[i] ^ b[i]);
        while (x) { d += (x & 1); x >>= 1; }
    }
    return d;
}

/*
 * 在 b 相对 a 平移 lag 的位置上求归一化相关：lag > 0 表示 b 滞后 a。
 *
 * AMBE 编解码有帧间延迟，解码输出相对输入必然错开若干个采样。
 * 固定位置比较会把延迟误判成"相关性低"，所以必须允许平移后再找峰值。
 */
double corrAtLag(const int16_t *a, const int16_t *b, int n, int lag) {
    int start = (lag < 0) ? -lag : 0;
    int end   = (lag > 0) ? n - lag : n;
    int cnt   = end - start;
    if (cnt < 64) return -1.0;

    double ma = 0, mb = 0;
    for (int i = start; i < end; i++) { ma += a[i]; mb += b[i + lag]; }
    ma /= cnt; mb /= cnt;

    double num = 0, da = 0, db = 0;
    for (int i = start; i < end; i++) {
        double x = a[i] - ma, y = b[i + lag] - mb;
        num += x * y; da += x * x; db += y * y;
    }
    if (da <= 0 || db <= 0) return 0.0;
    return num / std::sqrt(da * db);
}

/* 一段输出里最大的绝对幅度 */
int peakOf(const int16_t *p, int n) {
    int pk = 0;
    for (int i = 0; i < n; i++) {
        int a = p[i] < 0 ? -p[i] : p[i];
        if (a > pk) pk = a;
    }
    return pk;
}

/* ══════════════════════════════════════════════════════════════════════
 * 以下是第二版自检新增的工具。设计依据来自对上游 mbevocoder.cpp 的通读：
 *
 *   ① imbe_vocoder.h 的 imbe_encode 是 P25 IMBE 的定点分析前端，
 *      PITCH_EST_FRAME = 301、PE_LPF_ORD = 21 —— 基音估计用了 301 点窗，
 *      所以编码器对输入有几百个采样的延迟。**单帧固定位置比较必然错位。**
 *
 *   ② encode_ambe() 末尾会调用 mbe_moveMbeParms(cur_mp, prev_mp)，
 *      而解码路径 mbe_processAmbe3600x2450Framef() 也会写这两个参数。
 *      → **同一个实例上交替编解码会互相污染状态**，必须用两个实例。
 *
 *   ③ 上游注释 m_audio_out_buf[2*48000] 明写 "S16LE"，
 *      decode_2450x1150() 又是裸 memcpy(short*) —— **字节序是宿主序**，
 *      不必再靠相关性去猜。
 * ══════════════════════════════════════════════════════════════════════ */

/* 把一段 PCM 压成"每 20 ms 一个 RMS"的幅度包络 */
void envelopeOf(const int16_t *p, int frames, double *env) {
    for (int f = 0; f < frames; f++)
        env[f] = rmsOf(p + f * PCM_FRAME_SAMPLES, PCM_FRAME_SAMPLES);
}

/* 包络域的归一化互相关，允许帧级平移（lag > 0 表示 b 滞后 a） */
double envCorrAtLag(const double *a, const double *b, int n, int lag) {
    int start = (lag < 0) ? -lag : 0;
    int end   = (lag > 0) ? n - lag : n;
    int cnt   = end - start;
    if (cnt < 8) return -2.0;

    double ma = 0, mb = 0;
    for (int i = start; i < end; i++) { ma += a[i]; mb += b[i + lag]; }
    ma /= cnt; mb /= cnt;

    double num = 0, da = 0, db = 0;
    for (int i = start; i < end; i++) {
        double x = a[i] - ma, y = b[i + lag] - mb;
        num += x * y; da += x * x; db += y * y;
    }
    if (da <= 0 || db <= 0) return 0.0;
    return num / std::sqrt(da * db);
}

/* Goertzel：单频能量。用来判断解码输出里还认不认得出原来的音调。 */
double goertzel(const int16_t *p, int n, double freq, double fs) {
    double w  = 2.0 * M_PI * freq / fs;
    double cw = 2.0 * std::cos(w);
    double s1 = 0, s2 = 0;
    for (int i = 0; i < n; i++) {
        double s0 = (double) p[i] + cw * s1 - s2;
        s2 = s1; s1 = s0;
    }
    double mag2 = s1 * s1 + s2 * s2 - cw * s1 * s2;
    return (mag2 > 0) ? std::sqrt(mag2) / n : 0.0;
}

/*
 * 合成一段"类语音"信号：基频 + 多次谐波 + 双共振峰包络 + 音节起伏。
 *
 * 为什么不直接用正弦 —— AMBE 是参数化声码器：
 *   · 它的基音（F0）搜索范围大约 50~400 Hz。1 kHz 纯正弦**根本不在范围内**。
 *   · 它会逐帧判定"有声 / 无声"。孤立的单频正弦不具备语音的谐波结构，
 *     很容易被判成"无声"，解码出来就是噪声。
 * 所以"正弦往返相关度低"完全可能发生在声码器**工作正常**的情况下。
 * 本函数生成的信号带真实语音的谐波与共振峰结构，是有效得多的测试激励。
 */
void makeSpeechLike(int16_t *out, int frames, uint32_t seed, int f0) {
    const double fs = 8000.0;
    double phase = 0.0;
    uint32_t s = seed;

    for (int f = 0; f < frames; f++) {
        /* 每 5 帧（100 ms）换一次幅度，模拟音节的强弱起伏 */
        if (f % 5 == 0) s = s * 1664525u + 1013904223u;
        double amp = 0.20 + 0.80 * (double) ((s >> 16) & 0xFF) / 255.0;

        for (int i = 0; i < PCM_FRAME_SAMPLES; i++) {
            double t = phase + (double) i / fs;
            double v = 0.0;
            for (int k = 1; k <= 24; k++) {
                double fk = (double) f0 * k;
                if (fk > 3400.0) break;
                /* 两个共振峰，让谐波包络像人声 */
                double fo = 1.0
                          + 3.0 * std::exp(-std::pow((fk -  600.0) / 350.0, 2.0))
                          + 2.0 * std::exp(-std::pow((fk - 1600.0) / 500.0, 2.0));
                v += std::sin(2.0 * M_PI * fk * t) / (double) k * fo;
            }
            out[f * PCM_FRAME_SAMPLES + i] = (int16_t) (v * amp * 3000.0);
        }
        phase += (double) PCM_FRAME_SAMPLES / fs;
    }

    /* 归一化到峰值 24000：留 3 dB 余量，避免定点分析前端溢出 */
    int n = frames * PCM_FRAME_SAMPLES;
    int pk = peakOf(out, n);
    if (pk > 0) {
        double g = 24000.0 / (double) pk;
        for (int i = 0; i < n; i++) out[i] = (int16_t) ((double) out[i] * g);
    }
}

/* 固定种子的伪随机噪声（LCG），幅度 amp —— 噪声比正弦更难被声码器"吃掉" */
void makeNoise(int16_t *out, int n, uint32_t seed, int amp) {
    uint32_t s = seed;
    for (int i = 0; i < n; i++) {
        s = s * 1664525u + 1013904223u;
        int v = (int) ((s >> 9) & 0xFFFFu) - 32768;
        out[i] = (int16_t) (v * amp / 32768);
    }
}

}  // namespace

/* ════════════════════════════════════════════════════════════════════
 * Java: net.beacon.dmr.vocoder.Vocoder
 * ════════════════════════════════════════════════════════════════════ */

extern "C" JNIEXPORT jboolean JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    pthread_mutex_lock(&g_lock);

    if (g_enc != nullptr && g_dec != nullptr) {   /* 已初始化 */
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }
    std::string spath(path);
    env->ReleaseStringUTFChars(jpath, path);

    g_handle = dlopen(spath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (g_handle == nullptr) {
        /* 回退路径：若 extractNativeLibs=false（targetSdk 30+ 的默认值），
         * .so 不会解压到 nativeLibraryDir，绝对路径下根本没有这个文件。
         * 此时用短名交给 linker 的 app namespace 处理，它可以直接从 APK 内映射。 */
        const char *firstErr = dlerror();
        LOGW("按绝对路径 dlopen 失败(%s): %s —— 回退到短名 %s",
             spath.c_str(), firstErr ? firstErr : "未知原因", VOCODER_LIB_SONAME);

        g_handle = dlopen(VOCODER_LIB_SONAME, RTLD_NOW | RTLD_LOCAL);
        if (g_handle == nullptr) {
            LOGE("短名 dlopen 也失败: %s", dlerror());
            pthread_mutex_unlock(&g_lock);
            return JNI_FALSE;
        }
    }

    /* 用 dlsym 取入口，不依赖 C++ 类的布局 */
    g_create  = (vocoder_create_fn)  dlsym(g_handle, VOCODER_SYM_CREATE);
    g_destroy = (vocoder_destroy_fn) dlsym(g_handle, VOCODER_SYM_DESTROY);
    g_encode  = (vocoder_codec_fn)   dlsym(g_handle, VOCODER_SYM_ENCODE_2450x1150);
    g_decode  = (vocoder_codec_fn)   dlsym(g_handle, VOCODER_SYM_DECODE_2450x1150);

    if (!g_create || !g_destroy || !g_encode || !g_decode) {
        LOGE("符号缺失: create=%p destroy=%p encode=%p decode=%p",
             (void *)g_create, (void *)g_destroy, (void *)g_encode, (void *)g_decode);
        dlclose(g_handle);
        g_handle = nullptr;
        g_create = nullptr; g_destroy = nullptr; g_encode = nullptr; g_decode = nullptr;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }

    /* 编码、解码各建一个实例：两者的 m_cur_mp / m_prev_mp 互不干扰 */
    g_enc = g_create();
    g_dec = g_create();
    if (g_enc == nullptr || g_dec == nullptr) {
        LOGE("create() 返回空指针 (enc=%p dec=%p)", (void *) g_enc, (void *) g_dec);
        if (g_enc) g_destroy(g_enc);
        if (g_dec) g_destroy(g_dec);
        g_enc = nullptr; g_dec = nullptr;
        dlclose(g_handle);
        g_handle = nullptr;
        g_create = nullptr; g_destroy = nullptr; g_encode = nullptr; g_decode = nullptr;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }

    LOGI("声码器就绪: %s", spath.c_str());
    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeIsReady(JNIEnv *, jclass) {
    return (g_enc != nullptr && g_dec != nullptr) ? JNI_TRUE : JNI_FALSE;
}

/* 发射方向：160 个 int16 采样 → 9 字节 AMBE+2 */
extern "C" JNIEXPORT jint JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeEncode(JNIEnv *env, jclass,
                                                 jshortArray jpcm, jbyteArray jout) {
    if (g_enc == nullptr) return -1;
    if (env->GetArrayLength(jpcm) < PCM_FRAME_SAMPLES) return -2;
    if (env->GetArrayLength(jout) < CODEC_FRAME_BYTES) return -3;

    pthread_mutex_lock(&g_encLock);

    jshort *pcm = env->GetShortArrayElements(jpcm, nullptr);
    if (pcm == nullptr) { pthread_mutex_unlock(&g_encLock); return -4; }

    uint8_t codec[32];
    memset(codec, 0, sizeof(codec));
    g_encode(g_enc, (int16_t *)pcm, codec);

    env->ReleaseShortArrayElements(jpcm, pcm, JNI_ABORT);   /* 只读，不写回 */

    jbyte *out = env->GetByteArrayElements(jout, nullptr);
    if (out == nullptr) { pthread_mutex_unlock(&g_encLock); return -5; }
    memcpy(out, codec, CODEC_FRAME_BYTES);
    env->ReleaseByteArrayElements(jout, out, 0);

    pthread_mutex_unlock(&g_encLock);
    return CODEC_FRAME_BYTES;
}

/* 接收方向：9 字节 AMBE+2 → 160 个 int16 采样 */
extern "C" JNIEXPORT jint JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeDecode(JNIEnv *env, jclass,
                                                 jbyteArray jcodec, jshortArray jpcm) {
    if (g_dec == nullptr) return -1;
    if (env->GetArrayLength(jcodec) < CODEC_FRAME_BYTES) return -2;
    if (env->GetArrayLength(jpcm) < PCM_FRAME_SAMPLES) return -3;

    pthread_mutex_lock(&g_decLock);

    jbyte *codec = env->GetByteArrayElements(jcodec, nullptr);
    if (codec == nullptr) { pthread_mutex_unlock(&g_decLock); return -4; }

    int16_t pcm[PCM_FRAME_SAMPLES];
    memset(pcm, 0, sizeof(pcm));
    g_decode(g_dec, pcm, (uint8_t *)codec);

    env->ReleaseByteArrayElements(jcodec, codec, JNI_ABORT);

    jshort *out = env->GetShortArrayElements(jpcm, nullptr);
    if (out == nullptr) { pthread_mutex_unlock(&g_decLock); return -5; }
    memcpy(out, pcm, sizeof(pcm));
    env->ReleaseShortArrayElements(jpcm, out, 0);

    pthread_mutex_unlock(&g_decLock);
    return PCM_FRAME_SAMPLES;
}

/* ════════════════════════════════════════════════════════════════════
 * 自检 —— 一次回答 Q1~Q9
 * ════════════════════════════════════════════════════════════════════ */
extern "C" JNIEXPORT jstring JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeProbe(JNIEnv *env, jclass, jstring jpath) {
    std::string r;
    r.reserve(8192);

    r += "=== 声码器插件自检 ===\n\n";

    /* ---- Q1: dlopen ---- */
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    std::string spath = path ? path : "";
    if (path) env->ReleaseStringUTFChars(jpath, path);

    r += "Q1 dlopen\n";
    r += fmt("  路径: %s\n", spath.c_str());

    void *h = dlopen(spath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) {
        r += fmt("  ✗ 失败: %s\n\n", dlerror());
        r += "结论：插件无法加载，后续测试全部跳过。\n";
        return env->NewStringUTF(r.c_str());
    }
    r += "  ✓ 成功\n\n";

    /* ---- Q2: 符号与 create ---- */
    r += "Q2 符号解析与 create()\n";
    struct { const char *name; void *fn; const char *label; } syms[] = {
        {VOCODER_SYM_CREATE,          (void *)dlsym(h, VOCODER_SYM_CREATE),          "create"},
        {VOCODER_SYM_DESTROY,         (void *)dlsym(h, VOCODER_SYM_DESTROY),         "destroy"},
        {VOCODER_SYM_ENCODE_2450x1150,(void *)dlsym(h, VOCODER_SYM_ENCODE_2450x1150),"encode_2450x1150"},
        {VOCODER_SYM_DECODE_2450x1150,(void *)dlsym(h, VOCODER_SYM_DECODE_2450x1150),"decode_2450x1150"},
    };
    bool allSym = true;
    for (auto &s : syms) {
        r += fmt("  %s %s\n", s.fn ? "✓" : "✗", s.label);
        if (!s.fn) allSym = false;
    }
    if (!allSym) {
        r += "\n结论：符号缺失，插件与 ABI 头文件不匹配。\n";
        dlclose(h);
        return env->NewStringUTF(r.c_str());
    }

    auto fnCreate  = (vocoder_create_fn)  syms[0].fn;
    auto fnDestroy = (vocoder_destroy_fn) syms[1].fn;
    auto fnEncode  = (vocoder_codec_fn)   syms[2].fn;
    auto fnDecode  = (vocoder_codec_fn)   syms[3].fn;

    /* 自检用独立的实例，不干扰正常使用的 g_enc / g_dec */
    VocoderHandle v = fnCreate();
    if (v == nullptr) {
        r += "  ✗ create() 返回空指针\n\n结论：无法创建声码器对象。\n";
        dlclose(h);
        return env->NewStringUTF(r.c_str());
    }
    r += "  ✓ create() 返回有效指针\n\n";

    /* ══════════════════════════════════════════════════════════════════
     * Q3~Q8 端到端测试
     *
     * 与第一版的区别（第一版的判据是错的，真机上误报为"失败"）：
     *   ① 编、解码各用**独立实例**。MBEVocoder 的 m_cur_mp / m_prev_mp
     *      既被编码器的谱预测读写，也被解码器读写；同一实例交替编解码
     *      会互相污染。（DroidStar 实际是半双工，从不交替，所以没暴露。）
     *   ② 用**长序列**（120 帧 = 2.4 秒）而不是单帧。IMBE 分析前端有
     *      301 点基音估计窗，编解码链路有几百采样延迟，单帧比较必然错位。
     *   ③ 主判据改用**包络相关性**。AMBE 会重建相位，波形对不上是正常的；
     *      它保留的是幅度包络。
     *   ④ 补一个**完全不依赖相关性**的判据：静音码字的解码输出 RMS 应
     *      接近 0，有声码字应显著大于 0。这条最硬。
     *   ⑤ 激励信号换成**类语音**（基频 + 谐波 + 共振峰 + 音节起伏）。
     *      纯正弦既不在 AMBE 的基音范围内、也不具备语音谐波结构，
     *      声码器正常工作时也会把它判成"无声"。
     * ══════════════════════════════════════════════════════════════════ */

    /* 先确认"只写 9 字节"——后面的序列测试把码字紧挨着排布，依赖这一点 */
    {
        int16_t probeIn[PCM_FRAME_SAMPLES];
        makeSine(probeIn, PCM_FRAME_SAMPLES, 8000, 200, 20000);
        uint8_t wide[32];
        memset(wide, 0xA5, sizeof(wide));
        fnEncode(v, probeIn, wide);
        int intact = 0;
        for (int i = CODEC_FRAME_BYTES; i < 32; i++) if (wide[i] == 0xA5) intact++;
        r += "Q3 编码输出边界\n";
        r += fmt("  200Hz 满幅正弦 → 码字: %s\n", hexDump(wide, CODEC_FRAME_BYTES).c_str());
        r += fmt("  第 9 字节之后的 23 字节未被改动: %d/23  %s\n\n",
                 intact, (intact == 23) ? "✓" : "✗ 有越界写，序列测试不可信！");
    }

    /* ---- 准备三种测试信号 ---- */
    const int SEQ_FRAMES  = 120;                                  /* 2.4 秒 */
    const int SEQ_SAMPLES = SEQ_FRAMES * PCM_FRAME_SAMPLES;

    std::vector<int16_t> sigSpeech(SEQ_SAMPLES);
    std::vector<int16_t> sigSilence(SEQ_SAMPLES, 0);
    std::vector<int16_t> sigNoise(SEQ_SAMPLES);
    makeSpeechLike(sigSpeech.data(), SEQ_FRAMES, 12345u, 120);
    makeNoise(sigNoise.data(), SEQ_SAMPLES, 6789u, 20000);

    r += "Q4 测试信号\n";
    r += "  类语音: 基频 120Hz + 24 次谐波 + 双共振峰 + 音节起伏\n";
    r += fmt("  长度  : %d 帧 / %d 采样 / %.1f 秒 @ 8kHz\n",
             SEQ_FRAMES, SEQ_SAMPLES, SEQ_FRAMES * 0.02);
    r += fmt("  峰值  : 语音 %d / 静音 %d / 噪声 %d\n\n",
             peakOf(sigSpeech.data(),  SEQ_SAMPLES),
             peakOf(sigSilence.data(), SEQ_SAMPLES),
             peakOf(sigNoise.data(),   SEQ_SAMPLES));

    /* ---- 编、解码分开两个实例 ---- */
    VocoderHandle vEnc = fnCreate();
    VocoderHandle vDec = fnCreate();
    if (vEnc == nullptr || vDec == nullptr) {
        r += "✗ 无法创建编/解码实例\n结论：create() 不稳定。\n";
        if (vEnc) fnDestroy(vEnc);
        if (vDec) fnDestroy(vDec);
        fnDestroy(v);
        dlclose(h);
        return env->NewStringUTF(r.c_str());
    }

    /* ---- Q5: 编码器是否真的读取输入（决定性） ---- */
    r += "Q5 编码器是否真的读取输入\n";
    std::vector<uint8_t> cwSil(SEQ_FRAMES * CODEC_FRAME_BYTES);
    std::vector<uint8_t> cwSp (SEQ_FRAMES * CODEC_FRAME_BYTES);
    std::vector<uint8_t> cwNs (SEQ_FRAMES * CODEC_FRAME_BYTES);

    for (int f = 0; f < SEQ_FRAMES; f++)
        fnEncode(vEnc, &sigSilence[f * PCM_FRAME_SAMPLES], &cwSil[f * CODEC_FRAME_BYTES]);
    for (int f = 0; f < SEQ_FRAMES; f++)
        fnEncode(vEnc, &sigSpeech [f * PCM_FRAME_SAMPLES], &cwSp [f * CODEC_FRAME_BYTES]);
    for (int f = 0; f < SEQ_FRAMES; f++)
        fnEncode(vEnc, &sigNoise  [f * PCM_FRAME_SAMPLES], &cwNs [f * CODEC_FRAME_BYTES]);

    const int fRef = SEQ_FRAMES - 1;                 /* 取末帧，状态已收敛 */
    const uint8_t *rSil = &cwSil[fRef * CODEC_FRAME_BYTES];
    const uint8_t *rSp  = &cwSp [fRef * CODEC_FRAME_BYTES];
    const uint8_t *rNs  = &cwNs [fRef * CODEC_FRAME_BYTES];
    const int dSilSp = hammingBytes(rSil, rSp, CODEC_FRAME_BYTES);
    const int dSilNs = hammingBytes(rSil, rNs, CODEC_FRAME_BYTES);
    const int dSpNs  = hammingBytes(rSp,  rNs, CODEC_FRAME_BYTES);

    r += fmt("  末帧码字 静音: %s\n", hexDump(rSil, CODEC_FRAME_BYTES).c_str());
    r += fmt("  末帧码字 语音: %s\n", hexDump(rSp,  CODEC_FRAME_BYTES).c_str());
    r += fmt("  末帧码字 噪声: %s\n", hexDump(rNs,  CODEC_FRAME_BYTES).c_str());
    r += fmt("  比特差异 静音↔语音=%d  静音↔噪声=%d  语音↔噪声=%d  (每帧 72 bit)\n",
             dSilSp, dSilNs, dSpNs);
    if (dSilSp == 0 && dSilNs == 0) {
        r += "  ✗ 三种截然不同的输入产出完全相同的码字\n";
        r += "    → 编码器根本没读我们的 PCM，这是 ABI / 调用层面的问题，不是信号问题。\n";
    } else {
        r += "  ✓ 不同输入产出不同码字 → 编码器确实在读输入\n";
    }
    r += "\n";

    /* ---- Q6: 解码器是否产出声音（不依赖相关性） ---- */
    r += "Q6 解码器是否产出声音\n";
    std::vector<int16_t> outSil(SEQ_SAMPLES);
    std::vector<int16_t> outSp (SEQ_SAMPLES);
    std::vector<int16_t> outNs (SEQ_SAMPLES);

    for (int f = 0; f < SEQ_FRAMES; f++)
        fnDecode(vDec, &outSil[f * PCM_FRAME_SAMPLES], &cwSil[f * CODEC_FRAME_BYTES]);
    for (int f = 0; f < SEQ_FRAMES; f++)
        fnDecode(vDec, &outSp [f * PCM_FRAME_SAMPLES], &cwSp [f * CODEC_FRAME_BYTES]);
    for (int f = 0; f < SEQ_FRAMES; f++)
        fnDecode(vDec, &outNs [f * PCM_FRAME_SAMPLES], &cwNs [f * CODEC_FRAME_BYTES]);

    /* 跳过前 20 帧，避开解码器收敛期 */
    const int    SKIP  = 20;
    const int    TAILN = (SEQ_FRAMES - SKIP) * PCM_FRAME_SAMPLES;
    const int16_t *tSil = outSil.data() + SKIP * PCM_FRAME_SAMPLES;
    const int16_t *tSp  = outSp .data() + SKIP * PCM_FRAME_SAMPLES;
    const int16_t *tNs  = outNs .data() + SKIP * PCM_FRAME_SAMPLES;

    const double rmsSil = rmsOf(tSil, TAILN);
    const double rmsSp  = rmsOf(tSp,  TAILN);
    const double rmsNs  = rmsOf(tNs,  TAILN);
    const int    pkSp   = peakOf(tSp, TAILN);

    r += fmt("  尾部 %d 帧 RMS   静音码字→%8.1f   语音码字→%8.1f   噪声码字→%8.1f\n",
             SEQ_FRAMES - SKIP, rmsSil, rmsSp, rmsNs);
    r += fmt("  语音码字解码峰值 %d   (输入语音峰值 %d)\n",
             pkSp, peakOf(sigSpeech.data(), SEQ_SAMPLES));
    r += fmt("  语音码字解码前 8 采样: %d %d %d %d %d %d %d %d\n",
             tSp[0], tSp[1], tSp[2], tSp[3], tSp[4], tSp[5], tSp[6], tSp[7]);

    if (rmsSp < 50.0 && rmsNs < 50.0) {
        r += "  ✗ 解码输出几乎全是静音 → 解码器没工作，或编码器送出的就是静音帧\n";
    } else if (rmsSp > 4.0 * (rmsSil + 1.0)) {
        r += "  ✓ 有声码字的解码输出显著强于静音码字 → 解码器工作\n";
    } else {
        r += "  ⚠ 有输出，但与静音码字差异不明显 → 编码侧可能没送出有效参数\n";
    }
    r += "\n";

    /* ---- Q7: 包络相关性 + 编解码延迟 ---- */
    r += "Q7 包络相关性与编解码延迟\n";
    r += "  把输入与解码输出各压成\"每 20ms 一个 RMS\"的幅度包络，再做帧级平移互相关。\n";

    std::vector<double> envIn(SEQ_FRAMES), envOut(SEQ_FRAMES);
    envelopeOf(sigSpeech.data(), SEQ_FRAMES, envIn.data());
    envelopeOf(outSp.data(),     SEQ_FRAMES, envOut.data());

    double bestE = -2.0; int bestLag = 0;
    for (int lag = -15; lag <= 15; lag++) {
        double c = envCorrAtLag(envIn.data(), envOut.data(), SEQ_FRAMES, lag);
        if (c > bestE) { bestE = c; bestLag = lag; }
    }
    r += fmt("  最佳包络相关 %.4f  @ 输出滞后 %+d 帧 (%+d ms)\n",
             bestE, bestLag, bestLag * 20);
    if (bestE > 0.5)       r += "  ✓ 包络跟随 → 声码器链路整体可用\n";
    else if (bestE > 0.2)  r += "  ⚠ 包络部分跟随 → 声码器大致工作，但质量可能很差\n";
    else                   r += "  ✗ 包络不跟随 → 编解码链路有问题\n";

    /* 波形域也扫一遍，看有没有明显的延迟峰 */
    double bestW = -2.0; int bestWLag = 0;
    for (int lag = -600; lag <= 600; lag += 4) {
        double c = corrAtLag(sigSpeech.data(), outSp.data(), SEQ_SAMPLES, lag);
        if (c > bestW) { bestW = c; bestWLag = lag; }
    }
    r += fmt("  波形相关(±600 采样扫描) 最佳 %.4f @ %+d 采样 (%+.1f ms)\n",
             bestW, bestWLag, bestWLag / 8.0);

    /* 字节序交叉验证：按大端重新解释输出，包络相关应明显变差 */
    std::vector<int16_t> outSpSw(SEQ_SAMPLES);
    for (int i = 0; i < SEQ_SAMPLES; i++) outSpSw[i] = swap16(outSp[i]);
    std::vector<double> envSw(SEQ_FRAMES);
    envelopeOf(outSpSw.data(), SEQ_FRAMES, envSw.data());
    double bestSw = -2.0;
    for (int lag = -15; lag <= 15; lag++) {
        double c = envCorrAtLag(envIn.data(), envSw.data(), SEQ_FRAMES, lag);
        if (c > bestSw) bestSw = c;
    }
    r += fmt("  交叉验证: 把输出按大端解释后包络相关 %.4f（应明显低于宿主序）\n", bestSw);
    r += "\n";

    /* ---- Q8: 解码输出的主频是否保留 ---- */
    r += "Q8 解码输出的主频（Goertzel 单频能量）\n";
    const int    G_FRAMES = 40;
    const int16_t *gp = outSp.data() + (SEQ_FRAMES - G_FRAMES) * PCM_FRAME_SAMPLES;
    const int    gn = G_FRAMES * PCM_FRAME_SAMPLES;
    const double cand[] = {120, 150, 200, 240, 300, 400, 600, 800, 1000, 1500, 2000};
    double gBest = -1.0, gBestF = 0.0;
    std::string gline;
    for (double cf : cand) {
        double e = goertzel(gp, gn, cf, 8000.0);
        if (e > gBest) { gBest = e; gBestF = cf; }
        gline += fmt("%.0f:%.0f  ", cf, e);
    }
    r += fmt("  能量: %s\n", gline.c_str());
    r += fmt("  最大能量落在 %.0f Hz（输入基频 120 Hz）\n", gBestF);
    if (gBestF >= 100.0 && gBestF <= 260.0)
        r += "  ✓ 主频落在合理范围 → 音调被保留\n";
    else
        r += "  ⚠ 主频偏离 120 Hz 较多 → 基音估计可能没收敛（120 帧下本不该如此）\n";
    r += "\n";

    /* ---- Q9: 编码确定性 ---- */
    r += "Q9 编码确定性\n";
    VocoderHandle vEnc2 = fnCreate();
    if (vEnc2 != nullptr) {
        std::vector<uint8_t> cwSp2(SEQ_FRAMES * CODEC_FRAME_BYTES);
        for (int f = 0; f < SEQ_FRAMES; f++)
            fnEncode(vEnc2, &sigSpeech[f * PCM_FRAME_SAMPLES], &cwSp2[f * CODEC_FRAME_BYTES]);
        int diff = 0;
        for (int f = 0; f < SEQ_FRAMES; f++)
            diff += hammingBytes(&cwSp[f * CODEC_FRAME_BYTES],
                                 &cwSp2[f * CODEC_FRAME_BYTES], CODEC_FRAME_BYTES);
        r += fmt("  同输入、同初态跑两遍，%d 帧累计比特差异 %d / %d\n",
                 SEQ_FRAMES, diff, SEQ_FRAMES * 72);
        r += (diff == 0) ? "  ✓ 完全确定\n" : "  ⚠ 存在抖动\n";
        fnDestroy(vEnc2);
    }
    r += "\n";

    /* ---- 结论 ---- */
    r += "  ---- 结论 ----\n";
    if (dSilSp == 0 && dSilNs == 0) {
        r += "  ✗ 编码器没有读输入 —— ABI / 调用层面的问题，不是信号问题。\n";
    } else if (rmsSp < 50.0) {
        r += "  ✗ 解码器没有输出 —— 问题在解码侧或码字格式。\n";
    } else if (bestE > 0.5) {
        r += "  ✓ 声码器链路整体正常：编码器读输入、解码器有输出、包络跟随。\n";
        r += "    字节序 = 宿主序（上游 m_audio_out_buf 注释明写 S16LE，\n";
        r += "    且 decode_2450x1150 是裸 memcpy(short*)）。无需 byte-swap。\n";
        r += "    → 可以进入真机通联测试。\n";
    } else {
        r += "  ⚠ 编码器、解码器都活着，但包络不跟随。\n";
        r += "    最可能的原因：输入幅度不合适（本测试已用峰值 24000），\n";
        r += "    或这个软件声码器的合成质量本身就很差。\n";
        r += "    请把本报告完整发回。\n";
    }

    fnDestroy(vEnc);
    fnDestroy(vDec);
    fnDestroy(v);
    dlclose(h);

    r += "\n=== 自检结束 ===\n";
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_net_beacon_dmr_vocoder_Vocoder_nativeRelease(JNIEnv *, jclass) {
    /* 三把锁都拿住再释放实例 —— 否则正在 encode/decode 的线程会用到已 free 的对象。
     * 加锁顺序固定为 g_lock → g_encLock → g_decLock，而 encode/decode 只拿自己那一把，
     * 不构成环，不会死锁。 */
    pthread_mutex_lock(&g_lock);
    pthread_mutex_lock(&g_encLock);
    pthread_mutex_lock(&g_decLock);
    if (g_destroy) {
        if (g_enc) { g_destroy(g_enc); g_enc = nullptr; }
        if (g_dec) { g_destroy(g_dec); g_dec = nullptr; }
    }
    if (g_handle) {
        dlclose(g_handle);
        g_handle = nullptr;
    }
    g_create = nullptr; g_destroy = nullptr; g_encode = nullptr; g_decode = nullptr;
    pthread_mutex_unlock(&g_decLock);
    pthread_mutex_unlock(&g_encLock);
    pthread_mutex_unlock(&g_lock);
}
