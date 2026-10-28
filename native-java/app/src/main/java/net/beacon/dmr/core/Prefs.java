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
    public static boolean recOn(Context c) { return sp(c).getBoolean("recOn", false); }

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
