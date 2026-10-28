package net.beacon.dmr.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 所有设置项的**唯一**读写入口。
 *
 * <p>为什么不各处直接 {@code getSharedPreferences}：默认值散在七八个文件里时，
 * 「这个开关默认是开还是关」会变成考古题，而默认值恰好是最容易被抱怨的东西
 * （「怎么一装就没声音」）。全部集中在这里，默认值一眼能看全。
 *
 * <p>键名保持 {@code volume} / {@code muted} 不变 —— 早期 alpha 已经写过这两个，
 * 改名会让装过旧包的测试者丢掉音量设置。
 *
 * <p>⚠ 有一处**故意和 v2.5.5 不同**：{@code presence}（窄带增强）在 2.5.5 里
 * 默认是<b>开</b>，这里默认<b>关</b>。原因是纯 Java 版当前的音频链路已经被用户
 * 验收为「声音是对的」，默认加一级 2.6 kHz 抬升会改变音色；想要 2.5.5 那种
 * 亮一点的声音，在设置里打开这一档即可。改默认值前先想清楚这一点。
 */
public final class Prefs {

    private static final String P = "dmr";

    /* 主题三档 */
    public static final int THEME_AUTO = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /* ==================== 外观 ==================== */

    public static int theme(Context c) {
        int v = sp(c).getInt("theme", THEME_AUTO);
        return (v < 0 || v > 2) ? THEME_AUTO : v;
    }

    public static void setTheme(Context c, int mode) {
        sp(c).edit().putInt("theme", mode).apply();
    }

    /** 简洁模式：藏起看板标签与实时活动，只留「守听 + 按住说话」。 */
    public static boolean simple(Context c) { return sp(c).getBoolean("simple", false); }

    public static void setSimple(Context c, boolean on) {
        sp(c).edit().putBoolean("simple", on).apply();
    }

    /**
     * 主页那片操作区（网络条 + 发射条）是否已收起。
     *
     * <p>默认展开。收起是个纯视觉偏好，但值得存：屏幕小的机型上它能把
     * 列表多露四五行出来，用户第一次收起就不想每次都收一遍。
     */
    public static boolean htCollapsed(Context c) {
        return sp(c).getBoolean("ht.collapsed", false);
    }

    public static void setHtCollapsed(Context c, boolean on) {
        sp(c).edit().putBoolean("ht.collapsed", on).apply();
    }

    /* ==================== 网络 ==================== */

    /**
     * 当前网络：{@code "bm"} 国际 BrandMeister / {@code "cn"} 国内 China DMR。
     *
     * <p>两套网络的**话组号体系、订阅协议、发射主站全都不一样**，
     * 所以这个值决定用哪条收听链路、哪张话组表、哪份发射身份。
     */
    public static String net(Context c) {
        return "cn".equals(sp(c).getString("net", "bm")) ? "cn" : "bm";
    }

    public static void setNet(Context c, String v) {
        sp(c).edit().putString("net", "cn".equals(v) ? "cn" : "bm").apply();
    }

    public static boolean isCn(Context c) {
        return "cn".equals(net(c));
    }

    /* ==================== 接收过滤 ==================== */

    /**
     * 单组静音名单（话组号，逗号分隔）。
     *
     * <p>静音是**三层闸**，缺一层就会「静音了还在响」：
     * ① 从订阅里摘掉 —— 服务器根本不再推这个组的音频（最彻底）；
     * ② 正在播的立刻掐掉；
     * ③ 播放前的最后一道检查。
     * 这里存的是那份名单，三处共用。
     */
    public static java.util.Set<Integer> rxMute(Context c) {
        String raw = sp(c).getString("rx.mute", "");
        java.util.Set<Integer> s = new java.util.LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return s;
        for (String p : raw.split(",")) {
            try {
                s.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignore) { }
        }
        return s;
    }

