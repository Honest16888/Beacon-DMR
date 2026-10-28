package net.beacon.dmr.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主站地址的本地校验。
 *
 * <p><b>为什么值得单独一个类：</b>真机反馈「连不上主站」，报告里写的是
 *
 * <pre>  主站  4602.ma4614ster.brandmeister.network:62031</pre>
 *
 * <p>—— 正确地址是 {@code 4602.master.brandmeister.network}，中间被插进了
 * {@code 4614}（正好是 DMR ID 的前四位，多半是切换输入框时光标没跟上）。
 * 这种地址的唯一后果是 DNS 查不到，而界面上的表现是
 * {@code Load failed : Host not found (authoritative)} ——
 * <b>一句话都不会提「地址写错了」</b>。用户只会以为软件坏了。
 *
 * <p>所以把它做成「能本地判断的事就不该交给 DNS」：
 * 空格、全角字符、非法字符、BrandMeister 域名格式，全在点击连接的那一瞬间查出来，
 * 并且尽量给出可直接替换的正确写法。
 *
 * <p>纯 Java，不碰 Android —— {@code tests/CoreSelfTest.java} 里能直接跑。
 */
public final class HostCheck {

    private HostCheck() {
    }

    /** 三档结论。 */
    public enum Level {
        /** 格式没问题 */
        OK,
        /** 能用，但有可疑之处 —— 只提示，不拦 */
        WARN,
        /** 明显是写错了 —— 应该拦住，别浪费一次 DNS + 握手 */
        BAD
    }

    public static final class Result {
        public final Level level;
        /** 给用户看的一句话。OK 时为空串。 */
        public final String message;
        /** 可直接替换的地址；没有建议时为 null。 */
        public final String suggestion;

        Result(Level level, String message, String suggestion) {
            this.level = level;
            this.message = message == null ? "" : message;
            this.suggestion = suggestion;
        }

        public boolean ok() {
            return level == Level.OK;
        }

        public boolean bad() {
            return level == Level.BAD;
        }

        @Override
        public String toString() {
            return level + ": " + message
                    + (suggestion == null ? "" : "  →  建议：" + suggestion);
        }
    }

    /* BrandMeister 的域名规则：<主站号>.master.brandmeister.network
     * 中国区是 4602，所以 4602.master.brandmeister.network。 */
    private static final String BM_SUFFIX = ".brandmeister.network";
    private static final String BM_MASTER = ".master.brandmeister.network";

