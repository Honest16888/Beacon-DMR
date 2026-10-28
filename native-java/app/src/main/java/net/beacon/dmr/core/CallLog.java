package net.beacon.dmr.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 通联记录 —— 谁在哪个话组说了多久。
 *
 * <p>对应 WebView 版的 {@code state.calls}：上限 <b>50 条</b>、保留 <b>24 小时</b>，
 * 新的在前。
 *
 * <p><b>为什么用 TSV 而不是 JSON：</b>本工程零第三方依赖，手写 JSON 解析器不值当；
 * 而这份数据是一张纯二维表，字段里不可能出现制表符（呼号 `/`、名字是中文或字母），
 * 所以 `\\t` 分隔 + `\\n` 分行就是最省事且不会出错的序列化。
 * 万一是老版本留下的畸形行，跳过那一行而不是整份丢掉 —— 一条坏记录不该
 * 让用户的整个记录列表消失。
 */
public final class CallLog {

    /** 50 条。再多用户也翻不动，而且读屏时要滚很久。 */
    public static final int MAX = 50;
    /** 24 小时前的记录不再显示。 */
    public static final long TTL_MS = 24L * 3600 * 1000;

    private static final String KEY = "calls.tsv";

    private CallLog() {
    }

    /** 一条记录。字段全 public final —— 它就是个值对象。 */
    public static final class Rec {
        public final int tg;
        public final int srcId;
        public final String call;
        public final String name;
        /** epoch 毫秒 */
        public final long at;
        /** 通话时长，秒 */
        public final int dur;
        public final boolean priv;

        public Rec(int tg, int srcId, String call, String name, long at, int dur, boolean priv) {
            this.tg = tg;
            this.srcId = srcId;
            this.call = call == null ? "" : call;
            this.name = name == null ? "" : name;
            this.at = at;
            this.dur = dur;
            this.priv = priv;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("dmr-calls", Context.MODE_PRIVATE);
    }

    public static List<Rec> load(Context c) {
        String raw = sp(c).getString(KEY, "");
        List<Rec> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;

        long cut = System.currentTimeMillis() - TTL_MS;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;         // 畸形行跳过，别让一行毁掉整份
            try {
                long at = Long.parseLong(f[0]);
                if (at < cut) continue;         // 过期的不进内存
                out.add(new Rec(Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                        f[3], f[4], at, Integer.parseInt(f[5]), "1".equals(f[6])));
            } catch (NumberFormatException e) {
                /* 老版本留下的、或者写坏的 —— 丢掉这一行就好。 */
            }
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** 加一条（放最前面），顺手裁剪再存盘。返回裁剪后的列表。 */
    public static List<Rec> add(Context c, Rec r) {
        List<Rec> list = load(c);

        /* 去重：同一话组 + 同一 srcId + 3 秒内，算同一条。
         * HOSE 偶尔会连发两次 CALL_END（断线重连、切网络），不去重就是双击。 */
        if (!list.isEmpty()) {
            Rec top = list.get(0);
            if (top.tg == r.tg && top.srcId == r.srcId && r.at - top.at < 3000) {
                return list;
            }
        }

        list.add(0, r);
        long cut = System.currentTimeMillis() - TTL_MS;
        List<Rec> kept = new ArrayList<>(MAX);
        for (Rec x : list) {
            if (x.at < cut) continue;
            if (kept.size() >= MAX) break;
            kept.add(x);
        }
        save(c, kept);
        return kept;
    }

    public static void save(Context c, List<Rec> list) {
        StringBuilder sb = new StringBuilder();
        for (Rec r : list) {
            sb.append(r.at).append('\t')
              .append(r.tg).append('\t')
              .append(r.srcId).append('\t')
              .append(clean(r.call)).append('\t')
              .append(clean(r.name)).append('\t')
              .append(r.dur).append('\t')
              .append(r.priv ? "1" : "0").append('\n');
        }
        sp(c).edit().putString(KEY, sb.toString()).apply();
    }

    public static void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }

    /** 字段里不能有制表符和换行 —— 否则整份表都会被读歪。 */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
