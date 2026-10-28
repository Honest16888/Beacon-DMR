package net.beacon.dmr;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import net.beacon.dmr.audio.AudioOut;
import net.beacon.dmr.core.MuLaw;
import net.beacon.dmr.core.Talkgroups;
import net.beacon.dmr.hose.HoseClient;
import net.beacon.dmr.ui.WaveView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主页 —— 收听。
 *
 * <p>这是纯 Java 版的第一屏（Phase 0）。目标是先把「能出声」这件事在新架构里
 * 跑通，再往上加看板、设置、收发。
 *
 * <p>和 WebView 版最大的区别：这里没有 WebView、没有 WebAudio、没有 JS 桥。
 * 音频链路是
 * <pre>HOSE WebSocket → MessagePack → μ-law 查表 → AudioTrack</pre>
 * 一条直线，中间没有任何「上下文状态」「自动播放策略」「帧边界重采样」。
 */
public final class MainActivity extends Activity implements HoseClient.Listener {

    private static final String PREFS = "dmr";
    private static final String KEY_VOL = "volume";
    private static final String KEY_MUTE = "muted";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView stateText, npTg, npCaller, npTime, volPct;
    private View dot;
    private WaveView wave;
    private Button btnMute;
    private SeekBar seekVol;
    private ListView tgList;

    private List<Talkgroups.Tg> allTgs = new ArrayList<>();
    private Set<Integer> subs = new LinkedHashSet<>();
    private TgAdapter adapter;

    private HoseClient hose;
    private AudioOut audio;

    /* 当前这一句的状态。CALL_START 时整组写入，CALL_END 时清掉。 */
    private int curTg = 0;
    private int curSrc = 0;
    private String curCall = "";
    private String curName = "";
    private long curStart = 0;

    private boolean muted = false;
    private PowerManager.WakeLock wakeLock;

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        stateText = findViewById(R.id.stateText);
        npTg = findViewById(R.id.npTg);
        npCaller = findViewById(R.id.npCaller);
        npTime = findViewById(R.id.npTime);
        volPct = findViewById(R.id.volPct);
        dot = findViewById(R.id.dot);
        wave = findViewById(R.id.wave);
        btnMute = findViewById(R.id.btnMute);
        seekVol = findViewById(R.id.seekVol);
        tgList = findViewById(R.id.tgList);

        allTgs = Talkgroups.loadAll(this);
        subs = Talkgroups.loadSubs(this);

        adapter = new TgAdapter();
        tgList.setAdapter(adapter);
        tgList.setOnItemClickListener((p, v, pos, id) -> toggleSub(allTgs.get(pos).id));

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int vol = sp.getInt(KEY_VOL, 100);
        muted = sp.getBoolean(KEY_MUTE, false);
        seekVol.setProgress(vol);
        applyVolume(vol, false);
        seekVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                applyVolume(v, true);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnMute.setOnClickListener(v -> setMuted(!muted));
        renderMute();

        audio = new AudioOut();
        /* 电平回调在写线程上，动 View 必须回主线程。
         * 只在这里 post 一次 —— 以前写过「先直接 push 再 post」，等于每帧画两遍。 */
        audio.setLevelListener(rms -> ui.post(() -> wave.push(rms)));
        if (!audio.start()) {
            stateText.setText("音频输出初始化失败");
        }
        audio.setMuted(muted);

        hose = new HoseClient(this);
        hose.start(Talkgroups.toArray(subs));

        /* 每秒刷一次计时和波形衰减 —— 没有数据时柱子要自己退下去，
         * 否则会定格在最后一根上，看起来像「还在说」。 */
        ui.postDelayed(tick, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (curStart > 0) {
                long s = (System.currentTimeMillis() - curStart) / 1000;
                npTime.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
            } else {
                npTime.setText("");
            }
            wave.decay();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (hose != null) hose.stop();
        if (audio != null) audio.stop();
        releaseWake();
        super.onDestroy();
    }

    /* ==================== 音量 / 静音 ==================== */

