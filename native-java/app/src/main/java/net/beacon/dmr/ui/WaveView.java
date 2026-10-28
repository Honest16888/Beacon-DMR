package net.beacon.dmr.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * 播报条上那 16 根电平柱（WebView 版的 {@code .vu}）。
 *
 * <p><b>它不是波形，是「电平条」。</b>这个区别很重要，也踩过一次：
 * 上一版画的是「每 20 ms 一帧的 RMS 从右往左推」，看着像示波器 ——
 * 但那需要每一帧都喂进来，而原生链路（DMRD → 声码器 → AudioTrack）的
 * 解码结果根本不经过这里，于是「声音在响、柱子是一条平的」。用户反馈
 * 「波形图显示不形象」就是这个。
 *
 * <p>WebView 版的做法简单得多，也更稳：只取<b>一个整体电平</b>（dBFS），
 * 按固定阈值逐根点亮，亮起来的高度加一点随机抖动。16 根柱、10 Hz 刷新。
 * 谁在出声、大概多响，一眼就有；不需要逐帧数据。
 *
 * 数值全部照抄 {@code renderVu()}：
 * <ul>
 *   <li>电平映射 {@code lvl = clamp((db + 50) / 44)} —— −50 dB 是底噪起点，
 *       −6 dB 满格；正常说话落在 −30 ~ −12，正好占中间一大段。</li>
 *   <li>第 i 根的阈值 {@code (i + 0.5) / 16}，再乘 0.92 留 8% 裕量。</li>
 *   <li>亮柱高度 {@code 9 + rand(13)} px，灭柱 3 px。</li>
 *   <li>颜色按<b>柱子位置</b>分三段（不是按电平）：高 1/4 红、中段橙、其余绿。</li>
 * </ul>
 */
public final class WaveView extends View {

    /** 柱子数量。WebView 版就是 16，和容器 50×20dp 是配死的。 */
    private static final int BARS = 16;

    private static final long FRAME_MS = 100;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Random rnd = new Random();

    /** 当前电平，0..1。由 {@link #push(float)} 从 RMS 换算。 */
    private volatile float level = 0f;
    /** 多久没新数据了 —— 超过 {@link #HOLD_MS} 就当静音，别定格。 */
    private volatile long lastAt = 0L;
    private static final long HOLD_MS = 700;

    /** 三段颜色。由 {@code Palette} 灌进来，跟着主题走。 */
    private int cLow = 0xFF12A150, cMid = 0xFFD9822B, cHigh = 0xFFE5484D, cOff = 0xFFDDE3EA;

    /** 10 Hz 重绘。WebView 版是 setInterval(100)。 */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public WaveView(Context c) { this(c, null); }

    public WaveView(Context c, AttributeSet a) {
        super(c, a);
        barPaint.setStyle(Paint.Style.FILL);
    }

    /* ==================== 配色 ==================== */

    /**
     * 设定颜色。旧接口只想要一个色，这里映射成「低段」，
     * 中/高段自动从同一套色板里取 —— 见 {@link #setColors}。
     */
    public void setBarColor(int color) {
        cLow = color;
        invalidate();
    }

    /** 三段色 + 灭柱底色。 */
    public void setColors(int low, int mid, int high, int off) {
        cLow = low; cMid = mid; cHigh = high; cOff = off;
        invalidate();
    }

    /* ==================== 电平 ==================== */

    /**
     * 推入一个 0..1 的 RMS。
     *
     * <p>保留这个签名是因为 {@code AudioOut} 的 level listener 就吐 RMS。
     * 内部转成 dBFS 再走 {@link #setDb}。
     */
    public void push(float rms) {
        float v = Math.max(0f, Math.min(1f, rms));
        setDb(v <= 0.0005f ? -120f : (float) (20.0 * Math.log10(v)));
    }

    /** 直接给 dBFS（原生链路那条路上报的就是这个）。 */
    public void setDb(float db) {
        /* (db + 50) / 44：−50 dB → 0，−6 dB → 1。和 WebView 版同一个常数，
         * 两条链画出来的高度才一致。 */
        level = Math.max(0f, Math.min(1f, (db + 50f) / 44f));
        lastAt = System.currentTimeMillis();
    }

    /** 清空（切话组 / 断流）。 */
    public void reset() {
        level = 0f;
        lastAt = 0L;
        invalidate();
    }

    /** 静音或长时间没数据时调用 —— 超过 {@link #HOLD_MS} 就归零。 */
    public void decay() {
        if (lastAt == 0) return;
        if (System.currentTimeMillis() - lastAt < HOLD_MS) return;
        level = 0f;
        lastAt = 0L;
        invalidate();
    }

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        /* 只有挂在窗口上才转 —— 页面切走（setVisibility GONE）时 View 还在，
         * 但没必要每秒 10 次 invalidate 一个看不见的东西。 */
        if (getVisibility() == VISIBLE) {
            removeCallbacks(tick);
            postDelayed(tick, FRAME_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            removeCallbacks(tick);
            postDelayed(tick, FRAME_MS);
        } else {
            removeCallbacks(tick);
        }
    }

    /* ==================== 绘制 ==================== */

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float slot = (float) w / BARS;
        /* 2dp 间距（CSS gap:2px），柱宽取剩余部分。 */
        float gap = dp(2);
        float bw = Math.max(dp(1), slot - gap);
        float radius = Math.min(bw / 2f, dp(1.5f));

        float lvl = level;
        float hOn = dp(9), hRand = dp(13), hOff = dp(3);

        for (int i = 0; i < BARS; i++) {
            /* 阈值逐根抬高，再乘 0.92 —— 最高那根要 lvl > 0.89 才亮（约 −10.8 dBFS）。 */
            float th = (i + 0.5f) / BARS;
            boolean on = lvl > th * 0.92f;

            float bh = on ? (hOn + rnd.nextFloat() * hRand) : hOff;
            bh = Math.min(bh, h);

            int color;
            if (!on) {
                color = cOff;
            } else if (th > 0.75f) {
                color = cHigh;
            } else if (th > 0.5f) {
                color = cMid;
            } else {
                color = cLow;
            }
            barPaint.setColor(color);

            float x = i * slot + gap / 2f;
            /* 底部对齐（CSS align-items:flex-end）—— 柱子从下往上长，
             * 不是居中往两边涨。居中的话「电平低」看起来像「中间一段亮」。 */
            r.set(x, h - bh, x + bw, h);
            canvas.drawRoundRect(r, radius, radius, barPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
