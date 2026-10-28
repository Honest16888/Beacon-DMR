package net.beacon.dmr.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import net.beacon.dmr.R;
import net.beacon.dmr.core.Palette;
import net.beacon.dmr.core.Prefs;

/**
 * 设置页。
 *
 * <p>只做三件事：把 {@link Prefs} 的值铺到控件上、把控件的变化写回
 * {@link Prefs}、然后通过 {@link Listener} 让 {@code MainActivity} 真正生效。
 * 这里不直接碰 AudioOut / HoseClient —— 设置页不该知道音频链路长什么样。
 *
 * <p>⚠ 回填控件时会触发监听器，所以全程用一个 {@code binding} 标记挡住回环。
 * 没有这个标记的话，一进设置页就会把「读出来的值」当成「用户改的值」写回去，
 * 顺手弹一堆 Toast，还会把音量斜坡打断。
 */
public final class SettingsPage {

    public interface Listener {
        /** 主题变了。需要 recreate() 才能整体换肤。 */
        void onThemeChanged(int mode);

        void onVolumeChanged(int pct);

        void onBoostChanged(int gain);

        void onPresenceChanged(boolean on);

        void onAwakeChanged(boolean on);

        void onSimpleChanged(boolean on);

        void onScanChanged();
    }

    private final Activity act;
    private final Palette pal;
    private final Listener cb;

    private final LinearLayout segTheme, segGain;
    private final Switch swSimple, swPresence, swAwake, swRec, swScan, swScanResume;
    private final SeekBar seekVol, seekAutoN;
    private final TextView setVolVal, setAutoNVal, audioDiag, aboutText;

    private boolean binding = true;

    public SettingsPage(Activity a, View root, Palette p, Listener l) {
        this.act = a;
        this.pal = p;
        this.cb = l;

        segTheme = root.findViewById(R.id.segTheme);
        segGain = root.findViewById(R.id.segGain);
        swSimple = root.findViewById(R.id.swSimple);
        swPresence = root.findViewById(R.id.swPresence);
        swAwake = root.findViewById(R.id.swAwake);
        swRec = root.findViewById(R.id.swRec);
        swScan = root.findViewById(R.id.swScan);
        swScanResume = root.findViewById(R.id.swScanResume);
        seekVol = root.findViewById(R.id.seekSetVol);
        seekAutoN = root.findViewById(R.id.seekAutoN);
        setVolVal = root.findViewById(R.id.setVolVal);
        setAutoNVal = root.findViewById(R.id.setAutoNVal);
        audioDiag = root.findViewById(R.id.audioDiag);
        aboutText = root.findViewById(R.id.aboutText);

        Ui.tintSwitch(swSimple, pal);
        Ui.tintSwitch(swPresence, pal);
        Ui.tintSwitch(swAwake, pal);
        Ui.tintSwitch(swRec, pal);
        Ui.tintSwitch(swScan, pal);
        Ui.tintSwitch(swScanResume, pal);
        Ui.tintSeek(seekVol, pal);
        Ui.tintSeek(seekAutoN, pal);
        audioDiag.setTextColor(pal.muted);

        buildThemeSeg();
        buildGainSeg();
        bindValues();
        wire();

        aboutText.setText(versionLine());
        aboutText.setTextColor(pal.muted);
    }

    private String versionLine() {
        String v = "3.0.0-alpha";
        try {
            v = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception ignore) { }
        return "信标 DMR · Java 版 " + v + "（Phase 1 看板 + Phase 2 设置）\n"
                + "纯 Java 重写，零第三方依赖。收听链路：HOSE → MessagePack → μ-law → AudioTrack。\n"
                + "界面设计署名 BH6RGQ。";
    }

    /* ==================== 分段控件 ==================== */

    private void buildThemeSeg() {
        Ui.seg(segTheme, new String[]{"跟随系统", "浅色", "深色"},
                Prefs.theme(act), pal, idx -> {
                    if (binding) return;
                    Prefs.setTheme(act, idx);
                    if (cb != null) cb.onThemeChanged(idx);
                });
    }

    private void buildGainSeg() {
        Ui.seg(segGain, new String[]{"1×", "2×", "3×"},
                Prefs.gain(act) - 1, pal, idx -> {
                    if (binding) return;
                    int g = idx + 1;
                    Prefs.setGain(act, g);
                    if (cb != null) cb.onBoostChanged(g);
                });
    }

    /** 主题被外部改掉（比如 recreate 之后）时重画选中态。 */
    public void syncThemeSeg() {
        int cur = Prefs.theme(act);
        for (int i = 0; i < segTheme.getChildCount(); i++) {
            View v = segTheme.getChildAt(i);
            boolean on = i == cur;
            v.setBackgroundResource(on ? R.drawable.bg_seg_on : android.R.color.transparent);
            ((TextView) v).setTextColor(on ? pal.accent : pal.muted);
        }
    }

    /* ==================== 回填 ==================== */

    private void bindValues() {
        binding = true;
        int vol = Prefs.volume(act);
        seekVol.setProgress(vol);
        setVolVal.setText(vol + "%");
        setVolVal.setTextColor(pal.dim);

        int n = Prefs.autoN(act);
        seekAutoN.setProgress(n - 1);
        setAutoNVal.setText(String.valueOf(n));
        setAutoNVal.setTextColor(pal.dim);

        swSimple.setChecked(Prefs.simple(act));
        swPresence.setChecked(Prefs.presence(act));
        swAwake.setChecked(Prefs.awake(act));
        swRec.setChecked(Prefs.recOn(act));
        swScan.setChecked(Prefs.scanOn(act));
        swScanResume.setChecked(Prefs.scanResume(act));
        binding = false;
    }

    /* ==================== 监听 ==================== */

    private void wire() {
        seekVol.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                setVolVal.setText(v + "%");
                if (binding || !fromUser) return;
                Prefs.setVolume(act, v);
                if (cb != null) cb.onVolumeChanged(v);
            }
        });

        seekAutoN.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                int n = v + 1;
                setAutoNVal.setText(String.valueOf(n));
                if (binding || !fromUser) return;
                Prefs.setAutoN(act, n);
                if (cb != null) cb.onScanChanged();
            }
        });

        swSimple.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setSimple(act, on);
            if (cb != null) cb.onSimpleChanged(on);
        });
        swPresence.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setPresence(act, on);
            if (cb != null) cb.onPresenceChanged(on);
        });
        swAwake.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setAwake(act, on);
            if (cb != null) cb.onAwakeChanged(on);
        });
        swRec.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setRecOn(act, on);
        });
        swScan.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setScanOn(act, on);
            if (cb != null) cb.onScanChanged();
        });
        swScanResume.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setScanResume(act, on);
        });
    }

    /* ==================== 外部更新 ==================== */

    /** 音频自检那一行。由 MainActivity 的 tick 每 2 秒喂一次。 */
    public void setAudioDiag(String text) {
        audioDiag.setText(text);
    }

    /** 音量被主页那根条改了，同步过来（不回调，避免回环）。 */
    public void setVolume(int pct) {
        binding = true;
        seekVol.setProgress(pct);
        setVolVal.setText(pct + "%");
        binding = false;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }
}
