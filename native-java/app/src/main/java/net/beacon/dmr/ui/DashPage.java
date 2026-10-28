package net.beacon.dmr.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.beacon.dmr.R;
import net.beacon.dmr.core.Geo;
import net.beacon.dmr.core.Palette;
import net.beacon.dmr.live.LiveStore;
import net.beacon.dmr.live.MasterRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 看板页。数据源只有一个：{@link LiveStore} 的快照。
 *
 * <p>刷新节奏由 {@code MainActivity} 的 1 秒 tick 驱动，且**只在看板可见时**
 * 才刷 —— 主页和设置页不该为一张看不见的图表每秒重建几十个 View。
 *
 * <p>数字口径逐条对齐 WebView 版 {@code renderDash()}：
 * 6 张卡、30 分钟窗口、四个 Top 10、通话构成四项。
 */
public final class DashPage {

    public interface Listener {
        /** 点实时活动里的一条，把那个话组加进收听。 */
        void onPickTg(long tg, String name);
    }

    private static final int LIVE_MAX = 12;
    private static final int LOG_MAX = 50;

    private final Activity act;
    private final LiveStore store;
    private final Palette pal;
    private final Listener cb;
    private final LayoutInflater inf;

    private final LinearLayout statCards;
    private final LineChartView chart;
    private final TextView tlStat, liveCount, liveEmpty, logCount, mixFoot;
    private final EditText liveSearch;
    private final LinearLayout liveList, logList;
    private final RankList rankTg, rankCountry, rankMaster, rankCall, mixPanel;

    /** 六张统计卡的 [标签, 数值, 单位, 脚注] 四个控件，建一次复用。 */
    private final TextView[] cardVal = new TextView[6];
    private final TextView[] cardUnit = new TextView[6];
    private final TextView[] cardFoot = new TextView[6];

    /** 话组号 → 名称。由 MainActivity 提供（它手里有整张话组表）。 */
    public interface TgName {
        String name(long id);
    }

    private TgName tgName;

    private String filter = "";
    private String liveSig = "";
    private String logSig = "";

