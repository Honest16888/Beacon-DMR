package net.beacon.dmr.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 实时音量波形。
 *
 * <p>不做 FFT，也不画采样点 —— 只把「每 20 ms 一帧的 RMS」按时间排成一根根竖条，
 * 从右往左推。对讲看的是「有没有声音、大概多响」，这种画法比真波形更容易读，
 * 而且开销几乎为零（60 根矩形）。
 *
 * <p>空档（没有通话）时不画柱子，只留一条中线，一眼能区分「安静」和「断流」。
 */
public final class WaveView extends View {

    private static final int BARS = 56;

    private final float[] hist = new float[BARS];
    private int head = 0;
    private int count = 0;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    /** 最近一次更新时间，用来判断「多久没数据了」。 */
    private long lastAt = 0;

    public WaveView(Context c) { this(c, null); }

    public WaveView(Context c, AttributeSet a) {
        super(c, a);
        barPaint.setColor(0xFF3BA55D);
        basePaint.setColor(0x22888888);
        basePaint.setStrokeWidth(dp(1));
    }

    public void setBarColor(int color) { barPaint.setColor(color); invalidate(); }

    /** 推入一个 0..1 的电平。 */
    public void push(float rms) {
        /* 人耳是对数的：直接把 RMS 画成高度，轻声几乎看不见。
         * 用 sqrt 做一次温和的压缩，再夹到 1，视觉上和「响度」对得上。 */
        float v = (float) Math.sqrt(Math.max(0f, Math.min(1f, rms)));
        hist[head] = v;
        head = (head + 1) % BARS;
        if (count < BARS) count++;
        lastAt = System.currentTimeMillis();
        invalidate();
    }

    /** 清空（切话组 / 断流）。 */
    public void reset() {
        for (int i = 0; i < BARS; i++) hist[i] = 0;
        head = 0; count = 0; lastAt = 0;
        invalidate();
    }

    /** 静音或长时间没数据时调用：把历史整体衰减一档，避免定格在最后一根柱子上。 */
    public void decay() {
        if (lastAt == 0) return;
        if (System.currentTimeMillis() - lastAt < 400) return;
        boolean any = false;
        for (int i = 0; i < BARS; i++) {
            if (hist[i] > 0.001f) { hist[i] *= 0.72f; any = true; }
            else hist[i] = 0;
        }
        lastAt = 0;
        if (any) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float mid = h / 2f;
        canvas.drawLine(0, mid, w, mid, basePaint);

        float slot = (float) w / BARS;
        float bw = Math.max(dp(1.5f), slot * 0.62f);
        float maxHalf = mid - dp(1);

        for (int i = 0; i < count; i++) {
            /* 从右往左取：head 指向「下一个要写的格子」，最旧的在 head 处。 */
            int idx = (head - count + i + BARS * 2) % BARS;
            float v = hist[idx];
            if (v <= 0.004f) continue;
            float half = Math.max(dp(0.8f), v * maxHalf);
            float x = i * slot + (slot - bw) / 2f;
            r.set(x, mid - half, x + bw, mid + half);
            /* 越新的柱子越亮，让人一眼看出方向。 */
            barPaint.setAlpha((int) (110 + 145f * (i + 1) / BARS));
            canvas.drawRoundRect(r, bw / 2f, bw / 2f, barPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
