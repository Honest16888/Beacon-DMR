package net.beacon.dmr.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 话组号 / ISO 代码 → 国家（地区）。
 *
 * <p>规则和 WebView 版 {@code tgCountry()} 完全一致：取话组号的<b>前三位</b>当
 * ITU 国家码前缀，落在 200~899 之间才查表。所以
 * {@code 2621}（德国）→ de、{@code 460}（中国）→ cn，
 * 而 {@code 860}（China DMR 全国公共）前三位 860 不在表里 → 查不到，
 * 这是对的：860 是话组号段，不是国家码。
 *
 * <p>表从 {@code web/index.html} 的 {@code PREFIX} 逐条搬过来，
 * 想加条目请先在那边确认，别只改这里 —— 两个版本的看板要对得上。
 *
 * <p>不依赖 Android，桌面 JDK 可以直接跑测试（见 tests/GeoTest.java）。
 */
public final class Geo {

    /** 一个国家 / 地区：中文名 + ISO 3166-1 alpha-2（小写）。 */
    public static final class Region {
        public final String cn;
        public final String iso;

        Region(String cn, String iso) {
            this.cn = cn;
            this.iso = iso;
        }

        @Override public String toString() { return cn + "/" + iso; }
    }

    private static final Map<Integer, Region> BY_PREFIX = new HashMap<>();
    private static final Map<String, String> ISO_CN = new HashMap<>();

    private Geo() {}

    /** 话组号 → 国家/地区。查不到返回 {@code null}（不是空 Region）。 */
    public static Region byTg(long tg) {
        String s = Long.toString(tg);
        if (s.length() < 3) return null;
        int p = Integer.parseInt(s.substring(0, 3));
        if (p < 200 || p >= 900) return null;
        return BY_PREFIX.get(p);
    }

    /** 话组号 → 中文地名，查不到返回 {@code null}。 */
    public static String regionName(long tg) {
        Region r = byTg(tg);
        return r == null ? null : r.cn;
    }

    /** 话组号 → ISO 代码，查不到返回 {@code null}。 */
    public static String regionIso(long tg) {
        Region r = byTg(tg);
        return r == null ? null : r.iso;
    }

    /** ISO 代码 → 中文名。表里没有就原样返回大写代码，不返回 null —— 界面上宁可显示 "XK"。 */
    public static String isoName(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        String up = iso.toUpperCase(Locale.ROOT);
        String n = ISO_CN.get(up);
        return n == null ? up : n;
    }

    /**
     * ISO 代码 → 国旗 emoji（🇨🇳 / 🇩🇪）。
     *
     * <p><b>和 WebView 版的一个有意偏离：</b>那边用 CDN 上的 SVG
     * （{@code cdn.jsdelivr.net/.../flag-icons/4x3/xx.svg}，15×11 圆角小图），
     * 这里换成 Unicode 区域指示符。理由：
     * <ul>
     *   <li>离线可用 —— 收听类 App 经常在没网/弱网下开，一屏灰方块不如不显示；</li>
     *   <li>不用为了一张 15px 的图引一条 HTTP + 图片缓存 + 三级缓存淘汰；</li>
     *   <li>体积为零，渲染在系统字体里，跟着系统字体走。</li>
     * </ul>
     * 代价是不同 ROM 的旗帜画风略有差异，而且极少数老机型字体缺这几个码位
     * （缺了就显示成两个方框字母，不会崩）。
     *
     * @return 两个区域指示符拼成的旗帜；iso 不合法时返回空串
     */
    public static String flag(String iso) {
        if (iso == null || iso.length() != 2) return "";
        char a = Character.toUpperCase(iso.charAt(0));
        char b = Character.toUpperCase(iso.charAt(1));
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        // 区域指示符 A = U+1F1E6，所以 'A' 偏移 0、'Z' 偏移 25
        return new String(Character.toChars(0x1F1E6 + (a - 'A')))
                + new String(Character.toChars(0x1F1E6 + (b - 'A')));
    }

