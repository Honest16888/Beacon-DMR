package net.beacon.dmr.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.beacon.dmr.R;
import net.beacon.dmr.core.Palette;

/**
 * 造控件的小工具。零依赖工程没有 Material Components，这些小东西
 * （分段控件、排行榜一行、比例条染色）每个页面都要用，集中在这里。
 */
public final class Ui {

    private Ui() {}

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int dpI(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /**
     * 分段控件：把 {@code container} 填成一排互斥按钮。
     *
     * <p>选中态 = 主色浅底 + 主色字，未选中 = 透明底 + 次要字。
     * 不用 state_selected 的 selector，是因为「主题属性写在 color selector 里」
     * 在部分 ROM 上解析不出来（拿到透明），代码里直接设反而稳。
     */
    public static void seg(LinearLayout container, String[] labels, int selected,
                           Palette pal, OnPick onPick) {
        container.removeAllViews();
        Context c = container.getContext();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = new Button(c);
            b.setText(labels[i]);
            b.setTextSize(12f);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(dpI(c, 10), dpI(c, 6), dpI(c, 10), dpI(c, 6));
            b.setGravity(Gravity.CENTER);
            b.setStateListAnimator(null);          // 去掉系统按钮的抬升阴影
            boolean on = idx == selected;
            b.setBackgroundResource(on ? R.drawable.bg_seg_on : android.R.color.transparent);
            b.setTextColor(on ? pal.accent : pal.muted);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (onPick != null) onPick.onPick(idx);
            });
            container.addView(b);
        }
    }

    public interface OnPick {
        void onPick(int index);
    }

    /** 排行榜一行。{@code max} 用来算比例条长度；{@code n<=0} 时不显示名次。 */
    public static View rankRow(Context c, LayoutInflater inf, ViewGroup parent,
                               int n, String label, int count, int max, Palette pal) {
        View v = inf.inflate(R.layout.item_rank, parent, false);
        TextView num = v.findViewById(R.id.n);
        TextView lb = v.findViewById(R.id.label);
        TextView val = v.findViewById(R.id.val);
        ProgressBar bar = v.findViewById(R.id.bar);

        num.setText(n > 0 ? String.valueOf(n) : "");
        num.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        lb.setText(label);
        lb.setTextColor(pal.text);
        val.setText(String.valueOf(count));
        val.setTextColor(pal.muted);

        int pct = max <= 0 ? 0 : Math.round(count * 100f / max);
        bar.setProgress(Math.max(0, Math.min(100, pct)));
        bar.setProgressTintList(ColorStateList.valueOf(pal.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(pal.mix(pal.border, pal.panel, 0.35f)));
        return v;
    }

    /** 排行榜里那行「暂无数据」的占位。 */
    public static TextView empty(Context c, Palette pal, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(pal.dim);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dpI(c, 12), 0, dpI(c, 12));
        return t;
    }

    /** 把 Switch 的轨道 / 拇指染成主色 —— 系统默认的绿色和这套配色不搭。 */
    public static void tintSwitch(android.widget.Switch sw, Palette pal) {
        sw.setThumbTintList(ColorStateList.valueOf(pal.accent));
        sw.setTrackTintList(ColorStateList.valueOf(pal.mix(pal.border, pal.panel, 0.2f)));
    }

    public static void tintSeek(android.widget.SeekBar sb, Palette pal) {
        sb.setProgressTintList(ColorStateList.valueOf(pal.accent));
        sb.setThumbTintList(ColorStateList.valueOf(pal.accent));
        sb.setProgressBackgroundTintList(
                ColorStateList.valueOf(pal.mix(pal.border, pal.panel, 0.3f)));
    }

    /** 一秒 → "1:23"。 */
    public static String mmss(int sec) {
        if (sec < 0) sec = 0;
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** 时间戳 → "10:23"。 */
    public static String hhmm(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
