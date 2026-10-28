package net.beacon.dmr;

import android.app.Activity;
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
import net.beacon.dmr.core.Palette;
import net.beacon.dmr.core.Prefs;
import net.beacon.dmr.core.TalkgroupRepo;
import net.beacon.dmr.core.Talkgroups;
import net.beacon.dmr.hose.HoseClient;
import net.beacon.dmr.live.LhClient;
import net.beacon.dmr.live.LiveStore;
import net.beacon.dmr.live.MasterRepo;
import net.beacon.dmr.ui.DashPage;
import net.beacon.dmr.ui.SettingsPage;
import net.beacon.dmr.ui.Ui;
import net.beacon.dmr.ui.WaveView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 三页壳：主页（收听） / 看板 / 设置。
 *
 * <p>两条链路各管一摊，互不干扰：
 * <ul>
 *   <li>{@link HoseClient} —— HOSE 收听，只推我订阅的那几个话组，出声</li>
 *   <li>{@link LhClient} —— Last Heard 实时活动，全球所有通联，<b>不出声</b>，
 *       只喂看板</li>
 * </ul>
 * 把两件事分开是刻意的：看板要的是「全球在发生什么」，收听要的是「我要听哪几个组」。
 * WebView 版里这两条链路还互相影响过（原生接收让位那一段），纯 Java 版不存在这个问题。
 */
