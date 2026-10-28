package net.beacon.dmr.mmdvm;

import net.beacon.dmr.mmdvm.vocoder.Vocoder;

/**
 * DMR 接收解码：把主站推来的 DMRD 语音包还原成 PCM。
 *
 * <p>这是「通联」的接收半边 —— 发射链是
 * {@code 麦克风 → AMBE → DMRD}，本类是它的逆运算
 * {@code DMRD → AMBE → PCM}。
 *
 * <h3>为什么必须解交织</h3>
 * 一个 DMRD 语音包（55 字节）里的 33 字节载荷 <b>不是</b>裸 AMBE ——
 * 它是空中突发的载荷，27 字节 AMBE 被「织」进 33 字节里，
 * 腾出 48 bit 放同步码与嵌入信令。发射侧由
 * {@code dmrtx_jni.cpp} 的 {@code interleave_ambe()} 完成，本类做它的<b>精确逆运算</b>。
 *
 * <p>两侧的位缝分配（这是能对上的关键）：
 * <pre>
 *   interleave_ambe 写：p[0..12] 全字节 · p[13] 高半字节 · p[20..32] 全字节 · p[19] 低半字节
 *   addDMRAudioSync 写：p[13..19] 且被 SYNC_MASK = {0F,FF,FF,FF,FF,FF,F0} 掩过
 *   get_emb_data    写：p[13] 低半字节 · p[14] 高半字节 · p[18] 低半字节 · p[19] 高半字节
 * </pre>
 * 也就是 AMBE 占「13 低半字节 + 19 高半字节」，同步/信令占「13 高半字节 + 19 低半字节」——
 * <b>互不重叠</b>，所以解交织能把 27 字节一字不差地取回来
 * （{@link #selfTest()} 会做位级往返验证，跑在真机上也应该是 0 字节差异）。
 *
 * <p>本类不做抖动缓冲、不碰 AudioTrack —— 那是 {@link DmrAudio} 的事。
 */
public final class DmrRx {

    /** DMRD 包里载荷的偏移（"DMRD" 4 + seq 1 + src 3 + dst 3 + rpt 4 + flags 1 + streamId 4 = 20） */
    public static final int PAYLOAD_OFF = 20;
    /** DMRD 载荷字节数 */
    public static final int PAYLOAD_BYTES = 33;
    /** 一个载荷里的 AMBE 字节数：3 帧 × 9 字节 */
    public static final int AMBE_BYTES = 27;
    /** 单帧 AMBE 字节数 */
    public static final int FRAME_BYTES = 9;
    /** 一个载荷承载的帧数 */
    public static final int FRAMES = 3;
    /** 单帧 PCM 采样数（20 ms @ 8 kHz） */
    public static final int PCM_PER_FRAME = Vocoder.PCM_SAMPLES;      // 160
    /** 一个载荷的 PCM 采样数（60 ms @ 8 kHz） */
    public static final int PCM_PER_BUNDLE = PCM_PER_FRAME * FRAMES;  // 480

    /** DMRD 头 [15] 的标志位 */
    public static final int FLAG_SLOT2 = 0x80;   // 置位 = 时隙 2
    public static final int FLAG_PRIVATE = 0x40; // 置位 = 个呼（FLCO_USER_USER）
    public static final int FLAG_DATA = 0x20;    // 置位 = 数据/信令帧，非语音
    public static final int FLAG_SYNC = 0x10;    // 语音帧里置位 = 本帧带同步码（一个 6 帧超帧的第 0 帧）

    /* 复用的临时缓冲，避免每 60 ms 分配一次。
     * frame 之所以要单独一份，是因为 Vocoder.nativeDecode 没有偏移量参数，
     * 一次只能吃「整整 9 字节」的数组。 */
    private final byte[] ambe = new byte[AMBE_BYTES];
    private final byte[] frame = new byte[FRAME_BYTES];
    private final short[] pcm160 = new short[PCM_PER_FRAME];

