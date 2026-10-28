package net.beacon.dmr.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import net.beacon.dmr.R;
import net.beacon.dmr.core.Palette;
import net.beacon.dmr.core.Prefs;

import java.util.Locale;

/**
 * 设置页。
 *
 * <p>只做三件事：把 {@link Prefs} 的值铺到控件上、把控件的变化写回
 * {@link Prefs}、然后通过 {@link Listener} 让 {@code MainActivity} 真正生效。
 * 这里不直接碰 AudioOut / HoseClient / DmrLink —— 设置页不该知道链路长什么样。
 *
 * <p>⚠ 回填控件时会触发监听器，所以全程用一个 {@code binding} 标记挡住回环。
 * 没有这个标记的话，一进设置页就会把「读出来的值」当成「用户改的值」写回去，
 * 顺手弹一堆 Toast，还会把音量斜坡打断。
 *
 * <p>收发那张卡的文本框没有「保存」按钮：每次编辑直接落 {@link Prefs}，
 * 点「连接主站」时从 Prefs 读。多一个保存动作只会多一种「忘了点」的失败模式。
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

        /** 点了「连接主站 / 断开」。是否要连由 MainActivity 判（它知道当前状态）。 */
        void onTxConnect();

        /** 点了「自检」。 */
        void onTxSelfTest();

        /** 接收开关 / 只收当前话组 / 麦克风增益 / 色码 变了。 */
        void onTxOptionChanged();
    }

    private final Activity act;
    private final Palette pal;
    private final Listener cb;

    private final LinearLayout segTheme, segGain, segSlot;
    private final Switch swSimple, swPresence, swAwake, swRec, swScan, swScanResume;
    private final Switch swRxOn, swOnlyWatch;
    private final SeekBar seekVol, seekAutoN, seekCc, seekMic;
    private final TextView setVolVal, setAutoNVal, audioDiag, aboutText;
    private final TextView setCcVal, setMicVal, txState;
    private final EditText txHost, txPort, txId, txEssid, txCall, txPwd;
    private final Button btnConnect, btnSelfTest;

    private boolean binding = true;

    public SettingsPage(Activity a, View root, Palette p, Listener l) {
        this.act = a;
        this.pal = p;
        this.cb = l;

        segTheme = root.findViewById(R.id.segTheme);
        segGain = root.findViewById(R.id.segGain);
        segSlot = root.findViewById(R.id.segSlot);
        swSimple = root.findViewById(R.id.swSimple);
        swPresence = root.findViewById(R.id.swPresence);
        swAwake = root.findViewById(R.id.swAwake);
        swRec = root.findViewById(R.id.swRec);
        swScan = root.findViewById(R.id.swScan);
        swScanResume = root.findViewById(R.id.swScanResume);
        swRxOn = root.findViewById(R.id.swRxOn);
        swOnlyWatch = root.findViewById(R.id.swOnlyWatch);
        seekVol = root.findViewById(R.id.seekSetVol);
        seekAutoN = root.findViewById(R.id.seekAutoN);
        seekCc = root.findViewById(R.id.seekCc);
        seekMic = root.findViewById(R.id.seekMic);
        setVolVal = root.findViewById(R.id.setVolVal);
        setAutoNVal = root.findViewById(R.id.setAutoNVal);
        setCcVal = root.findViewById(R.id.setCcVal);
        setMicVal = root.findViewById(R.id.setMicVal);
        audioDiag = root.findViewById(R.id.audioDiag);
        aboutText = root.findViewById(R.id.aboutText);
        txState = root.findViewById(R.id.txState);
        txHost = root.findViewById(R.id.txHost);
        txPort = root.findViewById(R.id.txPort);
        txId = root.findViewById(R.id.txId);
        txEssid = root.findViewById(R.id.txEssid);
        txCall = root.findViewById(R.id.txCall);
        txPwd = root.findViewById(R.id.txPwd);
        btnConnect = root.findViewById(R.id.btnConnect);
        btnSelfTest = root.findViewById(R.id.btnSelfTest);

        Ui.tintSwitch(swSimple, pal);
        Ui.tintSwitch(swPresence, pal);
        Ui.tintSwitch(swAwake, pal);
        Ui.tintSwitch(swRec, pal);
        Ui.tintSwitch(swScan, pal);
        Ui.tintSwitch(swScanResume, pal);
        Ui.tintSwitch(swRxOn, pal);
        Ui.tintSwitch(swOnlyWatch, pal);
        Ui.tintSeek(seekVol, pal);
        Ui.tintSeek(seekAutoN, pal);
        Ui.tintSeek(seekCc, pal);
        Ui.tintSeek(seekMic, pal);
        audioDiag.setTextColor(pal.muted);
        txState.setTextColor(pal.muted);

        buildThemeSeg();
        buildGainSeg();
        buildSlotSeg();
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
        return "信标 DMR · Java 版 " + v + "（Phase 3 收发）\n"
                + "纯 Java 重写，零第三方依赖。收听：HOSE → MessagePack → μ-law；"
                + "收发：MMDVM → AMBE（DroidStar 声码器）→ 同一条 AudioTrack。\n"
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

    private void buildSlotSeg() {
        /* 索引 0 = TS1，索引 1 = TS2 —— 和 Prefs.slot() 的 1/2 差一 */
        Ui.seg(segSlot, new String[]{"TS1", "TS2"},
                Prefs.slot(act) - 1, pal, idx -> {
                    if (binding) return;
                    Prefs.setSlot(act, idx + 1);
                    if (cb != null) cb.onTxOptionChanged();
                });
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

        /* ---- 收发 ---- */
        swRxOn.setChecked(Prefs.rxOn(act));
        swOnlyWatch.setChecked(Prefs.onlyWatchTg(act));

        int cc = Prefs.colorCode(act);
        seekCc.setProgress(cc);
        setCcVal.setText(String.valueOf(cc));

        /* 增益滑块 0~36 映射到 −24 ~ +12 dB，offset 24 —— 拖到中间正好 0 dB */
        int mic = Math.round(Prefs.micGainDb(act)) + 24;
        seekMic.setProgress(mic);
        setMicVal.setText(micLabel(Prefs.micGainDb(act)));

        txHost.setText(Prefs.host(act));
        txPort.setText(String.valueOf(Prefs.port(act)));
        txId.setText(Prefs.dmrId(act) == 0 ? "" : String.valueOf(Prefs.dmrId(act)));
        txEssid.setText(Prefs.essid(act) == 0 ? "" : String.valueOf(Prefs.essid(act)));
        txCall.setText(Prefs.callsign(act));
        txPwd.setText(Prefs.password(act));

        setCcVal.setTextColor(pal.dim);
        setMicVal.setTextColor(pal.dim);
        binding = false;
    }

    private static String micLabel(float db) {
        return String.format(Locale.US, "%+d dB", Math.round(db));
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

        /* ---- 收发 ---- */

        seekCc.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                setCcVal.setText(String.valueOf(v));
                if (binding || !fromUser) return;
                Prefs.setColorCode(act, v);
                if (cb != null) cb.onTxOptionChanged();
            }
        });

        seekMic.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                float db = v - 24;
                setMicVal.setText(micLabel(db));
                if (binding || !fromUser) return;
                Prefs.setMicGainDb(act, db);
                if (cb != null) cb.onTxOptionChanged();
            }
        });

        swRxOn.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setRxOn(act, on);
            if (cb != null) cb.onTxOptionChanged();
        });
        swOnlyWatch.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            Prefs.setOnlyWatchTg(act, on);
            if (cb != null) cb.onTxOptionChanged();
        });

        /* 文本框：每次编辑直接落 Prefs，没有「保存」按钮。
         * 多一个保存动作只会多一种「忘了点」的失败模式。 */
        textToPrefs(txHost, s -> Prefs.setHost(act, s));
        textToPrefs(txCall, s -> Prefs.setCallsign(act, s));
        textToPrefs(txPwd, s -> Prefs.setPassword(act, s));
        /* 端口只在 4~5 位时才落盘：边输边写的话，刚敲下「6」就把 62031 覆盖成 6 了，
         * 用户中途退出设置页就留下一个错端口，下次连不上还找不到原因。 */
        textToPrefs(txPort, s -> {
            int p = parseInt(s, 0);
            if (p >= 1000 && p <= 65535) Prefs.setPort(act, p);
        });
        textToPrefs(txId, s -> Prefs.setDmrId(act, parseInt(s, 0)));
        textToPrefs(txEssid, s -> Prefs.setEssid(act, parseInt(s, 0)));

        btnConnect.setOnClickListener(v -> {
            if (cb != null) cb.onTxConnect();
        });
        btnSelfTest.setOnClickListener(v -> {
            if (cb != null) cb.onTxSelfTest();
        });
    }

    private interface Sink { void put(String s); }

    private void textToPrefs(EditText et, Sink sink) {
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (binding) return;
                sink.put(e == null ? "" : e.toString());
            }
        });
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        if (t.isEmpty()) return def;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return def;
        }
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

    /**
     * 收发状态那一行。
     *
     * @param text  多行文本，直接显示（含收发包数、丢包率、声码器状态）
     * @param ok    true 用「正常」色，false 用弱色
     */
    public void setTxState(String text, boolean ok) {
        if (txState == null) return;
        txState.setText(text);
        txState.setTextColor(ok ? pal.ok : pal.muted);
    }

    /** 连接状态变了：按钮文字跟着换。 */
    public void setTxConnected(boolean connected) {
        if (btnConnect == null) return;
        btnConnect.setText(connected ? "断开连接" : "连接主站");
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }
}
