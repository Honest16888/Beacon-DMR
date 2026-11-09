package nrl.pulse.dmr;

/**
 * OpenDmr —— JNI 桥 → libopendmr.so（530 KB, arm64-v8a）。
 *
 * 从 .dynsym 和字符串分析确认：
 *   编解码器：AMBE（Advanced Multi-Band Excitation）
 *   帧格式：AMBE 2450 / AMBE 3600x2450
 *   帧长：AMBE_FRAME_BYTES（常量）
 *   纠错：Golay(23,12) + Hamming(15,11)
 *   还支持 D-Star（dstar）模式
 *
 * JNI 方法：
 *   nativeDecode        AMBE 帧 → PCM 8kHz
 *   nativeEncode        PCM 8kHz → AMBE 帧
 *   nativeReset         重置解码器
 *   nativeResetEncoder  重置编码器
 *
 * 内部 MBE 函数链：
 *   mbe_demodulateAmbe3600x2450Data → mbe_eccAmbe3600x2450C0/Data
 *   → mbe_decodeAmbe2450Parms → mbe_processAmbe2450Dataf
 *   → mbe_synthesizeSpeechf → mbe_floattoshort
 *
 * 纠错：
 *   mbe_golay2312          Golay(23,12)
 *   mbe_hamming1511        Hamming(15,11)
 *   mbe_7100x4400hamming1511  7100x4400 纠错
 */
public class OpenDmr {

    static {
        try { System.loadLibrary("opendmr"); }
        catch (UnsatisfiedLinkError e) { /* 开发阶段允许没有 .so */ }
    }

    /** AMBE 帧字节数（从 .so 里的 AMBE_FRAME_BYTES 常量） */
    public static final int AMBE_FRAME_BYTES = 9;  // 72 bits = 9 bytes（DMR AMBE+2 标准帧）

    /** AMBE 采样率 */
    public static final int AMBE_SAMPLE_RATE = 8000;

    /**
     * AMBE 解码：AMBE 帧 → PCM 8kHz。
     * 输入：AMBE 编码字节（AMBE_FRAME_BYTES × N 帧）
     * 输出：PCM 8kHz 单声道 s16le
     */
    public static native byte[] nativeDecode(byte[] ambe);

    /**
     * AMBE 编码：PCM 8kHz → AMBE 帧。
     * 输入：PCM 8kHz 单声道 s16le
     * 输出：AMBE 编码字节（AMBE_FRAME_BYTES × N 帧）
     */
    public static native byte[] nativeEncode(byte[] pcm);

    /** 重置解码器状态（换频率/换台/断线重连时调用） */
    public static native void nativeReset();

    /** 重置编码器状态 */
    public static native void nativeResetEncoder();
}
