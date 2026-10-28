import net.beacon.dmr.mmdvm.DmrRx;

/**
 * DMR 接收链的<b>位级</b>自检 —— 不需要设备、不需要声码器、不需要网络。
 *
 * <p>这一组的价值在于它验证的是「两侧用的是同一套交织规则」。
 * 交织规则对不上时的表现不是报错，而是<b>收到的是噪声</b>：
 * 声码器照样解出 160 个采样、电平条照样跳、日志全干净，就是听不清人说话。
 * 这种故障在真机上排查极贵（要抓包、要对比十六进制、还要怀疑是射频问题），
 * 所以把 {@code dmrtx_jni.cpp} 里的 {@code interleave_ambe()} 用 Java 抄一份，
 * 在桌面上做往返比对。
 *
 * <p>⚠ 抄的这份是**测试用**的，只求与 C++ 那 6 行逐字对应，不做任何优化。
 * 如果哪天 C++ 那边改了位缝分配，这里必须同步改 —— 两边不一致时
 * 这个测试会失败，那正是它存在的意义。
 */
public final class DmrRxTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== DMR 接收链（交织 / 解交织 / 常量）===");

        testConstants();
        testRoundTripPlain();
        testRoundTripWithSyncBits();
        testRoundTripExhaustive();
        testArgValidation();
        testOffsetHandling();

        System.out.println();
        System.out.println("===============  " + pass + " 通过 / " + fail + " 失败  ===============");
        if (fail > 0) System.exit(1);
    }

    /* ================================================================ */

    /**
     * C++ {@code interleave_ambe()} 的逐行翻译。
     *
     * <pre>
     *   memcpy(p, a, 13);
     *   p[13] = (p[13] & 0x0F) | (a[13] & 0xF0);
     *   memcpy(p + 20, a + 14, 13);
     *   p[19] = (p[19] & 0xF0) | (a[13] & 0x0F);
     * </pre>
     *
     * <p>注意 p 是「33 字节载荷」的起点，a 是 27 字节 AMBE。
     */
    private static void interleave(byte[] ambe, byte[] payload, int off) {
        System.arraycopy(ambe, 0, payload, off, 13);
        payload[off + 13] = (byte) ((payload[off + 13] & 0x0F) | (ambe[13] & 0xF0));
        System.arraycopy(ambe, 14, payload, off + 20, 13);
        payload[off + 19] = (byte) ((payload[off + 19] & 0xF0) | (ambe[13] & 0x0F));
    }

    private static void testConstants() {
        check("PAYLOAD_OFF = 20", DmrRx.PAYLOAD_OFF == 20);
        check("PAYLOAD_BYTES = 33", DmrRx.PAYLOAD_BYTES == 33);
        check("AMBE_BYTES = 27（3 帧 × 9）", DmrRx.AMBE_BYTES == 27);
        check("FRAME_BYTES = 9", DmrRx.FRAME_BYTES == 9);
        check("FRAMES = 3", DmrRx.FRAMES == 3);
        check("PCM_PER_FRAME = 160（20 ms @ 8 kHz）", DmrRx.PCM_PER_FRAME == 160);
        check("PCM_PER_BUNDLE = 480（60 ms）", DmrRx.PCM_PER_BUNDLE == 480);
        /* 48 kHz 下 480 采样正好是 10 ms；8 kHz 下是 60 ms —— 这个数错了
         * 会让播放速度整体偏快或偏慢，听起来像「快放」而不是失真 */
        check("FLAG_SLOT2 / PRIVATE / DATA / SYNC 互不重叠",
                (DmrRx.FLAG_SLOT2 & DmrRx.FLAG_PRIVATE) == 0
                        && (DmrRx.FLAG_SLOT2 & DmrRx.FLAG_DATA) == 0
                        && (DmrRx.FLAG_PRIVATE & DmrRx.FLAG_DATA) == 0
                        && (DmrRx.FLAG_DATA & DmrRx.FLAG_SYNC) == 0);
    }

    /** 最干净的情形：载荷全零，只有交织写进去的那些位。 */
    private static void testRoundTripPlain() {
        byte[] ambe = new byte[DmrRx.AMBE_BYTES];
        for (int i = 0; i < ambe.length; i++) ambe[i] = (byte) ((i * 37 + 11) & 0xFF);

        byte[] payload = new byte[DmrRx.PAYLOAD_BYTES];
        interleave(ambe, payload, 0);

        byte[] back = new byte[DmrRx.AMBE_BYTES];
        int n = DmrRx.deinterleave(payload, 0, back);
        check("往返返回 27", n == DmrRx.AMBE_BYTES);
        check("往返 27 字节全同", same(ambe, back));
    }

    /**
     * 真实情形：{@code addDMRAudioSync()} 会往 p[13..19] 写同步码，
     * {@code get_emb_data()} 会往 p[13] 低半字节 / p[19] 高半字节写嵌入信令。
     *
     * <p>这是最容易写错的一处 —— 如果解交织时取错了半字节，
     * 这里就会把同步码当成 AMBE 的低 4 位，于是「只有带同步码的那一帧坏」，
     * 表现为每 6 帧（360 ms）卡一下。所以必须专门测。
     */
    private static void testRoundTripWithSyncBits() {
        /* SYNC_MASK = {0F, FF, FF, FF, FF, FF, F0}，从 p[13] 起 */
        final int[] syncMask = {0x0F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xF0};

        int bad = 0;
        for (int round = 0; round < 64; round++) {
            byte[] ambe = new byte[DmrRx.AMBE_BYTES];
            for (int i = 0; i < ambe.length; i++) {
                ambe[i] = (byte) ((i * 53 + round * 17 + 3) & 0xFF);
            }
            byte[] payload = new byte[DmrRx.PAYLOAD_BYTES];
            /* 先铺同步码与嵌入信令，再交织 —— 和 C++ 里的顺序一致 */
            for (int i = 0; i < syncMask.length; i++) {
                payload[13 + i] = (byte) (syncMask[i] & 0xFF);
            }
            interleave(ambe, payload, 0);

            byte[] back = new byte[DmrRx.AMBE_BYTES];
            DmrRx.deinterleave(payload, 0, back);
            if (!same(ambe, back)) bad++;
        }
        check("同步码 + 嵌入信令在场的 64 轮往返全部一致", bad == 0);
    }

    /** 把 27 字节里的每一个 bit 单独翻一次，确认没有「某一位置永远丢」。 */
    private static void testRoundTripExhaustive() {
        int bad = 0;
        for (int byteIdx = 0; byteIdx < DmrRx.AMBE_BYTES; byteIdx++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] ambe = new byte[DmrRx.AMBE_BYTES];
                ambe[byteIdx] = (byte) (1 << bit);

                byte[] payload = new byte[DmrRx.PAYLOAD_BYTES];
                /* 同时铺上同步码，模拟最坏情形 */
                for (int i = 0; i < 7; i++) payload[13 + i] = (byte) 0xFF;
                interleave(ambe, payload, 0);

                byte[] back = new byte[DmrRx.AMBE_BYTES];
                DmrRx.deinterleave(payload, 0, back);
                if (!same(ambe, back)) bad++;
            }
        }
        check("27×8 = 216 个单 bit 全部能原样取回", bad == 0);
    }

    private static void testArgValidation() {
        byte[] ok = new byte[DmrRx.PAYLOAD_BYTES];
        byte[] out = new byte[DmrRx.AMBE_BYTES];

        check("payload 为 null 返回 -1", DmrRx.deinterleave(null, 0, out) == -1);
        check("out 为 null 返回 -1", DmrRx.deinterleave(ok, 0, null) == -1);
        check("out 太短返回 -1",
                DmrRx.deinterleave(ok, 0, new byte[DmrRx.AMBE_BYTES - 1]) == -1);
        check("负偏移返回 -1", DmrRx.deinterleave(ok, -1, out) == -1);
        check("偏移越界返回 -1", DmrRx.deinterleave(ok, 1, out) == -1);
        check("正好放得下时返回 27", DmrRx.deinterleave(ok, 0, out) == DmrRx.AMBE_BYTES);
    }

    /** DMRD 包是 55 字节、载荷从第 20 字节起 —— 偏移必须真的被用上。 */
    private static void testOffsetHandling() {
        byte[] ambe = new byte[DmrRx.AMBE_BYTES];
        for (int i = 0; i < ambe.length; i++) ambe[i] = (byte) (0xA0 + i);

        /* 造一个完整的 55 字节 DMRD：头 20 字节随便填，载荷从 20 起 */
        byte[] pkt = new byte[55];
        for (int i = 0; i < DmrRx.PAYLOAD_OFF; i++) pkt[i] = (byte) 0x5A;
        interleave(ambe, pkt, DmrRx.PAYLOAD_OFF);

        byte[] back = new byte[DmrRx.AMBE_BYTES];
        DmrRx.deinterleave(pkt, DmrRx.PAYLOAD_OFF, back);
        check("55 字节 DMRD 包按 PAYLOAD_OFF=20 取载荷", same(ambe, back));

        /* 反向确认：用偏移 0 去解同一个包必然对不上（防止「偏移被忽略」） */
        byte[] wrong = new byte[DmrRx.AMBE_BYTES];
        DmrRx.deinterleave(pkt, 0, wrong);
        check("偏移 0 解同一个包对不上（证明偏移确实生效）", !same(ambe, wrong));
    }

    /* ================================================================ */

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name);
        }
    }
}