    /** 整串只允许这些字符（域名 + IPv4 够用了；IPv6 要方括号，另说）。 */
    private static final Pattern LEGAL_CHARS = Pattern.compile("[A-Za-z0-9.\\-]+");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    /** BrandMeister 主站号：4 位数字（4601 荷兰 / 4602 中国 / 4603 …）。 */
    private static final Pattern BM_PREFIX = Pattern.compile("\\d+");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    /**
     * 检查一个主站地址。
     *
     * @param raw 界面上原样的字符串，允许带前后空格
     */
    public static Result check(String raw) {
        String h = raw == null ? "" : raw.trim();

        /* ---- 1. 空 ---- */
        if (h.isEmpty()) {
            return bad("主站地址是空的。BrandMeister 中国区填 4602.master.brandmeister.network");
        }

        /* ---- 2. 非 ASCII ----
         * 全角句号「．」、全角数字、中文都不行。这类错误肉眼极难发现，
         * 因为全角「．」和半角「.」在有些字体下几乎一样。 */
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c > 0x7F) {
                return bad("地址第 " + (i + 1) + " 个字符是「" + c
                        + "」，不是英文半角。常见于全角输入法或粘贴时带了中文标点。");
            }
        }

        /* ---- 3. 空格 ---- */
        if (h.indexOf(' ') >= 0 || h.indexOf('\t') >= 0) {
            return new Result(Level.BAD, "地址中间有空格",
                    h.replace(" ", "").replace("\t", ""));
        }

        /* ---- 4. 非法字符 ---- */
        if (!LEGAL_CHARS.matcher(h).matches()) {
            for (int i = 0; i < h.length(); i++) {
                char c = h.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-')) {
                    return bad("地址第 " + (i + 1) + " 个字符「" + c
                            + "」不允许出现在主机名里");
                }
            }
            return bad("地址里有不允许的字符");
        }

        /* ---- 5. 点的位置 ---- */
        if (h.startsWith(".") || h.endsWith(".")) {
            return bad("地址不能以点开头或结尾");
        }
        if (h.contains("..")) {
            return bad("地址里有两个连续的点", h.replace("..", "."));
        }

        /* ---- 6. IPv4 ---- */
        if (IPV4.matcher(h).matches()) {
            String[] parts = h.split("\\.");
            for (String p : parts) {
                /* 四位数字一定越界，且 Integer.parseInt 对上亿的串也不会炸，
                 * 但「0255」这种前导零值得提醒一句。 */
                int v;
                try {
                    v = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    return bad("IP 段「" + p + "」不是合法数字");
                }
                if (v > 255) {
                    return bad("IP 段「" + p + "」超过 255");
                }
            }
            return new Result(Level.OK, "", null);
        }

        /* ---- 7. 单段主机名 ----
         * 「4602」这种写法很常见 —— 有人以为只填主站号就够了。 */
        if (h.indexOf('.') < 0 && !"localhost".equalsIgnoreCase(h)) {
            return new Result(Level.BAD,
                    "「" + h + "」没有点，不是完整域名，DNS 查不到。"
                            + "只填主站号的话要补全成 <号>.master.brandmeister.network",
                    h.matches("\\d+") ? h + BM_MASTER : null);
        }

        /* ---- 8. 域名段的边界 ----
         * 段不能以 - 开头或结尾（RFC 952/1123）。 */
        for (String seg : h.split("\\.")) {
            if (seg.isEmpty()) {
                continue;   // 上面已经查过 ".."
            }
            if (seg.startsWith("-") || seg.endsWith("-")) {
                return bad("域名段「" + seg + "」不能以连字符开头或结尾");
            }
        }

        String low = h.toLowerCase(Locale.US);

        /* ---- 9. 提到了 brandmeister 但域名不完整 ----
         * 典型是漏打「work」：4602.master.brandmeister.net。
         * 这是个合法域名格式（只是 DNS 查不到），光看字符是查不出来的 ——
         * 所以专门按关键词拦一道。 */
        if (low.contains("brandmeister") && !low.endsWith(BM_SUFFIX)) {
            return new Result(Level.BAD,
                    "BrandMeister 的域名结尾是 brandmeister.network，你写的是「"
                            + h + "」。是不是漏了 work？", guessBm(low));
        }

        /* ---- 10. BrandMeister 特判 ----
         * 这是本项目最常填的地址，也是唯一能「猜出正确写法」的地方。 */
        if (low.endsWith(BM_MASTER)) {
            String prefix = low.substring(0, low.length() - BM_MASTER.length());
            if (!BM_PREFIX.matcher(prefix).matches()) {
                /* 例：4602.ma4614ster.brandmeister.network 走不到这里（下面第 10 条拦），
                 * 这里接的是 foo.master.brandmeister.network 之类。 */
                return new Result(Level.BAD,
                        "BrandMeister 主站的号必须是纯数字（中国区是 4602），你写的是「"
                                + prefix + "」", guessBm(low));
            }
            if (prefix.length() != 4) {
                return new Result(Level.WARN,
                        "BrandMeister 主站号一般是 4 位数字（中国区 4602），「" + prefix
                                + "」是 " + prefix.length() + " 位，确认一下有没有漏字",
                        null);
            }
            return new Result(Level.OK, "", null);
        }

        /* ---- 10. 是 brandmeister.network 但不是 master 子域 ----
         * 这就是那个真机案例：4602.ma4614ster.brandmeister.network。
         * 数字被插进了 "master" 中间，把第二段弄成了 ma4614ster。 */
        if (low.endsWith(BM_SUFFIX)) {
            return new Result(Level.BAD,
                    "BrandMeister 的域名格式是 <主站号>.master.brandmeister.network，"
                            + "你这个地址里「master」那一段被掺进了别的字符",
                    guessBm(low));
        }

        /* ---- 11. 其它域名 ----
         * 用户可能连自建主站（HBLink、MMDVMHost、本地局域网）。
         * 这里不能拦，只能放行。 */
        return new Result(Level.OK, "", null);
    }

    /**
     * 从一串写坏的地址里猜出正确的 BrandMeister 地址。
     *
     * <p>规则：取第一个 4 位数字段作主站号（4602 / 4601 / 4603 …），
     * 没有 4 位的就取第一段数字。猜不出来返回 null。
     */
    public static String guessBm(String host) {
        if (host == null) {
            return null;
        }
        Matcher m = DIGIT_RUN.matcher(host);
        String first = null;
        while (m.find()) {
            String g = m.group();
            if (g.length() == 4) {
                return g + BM_MASTER;
            }
            if (first == null) {
                first = g;
            }
        }
        return first == null ? null : first + BM_MASTER;
    }

    private static Result bad(String message) {
        return new Result(Level.BAD, message, null);
    }

    private static Result bad(String message, String suggestion) {
        return new Result(Level.BAD, message, suggestion);
    }

    /**
     * 把用户输入规整成可以直接交给 DNS 的样子。
     *
     * <p>只做「显然安全」的改动：去前后空白、去中间空格、转小写。
     * 不做「猜修正」—— 那必须由用户确认过 {@link #check} 的 suggestion 才生效。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(" ", "").replace("\t", "").trim().toLowerCase(Locale.US);
    }
}