    /**
     * 话组号 → 国旗 emoji，查不到返回空串。
     *
     * <p>★ 只在国际网有意义。国内的 460 / 860 是**话组号**不是国家码，
     * 给 460 挂面中国国旗反而误导（860 又查不到，两边不一致）。
     * 调用方要自己判断当前网络 —— 和 WebView 版 {@code flagHtml} 一样。
     */
    public static String tgFlag(long tg) {
        return flag(regionIso(tg));
    }

    /** 话组显示名：表里有名字用名字，没有就用「德国 区域话组」这种兜底。 */
    public static String tgLabel(long tg, String name) {
        if (name != null && !name.isEmpty()) return name;
        String r = regionName(tg);
        return r == null ? "未收录话组" : r + " 区域话组";
    }

    /* ==================== 数据表 ==================== */

    /* 话组号前三位 → 国家/地区（ISO 3166-1 alpha-2，小写）。
     * 数据从 WebView 版 web/index.html 的 PREFIX 表逐条搬过来，别手改。 */
    private static final String[] P = {
            "202:希腊:gr", "204:荷兰:nl", "206:比利时:be", "208:法国:fr",
            "213:安道尔:ad", "214:西班牙:es", "216:匈牙利:hu", "218:波黑:ba",
            "219:克罗地亚:hr", "220:塞尔维亚:rs", "222:意大利:it", "226:罗马尼亚:ro",
            "228:瑞士:ch", "230:捷克:cz", "231:斯洛伐克:sk", "232:奥地利:at",
            "234:英国:gb", "235:英国:gb", "238:丹麦:dk", "240:瑞典:se",
            "242:挪威:no", "244:芬兰:fi", "246:立陶宛:lt", "247:拉脱维亚:lv",
            "248:爱沙尼亚:ee", "250:俄罗斯:ru", "255:乌克兰:ua", "257:白俄罗斯:by",
            "259:摩尔多瓦:md", "260:波兰:pl", "262:德国:de", "263:德国:de",
            "264:德国:de", "268:葡萄牙:pt", "270:卢森堡:lu", "272:爱尔兰:ie",
            "274:冰岛:is", "276:阿尔巴尼亚:al", "278:马耳他:mt", "280:塞浦路斯:cy",
            "282:格鲁吉亚:ge", "283:亚美尼亚:am", "284:保加利亚:bg", "286:土耳其:tr",
            "288:法罗群岛:fo", "290:格陵兰:gl", "292:圣马力诺:sm", "293:斯洛文尼亚:si",
            "294:北马其顿:mk", "295:列支敦士登:li", "297:黑山:me", "302:加拿大:ca",
            "310:美国:us", "311:美国:us", "312:美国:us", "313:美国:us",
            "314:美国:us", "315:美国:us", "316:美国:us", "317:美国:us",
            "318:美国:us", "319:美国:us", "330:波多黎各:pr", "334:墨西哥:mx",
            "338:牙买加:jm", "352:格林纳达:gd", "358:圣卢西亚:lc", "362:库拉索:cw",
            "363:阿鲁巴:aw", "364:巴哈马:bs", "366:多米尼克:dm", "368:古巴:cu",
            "370:多米尼加:do", "372:海地:ht", "374:特立尼达和多巴哥:tt", "376:特克斯和凯科斯:tc",
            "400:阿塞拜疆:az", "401:哈萨克斯坦:kz", "402:不丹:bt", "404:印度:in",
            "410:巴基斯坦:pk", "413:斯里兰卡:lk", "415:黎巴嫩:lb", "419:科威特:kw",
            "420:沙特阿拉伯:sa", "422:阿曼:om", "425:以色列:il", "426:巴林:bh",
            "427:卡塔尔:qa", "430:阿联酋:ae", "434:乌兹别克斯坦:uz", "440:日本:jp",
            "450:韩国:kr", "452:越南:vn", "454:中国香港:hk", "456:柬埔寨:kh",
            "460:中国:cn", "466:中国台湾:tw", "470:孟加拉国:bd", "502:马来西亚:my",
            "505:澳大利亚:au", "510:印度尼西亚:id", "515:菲律宾:ph", "520:泰国:th",
            "525:新加坡:sg", "530:新西兰:nz", "602:埃及:eg", "603:阿尔及利亚:dz",
            "604:摩洛哥:ma", "608:塞内加尔:sn", "620:加纳:gh", "621:尼日利亚:ng",
            "625:佛得角:cv", "639:肯尼亚:ke", "646:马达加斯加:mg", "647:留尼汪:re",
            "655:南非:za", "704:危地马拉:gt", "706:萨尔瓦多:sv", "708:洪都拉斯:hn",
            "710:尼加拉瓜:ni", "712:哥斯达黎加:cr", "714:巴拿马:pa", "716:秘鲁:pe",
            "722:阿根廷:ar", "724:巴西:br", "730:智利:cl", "732:哥伦比亚:co",
            "734:委内瑞拉:ve", "736:玻利维亚:bo", "738:圭亚那:gy", "740:厄瓜多尔:ec",
            "744:巴拉圭:py", "748:乌拉圭:uy"
    };

