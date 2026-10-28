package net.beacon.dmr.mmdvm.vocoder;

/**
 * DroidStar 声码器插件（droidstar_vocoder_plugin）的 Java 封装。
 *
 * <p>插件本体是 wiblingen/droidstar_vocoder_plugin 的预编译产物，
 * 以 libdsvocoder.so 的名字随 APK 分发（必须带 lib 前缀，否则 Android 不会解压）。
 * 它由 OP25 的 imbe_vocoder（编码）+ mbelib（解码）+ 一层封装构成，GPL-3.0-or-later。
 *
 * <p>接口来自插件官方头文件 vocoder_plugin.h，并与上游 DroidStar 的
 * MBEVocoder 类逐字对照过：
 * <pre>
 *   void encode_2450x1150(int16_t *pcm, uint8_t *codec);   // 160 采样 → 9 字节
 *   void decode_2450x1150(int16_t *pcm, uint8_t *codec);   // 9 字节 → 160 采样
 * </pre>
 *
 * <p>native 侧全程走 dlsym 取函数指针，不声明 C++ 类 —— 插件方法是 virtual，
 * 自己声明类的虚表顺序一旦不一致就会静默调到错误的函数上。
 */
public final class Vocoder {

    /** 20 ms @ 8 kHz 的 PCM 采样数 */
    public static final int PCM_SAMPLES = 160;
    /** AMBE+2 2450x1150 的一帧字节数：72 bit */
    public static final int CODEC_BYTES = 9;

    static {
        System.loadLibrary("beaconvoc");
    }

    private Vocoder() {
    }

    /**
     * 加载插件并创建一个声码器实例。
     *
     * @param libPath libdsvocoder.so 的绝对路径；若该路径不存在，
     *                native 侧会自动回退到短名交给 linker 从 APK 内加载
     * @return 是否就绪
     */
    public static native boolean nativeInit(String libPath);

    public static native boolean nativeIsReady();

    /**
     * 编码一帧。
     *
     * @param pcm 长度须为 {@link #PCM_SAMPLES}
     * @param out 长度须为 {@link #CODEC_BYTES}
     * @return 成功返回 9，失败返回负值
     */
    public static native int nativeEncode(short[] pcm, byte[] out);

    /**
     * 解码一帧。
     *
     * @param codec 长度须为 {@link #CODEC_BYTES}
     * @param pcm   长度须为 {@link #PCM_SAMPLES}
     * @return 成功返回 160，失败返回负值
     */
    public static native int nativeDecode(byte[] codec, short[] pcm);

    /**
     * 自检：依次回答 dlopen / create / 编码产出 / 字节序 / 采样率 五个问题，
     * 返回可直接显示的多行报告。不干扰已创建的实例。
     */
    public static native String nativeProbe(String libPath);

    public static native void nativeRelease();
}