    private void applyVolume(int progress, boolean persist) {
        float gain = progress / 100f;
        if (audio != null) audio.setGain(gain);
        volPct.setText(progress + "%");
        if (persist) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_VOL, progress).apply();
        }
    }

    private void setMuted(boolean on) {
        muted = on;
        if (audio != null) audio.setMuted(on);
        if (on) wave.reset();
        renderMute();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MUTE, on).apply();
    }

    private void renderMute() {
        btnMute.setText(muted ? getString(R.string.unmute) : getString(R.string.mute));
        if (muted) {
            npTg.setText(R.string.idle_tg);
            npCaller.setText("已静音 · 仍会记录通联");
        }
    }

    /* ==================== 订阅 ==================== */

    private void toggleSub(int id) {
        if (subs.contains(id)) subs.remove(id); else subs.add(id);
        Talkgroups.saveSubs(this, subs);
        adapter.notifyDataSetChanged();
        if (hose != null) hose.setSubscriptions(Talkgroups.toArray(subs));
        if (subs.isEmpty() && curStart == 0) {
            npCaller.setText("没有订阅任何话组 —— 勾选下面任意一项开始收听");
        }
    }

    /* ==================== HoseClient.Listener ==================== */

    @Override
    public void onState(HoseClient.State s, String detail) {
        stateText.setText(detail == null ? "" : detail);
        int color;
        switch (s) {
            case OPEN: color = getColor(R.color.ok); break;
            case CONNECTING: color = getColor(R.color.warn); break;
            case RETRY: color = getColor(R.color.warn); break;
            default: color = getColor(R.color.dim); break;
        }
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        if (s == HoseClient.State.OPEN) acquireWake();
    }

    @Override
    public void onCallStart(int srcId, int tg) {
        curTg = tg;
        curSrc = srcId;
        curCall = "";
        curName = "";
        curStart = System.currentTimeMillis();
        wave.reset();
        renderNow();
        acquireWake();
    }

    @Override
    public void onCallEnd(int tg) {
        if (tg != 0 && tg != curTg) return;          // 不是当前这一句的结束
        curStart = 0;
        curTg = 0;
        curSrc = 0;
        curCall = "";
        curName = "";
        renderNow();
        npTime.setText("");
    }

    @Override
    public void onAlias(int tg, String alias) {
        if (alias == null || alias.isEmpty()) return;
        /* HOSE 的 alias 形如 "IU6VLK Marco Roma"。第一段是呼号，剩下的是名字和位置。
         * 呼号可能为空（有些源只给名字），所以两段都留容错。 */
        String[] p = alias.trim().split("\\s+", 3);
        if (p.length > 0 && p[0].matches("[A-Z0-9/]{3,}")) {
            curCall = p[0];
            curName = p.length > 1 ? alias.substring(p[0].length()).trim() : "";
        } else {
            curCall = "";
            curName = alias.trim();
        }
        renderNow();
    }

    @Override
    public void onAudio(byte[] ulaw) {
        if (muted || audio == null) return;
        short[] pcm = new short[ulaw.length];
        MuLaw.decode(ulaw, 0, ulaw.length, pcm);
        audio.feed(pcm, ulaw.length);
    }

    @Override
    public void onMeter(int tg, int db) {
        /* 官方电平表字段位置没公开，Phase 0 不用它 —— 波形来自真实 PCM 的 RMS，
         * 那个更准。留着这个回调是为了以后接 S 表。 */
    }

    private void renderNow() {
        if (curTg == 0) {
            npTg.setText(R.string.idle_tg);
            npCaller.setText(R.string.idle_caller);
            return;
        }
        String name = Talkgroups.nameOf(allTgs, curTg);
        npTg.setText(name + " · " + curTg);
        StringBuilder sb = new StringBuilder();
        if (!curCall.isEmpty()) sb.append(curCall);
        if (!curName.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(curName);
        }
        if (sb.length() == 0) sb.append("ID ").append(curSrc);
        else sb.append("  ·  ").append(curSrc);
        npCaller.setText(sb.toString());
    }

    /* ==================== WakeLock ==================== */

    private void acquireWake() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beacon:listen");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(30 * 60 * 1000L); } catch (Exception ignore) { }
    }

    private void releaseWake() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignore) { }
            wakeLock = null;
        }
    }

    /* ==================== 列表 ==================== */

    private final class TgAdapter extends BaseAdapter {
        @Override public int getCount() { return allTgs.size(); }
        @Override public Object getItem(int i) { return allTgs.get(i); }
        @Override public long getItemId(int i) { return allTgs.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_tg, parent, false);
            }
            Talkgroups.Tg t = allTgs.get(pos);
            CheckBox ck = v.findViewById(R.id.ck);
            TextView name = v.findViewById(R.id.name);
            TextView id = v.findViewById(R.id.tgid);
            TextView desc = v.findViewById(R.id.desc);

            boolean on = subs.contains(t.id);
            ck.setChecked(on);
            name.setText(t.name);
            name.setTextColor(MainActivity.this.getColor(on ? R.color.text : R.color.muted));
            id.setText(String.valueOf(t.id));
            desc.setText(t.desc);
            desc.setVisibility(t.desc.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