    /* ISO 代码 → 中文名。PREFIX 自带中文名，这张表给
     * 「只有主站国家码」的场景用（看板的主站分布）。 */
    private static final String[] I = {
            "GR:希腊", "NL:荷兰", "BE:比利时", "FR:法国", "ES:西班牙", "IT:意大利",
            "DE:德国", "AT:奥地利", "CH:瑞士", "SE:瑞典", "NO:挪威", "DK:丹麦",
            "FI:芬兰", "PL:波兰", "CZ:捷克", "SK:斯洛伐克", "HU:匈牙利", "RO:罗马尼亚",
            "BG:保加利亚", "HR:克罗地亚", "SI:斯洛文尼亚", "RS:塞尔维亚", "BA:波黑", "ME:黑山",
            "MK:北马其顿", "AL:阿尔巴尼亚", "GB:英国", "IE:爱尔兰", "IS:冰岛", "PT:葡萄牙",
            "LU:卢森堡", "MT:马耳他", "CY:塞浦路斯", "EE:爱沙尼亚", "LV:拉脱维亚", "LT:立陶宛",
            "RU:俄罗斯", "UA:乌克兰", "BY:白俄罗斯", "MD:摩尔多瓦", "GE:格鲁吉亚", "AM:亚美尼亚",
            "AZ:阿塞拜疆", "TR:土耳其", "IL:以色列", "LB:黎巴嫩", "JO:约旦", "SA:沙特阿拉伯",
            "AE:阿联酋", "QA:卡塔尔", "KW:科威特", "BH:巴林", "OM:阿曼", "PK:巴基斯坦",
            "IN:印度", "LK:斯里兰卡", "BD:孟加拉国", "NP:尼泊尔", "TH:泰国", "VN:越南",
            "MY:马来西亚", "SG:新加坡", "ID:印度尼西亚", "PH:菲律宾", "CN:中国", "HK:中国香港",
            "MO:中国澳门", "TW:中国台湾", "JP:日本", "KR:韩国", "AU:澳大利亚", "NZ:新西兰",
            "CA:加拿大", "US:美国", "MX:墨西哥", "BR:巴西", "AR:阿根廷", "CL:智利",
            "CO:哥伦比亚", "PE:秘鲁", "UY:乌拉圭", "PY:巴拉圭", "BO:玻利维亚", "EC:厄瓜多尔",
            "VE:委内瑞拉", "ZA:南非", "EG:埃及", "MA:摩洛哥", "DZ:阿尔及利亚", "TN:突尼斯",
            "NG:尼日利亚", "KE:肯尼亚", "GH:加纳"
    };

    /* ★ 这段静态块必须放在 P / I 之后。
     * Java 不允许在静态初始化块里引用「文本上还没声明」的静态字段
     * （illegal forward reference），放前面直接编译不过。 */
    static {
        for (String s : P) {
            /* "262:德国:de" —— 用 indexOf 而不是 split：
             * 中文名里没有冒号，split 还要为正则多走一遍，没必要。 */
            int a = s.indexOf(':');
            int b = s.indexOf(':', a + 1);
            BY_PREFIX.put(Integer.parseInt(s.substring(0, a)),
                    new Region(s.substring(a + 1, b), s.substring(b + 1)));
        }
        for (String s : I) {
            int a = s.indexOf(':');
            ISO_CN.put(s.substring(0, a), s.substring(a + 1));
        }
    }
}
