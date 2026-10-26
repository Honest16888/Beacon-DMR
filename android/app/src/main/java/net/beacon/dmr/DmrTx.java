package net.beacon.dmr;

/**
 * DMR 发射帧组装（Homebrew / MMDVM 的 55 字节 DMRD 包）。
 *
 * <p>native 侧是 DroidStar dmr.cpp 发射路径的移植，包含完整的 FEC 链：
 * RS(12,9) → BPTC(196,96) → Golay(20,8,7) → QR(16,7) → Hamming(16,11)。
 *
 * <p>一次完整发射的调用顺序：
 * <pre>
 *   nativeReset();
 *   nativeSetParams(dmrId, tgid, slot, colorCode, flco);
 *   nativeSetRepeaterId(loginId);      // 配了 ESSID 别名时必调，见下
 *   nativeHeader(buf);                 // 语音 LC 头，必须先发
 *   while (有语音) nativeVoice(ambe27, buf);   // 每 27 字节 AMBE 打一个包
 *   nativeEot(buf);                    // 终止帧
 * </pre>
 *
 * <p>AMBE 交织、seqNo 自增、同步码插入、嵌入 LC 全部在 native 侧维护，
 * Java 只需要按 3 帧一组喂进来。
 *
 * <p><b>srcId 与 repeaterId 是两回事：</b>DMRD 头 [5..7] 的 srcId 是「谁在说话」，
 * 恒为本机 DMR ID；[11..14] 的 repeaterId 是「经哪台热点中继」，
 * 配了 BrandMeister 的 ESSID 别名 ID 后要填 9 位的别名 ID。
 */
public final class DmrTx {

    /** DMRD 包长度 */
    public static final int PACKET_BYTES = 55;
    /** 一个 DMRD 语音包承载的 AMBE 字节数：3 帧 × 9 字节 */
    public static final int AMBE_BUNDLE = 27;

    /** 组呼（通播到话组） */
    public static final int FLCO_GROUP = 0;
    /** 个呼（点对点） */
    public static final int FLCO_USER_USER = 3;

    static {
        System.loadLibrary("beaconvoc");
    }

    private DmrTx() {
    }

    /** 清空帧计数与缓冲，开始一次新的发射 */
    public static native void nativeReset();

    /**
     * 设置本次发射的参数。
     *
     * @param srcId     本机 DMR ID
     * @param dstId     目标话组号或对方 DMR ID
     * @param slot      时隙，1 或 2
     * @param colorCode 色码 0..15
     * @param flco      {@link #FLCO_GROUP} 或 {@link #FLCO_USER_USER}
     */
    public static native void nativeSetParams(int srcId, int dstId, int slot, int colorCode, int flco);

    /** 发射中途切换目标（改话组时用） */
    public static native void nativeSetIds(int srcId, int dstId);

    /**
     * 设置 DMRD 头 [11..14] 的 repeaterId —— 标识「经哪台热点中继」。
     *
     * <p>{@link #nativeSetParams} 会把它默认设成本机 DMR ID；
     * 配了 BrandMeister 的 ESSID 别名 ID 之后必须再调一次本方法覆盖成别名 ID
     * （如 {@code 461418701}），否则主站会把通联记录挂到原 ID 那台设备名下。
     *
     * @param repeaterId 登录用的 ID（含 ESSID）；≤0 时忽略，保留原值
     */
    public static native void nativeSetRepeaterId(int repeaterId);

    /** 写出语音 LC 头帧，返回 55；失败返回负值 */
    public static native int nativeHeader(byte[] out);

    /** 传入 27 字节 AMBE，写出一个语音突发帧，返回 55；失败返回负值 */
    public static native int nativeVoice(byte[] ambe, byte[] out);

    /** 写出终止帧，返回 55；失败返回负值 */
    public static native int nativeEot(byte[] out);

    /** 自检：用假 AMBE 走完整条 FEC 链并 dump 三帧十六进制，用于离线核对位级正确性 */
    public static native String nativeSelfTest();
}
