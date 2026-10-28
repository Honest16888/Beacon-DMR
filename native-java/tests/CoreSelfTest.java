import net.beacon.dmr.core.MsgPack;
import net.beacon.dmr.core.MuLaw;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 纯逻辑自检：MessagePack 编解码 + μ-law 查表。
 *
 * <p>这两个类是整条音频链路的地基，而且**不依赖 Android** —— 所以可以直接用
 * 桌面的 javac / java 跑，不需要模拟器。协议解析错了会表现为「连上了但没声音」，
 * 在手机上排查成本极高，能在桌面上钉死就别留到设备上。
 *
 * <p>跑法（见 native-java/tests/run.sh）：
 * <pre>
 *   javac -d out core/MsgPack.java core/MuLaw.java tests/CoreSelfTest.java
 *   java -cp out CoreSelfTest
 * </pre>
 */
public final class CoreSelfTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, Object detail) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else {
            fail++;
            System.out.println("  \u2717 " + name + (detail == null ? "" : "  \u2192 " + detail));
        }
    }

    public static void main(String[] args) {
        testMsgPackDecode();
        testMsgPackEncode();
        testHoseFrames();
        testMuLaw();
        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }

    /* ---------- 解码 ---------- */

    private static void testMsgPackDecode() {
        System.out.println("\n=== 1. MessagePack 解码 ===");

        // fixint 正负
        ok("正 fixint 0x2A → 42", eq(MsgPack.decode(new byte[]{0x2A}), 42L), MsgPack.decode(new byte[]{0x2A}));
        ok("负 fixint 0xE0 → -32", eq(MsgPack.decode(new byte[]{(byte) 0xE0}), -32L), MsgPack.decode(new byte[]{(byte) 0xE0}));
        ok("uint8 0xCC 0xC8 → 200", eq(MsgPack.decode(new byte[]{(byte) 0xCC, (byte) 0xC8}), 200L), null);
        ok("int16 0xD1 0xFE 0x0C → -500",
                eq(MsgPack.decode(new byte[]{(byte) 0xD1, (byte) 0xFE, (byte) 0x0C}), -500L), null);
        ok("uint32 0xCE 0x00 0x0F 0x42 0x40 → 1000000",
                eq(MsgPack.decode(new byte[]{(byte) 0xCE, 0x00, 0x0F, 0x42, 0x40}), 1000000L), null);

        // 字符串
        byte[] s = concat(new byte[]{(byte) 0xA5}, "HELLO".getBytes(StandardCharsets.UTF_8));
        ok("fixstr \"HELLO\"", "HELLO".equals(MsgPack.decode(s)), MsgPack.decode(s));

        // 数组 [1, 2, 3]
        ok("fixarray [1,2,3]",
                "[1, 2, 3]".equals(String.valueOf(MsgPack.decode(new byte[]{(byte)0x93, 0x01, 0x02, 0x03}))),
                MsgPack.decode(new byte[]{(byte)0x93, 0x01, 0x02, 0x03}));

        // bin8
        byte[] bin = concat(new byte[]{(byte) 0xC4, 0x03}, new byte[]{1, 2, 3});
        Object d = MsgPack.decode(bin);
        ok("bin8 → byte[3]", d instanceof byte[] && Arrays.equals((byte[]) d, new byte[]{1, 2, 3}), d);

        // nil / bool
        ok("0xC0 → null", MsgPack.decode(new byte[]{(byte) 0xC0}) == null, null);
        ok("0xC3 → true", eq(MsgPack.decode(new byte[]{(byte) 0xC3}), Boolean.TRUE), null);

        // 截断必须抛异常，不能静默返回半个对象
        boolean threw = false;
        try { MsgPack.decode(new byte[]{(byte) 0xC4, 0x05, 0x01}); } catch (MsgPack.MsgPackError e) { threw = true; }
        ok("截断的 bin 抛 MsgPackError", threw, null);

        // 多对象
        List<Object> multi = MsgPack.decodeAll(new byte[]{0x01, 0x02});
        ok("decodeAll 读出 2 个对象", multi.size() == 2, multi);
    }

    /* ---------- 编码 ---------- */

    private static void testMsgPackEncode() {
        System.out.println("\n=== 2. MessagePack 编码（订阅报文）===");

        byte[] e2 = MsgPack.encodeIntArray(1, new int[]{91, 460});
        ok("2 个话组 → [1,[91,460]]",
                Arrays.equals(e2, new byte[]{(byte)0x92, 0x01, (byte) 0x92, 0x5B, (byte) 0xCD, 0x01, (byte) 0xCC}),
                hex(e2));

        // 回环
        Object back = MsgPack.decode(e2);
        ok("回环解码出 list", back instanceof List, back);
        @SuppressWarnings("unchecked")
        List<Object> bl = (List<Object>) back;
        ok("回环类型 = 1", eq(bl.get(0), 1L), bl.get(0));
        @SuppressWarnings("unchecked")
        List<Object> tg = (List<Object>) bl.get(1);
        ok("回环话组 = [91,460]", tg.size() == 2 && eq(tg.get(0), 91L) && eq(tg.get(1), 460L), tg);

        // 超过 15 个要用 array16
        int[] many = new int[20];
        for (int i = 0; i < 20; i++) many[i] = 100 + i;
        byte[] e20 = MsgPack.encodeIntArray(1, many);
        ok("20 个话组用 array16（0xDC）", (e20[2] & 0xFF) == 0xDC, hex(e20, 4));
        Object back20 = MsgPack.decode(e20);
        @SuppressWarnings("unchecked")
        List<Object> b20 = (List<Object>) back20;
        @SuppressWarnings("unchecked")
        List<Object> t20 = (List<Object>) b20.get(1);
        ok("20 个话组回环正确", t20.size() == 20 && eq(t20.get(19), 119L), t20.size());

        // 空订阅 = GROUP_RESET
        byte[] e0 = MsgPack.encodeIntArray(3, new int[0]);
        ok("空订阅编码为 [3,[]]", Arrays.equals(e0, new byte[]{(byte)0x92, 0x03, (byte) 0x90}), hex(e0));
    }

    /* ---------- 真实 HOSE 帧 ---------- */

    private static void testHoseFrames() {
        System.out.println("\n=== 3. HOSE 真实帧形状 ===");

        // [11, 0, 1234567, 91]  CALL_START
        byte[] cs = new byte[]{(byte) 0x94, 0x0B, 0x00,
                (byte) 0xCE, 0x00, 0x12, (byte) 0xD6, (byte) 0x87, 0x5B};
        @SuppressWarnings("unchecked")
        List<Object> m = (List<Object>) MsgPack.decode(cs);
        ok("CALL_START 类型 = 11", eq(m.get(0), 11L), m.get(0));
        ok("CALL_START srcId = 1234567", eq(m.get(2), 1234567L), m.get(2));
        ok("CALL_START tg = 91", eq(m.get(3), 91L), m.get(3));

        // [20, bin(160)]  CALL_AUDIO
        byte[] payload = new byte[160];
        for (int i = 0; i < 160; i++) payload[i] = (byte) i;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x92); o.write(0x14);            // [20, ...
        o.write(0xC5); o.write(0x00); o.write(160);   // bin16
        o.write(payload, 0, 160);
        @SuppressWarnings("unchecked")
        List<Object> a = (List<Object>) MsgPack.decode(o.toByteArray());
        ok("CALL_AUDIO 类型 = 20", eq(a.get(0), 20L), a.get(0));
        ok("CALL_AUDIO 载荷 160 字节", a.get(1) instanceof byte[] && ((byte[]) a.get(1)).length == 160,
                a.get(1) instanceof byte[] ? ((byte[]) a.get(1)).length : a.get(1));

        // [13, 0, 1234567, 91]  CALL_END
        byte[] ce = new byte[]{(byte) 0x94, 0x0D, 0x00,
                (byte) 0xCE, 0x00, 0x12, (byte) 0xD6, (byte) 0x87, 0x5B};
        @SuppressWarnings("unchecked")
        List<Object> e = (List<Object>) MsgPack.decode(ce);
        ok("CALL_END 类型 = 13 且 tg = 91", eq(e.get(0), 13L) && eq(e.get(3), 91L), e);

        // [21, "IU6VLK Marco Roma"]  CALL_ALIAS
        byte[] alias = "IU6VLK Marco Roma".getBytes(StandardCharsets.UTF_8);
        byte[] al = concat(new byte[]{(byte) 0x92, 0x15, (byte) (0xA0 | alias.length)}, alias);
        @SuppressWarnings("unchecked")
        List<Object> al2 = (List<Object>) MsgPack.decode(al);
        ok("CALL_ALIAS 类型 = 21 且文本正确",
                eq(al2.get(0), 21L) && "IU6VLK Marco Roma".equals(al2.get(1)), al2);
    }

    /* ---------- μ-law ---------- */

    private static void testMuLaw() {
        System.out.println("\n=== 4. G.711 μ-law ===");

        ok("PCM[0x00] = -32124（满幅负）", MuLaw.PCM[0x00] == -32124, MuLaw.PCM[0x00]);
        ok("PCM[0xFF] = 0（数字静音）", MuLaw.PCM[0xFF] == 0, MuLaw.PCM[0xFF]);
        ok("PCM[0x80] = +32124（满幅正）", MuLaw.PCM[0x80] == 32124, MuLaw.PCM[0x80]);
        ok("PCM[0x7F] = 0（正侧静音）", MuLaw.PCM[0x7F] == 0, MuLaw.PCM[0x7F]);

        // 单调性：字节值从 0x80 递增到 0xFF 时，输出应从 +32124 单调降到 0
        boolean mono = true;
        for (int i = 0x80; i < 0xFF; i++) if (MuLaw.PCM[i] < MuLaw.PCM[i + 1]) { mono = false; break; }
        ok("正半轴单调不增", mono, null);

        // 解码长度与 RMS
        byte[] src = new byte[160];
        for (int i = 0; i < 160; i++) src[i] = (byte) (0x80 + (i % 16));
        short[] pcm = MuLaw.decode(src);
        ok("decode 输出 160 个样本", pcm.length == 160, pcm.length);
        float rms = MuLaw.rms(pcm, pcm.length);
        ok("rms 落在 (0,1)", rms > 0 && rms < 1, rms);

        short[] zeros = new short[160];
        ok("全零 PCM 的 rms = 0", MuLaw.rms(zeros, 160) == 0f, MuLaw.rms(zeros, 160));

        // ★ 逐项比对 WebView 版那张表。
        //   web/index.html 里是：
        //     const CY=[0,132,396,924,1980,4092,8316,16764];
        //     const u=~i, sign=128&u, ex=(u>>4)&7, man=15&u;
        //     let s=CY[ex]+(man<<(ex+3));  if(sign!==0)s=-s;  MULAW[i]=s/32768;
        //   这里把同一套算式再写一遍逐项比 —— 只要有一项不同，两个版本的音色就会
        //   不一样，而「换实现之后声音变了」是最难被用户接受的一种回归。
        int[] cy = {0, 132, 396, 924, 1980, 4092, 8316, 16764};
        int mism = 0;
        int firstBad = -1;
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xFF;
            int sign = 128 & u;
            int ex = (u >> 4) & 7;
            int man = 15 & u;
            int s = cy[ex] + (man << (ex + 3));
            if (sign != 0) s = -s;
            if ((short) s != MuLaw.PCM[i]) {
                mism++;
                if (firstBad < 0) firstBad = i;
            }
        }
        ok("256 项与 WebView 版算式逐项一致", mism == 0,
                mism == 0 ? null : (mism + " 项不一致，首个 0x" + Integer.toHexString(firstBad)));
        ok("抽查 PCM[0x90] = 15996", MuLaw.PCM[0x90] == 15996, MuLaw.PCM[0x90]);
        ok("抽查 PCM[0x70] = -120", MuLaw.PCM[0x70] == -120, MuLaw.PCM[0x70]);
    }

    /* ---------- 工具 ---------- */

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String hex(byte[] b) { return hex(b, b.length); }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }
}
