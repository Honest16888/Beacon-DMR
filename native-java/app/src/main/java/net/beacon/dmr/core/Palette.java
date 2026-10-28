package net.beacon.dmr.core;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import net.beacon.dmr.R;

/**
 * 把主题属性解析成具体的颜色值，供自绘 View 与代码里动态创建的控件使用。
 *
 * <p>XML 里可以直接写 {@code ?attr/cAccent}；代码里不行，得 resolve 一次。
 * 每次都 resolve 太碎，所以在 Activity 建界面时取一份 {@code Palette} 传下去。
 *
 * <p>三档主题见 {@link Prefs#theme}：
 * {@link Prefs#THEME_AUTO} 跟随系统、{@link Prefs#THEME_LIGHT} 强制浅色、
 * {@link Prefs#THEME_DARK} 强制深色。
 */
public final class Palette {

    public final int bg, panel, panel2, border, text, muted, dim, accent, accentWeak, ok, warn, bad;

    private Palette(int bg, int panel, int panel2, int border, int text, int muted, int dim,
                    int accent, int accentWeak, int ok, int warn, int bad) {
        this.bg = bg;
        this.panel = panel;
        this.panel2 = panel2;
        this.border = border;
        this.text = text;
        this.muted = muted;
        this.dim = dim;
        this.accent = accent;
        this.accentWeak = accentWeak;
        this.ok = ok;
        this.warn = warn;
        this.bad = bad;
    }

    public static Palette of(Context c) {
        return new Palette(
                col(c, R.attr.cBg), col(c, R.attr.cPanel), col(c, R.attr.cPanel2),
                col(c, R.attr.cBorder), col(c, R.attr.cText), col(c, R.attr.cMuted),
                col(c, R.attr.cDim), col(c, R.attr.cAccent), col(c, R.attr.cAccentWeak),
                col(c, R.attr.cOk), col(c, R.attr.cWarn), col(c, R.attr.cBad));
    }

    /** 解析一个颜色型主题属性。取不到时退回品红 —— 一眼就能看出是配置漏了。 */
    public static int col(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (!c.getTheme().resolveAttribute(attr, tv, true)) return 0xFFFF00FF;
        if (tv.resourceId != 0) {
            try { return c.getColor(tv.resourceId); } catch (Exception ignore) { }
        }
        return tv.data;
    }

    /**
     * 按用户选择套上主题。
     *
     * <p>★ 必须在 {@code super.onCreate()} 之前调用：窗口背景色在
     * setContentView 那一刻就定下来了，晚了会先闪一下旧底色。
     */
    public static void apply(Activity a) {
        a.setTheme(styleOf(Prefs.theme(a)));
    }

    public static int styleOf(int mode) {
        switch (mode) {
            case Prefs.THEME_LIGHT: return R.style.AppTheme_Light;
            case Prefs.THEME_DARK: return R.style.AppTheme_Dark;
            default: return R.style.AppTheme;
        }
    }

    /**
     * 当前是不是深色。用底色亮度判断而不是看 {@link Prefs#theme} ——
     * 「跟随系统」那一档的真实结果只有主题知道。
     */
    public boolean isDark() {
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128;
    }

    /** 主色的半透明版本，画渐变填充用。 */
    public int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    /** 按比例把颜色往底色调一点，用于排行榜进度条的底槽。 */
    public int mix(int color, int with, float t) {
        float u = 1f - t;
        int a = Math.round(((color >> 24) & 0xFF) * u + ((with >> 24) & 0xFF) * t);
        int r = Math.round(((color >> 16) & 0xFF) * u + ((with >> 16) & 0xFF) * t);
        int g = Math.round(((color >> 8) & 0xFF) * u + ((with >> 8) & 0xFF) * t);
        int b = Math.round((color & 0xFF) * u + (with & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