    public DashPage(Activity a, View root, LiveStore s, Palette p, Listener l) {
        this.act = a;
        this.store = s;
        this.pal = p;
        this.cb = l;
        this.inf = LayoutInflater.from(a);

        statCards = root.findViewById(R.id.statCards);
        chart = root.findViewById(R.id.chartTl);
        tlStat = root.findViewById(R.id.tlStat);
        liveCount = root.findViewById(R.id.liveCount);
        liveEmpty = root.findViewById(R.id.liveEmpty);
        liveSearch = root.findViewById(R.id.liveSearch);
        liveList = root.findViewById(R.id.liveList);
        logList = root.findViewById(R.id.logList);
        logCount = root.findViewById(R.id.logCount);
        mixPanel = new RankList(root.findViewById(R.id.mixPanel), false);
        mixFoot = root.findViewById(R.id.mixFoot);

        rankTg = new RankList(root.findViewById(R.id.rankTg), true);
        rankCountry = new RankList(root.findViewById(R.id.rankCountry), true);
        rankMaster = new RankList(root.findViewById(R.id.rankMaster), true);
        rankCall = new RankList(root.findViewById(R.id.rankCall), true);

        buildStatCards();

        liveSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a1, int c, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                filter = e.toString().trim().toLowerCase(Locale.ROOT);
                liveSig = "";                       // 强制重建
                refreshLive();
            }
        });
    }

    public void setTgName(TgName n) { this.tgName = n; }

    private String name(long id) {
        String n = tgName == null ? null : tgName.name(id);
        return Geo.tgLabel(id, n);
    }

    /* ==================== 统计卡 ==================== */

    private void buildStatCards() {
        statCards.removeAllViews();
        String[][] meta = {
                {"📡 当前活动通联", "实时在线会话数"},
                {"⏱ 近 30 分通联", "每分钟约 —"},
                {"🎙 活跃电台", "按呼号去重"},
                {"🗂 涉及话组", "近 30 分钟"},
                {"⌛ 平均时长", "已完成通联"},
                {"🔒 私密呼叫", "不广播的直呼"},
        };
        for (int row = 0; row < 3; row++) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row > 0) rlp.topMargin = Ui.dpI(act, 8);
            r.setLayoutParams(rlp);

            for (int col = 0; col < 2; col++) {
                int i = row * 2 + col;
                View cell = inf.inflate(R.layout.item_stat, r, false);
                ((TextView) cell.findViewById(R.id.lbl)).setText(meta[i][0]);
                ((TextView) cell.findViewById(R.id.lbl)).setTextColor(pal.muted);
                ((TextView) cell.findViewById(R.id.val)).setTextColor(pal.text);
                ((TextView) cell.findViewById(R.id.unit)).setTextColor(pal.dim);
                cardVal[i] = cell.findViewById(R.id.val);
                cardUnit[i] = cell.findViewById(R.id.unit);
                cardFoot[i] = cell.findViewById(R.id.foot);
                cardFoot[i].setText(meta[i][1]);
                cardFoot[i].setTextColor(pal.dim);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (col == 0) lp.rightMargin = Ui.dpI(act, 5);
                else lp.leftMargin = Ui.dpI(act, 5);
                cell.setLayoutParams(lp);
                r.addView(cell);
            }
            statCards.addView(r);
        }
    }

    /* ==================== 刷新 ==================== */

    /** 全量刷新。1 秒一次，只在看板可见时调。 */
    public void refresh() {
        LiveStore.Dash d = store.dash();

        setCard(0, String.valueOf(d.activeCount), "", "实时在线会话数");
        setCard(1, String.valueOf(d.window.size()), "次",
                String.format(Locale.US, "每分钟约 %.1f 次", d.window.size() / 30f));
        setCard(2, String.valueOf(d.uniqCall), "台", "按呼号去重");
        setCard(3, String.valueOf(d.uniqTg), "个", "近 30 分钟");
        setCard(4, String.valueOf(Math.round(d.avgDur)), "秒", "已完成通联");
        setCard(5, String.valueOf(d.priv), "次", "不广播的直呼");

        chart.setData(d.minutes, pal);
        int last = d.span - 1;
        tlStat.setText(String.format(Locale.US,
                "窗口 %d 分 · 峰值 %d 次/分 · 当前分钟 %d 次",
                d.span, d.peak, last >= 0 && last < d.minutes.length ? d.minutes[last] : 0));

        /* ---- 四个排行榜 ---- */
        List<Map.Entry<Long, Integer>> tgs = LiveStore.top(d.byTg, 10);
        List<String> tgLabels = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : tgs) tgLabels.add(e.getKey() + " " + name(e.getKey()));
        rankTg.show(tgs, tgLabels, pal, act);

        List<Map.Entry<String, Integer>> cs = LiveStore.top(d.byCountry, 10);
        List<String> cLabels = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cs) {
            cLabels.add(Geo.isoName(e.getKey()) + " " + e.getKey().toUpperCase(Locale.ROOT));
        }
        rankCountry.show(cs, cLabels, pal, act);

        List<Map.Entry<String, Integer>> ms = LiveStore.top(d.byMaster, 10);
        List<String> mLabels = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ms) {
            String iso = MasterRepo.iso(e.getKey());
            mLabels.add("M" + e.getKey() + (iso == null ? "" : " " + Geo.isoName(iso)));
        }
        rankMaster.show(ms, mLabels, pal, act);

        List<Map.Entry<String, Integer>> calls = LiveStore.top(d.byCall, 10);
        List<String> callLabels = new ArrayList<>();
        for (Map.Entry<String, Integer> e : calls) callLabels.add(e.getKey());
        rankCall.show(calls, callLabels, pal, act);

        /* ---- 通话构成 ---- */
        int tot = Math.max(1, d.slot0 + d.slot1);
        int p0 = Math.round(d.slot0 * 100f / tot);
        int pub = tot - d.priv;
        mixPanel.showBars(new String[]{"时隙 1", "时隙 2", "群组呼叫", "私密呼叫"},
                new int[]{d.slot0, d.slot1, pub, d.priv},
                new int[]{p0, 100 - p0, Math.round(pub * 100f / tot), Math.round(d.priv * 100f / tot)},
                pal, act);
        mixFoot.setText("统计窗口：最近 30 分钟 · 已累计记录 " + d.total + " 条会话\n"
                + "运行时长：" + Ui.mmss((int) ((System.currentTimeMillis() - d.startedAt) / 1000)));

        refreshLive();
        refreshLog();
    }

    private void setCard(int i, String val, String unit, String foot) {
        if (cardVal[i] == null) return;
        cardVal[i].setText(val);
        cardUnit[i].setText(unit);
        cardFoot[i].setText(foot);
    }

    /** 实时活动列表。 */
    public void refreshLive() {
        List<LiveStore.Session> list = store.activeList();
        liveCount.setText(String.valueOf(list.size()));

        StringBuilder sig = new StringBuilder();
        int shown = 0;
        for (LiveStore.Session s : list) {
            if (shown >= LIVE_MAX) break;
            if (!filter.isEmpty() && !match(s)) continue;
            sig.append(s.key).append(':').append(s.durSec(System.currentTimeMillis())).append(';');
            shown++;
        }
        if (sig.toString().equals(liveSig)) return;
        liveSig = sig.toString();

        liveList.removeAllViews();
        shown = 0;
        long now = System.currentTimeMillis();
        for (LiveStore.Session s : list) {
            if (shown >= LIVE_MAX) break;
            if (!filter.isEmpty() && !match(s)) continue;
            liveList.addView(liveRow(s, now));
            shown++;
        }
        liveEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        liveEmpty.setText(list.isEmpty() ? "等待实时活动…" : "没有匹配的活动");
        liveEmpty.setTextColor(pal.dim);
    }

    private boolean match(LiveStore.Session s) {
        String tg = String.valueOf(s.tg);
        String n = name(s.tg).toLowerCase(Locale.ROOT);
        return tg.contains(filter)
                || s.call.toLowerCase(Locale.ROOT).contains(filter)
                || s.name.toLowerCase(Locale.ROOT).contains(filter)
                || n.contains(filter);
    }

    private View liveRow(LiveStore.Session s, long now) {
        View v = inf.inflate(R.layout.item_two_line, liveList, false);
        TextView t1 = v.findViewById(R.id.t1);
        TextView t2 = v.findViewById(R.id.t2);
        TextView tail = v.findViewById(R.id.tail);
        View lamp = v.findViewById(R.id.lamp);

        String head = s.call.isEmpty() ? "ID " + s.srcId : s.call;
        if (!s.name.isEmpty()) head += "  " + s.name;
        t1.setText(head);
        t1.setTextColor(pal.text);

        StringBuilder sb = new StringBuilder();
        sb.append("TG ").append(s.tg).append(' ').append(name(s.tg));
        if (!s.master.isEmpty()) sb.append(" · M").append(s.master);
        sb.append(" · TS").append(s.slot + 1);
        if (s.priv) sb.append(" · 个呼");
        t2.setText(sb);
        t2.setTextColor(pal.dim);

        tail.setText(s.durSec(now) + "s");
        tail.setTextColor(pal.accent);
        lamp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(pal.accent));

        v.setOnClickListener(x -> {
            if (cb != null) cb.onPickTg(s.tg, name(s.tg));
        });
        return v;
    }

    /** 通联记录（最近 50 条 / 24 小时）。 */
    private void refreshLog() {
        List<LiveStore.Rec> recs = store.recent(LOG_MAX, LiveStore.KEEP_MS);
        logCount.setText(String.valueOf(recs.size()));

        /* 记录只在「有新会话」或「某通结束」时变。每秒重建 50 行太浪费，
         * 先用一个签名判一下要不要重建。 */
        StringBuilder sig = new StringBuilder().append(recs.size());
        if (!recs.isEmpty()) {
            LiveStore.Rec f = recs.get(0);
            sig.append('|').append(f.key).append('|').append(f.t).append('|').append(f.dur);
            LiveStore.Rec l = recs.get(recs.size() - 1);
            sig.append('|').append(l.key).append('|').append(l.dur);
        }
        if (sig.toString().equals(logSig)) return;
        logSig = sig.toString();

        logList.removeAllViews();
        for (LiveStore.Rec r : recs) {
            View v = inf.inflate(R.layout.item_two_line, logList, false);
            TextView t1 = v.findViewById(R.id.t1);
            TextView t2 = v.findViewById(R.id.t2);
            TextView tail = v.findViewById(R.id.tail);
            View lamp = v.findViewById(R.id.lamp);

            t1.setText(r.call.isEmpty() ? "ID " + r.srcId : r.call);
            t1.setTextColor(pal.text);

            StringBuilder sb = new StringBuilder();
            sb.append(r.tg).append(' ').append(name(r.tg));
            if (!r.master.isEmpty()) sb.append(" · M").append(r.master);
            sb.append(" · TS").append(r.slot + 1);
            if (r.dur > 0) sb.append(" · ").append(r.dur).append("s");
            t2.setText(sb);
            t2.setTextColor(pal.dim);

            tail.setText(Ui.hhmm(r.t));
            tail.setTextColor(pal.dim);
            lamp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    r.dur > 0 ? pal.dim : pal.ok));
            logList.addView(v);
        }
        if (recs.isEmpty()) {
            logList.addView(Ui.empty(act, pal, "还没有记录"));
        }
    }

    /* ==================== 排行榜容器 ==================== */

    /**
     * 排行榜的复用池：10 行 View 建一次，之后只改文字和比例。
     * 每秒重建 40 个 View 会让滚动明显发涩。
     */
    private static final class RankList {
        private final LinearLayout box;
        private final boolean numbered;
        private final List<View> rows = new ArrayList<>();
        private View emptyView;

        RankList(LinearLayout box, boolean numbered) {
            this.box = box;
            this.numbered = numbered;
        }

        void show(List<? extends Map.Entry<?, Integer>> data, List<String> labels,
                  Palette pal, Activity act) {
            LayoutInflater inf = LayoutInflater.from(act);
            if (data.isEmpty()) {
                if (emptyView == null) {
                    emptyView = Ui.empty(act, pal, "暂无数据");
                }
                if (box.getChildCount() != 1 || box.getChildAt(0) != emptyView) {
                    box.removeAllViews();
                    box.addView(emptyView);
                }
                return;
            }
            if (emptyView != null && box.getChildAt(0) == emptyView) box.removeAllViews();

            int max = 1;
            for (Map.Entry<?, Integer> e : data) if (e.getValue() > max) max = e.getValue();

            for (int i = 0; i < data.size(); i++) {
                View v;
                if (i < rows.size()) {
                    v = rows.get(i);
                    /* ★ 之前出现过「空 → 有数据」时列表不显示：
                     * 空的时候把 box 清空换成了占位文字，复用的行 View 就脱离
                     * 了父容器；这里必须补一次 addView，否则行永远回不来。 */
                    if (v.getParent() == null) box.addView(v);
                } else {
                    v = inf.inflate(R.layout.item_rank, box, false);
                    rows.add(v);
                    box.addView(v);
                }
                bind(v, i, labels.get(i), data.get(i).getValue(), max, pal);
            }
            /* 数据变少时把多余的行藏起来，别删 —— 下次又要用 */
            for (int i = data.size(); i < rows.size(); i++) {
                rows.get(i).setVisibility(View.GONE);
            }
            for (int i = 0; i < data.size(); i++) rows.get(i).setVisibility(View.VISIBLE);
        }

        /** 通话构成用：固定四行，值是「n · pct%」。 */
        void showBars(String[] labels, int[] counts, int[] pcts, Palette pal, Activity act) {
            LayoutInflater inf = LayoutInflater.from(act);
            int max = 1;
            for (int v : counts) if (v > max) max = v;
            for (int i = 0; i < labels.length; i++) {
                View v;
                if (i < rows.size()) {
                    v = rows.get(i);
                    if (v.getParent() == null) box.addView(v);
                } else {
                    v = inf.inflate(R.layout.item_rank, box, false);
                    rows.add(v);
                    box.addView(v);
                }
                bindText(v, "", labels[i], counts[i] + " · " + pcts[i] + "%", counts[i], max, pal);
                v.setVisibility(View.VISIBLE);
            }
        }

        private void bind(View v, int idx, String label, int count, int max, Palette pal) {
            bindText(v, numbered ? String.valueOf(idx + 1) : "", label,
                    String.valueOf(count), count, max, pal);
        }

        private void bindText(View v, String n, String label, String val,
                              int count, int max, Palette pal) {
            TextView num = v.findViewById(R.id.n);
            TextView lb = v.findViewById(R.id.label);
            TextView tv = v.findViewById(R.id.val);
            android.widget.ProgressBar bar = v.findViewById(R.id.bar);

            num.setText(n);
            num.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
            num.setTextColor(pal.dim);

            lb.setText(label);
            lb.setTextColor(pal.text);

            tv.setText(val);
            tv.setTextColor(pal.muted);

            int pct = max <= 0 ? 0 : Math.round(count * 100f / max);
            bar.setProgress(Math.max(0, Math.min(100, pct)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(pal.accent));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    pal.mix(pal.border, pal.panel, 0.3f)));
        }
    }
}