    /**
     * 解交织：33 字节 DMR 载荷 → 27 字节 AMBE。
     *
     * @param payload 载荷所在数组
     * @param off     payload 里载荷的起始下标
     * @param out     输出缓冲，长度须 ≥ {@link #AMBE_BYTES}
     * @return 写入字节数（{@link #AMBE_BYTES}），参数不合法返回 -1
     */
    public static int deinterleave(byte[] payload, int off, byte[] out) {
        if (payload == null || out == null) return -1;
        if (off < 0 || payload.length < off + PAYLOAD_BYTES) return -1;
        if (out.length < AMBE_BYTES) return -1;

        /* 与 interleave_ambe() 一一对应，顺序无关，三个区段互不重叠 */
        System.arraycopy(payload, off, out, 0, 13);
        out[13] = (byte) ((payload[off + 13] & 0xF0) | (payload[off + 19] & 0x0F));
        System.arraycopy(payload, off + 20, out, 14, 13);
        return AMBE_BYTES;
    }

    /**
     * 解交织 + 解码一个语音包。
     *
     * @param payload 载荷所在数组
     * @param off     载荷起始下标
     * @param out     输出 PCM，长度须 ≥ {@link #PCM_PER_BUNDLE}
     * @return 写入的采样数（{@link #PCM_PER_BUNDLE}），失败返回负值
     */
    public int decode(byte[] payload, int off, short[] out) {
        if (out == null || out.length < PCM_PER_BUNDLE) return -1;
        if (deinterleave(payload, off, ambe) < 0) return -1;

        for (int i = 0; i < FRAMES; i++) {
            System.arraycopy(ambe, i * FRAME_BYTES, frame, 0, FRAME_BYTES);
            int n = Vocoder.nativeDecode(frame, pcm160);
            if (n != PCM_PER_FRAME) {
                /* 单帧失败不致命：补静音继续，别让整包丢音频 */
                java.util.Arrays.fill(out, i * PCM_PER_FRAME,
                        i * PCM_PER_FRAME + PCM_PER_FRAME, (short) 0);
            } else {
                System.arraycopy(pcm160, 0, out, i * PCM_PER_FRAME, PCM_PER_FRAME);
            }
        }
        return PCM_PER_BUNDLE;
    }

    /* ================================================================ */
    /* 自检                                                             */
    /* ================================================================ */

