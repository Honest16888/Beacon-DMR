import net.beacon.dmr.core.Geo;

/**
 * 话组号 → 国家/地区 的映射自检。
 *
 * <p>看板的「国家/地区分布」全靠这张表。错一条不会崩，只会让排行榜上出现
 * 一个错误的地名 —— 这种错最容易被当成「数据不准」而整体失去信任，
 * 所以在桌面就把边界情况钉死。
 */
public final class GeoTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, Object detail) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else {
            fail++;
            System.out.println("  \u2717 " + name + (detail == null ? "" : "  \u2192 " + detail));
        }
    }

    public static void main(String[] args) {
        System.out.println("\n=== 6. 话组号 → 国家/地区 ===");

        /* ---- 正常映射 ---- */
        ok("2621 → de / 德国", "de".equals(Geo.regionIso(2621)) && "德国".equals(Geo.regionName(2621)),
                Geo.regionName(2621) + "/" + Geo.regionIso(2621));
        ok("460 → cn / 中国", "cn".equals(Geo.regionIso(460)), Geo.regionIso(460));
        ok("91 是三位数，前三位 91 不合法 → null", Geo.byTg(91) == null, Geo.byTg(91));
        ok("9 只有一位 → null", Geo.byTg(9) == null, Geo.byTg(9));
        ok("860 → 860 不是国家码 → null", Geo.byTg(860) == null, Geo.byTg(860));
        ok("86023 → 前三位 860 → null", Geo.byTg(86023) == null, Geo.byTg(86023));
        ok("3100 → us", "us".equals(Geo.regionIso(3100)), Geo.regionIso(3100));

        /* ---- 港澳台写法（合规） ---- */
        ok("454 → 中国香港", "中国香港".equals(Geo.regionName(454)), Geo.regionName(454));
        ok("466 → 中国台湾", "中国台湾".equals(Geo.regionName(466)), Geo.regionName(466));

        /* ---- 同国家多前缀 ---- */
        ok("262 / 263 / 264 都是德国",
                "de".equals(Geo.regionIso(262)) && "de".equals(Geo.regionIso(263))
                        && "de".equals(Geo.regionIso(264)), null);

        /* ---- ISO → 中文 ---- */
        ok("isoName(CN) = 中国", "中国".equals(Geo.isoName("cn")), Geo.isoName("cn"));
        ok("isoName(小写也认) = 日本", "日本".equals(Geo.isoName("jp")), Geo.isoName("jp"));
        ok("isoName 未知代码原样大写返回", "XX".equals(Geo.isoName("xx")), Geo.isoName("xx"));
        ok("isoName(null) 不抛异常", "".equals(Geo.isoName(null)), null);

        /* ---- 兜底标签 ---- */
        ok("有名字就用名字", "世界组".equals(Geo.tgLabel(91, "世界组")), Geo.tgLabel(91, "世界组"));
        ok("没名字用地区兜底", "德国 区域话组".equals(Geo.tgLabel(2621, null)), Geo.tgLabel(2621, null));
        ok("什么都没有就说未收录", "未收录话组".equals(Geo.tgLabel(999999, "")), Geo.tgLabel(999999, ""));

        /* ---- 表规模：小于这个数说明抽取时截断了 ---- */
        int n = 0;
        for (int p = 200; p < 900; p++) if (Geo.byTg(p) != null) n++;
        ok("前缀表至少 130 条（实际 " + n + "）", n >= 130, n);

        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }
}
