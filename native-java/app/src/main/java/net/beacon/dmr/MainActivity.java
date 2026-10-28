package net.beacon.dmr;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import net.beacon.dmr.audio.AudioOut;
import net.beacon.dmr.core.MuLaw;
import net.beacon.dmr.core.TalkgroupRepo;
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
 * <p>音频链路：{@code HOSE WebSocket → MessagePack → μ-law 查表 → AudioTrack}，
 * 一条直线，中间没有 WebAudio、没有自动播放策略、没有帧边界重采样。
 *
 * <p>话组表三级来源见 {@link TalkgroupRepo}：内置 47 条立刻可用，
 * 缓存顶上次拉的 1800 多条，在线表在后台补齐。
 */
public final class MainActivity extends Activity implements HoseClient.Listener {

    private static final String PREFS = "dmr";
    private static final String KEY_VOL = "volume";
    private static final String KEY_MUTE = "muted";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView stateText, npTg, npCaller, npTime, volPct, tgCount;
    private View dot;
    private WaveView wave;
    private Button btnMute, btnAdd;
    private SeekBar seekVol;
    private EditText search;
    private CheckBox ckOnlySub;
    private ListView tgList;

    /** 全表（自定义 + 内置 + 缓存/在线）。 */
    private final List<Talkgroups.Tg> allTgs = new ArrayList<>();
    /** 当前显示的子集（搜索 + 只看订阅过滤之后）。 */
    private final List<Talkgroups.Tg> shown = new ArrayList<>();
    /** 话组表现在是哪一级来源：0=内置 1=缓存 2=在线。显示在计数后面，方便判断表全不全。 */
    private int tgSource = 0;
    private final Set<Integer> subs = new LinkedHashSet<>();
    private final Set<Integer> custom = new LinkedHashSet<>();
    private TgAdapter adapter;

    private HoseClient hose;
    private AudioOut audio;

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
        btnAdd = findViewById(R.id.btnAdd);
        seekVol = findViewById(R.id.seekVol);
        search = findViewById(R.id.search);
        ckOnlySub = findViewById(R.id.ckOnlySub);
        tgCount = findViewById(R.id.tgCount);
        tgList = findViewById(R.id.tgList);

        subs.addAll(Talkgroups.loadSubs(this));
        custom.addAll(Talkgroups.loadCustom(this));