    /**
     * 接收链路自检：不需要网络、不需要主站，纯本地跑。
     *
     * <p>三问：
     * <ol>
     *   <li><b>交织/解交织往返是否位级一致</b> —— 借发射链的
     *       {@code nativeVoice()} 做交织，再用 {@link #deinterleave} 取回来比对。
     *       这一问不对，收听到的必然是噪声而不是报错，所以必须查。</li>
     *   <li><b>解码器是否真的出声音</b> —— 编一帧正弦、解回来，看能量。</li>
     *   <li><b>整链是否跑得通</b> —— 载荷 → PCM 480 采样，不抛异常。</li>
     * </ol>
     *
     * @return 可直接显示的多行报告
     */
    public static String selfTest() {
        StringBuilder sb = new StringBuilder();
        sb.append("DMR 接收链路自检\n");
        sb.append("────────────────\n");

        /* ---- Q1：交织 / 解交织往返（位级） ---- */
        byte[] src = new byte[AMBE_BYTES];
        for (int i = 0; i < AMBE_BYTES; i++) src[i] = (byte) ((i * 37 + 11) & 0xFF);

        byte[] pkt = new byte[DmrTx.PACKET_BYTES];
        try {
            DmrTx.nativeReset();
            DmrTx.nativeSetParams(4601234, 91, 2, 1, DmrTx.FLCO_GROUP);
            int h = DmrTx.nativeHeader(pkt);
            int v = DmrTx.nativeVoice(src, pkt);
            if (h != DmrTx.PACKET_BYTES || v != DmrTx.PACKET_BYTES) {
                sb.append("✗ 发射链返回异常：header=").append(h)
                  .append(" voice=").append(v)
                  .append("（应为 55）—— 无法做往返比对\n");
                return sb.toString();
            }
        } catch (Throwable t) {
            sb.append("✗ 调用发射链失败：").append(t).append('\n');
            return sb.toString();
        }

        byte[] back = new byte[AMBE_BYTES];
        if (deinterleave(pkt, PAYLOAD_OFF, back) < 0) {
            sb.append("✗ deinterleave 参数被拒\n");
            return sb.toString();
        }

        int diff = 0, firstBad = -1;
        for (int i = 0; i < AMBE_BYTES; i++) {
            if (back[i] != src[i]) {
                diff++;
                if (firstBad < 0) firstBad = i;
            }
        }
        if (diff == 0) {
            sb.append("✓ Q1 交织/解交织往返位级一致（27 字节全同）\n");
        } else {
            sb.append("✗ Q1 往返有 ").append(diff).append(" 字节不一致，首个在第 ")
              .append(firstBad).append(" 字节\n");
            sb.append("     原 ").append(hex(src)).append('\n');
            sb.append("     回 ").append(hex(back)).append('\n');
            sb.append("   → 收听到的会是噪声，别往下走了\n");
            return sb.toString();
        }

        /* ---- Q2：解码器是否出声音 ---- */
        short[] tone = new short[PCM_PER_FRAME];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (8000.0 * Math.sin(2.0 * Math.PI * 1000.0 * i / 8000.0));
        }
        byte[] codec = new byte[FRAME_BYTES];
        int enc = -1;
        try {
            enc = Vocoder.nativeEncode(tone, codec);
        } catch (Throwable t) {
            sb.append("✗ Q2 编码抛异常：").append(t).append('\n');
        }
        if (enc != FRAME_BYTES) {
            sb.append("✗ Q2 编码返回 ").append(enc).append("（应为 9）\n");
        } else {
            short[] out = new short[PCM_PER_FRAME];
            int dec = Vocoder.nativeDecode(codec, out);
            if (dec != PCM_PER_FRAME) {
                sb.append("✗ Q2 解码返回 ").append(dec).append("（应为 160）\n");
            } else {
                double sum = 0;
                for (short s : out) sum += (double) s * s;
                double rms = Math.sqrt(sum / out.length);
                if (rms < 50.0) {
                    sb.append("✗ Q2 解码出来基本是静音（RMS=")
                      .append(String.format(java.util.Locale.US, "%.1f", rms))
                      .append("）\n");
                } else {
                    sb.append("✓ Q2 编解码往返有声音（RMS=")
                      .append(String.format(java.util.Locale.US, "%.1f", rms))
                      .append("，编 9 字节 → 解 160 采样）\n");
                }
            }
        }

        /* ---- Q3：整链 ---- */
        DmrRx rx = new DmrRx();
        short[] pcm = new short[PCM_PER_BUNDLE];
        int n = rx.decode(pkt, PAYLOAD_OFF, pcm);
        if (n != PCM_PER_BUNDLE) {
            sb.append("✗ Q3 整链 decode 返回 ").append(n)
              .append("（应为 ").append(PCM_PER_BUNDLE).append("）\n");
        } else {
            double sum = 0;
            for (short s : pcm) sum += (double) s * s;
            double rms = Math.sqrt(sum / pcm.length);
            sb.append("✓ Q3 整链跑通：33 字节载荷 → ")
              .append(PCM_PER_BUNDLE).append(" 采样（60 ms，RMS=")
              .append(String.format(java.util.Locale.US, "%.1f", rms)).append("）\n");
        }

        sb.append("\n说明：Q1 是关键 —— 它位级证明我们与发射侧用同一套交织规则。\n");
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.US, "%02X", x & 0xFF));
        return s.toString();
    }
}