public final class MainActivity extends Activity
        implements HoseClient.Listener, LhClient.Listener {

    /* ==================== 界面 ==================== */
    private Palette pal;

    private View pageListen, pageDash, pageSettings;
    private Button tabListen, tabDash, tabSettings;

    private TextView stateText, npTg, npCaller, npTime, volPct, tgCount, liveState;
    private View dot, dotLive;
    private WaveView wave;
    private Button btnMute, btnAdd;
    private SeekBar seekVol;
    private EditText search;
    private CheckBox ckOnlySub;
    private ListView tgList;

    private DashPage dashPage;
    private SettingsPage settingsPage;

    /* ==================== 数据 ==================== */
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Talkgroups.Tg> allTgs = new ArrayList<>();
    private final List<Talkgroups.Tg> shown = new ArrayList<>();
    private final Set<Integer> subs = new LinkedHashSet<>();
    private final Set<Integer> custom = new LinkedHashSet<>();
    private final LiveStore live = new LiveStore();

    private TgAdapter adapter;
    private HoseClient hose;
    private AudioOut audio;
    private LhClient lh;

    private String view = "listen";
    private int tgSource = TalkgroupRepo.SRC_BUILTIN;

    private int curTg = 0;
    private int curSrc = 0;
    private String curCall = "";
    private String curName = "";
    private long curStart = 0;

    private boolean muted;
    private PowerManager.WakeLock wakeLock;

    /** 上次发给 HOSE 的订阅数组。用来判「要不要重发」。 */
    private int[] sentSubs = new int[0];
    private int tickCount = 0;

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        /* ★ 必须在 super.onCreate 之前：窗口底色在 setContentView 那一刻就定了，
         * 晚一步会先闪一下旧主题。 */
        Palette.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pal = Palette.of(this);

        view = Prefs.view(this);
        muted = Prefs.muted(this);

        bindViews();
        bindListenPage();
        bindTabs();

        dashPage = new DashPage(this, pageDash, live, pal, this::addTgFromDash);
        dashPage.setTgName(id -> nameOf((int) id));
        settingsPage = new SettingsPage(this, pageSettings, pal, settingsCb);
        settingsPage.syncThemeSeg();

        applySimpleMode(Prefs.simple(this));
        setView(view);

        subs.addAll(Talkgroups.loadSubs(this));
        custom.addAll(Talkgroups.loadCustom(this));

        audio = new AudioOut();
        audio.setLevelListener(rms -> ui.post(() -> wave.push(rms)));
        audio.setVolume(Prefs.volume(this));
        audio.setBoost(Prefs.gain(this));
        audio.setPresence(Prefs.presence(this));
        audio.setMuted(muted);
        if (!audio.start()) stateText.setText("音频输出初始化失败");

        TalkgroupRepo.load(this, (all, source) -> ui.post(() -> applyTgs(all, source)));
        MasterRepo.load(this, () -> ui.post(() -> { /* 主站表到了，下次刷看板就会用上 */ }));

        hose = new HoseClient(this);
        hose.start(effectiveSubs());
        sentSubs = effectiveSubs();

        lh = new LhClient(this);
        lh.start();

        showLastCrashIfAny();
        ui.postDelayed(tick, 500);
    }

    /**
     * 上次启动崩过就把堆栈弹出来。
     *
     * <p>alpha 阶段没有模拟器，第一次真机运行就是用户那台手机。
     * 万一启动即崩，用户能说的只有「打不开」—— 有了这一屏，
     * 截个图就能定位。看完点「知道了」即删除，不会每次都弹。
     */
    private void showLastCrashIfAny() {
        final String trace = App.lastCrash(this);
        if (trace == null || trace.isEmpty()) return;
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText(trace);
            tv.setTextSize(10f);
            tv.setTextIsSelectable(true);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextColor(pal.text);
            int p = Ui.dpI(this, 12);
            tv.setPadding(p, p, p, p);
            sv.addView(tv);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("上次运行崩溃了")
                    .setView(sv)
                    .setPositiveButton("知道了", (d, w) -> App.clearCrash(this))
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            App.clearCrash(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* AudioTrack 可能在上一次 onPause / 音频焦点丢失时被系统释放，
         * 回来时重新起一次。不起的话表现就是「切出去再回来就没声音」。 */
        if (audio != null && !audio.isRunning()) audio.start();
        syncAwake();
    }

    @Override
    protected void onPause() {
        super.onPause();
        /* 关掉「保持常亮」的用户不该被这个 App 一直按住 WakeLock。 */
        if (!Prefs.awake(this)) releaseWake();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (hose != null) hose.stop();
        if (lh != null) lh.stop();
        if (audio != null) audio.stop();
        releaseWake();
        super.onDestroy();
    }

    /* ==================== 绑定 ==================== */

    private void bindViews() {
        pageListen = findViewById(R.id.pageListen);
        pageDash = findViewById(R.id.pageDash);
        pageSettings = findViewById(R.id.pageSettings);

        tabListen = findViewById(R.id.tabListen);
        tabDash = findViewById(R.id.tabDash);
        tabSettings = findViewById(R.id.tabSettings);

        stateText = findViewById(R.id.stateText);
        liveState = findViewById(R.id.liveState);
        dot = findViewById(R.id.dot);
        dotLive = findViewById(R.id.dotLive);

        npTg = findViewById(R.id.npTg);
        npCaller = findViewById(R.id.npCaller);
        npTime = findViewById(R.id.npTime);
        wave = findViewById(R.id.wave);
        wave.setBarColor(pal.accent);

        volPct = findViewById(R.id.volPct);
        seekVol = findViewById(R.id.seekVol);
        btnMute = findViewById(R.id.btnMute);
        btnAdd = findViewById(R.id.btnAdd);
        search = findViewById(R.id.search);
        ckOnlySub = findViewById(R.id.ckOnlySub);
        tgCount = findViewById(R.id.tgCount);
        tgList = findViewById(R.id.tgList);

        Ui.tintSeek(seekVol, pal);
    }

    private void bindTabs() {
        tabListen.setOnClickListener(v -> setView("listen"));
        tabDash.setOnClickListener(v -> setView("dash"));
        tabSettings.setOnClickListener(v -> setView("settings"));
    }

    private void bindListenPage() {
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
        ckOnlySub.setOnCheckedChangeListener((v, on) -> rebuildShown());
        btnAdd.setOnClickListener(v -> addTyped());

        seekVol.setProgress(Prefs.volume(this));
        volPct.setText(Prefs.volume(this) + "%");
        seekVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                volPct.setText(v + "%");
                if (!fromUser) return;
                Prefs.setVolume(MainActivity.this, v);
                if (audio != null) audio.setVolume(v);
                if (settingsPage != null) settingsPage.setVolume(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnMute.setOnClickListener(v -> setMuted(!muted));
        renderMute();
    }

    /* ==================== 页面切换 ==================== */

    private void setView(String v) {
        if ("dash".equals(v) && Prefs.simple(this)) v = "listen";
        view = v;
        pageListen.setVisibility("listen".equals(v) ? View.VISIBLE : View.GONE);
        pageDash.setVisibility("dash".equals(v) ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility("settings".equals(v) ? View.VISIBLE : View.GONE);
        paintTab(tabListen, "listen".equals(v));
        paintTab(tabDash, "dash".equals(v));
        paintTab(tabSettings, "settings".equals(v));
        Prefs.setView(this, v);
        if ("dash".equals(v) && dashPage != null) dashPage.refresh();
    }

    private void paintTab(Button b, boolean on) {
        b.setTextColor(on ? pal.accent : pal.muted);
        b.getPaint().setFakeBoldText(on);
        b.invalidate();
    }

    /** 简洁模式：藏起看板标签。 */
    private void applySimpleMode(boolean on) {
        tabDash.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on && "dash".equals(view)) setView("listen");
    }

    /* ==================== 话组表 ==================== */

    private void applyTgs(List<Talkgroups.Tg> all, int source) {
        if (source == TalkgroupRepo.SRC_FAIL) {
            Toast.makeText(this, "在线话组表没拉到（网络），先用已有的 "
                    + allTgs.size() + " 个", Toast.LENGTH_LONG).show();
            return;
        }
        tgSource = source;
        allTgs.clear();
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

    private String nameOf(int id) {
        for (Talkgroups.Tg t : allTgs) if (t.id == id) return t.name;
        return null;
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
            String src = tgSource == TalkgroupRepo.SRC_ONLINE ? "在线"
                    : tgSource == TalkgroupRepo.SRC_CACHE ? "缓存" : "内置";
            tgCount.setText(String.format(Locale.US, "%d / %d · 订阅 %d · %s",
                    shown.size(), allTgs.size(), subs.size(), src));
        }
    }

    private void addTyped() {
        String raw = search.getText().toString().trim();
        if (!raw.matches("\\d{1,7}")) {
            Toast.makeText(this, "先在搜索框里输入话组号，例如 86023", Toast.LENGTH_SHORT).show();
            return;
        }
        int id = Integer.parseInt(raw);
        if (id <= 0) { Toast.makeText(this, "话组号不合法", Toast.LENGTH_SHORT).show(); return; }

        boolean known = nameOf(id) != null;
        if (!known) {
            custom.add(id);
            Talkgroups.saveCustom(this, custom);
            allTgs.add(0, new Talkgroups.Tg(id, "TG " + id, "手动添加"));
        }
        subs.add(id);
        Talkgroups.saveSubs(this, subs);
        search.setText("");
        rebuildShown();
        syncSubs();
        Toast.makeText(this, "已订阅 TG " + id, Toast.LENGTH_SHORT).show();
    }

    private void toggleSub(int id) {
        if (subs.contains(id)) subs.remove(id); else subs.add(id);
        Talkgroups.saveSubs(this, subs);
        rebuildShown();
        syncSubs();
        if (subs.isEmpty() && curStart == 0 && !muted) {
            npCaller.setText("没有订阅任何话组 —— 勾选下面任意一项开始收听");
        }
    }

    /** 从看板的实时活动里点一条 → 把那个话组加进收听。 */
    private void addTgFromDash(long tg, String name) {
        int id = (int) tg;
        if (id <= 0) return;
        if (subs.contains(id)) {
            Toast.makeText(this, "TG " + id + " 已经在收听列表里", Toast.LENGTH_SHORT).show();
            return;
        }
        subs.add(id);
        Talkgroups.saveSubs(this, subs);
        rebuildShown();
        syncSubs();
        Toast.makeText(this, "已加入收听：" + id + " " + name, Toast.LENGTH_SHORT).show();
    }

    /* ==================== 订阅同步 / 扫描 ==================== */

    /** 生效的订阅 = 手动订阅 + （扫描开着时）自动跟进的那几个。 */
    private int[] effectiveSubs() {
        LinkedHashSet<Integer> s = new LinkedHashSet<>(subs);
        if (Prefs.scanOn(this) && Prefs.scanAuto(this)) s.addAll(autoPick());
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        Arrays.sort(a);                 // 排序后比较才稳定，不然每秒都可能重发
        return a;
    }

    /** 自动跟进：此刻最活跃的 N 个话组（按最近有声音优先）。 */
    private List<Integer> autoPick() {
        int n = Prefs.autoN(this);
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (LiveStore.Session s : live.activeList()) {
            if (s.tg <= 90) continue;              // 和 WebView 版一致：9 / 91 以下不进自动跟进
            out.add((int) s.tg);
            if (out.size() >= n) break;
        }
        return new ArrayList<>(out);
    }

    private void syncSubs() {
        int[] now = effectiveSubs();
        if (Arrays.equals(now, sentSubs)) return;
        sentSubs = now;
        if (hose != null) hose.setSubscriptions(now);
        renderLiveState();
    }

    /* ==================== 音量 / 静音 ==================== */

    private void setMuted(boolean on) {
        muted = on;
        Prefs.setMuted(this, on);
        if (audio != null) audio.setMuted(on);
        if (on) wave.reset();
        renderMute();
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
            case OPEN: color = pal.ok; break;
            case CONNECTING:
            case RETRY: color = pal.warn; break;
            default: color = pal.dim; break;
        }
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        if (s == HoseClient.State.OPEN) syncAwake();
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
        syncAwake();
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
        String name = nameOf(curTg);
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

    /* ==================== LhClient.Listener ==================== */

    @Override
    public void onState(LhClient.State s, String detail) {
        int color;
        switch (s) {
            case OPEN: color = pal.ok; break;
            case CONNECTING:
            case RETRY: color = pal.warn; break;
            default: color = pal.dim; break;
        }
        dotLive.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        renderLiveState();
    }

    @Override
    public void onEvent(LiveStore.Event e) {
        /* ★ 这条回调在 WebSocket 读线程上，每秒十几条。这里只做一件事：
         * 丢进 LiveStore。绝对不要碰 View，也不要 post 到主线程 ——
         * 每秒 post 十几次 Runnable 会把主线程队列塞满，滑动直接卡。 */
        live.onEvent(e);
    }

    private void renderLiveState() {
        if (liveState == null) return;
        liveState.setText(String.format(Locale.US, "活动 %d · 收听 %d 组",
                live.activeCount(), sentSubs.length));
    }

    /* ==================== tick ==================== */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickCount++;
            if (curStart > 0) {
                long s = (System.currentTimeMillis() - curStart) / 1000;
                npTime.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
            } else {
                npTime.setText("");
            }
            wave.decay();

            /* 1 秒：看板（只在可见时刷）+ 实时活动的过期清理 */
            if (tickCount % 2 == 0) {
                live.prune();
                if ("dash".equals(view) && dashPage != null) dashPage.refresh();
                renderLiveState();
            }

            /* 3 秒：重算一次生效订阅（扫描会自动跟进新冒出来的活跃组） */
            if (tickCount % 6 == 0 && Prefs.scanOn(MainActivity.this)) syncSubs();

            /* 4 秒：音频自检读数 */
            if (tickCount % 8 == 0) updateAudioDiag();

            ui.postDelayed(this, 500);
        }
    };

    private void updateAudioDiag() {
        if (settingsPage == null || audio == null) return;
        settingsPage.setAudioDiag(String.format(Locale.US,
                "环=%s · 8kHz · 队列=%d · 丢帧=%d · 音量=%d%% · 增强=%d× · 窄带=%s",
                audio.isRunning() ? "on" : "off",
                audio.getQueuedFrames(),
                audio.getDroppedFrames(),
                Prefs.volume(this),
                Prefs.gain(this),
                Prefs.presence(this) ? "开" : "关"));
    }

    /* ==================== 设置回调 ==================== */

    private final SettingsPage.Listener settingsCb = new SettingsPage.Listener() {
        @Override public void onThemeChanged(int mode) {
            /* 换肤只能整页重建：主题属性在 inflate 时就绑死了。
             * 重建会重连两条 WebSocket，但换主题是低频操作，可以接受。 */
            recreate();
        }

        @Override public void onVolumeChanged(int pct) {
            seekVol.setProgress(pct);
            volPct.setText(pct + "%");
            if (audio != null) audio.setVolume(pct);
        }

        @Override public void onBoostChanged(int gain) {
            if (audio != null) audio.setBoost(gain);
            updateAudioDiag();
        }

        @Override public void onPresenceChanged(boolean on) {
            if (audio != null) audio.setPresence(on);
            updateAudioDiag();
        }

        @Override public void onAwakeChanged(boolean on) { syncAwake(); }

        @Override public void onSimpleChanged(boolean on) { applySimpleMode(on); }

        @Override public void onScanChanged() {
            syncSubs();
            Toast.makeText(MainActivity.this,
                    Prefs.scanOn(MainActivity.this) ? "扫描已开启" : "扫描已关闭",
                    Toast.LENGTH_SHORT).show();
        }
    };

    /* ==================== WakeLock ==================== */

    private void syncAwake() {
        if (!Prefs.awake(this)) { releaseWake(); return; }
        acquireWake();
    }

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
            name.setTextColor(on ? pal.text : pal.muted);
            id.setText(String.valueOf(t.id));
            id.setTextColor(pal.dim);
            desc.setText(t.desc);
            desc.setTextColor(pal.dim);
            desc.setVisibility(t.desc.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
