import net.beacon.dmr.live.LiveStore;

import java.util.List;
import java.util.Map;

/**
 * 看板统计口径自检。
 *
 * <p>看板上的每个数字都是从 {@link LiveStore} 按 30 分钟窗口算出来的。
 * 这些口径错一个，用户看到的是一张「数字不对」的看板 —— 比不显示更糟，
 * 因为它看起来是权威的。所以在桌面就把口径钉死，不靠肉眼在手机上看。
 *
 * <p>{@code LiveStore} 刻意不认识 JSON（报文由 LhClient 解析成 Event 再送进来），
 * 所以这个测试不需要 android.jar。
 */
public final class StatsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, Object detail) {
        if (cond) { pass++; System.out.println("  \u2713 " + name); }
        else {
            fail++;
            System.out.println("  \u2717 " + name + (detail == null ? "" : "  \u2192 " + detail));
        }
    }

    private static LiveStore.Event ev(String key, long tg, long src, String call,
                                      String master, int slot, boolean priv) {
        LiveStore.Event e = new LiveStore.Event();
        e.key = key;
        e.tg = tg;
        e.srcId = src;
        e.call = call;
        e.master = master;
        e.slot = slot;
        e.priv = priv;
        e.start = System.currentTimeMillis() / 1000;
        e.event = "Session-Start";
        return e;
    }

    private static LiveStore.Event stop(String key) {
        LiveStore.Event e = new LiveStore.Event();
        e.key = key;
        e.event = "Session-Stop";
        return e;
    }

    public static void main(String[] args) {
        System.out.println("\n=== 8. 看板统计口径 ===");

        LiveStore s = new LiveStore();

        /* ---- 三条不同话组 ---- */
        s.onEvent(ev("c1", 2621, 2620001, "DL1ABC", "2621", 1, false));   // 德国 TS2
        s.onEvent(ev("c2", 91, 4600001, "BG7KUH", "4602", 0, false));     // 世界组 TS1
        s.onEvent(ev("c3", 2621, 2620002, "DL2XYZ", "2621", 1, true));    // 德国 私呼

        LiveStore.Dash d = s.dash();
        ok("窗口内 3 条", d.window.size() == 3, d.window.size());
        ok("正在通话 3 个", d.activeCount == 3, d.activeCount);
        ok("涉及话组 2 个", d.uniqTg == 2, d.uniqTg);
        ok("活跃电台 3 台", d.uniqCall == 3, d.uniqCall);
        ok("时隙 1 一条 / 时隙 2 两条", d.slot0 == 1 && d.slot1 == 2, d.slot0 + "/" + d.slot1);
        ok("私密呼叫 1 条", d.priv == 1, d.priv);
        ok("2621 归到德国（de）", d.byCountry.containsKey("de"), d.byCountry);
        ok("91 不进国家榜（91 不是国家码）", !d.byCountry.containsKey(""), d.byCountry);
        ok("主站分布 4602 有 1 条", Integer.valueOf(1).equals(d.byMaster.get("4602")), d.byMaster);

        /* ---- 同一个 key 再来一条：只更新，不新增 ---- */
        s.onEvent(ev("c1", 2621, 2620001, "DL1ABC", "2621", 1, false));
        d = s.dash();
        ok("同一会话重复报文不重复计数", d.window.size() == 3, d.window.size());
        ok("重复报文后仍在通话 3 个", d.activeCount == 3, d.activeCount);

        /* ---- 结束一通 ---- */
        s.onEvent(stop("c2"));
        d = s.dash();
        ok("结束后正在通话 2 个", d.activeCount == 2, d.activeCount);
        boolean durOk = false;
        for (LiveStore.Rec r : d.window) if ("c2".equals(r.key) && r.dur >= 1) durOk = true;
        ok("结束的那条回填了时长（≥1 秒）", durOk, null);
        ok("结束后历史仍是 3 条", d.window.size() == 3, d.window.size());

        /* ---- 走势：全部落在当前这一分钟 ---- */
        ok("走势窗口 10 分钟（刚启动）", d.span == 10 && d.minutes.length == 10, d.span);
        int sum = 0;
        for (int v : d.minutes) sum += v;
        ok("走势合计 = 3（全部落在最近一分钟）", sum == 3, sum);
        ok("峰值 = 3", d.peak == 3, d.peak);
        ok("更早的分钟都是 0", d.minutes[0] == 0 && d.minutes[7] == 0,
                d.minutes[0] + "/" + d.minutes[7]);

        /* ---- 排行榜排序 ---- */
        List<Map.Entry<Long, Integer>> topTg = LiveStore.top(d.byTg, 10);
        ok("话组榜第一是 2621（2 条）", topTg.get(0).getKey() == 2621L
                && topTg.get(0).getValue() == 2, topTg.get(0));
        List<Map.Entry<String, Integer>> topM = LiveStore.top(d.byMaster, 10);
        ok("主站榜第一是 2621（2 条）", "2621".equals(topM.get(0).getKey()), topM.get(0));

        /* ---- 通联记录：新的在前 ---- */
        List<LiveStore.Rec> rec = s.recent(50, LiveStore.KEEP_MS);
        ok("记录 3 条", rec.size() == 3, rec.size());
        ok("最近一条在最前（c3）", "c3".equals(rec.get(0).key), rec.get(0).key);
        ok("limit 生效", s.recent(2, LiveStore.KEEP_MS).size() == 2, null);

        /* ---- 空库不炸 ---- */
        LiveStore empty = new LiveStore();
        LiveStore.Dash e0 = empty.dash();
        ok("空库：活动 0 / 窗口 0", e0.activeCount == 0 && e0.window.size() == 0, null);
        ok("空库：平均时长 0（不是 NaN）", e0.avgDur == 0, e0.avgDur);
        ok("空库：排行榜为空", LiveStore.top(e0.byTg, 10).isEmpty(), null);
        ok("空库：记录为空", empty.recent(50, LiveStore.KEEP_MS).isEmpty(), null);

        /* ---- 百分比 ---- */
        ok("pct(1,4) = 25", LiveStore.pct(1, 4) == 25, LiveStore.pct(1, 4));
        ok("pct(0,0) = 0（除零保护）", LiveStore.pct(0, 0) == 0, LiveStore.pct(0, 0));

        /* ---- reset ---- */
        s.reset();
        ok("reset 后清空", s.dash().window.size() == 0 && s.activeCount() == 0, null);

        System.out.println("\n================  " + pass + " \u901a\u8fc7 / " + fail + " \u5931\u8d25  ================");
        if (fail > 0) System.exit(1);
    }
}
