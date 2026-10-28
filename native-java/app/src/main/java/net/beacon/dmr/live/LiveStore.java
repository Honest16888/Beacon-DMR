package net.beacon.dmr.live;

import net.beacon.dmr.core.Geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实时活动（Last Heard）的内存模型 + 统计聚合。
 *
 * <p>两张表：
 * <ul>
 *   <li>{@code active} —— 此刻还在通话的会话（键 = ContextID，退化时用 SessionID）。
 *       看板第一张卡「当前活动通联」就是它的大小。</li>
 *   <li>{@code history} —— 每起一次新会话追加一条，通话结束时回填时长。
 *       看板其余所有数字都从这张表按 30 分钟窗口算出来。</li>
 * </ul>
 *
 * <p>规则逐条对齐 WebView 版的 {@code onMqtt()} / {@code statStart()} /
 * {@code statFinish()} / {@code dashData()} —— 两个版本的看板要对得上，
 * 差一个口径就会被问「为什么这边 87 那边 92」。
 *
 * <p>★ 这里**不认识 JSON**：报文由 {@link LhClient} 解析成 {@link Event} 再送进来。
 * 这样这个类就是纯 Java，桌面 JDK 直接能跑测试（见 tests/StatsTest.java）——
 * 统计口径是看板的全部正确性所在，不能只靠肉眼在手机上看。
 *
 * <p>线程：{@link #onEvent} 在 WebSocket 读线程上调，界面在主线程上读
 * {@link #dash()} / {@link #recent()}。全部 synchronized，单条报文处理是微秒级，
 * 每秒十几条完全不成问题。界面侧另有节流，不会每来一条就重绘。
 */
public final class LiveStore {

    /** 历史表上限。超过就砍掉最老的 1500 条 —— 和 WebView 版一致。 */
    private static final int MAX_HISTORY = 4000;
    private static final int TRIM = 1500;
    /** 看板的统计窗口。 */
    public static final long WINDOW_MS = 30L * 60 * 1000;
    /** 通联记录保留多久。 */
    public static final long KEEP_MS = 24L * 3600 * 1000;

    /** 一条已解析的 Last Heard 报文。字段名与官方 JSON 一一对应。 */
    public static final class Event {
        public String key = "";
        public long tg;
        public long srcId;
        public String call = "";
        public String name = "";
        public String alias = "";
        public String master = "";
        public int slot;
        public boolean priv;
        /** epoch 秒；0 表示报文里没给。 */
        public long start;
        public long stop;
        /** 原始 Event 字段，只有 "Session-Stop" 会被特殊处理。 */
        public String event = "";

        public boolean isStop() { return "Session-Stop".equals(event); }
    }

    /** 正在通话的会话。 */
    public static final class Session {
        public final String key;
        public final long tg;
        public final long srcId;
        public final String call;
        public final String name;
        public final String alias;
        public final String master;
        public final int slot;
        public final boolean priv;
        public final long start;          // epoch 秒
        public long stop;                 // epoch 秒，0 = 还在进行
        public long seen;                 // 最后一次报文，ms

        Session(String key, long tg, long srcId, String call, String name, String alias,
                String master, int slot, boolean priv, long start, long stop, long seen) {
            this.key = key;
            this.tg = tg;
            this.srcId = srcId;
            this.call = call;
            this.name = name;
            this.alias = alias;
            this.master = master;
            this.slot = slot;
            this.priv = priv;
            this.start = start;
            this.stop = stop;
            this.seen = seen;
        }

        public int durSec(long nowMs) {
            long s = stop > 0 ? stop : nowMs / 1000;
            return (int) Math.max(0, s - start);
        }
    }

    /** 一条通联记录（历史）。 */
    public static final class Rec {
        public final long tg;
        public final long srcId;
        public final String call;
        public final String iso;
        public final String master;
        public final int slot;
        public final boolean priv;
        public final String key;
        public final long t;              // 会话开始，ms
        public long dur;                  // 秒，0 = 尚未结束

        Rec(long tg, long srcId, String call, String iso, String master,
            int slot, boolean priv, String key, long t) {
            this.tg = tg;
            this.srcId = srcId;
            this.call = call == null ? "" : call;
            this.iso = iso == null ? "" : iso;
            this.master = master == null ? "" : master;
            this.slot = slot;
            this.priv = priv;
            this.key = key;
            this.t = t;
        }
    }

    /** 看板一次渲染需要的全部数字。不可变快照，界面拿去随便读。 */
    public static final class Dash {
        public int activeCount;
        /** 30 分钟窗口内的记录。 */
        public final List<Rec> window = new ArrayList<>();
        public final Map<Long, Integer> byTg = new LinkedHashMap<>();
        public final Map<String, Integer> byCountry = new LinkedHashMap<>();
        public final Map<String, Integer> byMaster = new LinkedHashMap<>();
        public final Map<String, Integer> byCall = new LinkedHashMap<>();
        public int priv, slot0, slot1, uniqCall, uniqTg;
        public double avgDur;
        /** 每分钟新起通联数，最后一格是当前分钟。 */
        public int[] minutes = new int[10];
        public int span = 10;
        public int peak;
        public int total;
        public long startedAt = System.currentTimeMillis();
    }

    private final LinkedHashMap<String, Session> active = new LinkedHashMap<>();
    private final ArrayList<Rec> history = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    /* ==================== 输入 ==================== */

    /**
     * 处理一条 Last Heard 报文。
     *
     * <p>{@code Event} 为 {@code Session-Stop} 表示这通结束，其余一律当作
     * 「开始 / 更新」—— 官方除了 Session-Start 还有若干中间态事件，
     * 只认 Stop 才不会漏掉只发了一次中间态的会话。
     */
    public synchronized void onEvent(Event p) {
        if (p == null) return;
        String key = p.key;

        if (p.isStop()) {
            Session prev = active.remove(key);
            if (prev != null) finish(prev);
            return;
        }

        Session prev = active.get(key);
        String call = p.call.isEmpty() && prev != null ? prev.call : p.call;
        String name = p.name.isEmpty() && prev != null ? prev.name : p.name;
        String alias = p.alias.isEmpty() && prev != null ? prev.alias : p.alias;

        long start = p.start;
        if (start == 0 && prev != null) start = prev.start;
        if (start == 0) start = System.currentTimeMillis() / 1000;

        Session now = new Session(key, p.tg, p.srcId, call, name, alias,
                p.master, p.slot, p.priv, start, p.stop, System.currentTimeMillis());
        active.put(key, now);
        if (prev == null) start(now);
    }

    /** 把 30 分钟都没动静的会话清掉 —— 服务端偶尔不发 Stop，不清就会一直显示在通话。 */
    public synchronized void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Session s = it.next().getValue();
            if (now - s.seen > 300_000L && now / 1000 - s.start > 360) it.remove();
        }
    }

    public synchronized void reset() {
        active.clear();
        history.clear();
    }

    private void start(Session s) {
        String iso = Geo.regionIso(s.tg);
        history.add(new Rec(s.tg, s.srcId, s.call, iso, s.master,
                s.slot, s.priv, s.key, System.currentTimeMillis()));
        if (history.size() > MAX_HISTORY) {
            history.subList(0, TRIM).clear();
        }
    }

    private void finish(Session s) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Rec r = history.get(i);
            if (r.key.equals(s.key)) {
                r.dur = Math.max(1, System.currentTimeMillis() / 1000 - s.start);
                return;
            }
        }
    }

    /* ==================== 输出 ==================== */

    public synchronized int activeCount() { return active.size(); }

    /** 正在通话的会话，最近有声音的排前面。 */
    public synchronized List<Session> activeList() {
        List<Session> l = new ArrayList<>(active.values());
        Collections.sort(l, (a, b) -> Long.compare(b.seen, a.seen));
        return l;
    }

    /** 通联记录，新的在前。 */
    public synchronized List<Rec> recent(int limit, long withinMs) {
        long now = System.currentTimeMillis();
        List<Rec> out = new ArrayList<>(Math.min(limit, history.size()));
        for (int i = history.size() - 1; i >= 0 && out.size() < limit; i--) {
            Rec r = history.get(i);
            if (now - r.t > withinMs) break;
            out.add(r);
        }
        return out;
    }

    public synchronized int total() { return history.size(); }

    /** 看板的一次性快照。 */
    public synchronized Dash dash() {
        long now = System.currentTimeMillis();
        Dash d = new Dash();
        d.activeCount = active.size();
        d.total = history.size();
        d.startedAt = startedAt;

        for (Rec r : history) {
            if (now - r.t >= WINDOW_MS) continue;
            d.window.add(r);
            inc(d.byTg, r.tg);
            if (!r.iso.isEmpty()) incS(d.byCountry, r.iso);
            if (!r.master.isEmpty()) incS(d.byMaster, r.master);
            if (!r.call.isEmpty()) incS(d.byCall, r.call);
            if (r.priv) d.priv++;
            if (r.slot == 1) d.slot1++; else d.slot0++;
        }

        Set<String> calls = new LinkedHashSet<>();
        Set<Long> tgs = new LinkedHashSet<>();
        long durSum = 0;
        int durN = 0;
        for (Rec r : d.window) {
            if (!r.call.isEmpty()) calls.add(r.call);
            tgs.add(r.tg);
            if (r.dur > 0) { durSum += r.dur; durN++; }
        }
        d.uniqCall = calls.size();
        d.uniqTg = tgs.size();
        d.avgDur = durN == 0 ? 0 : (double) durSum / durN;

        /* 走势窗口随运行时长自适应：刚打开是 10 分钟，慢慢长到 30 分钟，
         * 与统计窗口对齐 —— 一上来就画 30 分钟的话，前 20 分钟全是 0，
         * 看着像「没有通联」。 */
        int ranMin = (int) Math.ceil((now - startedAt) / 60000.0) + 1;
        d.span = Math.max(10, Math.min(30, ranMin));
        d.minutes = new int[d.span];
        long t0 = (now / 60000) * 60000;
        int last = d.span - 1;
        for (Rec r : d.window) {
            int i = last + (int) Math.floor((r.t - t0) / 60000.0);
            if (i >= 0 && i < d.span) d.minutes[i]++;
        }
        for (int v : d.minutes) if (v > d.peak) d.peak = v;
        return d;
    }

    private static void inc(Map<Long, Integer> m, long k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static void incS(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    /** 把 Map 按值降序取前 n 个。看板每个排行榜都用它。 */
    public static <K> List<Map.Entry<K, Integer>> top(Map<K, Integer> m, int n) {
        List<Map.Entry<K, Integer>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> {
            int c = b.getValue().compareTo(a.getValue());
            if (c != 0) return c;
            /* 计数相同按 key 排，否则每次刷新顺序都在跳。 */
            return String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey()));
        });
        return l.size() > n ? l.subList(0, n) : l;
    }

    /** 给「通话构成」算百分比，除零保护。 */
    public static int pct(int n, int total) {
        if (total <= 0) return 0;
        return Math.round(n * 100f / total);
    }

    /** 空的 map 工具，避免各处 new HashMap 带错泛型。 */
    public static <K, V> Map<K, V> map() { return new HashMap<>(); }
}
