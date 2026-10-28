package net.beacon.dmr.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import net.beacon.dmr.core.Palette;

/**
 * 通联量走势折线图。数据是「每分钟新起通联数」，最后一格是当前分钟。
 *
 * <p>不引图表库：整个工程零依赖，而这里要画的只有一条线、四条网格线、
 * 两个刻度。Canvas 画出来比塞一个 MPAndroidChart（1.5 MB）划算得多。
 *
 * <p>画法和 WebView 版的 {@code drawLine()} 一致：折线下方一层从主色到透明的
 * 渐变填充 + 末端一个圆点，看的是「趋势」不是精确读数。
 */
public final class LineChartView extends View {

    private static final int PAD_DP = 18;
    private static final float TEXT_SP = 10f;

    private int[] data = new int[0];
    private int accent = 0xFF0B6BCB;
    private int border = 0xFFDDE3EA;
    private int dim = 0xFF93A0B0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();

    public LineChartView(Context c) { super(c); init(); }

    public LineChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setData(int[] d, Palette pal) {
        this.data = d == null ? new int[0] : d;
        if (pal != null) {
            accent = pal.accent;
            border = pal.border;
            dim = pal.dim;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = PAD_DP * getResources().getDisplayMetrics().density;
        float iw = w - pad * 2, ih = h - pad * 2;
        if (iw <= 4 || ih <= 4) return;

        int max = 1;
        for (int v : data) if (v > max) max = v;

        /* 网格：4 条横线 */
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setShader(null);
        paint.setColor(border);
        for (int i = 0; i <= 3; i++) {
            float y = pad + ih * i / 3f;
            c.drawLine(pad, y, w - pad, y, paint);
        }

        /* 刻度文字：上限 / 0 / 时间轴两端 */
        textPaint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        textPaint.setColor(dim);
        c.drawText(String.valueOf(max), 2, pad + 4, textPaint);
        c.drawText("0", 2, pad + ih + 4, textPaint);
        if (data.length > 0) {
            c.drawText(data.length + "分前", pad, h - 3, textPaint);
            c.drawText("现在", w - pad - 24 * getResources().getDisplayMetrics().density, h - 3, textPaint);
        }

        if (data.length < 2) return;

        float stepX = iw / (data.length - 1);
        line.reset();
        fill.reset();
        fill.moveTo(pad, pad + ih);
        for (int i = 0; i < data.length; i++) {
            float x = pad + stepX * i;
            float y = pad + ih - ih * ((float) data[i] / max);
            if (i == 0) { line.moveTo(x, y); fill.lineTo(x, y); }
            else { line.lineTo(x, y); fill.lineTo(x, y); }
        }
        float lastX = pad + stepX * (data.length - 1);
        fill.lineTo(lastX, pad + ih);
        fill.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, pad, 0, pad + ih,
                (accent & 0x00FFFFFF) | 0x55000000, accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(fill, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density);
        paint.setColor(accent);
        c.drawPath(line, paint);

        paint.setStyle(Paint.Style.FILL);
        float r = 2.6f * getResources().getDisplayMetrics().density;
        float ly = pad + ih - ih * ((float) data[data.length - 1] / max);
        c.drawCircle(lastX, ly, r, paint);
    }
}
