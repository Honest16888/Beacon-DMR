package net.beacon.dmr.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置话组表 + 用户订阅。
 *
 * <p>表来自 {@code assets/talkgroups.tsv}，格式是「id \t 名称 \t 说明」，
 * 内容和 WebView 版 {@code web/index.html} 里的 {@code cnTgData} 一致
 * （国内组）再加几个国际常用组。以后接上 api.brandmeister.network 的在线表时，
 * 这份内置表就退化成「离线兜底」。
 *
 * <p>订阅存 SharedPreferences，键是 {@code tg.subs}，值是逗号分隔的 ID。
 * 存字符串而不是 StringSet：StringSet 在某些 ROM 上顺序不稳定，
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
    /** 没订阅过时默认订阅这几个 —— 装完就有声音，不用先勾一遍。 */
    private static final int[] DEFAULTS = {91, 460, 98006};

    private Talkgroups() {}

    public static List<Tg> loadAll(Context ctx) {
        List<Tg> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("talkgroups.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                if (p.length < 2) continue;
                int id;
                try { id = Integer.parseInt(p[0].trim()); } catch (NumberFormatException e) { continue; }
                String name = p[1].trim();
                String desc = p.length > 2 ? p[2].trim() : "";
                out.add(new Tg(id, name, desc));
            }
        } catch (Exception e) {
            /* 表读不出来时退回一个最小集合：至少让 App 能出声，
             * 而不是给用户一个空列表加一句「加载失败」。 */
            out.add(new Tg(91, "世界组", "Worldwide"));
            out.add(new Tg(460, "中国组", "China Nationwide"));
        }
        return out;
    }

    public static Set<Integer> loadSubs(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_SUBS, null);
        if (raw == null) {
            Set<Integer> d = new LinkedHashSet<>();
            for (int v : DEFAULTS) d.add(v);
            return d;
        }
        Set<Integer> s = new LinkedHashSet<>();
        for (String t : raw.split(",")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            try { s.add(Integer.parseInt(t)); } catch (NumberFormatException ignore) { }
        }
        return s;
    }

    public static void saveSubs(Context ctx, Set<Integer> subs) {
        StringBuilder sb = new StringBuilder();
        for (int v : subs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SUBS, sb.toString()).apply();
    }

    public static int[] toArray(Set<Integer> subs) {
        int[] a = new int[subs.size()];
        int i = 0;
        for (int v : subs) a[i++] = v;
        return a;
    }

    /** 在表里找话组名；找不到就返回 "TG 12345"。 */
    public static String nameOf(List<Tg> all, int id) {
        for (Tg t : all) if (t.id == id) return t.name;
        return "TG " + id;
    }
}
