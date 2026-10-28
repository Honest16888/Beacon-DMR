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

    /** 把 emoji 转成 `U+1F1E8 U+1F1F3` 这种，测试失败时能一眼看出偏了几位。 */
    private static String esc(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
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

        /* ---- 国旗 emoji ----
         * 区域指示符的码位是 0x1F1E6 + (字母 - 'A')，差一位就是另一面旗。
         * 这类"看着对、其实偏了一位"的错误在手机上极难发现，所以钉死。 */
        ok("flag(cn) = 🇨🇳", "\uD83C\uDDE8\uD83C\uDDF3".equals(Geo.flag("cn")), esc(Geo.flag("cn")));
        ok("flag(de) = 🇩🇪", "\uD83C\uDDE9\uD83C\uDDEA".equals(Geo.flag("de")), esc(Geo.flag("de")));
        ok("flag(us) = 🇺🇸", "\uD83C\uDDFA\uD83C\uDDF8".equals(Geo.flag("us")), esc(Geo.flag("us")));
        ok("flag(大写也认)", Geo.flag("CN").equals(Geo.flag("cn")), esc(Geo.flag("CN")));
        ok("flag(空) = 空串", "".equals(Geo.flag("")), esc(Geo.flag("")));
        ok("flag(null) = 空串", "".equals(Geo.flag(null)), null);
        ok("flag(单字母) = 空串", "".equals(Geo.flag("c")), esc(Geo.flag("c")));
        ok("flag(三位) = 空串", "".equals(Geo.flag("chn")), esc(Geo.flag("chn")));
        ok("flag(数字) = 空串", "".equals(Geo.flag("12")), esc(Geo.flag("12")));
        ok("tgFlag(2621) 是德国旗", Geo.flag("de").equals(Geo.tgFlag(2621)), esc(Geo.tgFlag(2621)));
        ok("tgFlag(91) 为空（91 不是国家码）", "".equals(Geo.tgFlag(91)), esc(Geo.tgFlag(91)));
        ok("tgFlag(860) 为空（860 不是国家码）", "".equals(Geo.tgFlag(860)), esc(Geo.tgFlag(860)));

        /* ---- 表规模：小于这个数说明抽取时截断了 ---- */
        int n = 0;
        for (int p = 200; p < 900; p++) if (Geo.byTg(p) != null) n++;
        ok("前缀表至少 130 条（实际 " + n + "）", n >= 130, n);

        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }
}