        adapter = new TgAdapter();
        tgList.setAdapter(adapter);
        tgList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) toggleSub(shown.get(pos).id);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { rebuildShown(); }
        });
        ckOnlySub.setOnCheckedChangeListener((v, checked) -> rebuildShown());
        btnAdd.setOnClickListener(v -> addTyped());

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int vol = sp.getInt(KEY_VOL, 100);
        muted = sp.getBoolean(KEY_MUTE, false);
        seekVol.setProgress(vol);
        applyVolume(vol, false);
        seekVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) { applyVolume(v, true); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnMute.setOnClickListener(v -> setMuted(!muted));
        renderMute();

        audio = new AudioOut();
        audio.setLevelListener(rms -> ui.post(() -> wave.push(rms)));
        if (!audio.start()) stateText.setText("音频输出初始化失败");
        audio.setMuted(muted);

        /* 先给一份「自定义 + 内置 + 缓存」，等在线表到了再刷一次。 */
        TalkgroupRepo.load(this, (all, source) -> ui.post(() -> applyTgs(all, source)));

        hose = new HoseClient(this);
        hose.start(Talkgroups.toArray(subs));

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

    /* ==================== 话组表 ==================== */

    private void applyTgs(List<Talkgroups.Tg> all, int source) {
        if (source == TalkgroupRepo.SRC_FAIL) {
            /* 在线表没拉到：列表保持原样（内置 + 缓存已经在了），只提示一句。
             * 千万别在这里重建 allTgs —— 会把用户刚手动加的组和滚动位置一起冲掉。 */
            Toast.makeText(this, "在线话组表没拉到（网络），先用已有的 "
                    + allTgs.size() + " 个", Toast.LENGTH_LONG).show();
            return;
        }
        tgSource = source;
        allTgs.clear();
        /* 自定义组永远排最前面 —— 那是用户自己填的，肯定最关心。 */
        Set<Integer> have = new LinkedHashSet<>();
        for (int id : custom) {
            allTgs.add(new Talkgroups.Tg(id, "TG " + id, "手动添加"));
            have.add(id);
        }
        for (Talkgroups.Tg t : all) {
            if (have.contains(t.id)) continue;
            allTgs.add(t);
            have.add(t.id);
        }
        rebuildShown();
        if (source == TalkgroupRepo.SRC_ONLINE) {
            Toast.makeText(this, "话组表已更新：" + allTgs.size() + " 个", Toast.LENGTH_SHORT).show();
        }
    }

    private void rebuildShown() {
        if (adapter == null) return;
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean onlySub = ckOnlySub != null && ckOnlySub.isChecked();
        shown.clear();
        for (Talkgroups.Tg t : allTgs) {
            if (onlySub && !subs.contains(t.id)) continue;
            if (!q.isEmpty()) {
                boolean hit = String.valueOf(t.id).contains(q)
                        || t.name.toLowerCase(Locale.ROOT).contains(q)
                        || t.desc.toLowerCase(Locale.ROOT).contains(q);
                if (!hit) continue;
            }
            shown.add(t);
        }
        adapter.notifyDataSetChanged();
        if (tgCount != null) {
            /* 来源标注很重要：显示「内置 47」说明在线表没拉到（网络问题），
             * 显示「在线 1873」才是全表。用户报「组不全」时先看这里。 */
            String src = tgSource == TalkgroupRepo.SRC_ONLINE ? "在线"
                    : tgSource == TalkgroupRepo.SRC_CACHE ? "缓存" : "内置";
            tgCount.setText(String.format(Locale.US, "%d / %d · 订阅 %d · %s",
                    shown.size(), allTgs.size(), subs.size(), src));
        }
    }

    /** 「＋」按钮：把搜索框里的数字直接加成订阅（在线表里没有的组也能加）。 */
    private void addTyped() {
        String raw = search.getText().toString().trim();
        if (!raw.matches("\\d{1,7}")) {
            Toast.makeText(this, "先在搜索框里输入话组号，例如 86023", Toast.LENGTH_SHORT).show();
            return;
        }
        int id = Integer.parseInt(raw);
        if (id <= 0) { Toast.makeText(this, "话组号不合法", Toast.LENGTH_SHORT).show(); return; }

        boolean known = false;
        for (Talkgroups.Tg t : allTgs) if (t.id == id) { known = true; break; }
        if (!known) {
            custom.add(id);
            Talkgroups.saveCustom(this, custom);
            allTgs.add(0, new Talkgroups.Tg(id, "TG " + id, "手动添加"));
        }
        subs.add(id);
        Talkgroups.saveSubs(this, subs);
        if (hose != null) hose.setSubscriptions(Talkgroups.toArray(subs));
        search.setText("");
        rebuildShown();
        Toast.makeText(this, "已订阅 TG " + id, Toast.LENGTH_SHORT).show();
    }

    private void toggleSub(int id) {
        if (subs.contains(id)) subs.remove(id); else subs.add(id);
        Talkgroups.saveSubs(this, subs);
        rebuildShown();
        if (hose != null) hose.setSubscriptions(Talkgroups.toArray(subs));
        if (subs.isEmpty() && curStart == 0 && !muted) {
            npCaller.setText("没有订阅任何话组 —— 勾选下面任意一项开始收听");
        }
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
        } else {
            renderNow();
        }
    }

    /* ==================== HoseClient.Listener ==================== */

    @Override
    public void onState(HoseClient.State s, String detail) {
        stateText.setText(detail == null ? "" : detail);
        int color;
        switch (s) {
            case OPEN: color = getColor(R.color.ok); break;
            case CONNECTING:
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
        if (tg != 0 && tg != curTg) return;
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
    public void onMeter(int tg, int db) { /* 波形来自真实 PCM 的 RMS，不用官方电平表 */ }

    private void renderNow() {
        if (muted) { npTg.setText(R.string.idle_tg); npCaller.setText("已静音 · 仍会记录通联"); return; }
        if (curTg == 0) {
            npTg.setText(R.string.idle_tg);
            npCaller.setText(R.string.idle_caller);
            return;
        }
        String name = null;
        for (Talkgroups.Tg t : allTgs) if (t.id == curTg) { name = t.name; break; }
        npTg.setText((name == null ? "TG " + curTg : name) + " · " + curTg);
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
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tg, parent, false);
            }
            Talkgroups.Tg t = shown.get(pos);
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
