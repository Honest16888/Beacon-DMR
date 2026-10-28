package net.beacon.dmr.core;

/**
 * ITU-T G.711 μ-law 解码表。
 *
 * <p>HOSE 送来的音频是 8 kHz / 8 bit μ-law / 单声道，每帧 160 字节 = 20 ms。
 * 解码就是查一张 256 项的短表 —— 逐样本算指数对数是没必要的。
 *
 * <p>表的值和 WebView 版 {@code web/index.html} 里的 {@code MULAW} 完全一致
 * （{@code CY[]} + {@code (man << (ex+3))} 那套算法），所以两版听感不会有差别。
 * 这一点很重要：换实现时如果音色变了，用户会立刻听出来。
 */
public final class MuLaw {

    private MuLaw() {}

    /** 8 kHz 下的采样率常量，AudioTrack / 抖动缓冲都按它算。 */
    public static final int RATE = 8000;
    /** 一帧的样本数（160 = 20 ms）。 */
    public static final int FRAME = 160;

    /** 256 项查表：μ-law 字节 → 16 bit PCM。 */
    public static final short[] PCM = new short[256];

    static {
        final int[] CY = {0, 132, 396, 924, 1980, 4092, 8316, 16764};
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xFF;
            int sign = 128 & u;
            int ex = (u >> 4) & 7;
            int man = 15 & u;
            int s = CY[ex] + (man << (ex + 3));
            if (sign != 0) s = -s;
            /* 上限夹一下：CY[7]=16764 + 15<<10 = 16764+15360 = 32124，本来就 < 32768，
               不会溢出。保留这句注释是因为改表的人很容易在这里出错。 */
            PCM[i] = (short) s;
        }
    }

    /**
     * 解码一段 μ-law 到 PCM。
     *
     * @param src   μ-law 字节
     * @param off   起点
     * @param len   字节数
     * @param out   输出缓冲，长度必须 ≥ len
     * @return 实际写入的样本数
     */
    public static int decode(byte[] src, int off, int len, short[] out) {
        for (int i = 0; i < len; i++) out[i] = PCM[src[off + i] & 0xFF];
        return len;
    }

    /** 便捷版：整段解码成新数组。 */
    public static short[] decode(byte[] src) {
        short[] out = new short[src.length];
        decode(src, 0, src.length, out);
        return out;
    }

    /**
     * 一帧的 RMS（0..1）。给波形和「信号强度」用。
     *
     * <p>和 WebView 版的 {@code Audio_.decode()} 一样按满刻度 32768 归一化，
     * 这样两边显示的柱高对得上。
     */
    public static float rms(short[] pcm, int len) {
        if (len <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < len; i++) {
            double v = pcm[i] / 32768.0;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / len);
    }
}