    public static void setRxMute(Context c, java.util.Set<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (int v : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        sp(c).edit().putString("rx.mute", sb.toString()).apply();
    }

    /** 静音名单上限。 */
    public static final int RX_MUTE_MAX = 40;

    /* ==================== 话组别名 ==================== */

    /**
     * 用户给话组起的别名，{@code "46001=我的叫法"} 这种。
     *
     * <p>★ 别名**压倒所有远端表**：表里的名字是别人定的，
     * 而用户想按自己圈子的习惯叫 —— 这是本机设置，不该被远端数据覆盖。
     */
    public static String tgAlias(Context c, int tg) {
        String all = sp(c).getString("tg.alias", "");
        if (all == null || all.isEmpty()) return null;
        String key = tg + "=";
        for (String p : all.split("\n")) {
            if (p.startsWith(key)) {
                String v = p.substring(key.length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    public static void setTgAlias(Context c, int tg, String name) {
        String all = sp(c).getString("tg.alias", "");
        StringBuilder sb = new StringBuilder();
        String key = tg + "=";
        if (all != null && !all.isEmpty()) {
            for (String p : all.split("\n")) {
                if (p.isEmpty() || p.startsWith(key)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(p);
            }
        }
        String v = name == null ? "" : name.trim();
        if (!v.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append(v);
        }
        sp(c).edit().putString("tg.alias", sb.toString()).apply();
    }

    /* ==================== PTT 按钮 ==================== */

    /** PTT 可拖动。关掉时坐标一起清，回到右下角默认位。 */
    public static boolean pttDrag(Context c) {
        return sp(c).getBoolean("ptt.drag", false);
    }

    public static void setPttDrag(Context c, boolean on) {
        sp(c).edit().putBoolean("ptt.drag", on).apply();
        if (!on) sp(c).edit().putString("ptt.pos", "").apply();
    }

    /** PTT 停放位置，{@code "x,y"} 像素；空串 = 右下角默认位。 */
    public static String pttPos(Context c) {
        return sp(c).getString("ptt.pos", "");
    }

    public static void setPttPos(Context c, String v) {
        sp(c).edit().putString("ptt.pos", v == null ? "" : v).apply();
    }

    /** 上次停在主页的哪一栏：0 我的收听 / 1 实时活动 / 2 话组库。 */
    public static int mcol(Context c) {
        int v = sp(c).getInt("ht.mcol", 0);
        return v < 0 || v > 2 ? 0 : v;
    }

    public static void setMcol(Context c, int v) {
        sp(c).edit().putInt("ht.mcol", Math.max(0, Math.min(2, v))).apply();
    }

    /* ==================== 收听与音频 ==================== */

    /** 0~150。100 = 原始电平，>100 靠 DSP 增益推上去。 */
    public static int volume(Context c) {
        int v = sp(c).getInt("volume", 100);
        return v < 0 ? 0 : Math.min(v, 150);
    }

    public static void setVolume(Context c, int v) {
        sp(c).edit().putInt("volume", Math.max(0, Math.min(v, 150))).apply();
    }

    /** 音量增强倍数：1 / 2 / 3。1 是默认，不改变信号。 */
    public static int gain(Context c) {
        int v = sp(c).getInt("gain", 1);
        return (v < 1 || v > 3) ? 1 : v;
    }

    public static void setGain(Context c, int v) {
        sp(c).edit().putInt("gain", Math.max(1, Math.min(v, 3))).apply();
    }

    /** 窄带增强：2.6 kHz 抬 2.5 dB。默认关，理由见类注释。 */
    public static boolean presence(Context c) { return sp(c).getBoolean("presence", false); }

    public static void setPresence(Context c, boolean on) {
        sp(c).edit().putBoolean("presence", on).apply();
    }

    /** 收音期间保持屏幕常亮。默认开 —— 熄屏后很多 ROM 会掐掉音频。 */
    public static boolean awake(Context c) { return sp(c).getBoolean("awake", true); }

    public static void setAwake(Context c, boolean on) {
        sp(c).edit().putBoolean("awake", on).apply();
    }

    /** 自动录音。录音本身在 Phase 4，这里先只存开关。 */
    /**
     * 自动录音。<b>默认开</b> —— 和 WebView 版一致。
     *
     * <p>默认关的话，用户永远想不起来去开它，等到「刚才那句很重要」时
     * 才发现没录。开着不影响什么：一条 12 分钟上限、总量 48 MB 自动裁剪。
     */
    public static boolean recOn(Context c) { return sp(c).getBoolean("recOn", true); }

    public static void setRecOn(Context c, boolean on) {
        sp(c).edit().putBoolean("recOn", on).apply();
    }

    /** 静音。和音量分开存：静音不等于把音量拖到 0。 */
    public static boolean muted(Context c) { return sp(c).getBoolean("muted", false); }

    public static void setMuted(Context c, boolean on) {
        sp(c).edit().putBoolean("muted", on).apply();
    }

    /* ==================== 扫描 ==================== */

    /** 扫描总开关。 */
    public static boolean scanOn(Context c) { return sp(c).getBoolean("scan.on", false); }

    public static void setScanOn(Context c, boolean on) {
        sp(c).edit().putBoolean("scan.on", on).apply();
    }

    /** 自动跟进：从实时活动里挑最活跃的 N 个话组自动订阅。 */
    public static boolean scanAuto(Context c) { return sp(c).getBoolean("scan.auto", true); }

    public static void setScanAuto(Context c, boolean on) {
        sp(c).edit().putBoolean("scan.auto", on).apply();
    }

    /** 自动跟进数量，1~20。越多越容易漏听。 */
    public static int autoN(Context c) {
        int v = sp(c).getInt("scan.autoN", 8);
        return v < 1 ? 1 : Math.min(v, 20);
    }

    public static void setAutoN(Context c, int v) {
        sp(c).edit().putInt("scan.autoN", Math.max(1, Math.min(v, 20))).apply();
    }

    /** 下次打开是否接着上次的扫描状态。 */
    public static boolean scanResume(Context c) { return sp(c).getBoolean("scan.resume", true); }

    public static void setScanResume(Context c, boolean on) {
        sp(c).edit().putBoolean("scan.resume", on).apply();
    }

    /* ==================== 收发（MMDVM / BrandMeister） ==================== */

    /**
     * 主站地址。要的是 <b>Master</b>（UDP 62031），不是收听用的
     * {@code hose.brandmeister.network} —— 这两个是完全不同的服务，
     * 填错了表现是「连上了但收发全不通」。
     *
     * <p>默认给 BrandMeister 的中国主站。国内用户多半该改成
     * {@code 8600.radiowo.com}，那台的延迟低得多。
     */
    public static String host(Context c) {
        String v = sp(c).getString("tx.host", DEF_HOST);
        return v == null ? DEF_HOST : v.trim();
    }

    public static void setHost(Context c, String v) {
        sp(c).edit().putString("tx.host", v == null ? "" : v.trim()).apply();
    }

    public static final String DEF_HOST = "4602.master.brandmeister.network";

    /** 主站 UDP 端口，MMDVM 协议固定 62031，几乎不用改。 */
    public static int port(Context c) {
        int v = sp(c).getInt("tx.port", 62031);
        return (v < 1 || v > 65535) ? 62031 : v;
    }

    public static void setPort(Context c, int v) {
        sp(c).edit().putInt("tx.port", (v < 1 || v > 65535) ? 62031 : v).apply();
    }

    /** 本机 DMR ID（7 位左右）。0 = 还没填，此时不允许发射。 */
    public static int dmrId(Context c) { return sp(c).getInt("tx.id", 0); }

    public static void setDmrId(Context c, int v) {
        sp(c).edit().putInt("tx.id", Math.max(0, v)).apply();
    }

    /**
     * 热点别名 ID（ESSID），0~99。0 = 不使用别名，直接用裸 DMR ID 登录。
     *
     * <p>同一个 DMR ID 已经有一台热点在线时，必须靠它才能并存 ——
     * 主站按「ID + ESSID」区分设备，裸 ID 撞了会被踢。
     */
    public static int essid(Context c) {
        int v = sp(c).getInt("tx.essid", 0);
        return (v < 0 || v > 99) ? 0 : v;
    }

    public static void setEssid(Context c, int v) {
        sp(c).edit().putInt("tx.essid", Math.max(0, Math.min(v, 99))).apply();
    }

    /** 主站密码（BrandMeister 是 selfcare 里设的那串）。 */
    public static String password(Context c) {
        String v = sp(c).getString("tx.pwd", "");
        return v == null ? "" : v;
    }

    public static void setPassword(Context c, String v) {
        sp(c).edit().putString("tx.pwd", v == null ? "" : v).apply();
    }

    /** 呼号，随 RPTL 上报，主站据此建台站记录。 */
    public static String callsign(Context c) {
        String v = sp(c).getString("tx.call", "");
        return v == null ? "" : v.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static void setCallsign(Context c, String v) {
        sp(c).edit().putString("tx.call", v == null ? "" : v.trim()).apply();
    }

    /** 默认时隙：1 或 2。国内话组基本都在 TS2。 */
    public static int slot(Context c) {
        int v = sp(c).getInt("tx.slot", 2);
        return v == 1 ? 1 : 2;
    }

    public static void setSlot(Context c, int v) {
        sp(c).edit().putInt("tx.slot", v == 1 ? 1 : 2).apply();
    }

    /** 色码 0~15。国内基本是 1。 */
    public static int colorCode(Context c) {
        int v = sp(c).getInt("tx.cc", 1);
        return (v < 0 || v > 15) ? 1 : v;
    }

    public static void setColorCode(Context c, int v) {
        sp(c).edit().putInt("tx.cc", Math.max(0, Math.min(v, 15))).apply();
    }

    /** 麦克风增益 dB，−24 ~ +12。0 = 不增不减。 */
    public static float micGainDb(Context c) {
        float v = sp(c).getFloat("tx.mic", 0f);
        return (v < -24f || v > 12f) ? 0f : v;
    }

    public static void setMicGainDb(Context c, float db) {
        sp(c).edit().putFloat("tx.mic", Math.max(-24f, Math.min(db, 12f))).apply();
    }

    /** 原生接收开关。关掉就只发不收（省电，也避免听见绕回来的自己）。 */
    public static boolean rxOn(Context c) { return sp(c).getBoolean("tx.rxOn", true); }

    public static void setRxOn(Context c, boolean on) {
        sp(c).edit().putBoolean("tx.rxOn", on).apply();
    }

    /** 只收当前话组。主站同时推多路时不混音。 */
    public static boolean onlyWatchTg(Context c) {
        return sp(c).getBoolean("tx.onlyWatch", false);
    }

    public static void setOnlyWatchTg(Context c, boolean on) {
        sp(c).edit().putBoolean("tx.onlyWatch", on).apply();
    }

    /** 当前关注 / 发射目标的话组。0 = 还没选。 */
    public static int watchTg(Context c) { return sp(c).getInt("tx.watchTg", 0); }

    public static void setWatchTg(Context c, int tg) {
        sp(c).edit().putInt("tx.watchTg", Math.max(0, tg)).apply();
    }

    /* ==================== 界面状态 ==================== */

    /** 上次停在哪一页：listen / dash / settings。 */
    public static String view(Context c) {
        String v = sp(c).getString("view", "listen");
        if ("dash".equals(v) || "settings".equals(v)) return v;
        return "listen";
    }

    public static void setView(Context c, String v) {
        sp(c).edit().putString("view", v).apply();
    }
}
