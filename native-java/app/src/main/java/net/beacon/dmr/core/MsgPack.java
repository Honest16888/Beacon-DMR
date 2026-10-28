package net.beacon.dmr.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 够用的 MessagePack 编解码。
 *
 * <p>只覆盖 HOSE 实际用到的类型：nil / bool / 整数 / float / str / bin / array / map。
 * 不做扩展类型（timestamp、bignum 之类），遇到就抛异常 —— 静默返回 null 会让
 * 上层的「这帧坏了」判断失效，反而更难查。
 *
 * <p>为什么不用第三方库：这个工程零依赖（见 build.gradle）。HOSE 的报文结构很简单，
 * 一个 250 行的解码器比引 2 MB 的依赖更划算，也更好读。
 */
public final class MsgPack {

    private MsgPack() {}

    /** 解码单个对象。多余字节会被忽略（HOSE 一条消息里可能有多个对象）。 */
    public static Object decode(byte[] b) {
        Cursor c = new Cursor(b, 0);
        return read(c);
    }

    /** 解码全部对象。HOSE 有时会把多个对象连在一个帧里。 */
    public static List<Object> decodeAll(byte[] b) {
        List<Object> out = new ArrayList<>();
        Cursor c = new Cursor(b, 0);
        while (c.pos < b.length) out.add(read(c));
        return out;
    }

    /* ==================== 读 ==================== */

    private static final class Cursor {
        final byte[] b;
        int pos;
        Cursor(byte[] b, int pos) { this.b = b; this.pos = pos; }
        int u8() {
            if (pos >= b.length) throw new MsgPackError("消息被截断（pos=" + pos + " len=" + b.length + "）");
            return b[pos++] & 0xFF;
        }
        byte[] take(int n) {
            if (n < 0 || pos + n > b.length) {
                throw new MsgPackError("消息被截断（要 " + n + " 字节，剩 " + (b.length - pos) + "）");
            }
            byte[] o = new byte[n];
            System.arraycopy(b, pos, o, 0, n);
            pos += n;
            return o;
        }
    }

    private static Object read(Cursor c) {
        int t = c.u8();
        if (t <= 0x7F) return (long) t;                       // positive fixint
        if (t >= 0xE0) return (long) (byte) t;                // negative fixint
        if ((t & 0xF0) == 0x80) return readMap(c, t & 0x0F);  // fixmap
        if ((t & 0xF0) == 0x90) return readArr(c, t & 0x0F);  // fixarray
        if ((t & 0xE0) == 0xA0) return readStr(c, t & 0x1F);  // fixstr

        switch (t) {
            case 0xC0: return null;
            case 0xC2: return Boolean.FALSE;
            case 0xC3: return Boolean.TRUE;
            case 0xC4: return c.take(u8Int(c));                 // bin 8
            case 0xC5: return c.take(u16(c));                   // bin 16
            case 0xC6: return c.take((int) u32(c));              // bin 32
            case 0xCA: return Float.intBitsToFloat((int) be32(c));   // float 32
            case 0xCB: return Double.longBitsToDouble(be64(c));      // float 64
            case 0xCC: return (long) c.u8();                    // uint 8
            case 0xCD: return (long) u16(c);                    // uint 16
            case 0xCE: return u32(c);                           // uint 32
            case 0xCF: {                                        // uint 64
                long v = be64(c);
                if (v < 0) throw new MsgPackError("uint64 超出 Java long 范围");
                return v;
            }
            case 0xD0: return (long) (byte) c.u8();             // int 8
            case 0xD1: return (long) (short) u16(c);            // int 16
            case 0xD2: return (long) (int) u32(c);              // int 32
            case 0xD3: return be64(c);                          // int 64
            case 0xD9: return readStr(c, u8Int(c));             // str 8
            case 0xDA: return readStr(c, u16(c));               // str 16
            case 0xDB: return readStr(c, (int) u32(c));          // str 32
            case 0xDC: return readArr(c, u16(c));               // array 16
            case 0xDD: return readArr(c, (int) u32(c));          // array 32
            case 0xDE: return readMap(c, u16(c));               // map 16
            case 0xDF: return readMap(c, (int) u32(c));          // map 32
            default:
                throw new MsgPackError("不支持的 MessagePack 类型 0x" + Integer.toHexString(t));
        }
    }

    private static List<Object> readArr(Cursor c, int n) {
        List<Object> a = new ArrayList<>(n);
        for (int i = 0; i < n; i++) a.add(read(c));
        return a;
    }

    private static Map<Object, Object> readMap(Cursor c, int n) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Object k = read(c);
            m.put(k, read(c));
        }
        return m;
    }

    private static String readStr(Cursor c, int n) {
        return new String(c.take(n), StandardCharsets.UTF_8);
    }

    private static int u8Int(Cursor c) { return c.u8(); }
    private static int u16(Cursor c) {
        return ((c.u8() << 8) | c.u8()) & 0xFFFF;
    }
    private static long u32(Cursor c) {
        return (((long) c.u8() << 24) | ((long) c.u8() << 16) | ((long) c.u8() << 8) | c.u8()) & 0xFFFFFFFFL;
    }
    private static long be64(Cursor c) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | c.u8();
        return v;
    }
    private static long be32(Cursor c) {
        return ((long) c.u8() << 24) | ((long) c.u8() << 16) | ((long) c.u8() << 8) | c.u8();
    }

    /* ==================== 写 ==================== */

    /** 编一个 [int, [int...]] 数组。HOSE 的订阅报文只需要这一种形状。 */
    public static byte[] encodeIntArray(long type, int[] values) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(8 + values.length * 5);
        o.write(0x92);                                  // fixarray(2)
        writeInt(o, type);
        if (values.length <= 15) {
            o.write(0x90 | values.length);              // fixarray(n)
        } else {
            /* 真超过 15 个话组时退回 array16 —— 用户勾满 20 个话组很正常。 */
            o.write(0xDC);
            o.write((values.length >> 8) & 0xFF);
            o.write(values.length & 0xFF);
        }
        for (int v : values) writeInt(o, v);
        return o.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream o, long v) {
        if (v >= 0 && v <= 0x7F) { o.write((int) v); return; }          // positive fixint
        if (v >= -32 && v < 0) { o.write((int) (v & 0xFF)); return; }   // negative fixint
        if (v >= 0 && v <= 0xFF) { o.write(0xCC); o.write((int) v); return; }
        if (v >= 0 && v <= 0xFFFF) {
            o.write(0xCD); o.write((int) (v >> 8) & 0xFF); o.write((int) v & 0xFF); return;
        }
        if (v >= 0 && v <= 0xFFFFFFFFL) {
            o.write(0xCE);
            o.write((int) (v >> 24) & 0xFF); o.write((int) (v >> 16) & 0xFF);
            o.write((int) (v >> 8) & 0xFF); o.write((int) v & 0xFF); return;
        }
        o.write(0xD3);
        for (int i = 7; i >= 0; i--) o.write((int) (v >> (8 * i)) & 0xFF);
    }

    /** 解码失败时抛这个，上层据此把整帧丢掉并记一次日志。 */
    public static final class MsgPackError extends RuntimeException {
        public MsgPackError(String m) { super(m); }
    }
}
