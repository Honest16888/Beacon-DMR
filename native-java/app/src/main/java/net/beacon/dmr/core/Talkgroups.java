package net.beacon.dmr.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 话组模型 + 用户订阅 / 自定义组的持久化。
 *
 * <p>表的加载与合并交给 {@link TalkgroupRepo}（内置 → 缓存 → 在线，三级来源）。
 * 这里只管「哪些是我订阅的」「哪些是我手动加的」。
 *
 * <p>存字符串而不是 StringSet：StringSet 在部分 ROM 上顺序不稳定，
 * 而且调试时一行逗号串比看一个 Set 直观得多。
 */
public final class Talkgroups {

    public static final class Tg {
        public final int id;
        public final String name;
        public final String desc;

        public Tg(int id, String name, String desc) {
            this.id = id;
            this.name = name;
            this.desc = desc;
        }
    }

    private static final String PREFS = "dmr";
    private static final String KEY_SUBS = "tg.subs";
    private static final String KEY_CUSTOM = "tg.custom";
    /** 没订阅过时默认订阅这几个 —— 装完就有声音，不用先勾一遍。 */
    private static final int[] DEFAULTS = {91, 460};

    private Talkgroups() {}

    /* ==================== 订阅 ==================== */

    public static Set<Integer> loadSubs(Context ctx) {
        String raw = prefs(ctx).getString(KEY_SUBS, null);
        if (raw == null) {
            Set<Integer> d = new LinkedHashSet<>();
            for (int v : DEFAULTS) d.add(v);
            return d;
        }
        return parseInts(raw);
    }

    public static void saveSubs(Context ctx, Set<Integer> subs) {
        prefs(ctx).edit().putString(KEY_SUBS, join(subs)).apply();
    }

    /* ==================== 手动添加的组 ==================== */

    public static Set<Integer> loadCustom(Context ctx) {
        return parseInts(prefs(ctx).getString(KEY_CUSTOM, ""));
    }

    public static void saveCustom(Context ctx, Set<Integer> custom) {
        prefs(ctx).edit().putString(KEY_CUSTOM, join(custom)).apply();
    }

    /* ==================== 工具 ==================== */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<Integer> parseInts(String raw) {
        Set<Integer> s = new LinkedHashSet<>();
        if (raw == null) return s;
        for (String t : raw.split(",")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            try { s.add(Integer.parseInt(t)); } catch (NumberFormatException ignore) { }
        }
        return s;
    }

    private static String join(Set<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (int v : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    public static int[] toArray(Set<Integer> subs) {
        int[] a = new int[subs.size()];
        int i = 0;
        for (int v : subs) a[i++] = v;
        return a;
    }
}
