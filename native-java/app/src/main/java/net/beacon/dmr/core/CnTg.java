package net.beacon.dmr.core;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 国内（China DMR / radiowo）话组表 —— 33 条。
 *
 * <p>数据来自 {@code 860.radiowo.com/api/v1/talkgroups}（2026-10-26 快照），
 * 内嵌为 {@code assets/cn_tg.tsv}，格式 {@code 话组号:名称:说明:时隙}。
 *
 * <p>★ <b>为什么不能和国际 BM 那张 1873 条的表混用：</b>
 * 同一个号码在两套网络里含义完全不同 —— {@code 460} 在国际网是「中国」区域组，
 * 在国内网是「460 全国公共」。合并的话必然出现「名字对不上号码」。
 * 所以国内单列一张，{@link #nameOf} 只在国内模式下被调用。
 *
 * <p>★ 表里的 {@code 8601} 这类是 4 位、{@code 86023} 是 5 位、
 * {@code 460} 是 3 位 —— 长度不统一，所以按 {@code int} 存而不是按字符串。
 */
public final class CnTg {

    private static final String TAG = "CnTg";

    public static final class Tg {
        public final int id;
        public final String name;
        public final String desc;
        /** 时隙，0 基（表里写的是 1/2）。 */
        public final int slot;

        Tg(int id, String name, String desc, int slot) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.desc = desc == null ? "" : desc;
            this.slot = slot;
        }
    }

    private static volatile List<Tg> all;
    private static volatile java.util.Map<Integer, Tg> byId;

    private CnTg() {
    }

    public static boolean isReady() {
        return all != null;
    }

    public static List<Tg> all() {
        List<Tg> l = all;
        return l == null ? Collections.emptyList() : l;
    }

    public static int size() {
        List<Tg> l = all;
        return l == null ? 0 : l.size();
    }

    /** 话组号 → 名称。查不到返回 null（调用方要能接住）。 */
    public static String nameOf(int id) {
        java.util.Map<Integer, Tg> m = byId;
        if (m == null) return null;
        Tg t = m.get(id);
        return t == null ? null : t.name;
    }

    public static Tg get(int id) {
        java.util.Map<Integer, Tg> m = byId;
        return m == null ? null : m.get(id);
    }

    /** 加载（幂等）。表只有 33 行，主线程也可以，但仍然放后台统一处理。 */
    public static synchronized void load(Context ctx, Runnable onReady) {
        if (all != null) {
            if (onReady != null) onReady.run();
            return;
        }
        List<Tg> list = new ArrayList<>(40);
        java.util.Map<Integer, Tg> map = new java.util.HashMap<>(64);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("cn_tg.tsv"), "UTF-8"), 2048)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split(":", 4);
                if (p.length < 2) continue;
                int id;
                try {
                    id = Integer.parseInt(p[0].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id <= 0) continue;
                int slot = 1;
                if (p.length > 3) {
                    try {
                        slot = Math.max(1, Math.min(2, Integer.parseInt(p[3].trim())));
                    } catch (NumberFormatException ignore) { }
                }
                Tg t = new Tg(id, p[1].trim(), p.length > 2 ? p[2].trim() : "", slot - 1);
                list.add(t);
                map.putIfAbsent(id, t);
            }
        } catch (Exception e) {
            Log.w(TAG, "读 cn_tg.tsv 失败：" + e);
        }
        list.sort((a, b) -> Integer.compare(a.id, b.id));
        all = list;
        byId = map;
        Log.i(TAG, "国内话组表就绪：" + list.size() + " 条");
        if (onReady != null) onReady.run();
    }

    /** 国内常用话组（硬编码，和 WebView 版 CN_COMMON 一致）。 */
    public static final int[] COMMON = {460, 860, 86002, 86008, 86010, 86020, 86023, 86028};

    /** 国内默认订阅（首次切到国内时用）。 */
    public static final int[] DEFAULT_SUBS = {460, 860};

    /** 给日志/诊断用。 */
    public static String describe() {
        List<Tg> l = all;
        return String.format(Locale.US, "%d 组", l == null ? 0 : l.size());
    }
}
