package net.beacon.dmr;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import net.beacon.dmr.audio.AudioOut;
import net.beacon.dmr.core.HostCheck;
import net.beacon.dmr.core.MuLaw;
import net.beacon.dmr.core.Palette;
import net.beacon.dmr.core.Prefs;
import net.beacon.dmr.core.TalkgroupRepo;
import net.beacon.dmr.core.Talkgroups;
import net.beacon.dmr.hose.HoseClient;
import net.beacon.dmr.live.LhClient;
import net.beacon.dmr.live.LiveStore;
import net.beacon.dmr.live.MasterRepo;
import net.beacon.dmr.mmdvm.DmrLink;
import net.beacon.dmr.mmdvm.MmdvmSession;
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
 * 三页壳：主页（收听 + 按住说话） / 看板 / 设置。
 *
 * <p>三条链路各管一摊，互不干扰：
 * <ul>
 *   <li>{@link HoseClient} —— HOSE 收听，只推我订阅的那几个话组，出声</li>
 *   <li>{@link LhClient} —— Last Heard 实时活动，全球所有通联，<b>不出声</b>，
 *       只喂看板</li>
 *   <li>{@link DmrLink} —— MMDVM 收发（UDP 62031），<b>和 HOSE 共用同一条
 *       {@link AudioOut}</b></li>
 * </ul>
 * 第三条是本工程和 WebView 版最本质的区别：那边 HOSE 走 WebAudio、
 * MMDVM 走另一条 AudioTrack，两条路叠着放的成因就在那里。这里只有一个队列。
 *
 * <p>闸门：{@link DmrLink#isNativeRx()} 为真时 HOSE 的帧直接丢掉（原生接管）。
 * 判据是 {@code rxOn && 已连接} —— <b>两个都成立</b>才算，否则「只发不收」的用户
 * 会遇到原生不播、HOSE 也让位的全静音。
 */
public final class MainActivity extends Activity
        implements HoseClient.Listener, LhClient.Listener, DmrLink.Listener {

    /* ==================== 界面 ==================== */
    private Palette pal;

    private View pageListen, pageDash, pageSettings;
    private Button tabListen, tabDash, tabSettings;

    /* ---- 顶栏第二行：音量 + 两个状态灯 ---- */
    private TextView stateText, volPct;
    private View dot, dotLive;
    private Button btnMute;
    private SeekBar seekVol;

    /* ---- 播报条（常驻，三页共享）----
     * 对应 WebView 版 .nowbar：左边一条 3dp 竖线 + 第一行「谁在说」+ 第二行「发到哪个组」。 */
    private LinearLayout npMain, npTg;
    private TextView npIdle, npCall, npName, npDb, npTime, npTgLbl, npTgNum, npTgName;
    private View npAccent, npLamp;
    private WaveView npVu;
    private Button npReply, btnRec;
    private TextView warnbar;

    /* ---- 主页操作区 ---- */
    private LinearLayout homeOp, htSum, mobNav, htFold;
    private Button btnNetBm, btnNetCn, btnTxConn, btnTxDisc, btnTxCfg;
    private TextView netInfo, txTgName, txIdState, txHint;
    private TextView hsNet, hsTxt, hsTg;
    private View hsDot, homeTopDivider;
    private TextView htFoldIco, htFoldTxt;
    private View htFoldLine;
    private EditText txTg, txIdQuick;
    private TextView txHostQuick;

    /* ---- 主页列表区（三栏，手机上一次只显示一栏）---- */
    private View colMine, colLive, colLib;
    private Button mcolMine, mcolLive, mcolLib;
    private TextView mineCount, tabCount, liveCount, libCount;
    private LinearLayout liveList;
    private EditText libSearch;
    private ListView libList;

    /* ---- 我的收听 ---- */
    private Button btnAdd;
    private EditText search;
    private ListView tgList;

    /* ---- 悬浮 PTT ---- */
    private LinearLayout pttBtn;
    private TextView pttTgNum, pttLbl;

    /* ---- 当前分栏（0 我的 / 1 活动 / 2 话组库）---- */
    private int mcol = 0;
    /** 操作区是否已收起。存本地，下次进来还是收起的。 */
    private boolean htCollapsed = false;
    /** 当前网络档："bm" 国际 BrandMeister / "cn" 国内 radiowo（Phase 4）。 */
    private String net = "bm";

    private DashPage dashPage;
    private SettingsPage settingsPage;

    /* ==================== 数据 ==================== */
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Talkgroups.Tg> allTgs = new ArrayList<>();
    private final List<Talkgroups.Tg> shown = new ArrayList<>();
    /** 「📚 话组库」那一栏的数据。和 {@link #shown}（我的收听）是两个独立列表。 */
    private final List<Talkgroups.Tg> libShown = new ArrayList<>();
    private final Set<Integer> subs = new LinkedHashSet<>();
    private final Set<Integer> custom = new LinkedHashSet<>();
    private final LiveStore live = new LiveStore();

    private TgAdapter adapter;
    private TgAdapter libAdapter;
    private HoseClient hose;
    private AudioOut audio;
    private LhClient lh;
    private DmrLink link;

    private String view = "listen";
    private int tgSource = TalkgroupRepo.SRC_BUILTIN;

    private int curTg = 0;
    private int curSrc = 0;
    private String curCall = "";
    private String curName = "";
    private long curStart = 0;

    private boolean muted;

    /** PTT 当前是否按着。用来防重复下发（DOWN 事件可能来两次） */
    private boolean pttDown;
    /**
     * 本次 PTT 是否真的开始发射过。
     *
     * <p>用来区分 {@link #onTx} 收到的 {@code false} 是哪一种：
     * 真的说过话之后正常/异常结束 =「发射中断」，从没开起来就失败 =「没发出去」。
     * 发射是异步启动的（见 {@code DmrLink.ptt}），所以按下的瞬间还判断不出来。
     */
    private boolean txStarted;
    /** 上一次请求录音权限的时机，避免连续弹窗 */
    private long permAskedAt = 0;
    private static final int REQ_MIC = 1001;
    private static final int REQ_NOTIF = 1002;

    /* ---- 原生链路的界面状态 ---- */
    /** 主卡片现在显示的是原生接收的那一路（句子结束时据此收回） */
    private boolean nativeRxOn;
    private volatile float lastRxDb = -120f;
    private volatile int txLevel;
    private volatile float txDb = -120f;
    /** 「话组忙」提示只弹一次，别每个包弹一遍 */
    private boolean busyWarned;
    /** 原生链路的日志尾巴，点「自检」时一起显示 */
    private final java.util.ArrayDeque<String> txLog = new java.util.ArrayDeque<>();

    /* ---- 收发状态行 ----
     * ★ 单独存一份，不能让 4 秒一次的 tick 用「未连接。填好主站…」把它冲掉 ——
     * 之前就是这样：出错原因在界面上最多活 4 秒，用户根本看不到，
     * 只记得「点了连接没反应」。 */
    private String txHeadline = "未连接";
    private boolean txHeadlineOk = false;

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
        /* 操作区折叠状态是纯视觉偏好，但必须在 bindListenPage 之前读出来 ——
         * 那里最后会 htFoldSet(htCollapsed, false) 落地一次，
         * 放到后面读的话第一次进会先闪一下展开再收起。 */
        htCollapsed = Prefs.htCollapsed(this);
        mcol = Prefs.mcol(this);

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
        audio.setLevelListener(rms -> ui.post(() -> npVu.push(rms)));
        audio.setVolume(Prefs.volume(this));
        audio.setBoost(Prefs.gain(this));
        audio.setPresence(Prefs.presence(this));
        audio.setMuted(muted);
        if (!audio.start()) stateText.setText("音频输出初始化失败");

        /* 收发链路。注意它拿的是**同一个** audio —— 这是整个重写的核心，
         * 不是随手传的。见类注释。 */
        link = new DmrLink(this, audio, this);
        link.setWatchTg(Prefs.watchTg(this));
        link.setRxOn(Prefs.rxOn(this));
        link.setRxOnlyWatchTg(Prefs.onlyWatchTg(this));
        link.setMicGainDb(Prefs.micGainDb(this));
        link.setTxParams(Prefs.slot(this), Prefs.colorCode(this));
        renderPttTg();

        TalkgroupRepo.load(this, (all, source) -> ui.post(() -> applyTgs(all, source)));
        MasterRepo.load(this, () -> ui.post(() -> { /* 主站表到了，下次刷看板就会用上 */ }));

        hose = new HoseClient(this);
        hose.start(effectiveSubs());
        sentSubs = effectiveSubs();

        lh = new LhClient(this);
        lh.start();

        showLastCrashIfAny();
        /* 通知权限（Android 13+）。不给也能用，只是看不到那条常驻通知。 */
        ensureNotifPermission();
        syncAwake();
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
        /* 屏幕常亮用 FLAG_KEEP_SCREEN_ON，系统会跟着窗口生命周期自动放开，
         * 这里不需要手动清 —— 手动清反而会在「切出去再回来」时丢掉常亮。
         * 链路保活由 KeepAliveService 负责，onPause 也不能停它：
         * 停了就正好是「切后台断链」。 */
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (link != null) link.disconnect();
        if (hose != null) hose.stop();
        if (lh != null) lh.stop();
        if (audio != null) audio.stop();
        KeepAliveService.stop(this);
        super.onDestroy();
    }

    /* ==================== 绑定 ==================== */

    private void bindViews() {
        pageListen = findViewById(R.id.pageListen);
        pageDash = findViewById(R.id.pageDash);
        pageSettings = findViewById(R.id.pageSettings);

        /* ---- 顶栏 ---- */
        tabListen = findViewById(R.id.tabListen);
        tabDash = findViewById(R.id.tabDash);
        tabSettings = findViewById(R.id.tabSettings);
        btnMute = findViewById(R.id.btnMute);
        seekVol = findViewById(R.id.seekVol);
        volPct = findViewById(R.id.volPct);
        stateText = findViewById(R.id.stateText);
        dot = findViewById(R.id.dot);
        dotLive = findViewById(R.id.dotLive);

        /* ---- 警告条 + 播报条 ---- */
        warnbar = findViewById(R.id.warnbar);
        npAccent = findViewById(R.id.npAccent);
        npIdle = findViewById(R.id.npIdle);
        npMain = findViewById(R.id.npMain);
        npTg = findViewById(R.id.npTg);
        npLamp = findViewById(R.id.npLamp);
        npCall = findViewById(R.id.npCall);
        npName = findViewById(R.id.npName);
        npDb = findViewById(R.id.npDb);
        npTime = findViewById(R.id.npTime);
        npVu = findViewById(R.id.npVu);
        npVu.setBarColor(pal.accent);
        npTgLbl = findViewById(R.id.npTgLbl);
        npTgNum = findViewById(R.id.npTgNum);
        npTgName = findViewById(R.id.npTgName);
        npReply = findViewById(R.id.npReply);
        btnRec = findViewById(R.id.btnRec);

        /* ---- 主页操作区 ---- */
        homeOp = findViewById(R.id.homeOp);
        btnNetBm = findViewById(R.id.btnNetBm);
        btnNetCn = findViewById(R.id.btnNetCn);
        netInfo = findViewById(R.id.netInfo);
        btnTxConn = findViewById(R.id.btnTxConn);
        btnTxDisc = findViewById(R.id.btnTxDisc);
        btnTxCfg = findViewById(R.id.btnTxCfg);
        txTg = findViewById(R.id.txTg);
        txTgName = findViewById(R.id.txTgName);
        txIdQuick = findViewById(R.id.txIdQuick);
        txHostQuick = findViewById(R.id.txHostQuick);
        txIdState = findViewById(R.id.txIdState);
        txHint = findViewById(R.id.txHint);
        htSum = findViewById(R.id.htSum);
        hsNet = findViewById(R.id.hsNet);
        hsDot = findViewById(R.id.hsDot);
        hsTxt = findViewById(R.id.hsTxt);
        hsTg = findViewById(R.id.hsTg);
        mobNav = findViewById(R.id.mobNav);
        mcolMine = findViewById(R.id.mcolMine);
        mcolLive = findViewById(R.id.mcolLive);
        mcolLib = findViewById(R.id.mcolLib);
        htFold = findViewById(R.id.htFold);
        htFoldLine = findViewById(R.id.htFoldLine);
        htFoldIco = findViewById(R.id.htFoldIco);
        htFoldTxt = findViewById(R.id.htFoldTxt);
        homeTopDivider = findViewById(R.id.homeTopDivider);

        /* ---- 三栏列表 ---- */
        colMine = findViewById(R.id.colMine);
        colLive = findViewById(R.id.colLive);
        colLib = findViewById(R.id.colLib);
        mineCount = findViewById(R.id.mineCount);
        tabCount = findViewById(R.id.tabCount);
        liveCount = findViewById(R.id.liveCount);
        libCount = findViewById(R.id.libCount);
        liveList = findViewById(R.id.liveList);
        libSearch = findViewById(R.id.libSearch);
        libList = findViewById(R.id.libList);

        /* ---- 我的收听 ---- */
        btnAdd = findViewById(R.id.btnAdd);
        search = findViewById(R.id.search);
        tgList = findViewById(R.id.tgList);

        /* ---- 悬浮 PTT ---- */
        pttBtn = findViewById(R.id.pttBtn);
        pttTgNum = findViewById(R.id.pttTgNum);
        pttLbl = findViewById(R.id.pttLbl);

        Ui.tintSeek(seekVol, pal);
    }

    private void bindTabs() {
        tabListen.setOnClickListener(v -> setView("listen"));
        tabDash.setOnClickListener(v -> setView("dash"));
        tabSettings.setOnClickListener(v -> setView("settings"));
    }

    private void bindListenPage() {
        adapter = new TgAdapter(shown);
        tgList.setAdapter(adapter);
        tgList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) toggleSub(shown.get(pos).id);
        });

        /* 「📚 话组库」是独立的一栏、独立的搜索框、独立的数据源。
         * 点一下就是加入 / 移出收听 —— 和「我的收听」里那个勾选框等价。 */
        libAdapter = new TgAdapter(libShown);
        libList.setAdapter(libAdapter);
        libList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < libShown.size()) toggleSub(libShown.get(pos).id);
        });
        libSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { rebuildLib(); }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { rebuildShown(); }
        });
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

        bindHomeChrome();
        bindPtt();
    }

    /* ==================== 主页操作区的交互 ==================== */

    /**
     * 绑定 home-top 那一整块：分栏条、折叠、网络切换、发射条。
     *
     * <p>这一块是 1:1 复刻 WebView 版新增的，上一版完全没有 —— 上一版把
     * 「网络 / 发射目标 / 身份」全塞进了设置页，主页只剩一个话组列表和一条 PTT。
     */
    private void bindHomeChrome() {
        /* ---- 分栏条：一次只显示一栏 ---- */
        mcolMine.setOnClickListener(v -> mobCol(0));
        mcolLive.setOnClickListener(v -> mobCol(1));
        mcolLib.setOnClickListener(v -> mobCol(2));

        /* ---- 收起 / 展开操作区 ---- */
        htFold.setOnClickListener(v -> htFoldSet(!htCollapsed, true));
        /* 折叠状态下点摘要行也能展开（WebView 版同样如此） */
        htSum.setOnClickListener(v -> { if (htCollapsed) htFoldSet(false, true); });

        /* ---- 网络切换 ----
         * 「国内 DMR」走的是 radiowo 那条链，归 Phase 4。现在点它只换选中态并
         * 说明清楚 —— 假装切过去了然后什么都不发生，比不做更糟。 */
        btnNetBm.setOnClickListener(v -> setNet("bm"));
        btnNetCn.setOnClickListener(v -> setNet("cn"));

        /* ---- 发射条 ----
         * 这颗「连接」和设置页里那颗是同一个动作（走 settingsCb.onTxConnect），
         * 所以不重复实现连接逻辑。 */
        btnTxConn.setOnClickListener(v -> settingsCb.onTxConnect());
        btnTxDisc.setOnClickListener(v -> {
            if (link != null && link.isConnected()) settingsCb.onTxConnect();
            else Toast.makeText(this, "当前没有连接", Toast.LENGTH_SHORT).show();
        });
        btnTxCfg.setOnClickListener(v -> setView("settings"));

        /* 话组号：输入完（失焦 / 回车）才落盘，边输边写会把 91 变成 9 再变成 91 */
        txTg.setOnFocusChangeListener((v, has) -> {
            if (!has) commitTxTg();
        });
        txTg.setOnEditorActionListener((v, actionId, ev) -> {
            commitTxTg();
            txTg.clearFocus();
            return true;
        });

        /* DMR ID 快填：同样失焦才落盘 */
        txIdQuick.setText(Prefs.dmrId(this) == 0 ? "" : String.valueOf(Prefs.dmrId(this)));
        txIdQuick.setOnFocusChangeListener((v, has) -> {
            if (has) return;
            int id = parseIntOr(txIdQuick.getText().toString(), 0);
            if (id >= 1000) {
                Prefs.setDmrId(this, id);
                renderIdentity(false);
            }
        });

        /* 主站点一下就能改：这里只做「跳到设置页那一段」，不另开一套编辑器 */
        txHostQuick.setOnClickListener(v -> setView("settings"));

        renderNet();
        renderIdentity(false);
        htFoldSet(htCollapsed, false);
        mobCol(mcol);
    }

    /** 提交「发射到哪个话组」。1~7 位数字才算数，别的直接回滚显示。 */
    private void commitTxTg() {
        if (txTg == null) return;
        String raw = txTg.getText().toString().trim();
        int tg = parseIntOr(raw, 0);
        if (tg <= 0 || tg > 9999999) {
            renderPttTg();          // 回滚成当前值
            return;
        }
        Prefs.setWatchTg(this, tg);
        if (link != null) link.setWatchTg(tg);
        renderPttTg();
    }

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        if (t.isEmpty()) return def;
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return def; }
    }

    /**
     * 切换分栏（手机端一次只显示一栏）。
     *
     * <p>对应 WebView 版 {@code mobCol(i)}：`.grid > .col` 按 DOM 顺序取第 i 个，
     * 加 `.mcol-on`，只有它 display:flex。这里是三个 ScrollView 的可见性。
     */
    private void mobCol(int i) {
        mcol = Math.max(0, Math.min(2, i));
        colMine.setVisibility(mcol == 0 ? View.VISIBLE : View.GONE);
        colLive.setVisibility(mcol == 1 ? View.VISIBLE : View.GONE);
        colLib.setVisibility(mcol == 2 ? View.VISIBLE : View.GONE);
        paintSeg(mcolMine, mcol == 0);
        paintSeg(mcolLive, mcol == 1);
        paintSeg(mcolLib, mcol == 2);
        Prefs.setMcol(this, mcol);
        /* 切到活动栏时才重建它 —— 它每 500 ms 都在变，没必要常驻渲染 */
        if (mcol == 1) renderLiveList();
        if (mcol == 2) rebuildLib();
    }

    /** 分栏条上的三颗按钮：选中的用主色实心 + 白字（.mobnav button.on）。 */
    private void paintSeg(Button b, boolean on) {
        if (b == null) return;
        b.setBackground(on ? getDrawable(R.drawable.bg_btn) : getDrawable(R.drawable.bg_btn_small));
        b.setTextColor(on ? 0xFFFFFFFF : pal.muted);
    }

    /**
     * 收起 / 展开操作区。
     *
     * <p>{@code .home-top.collapsed .home-op{display:none}} +
     * {@code .home-top.collapsed .htsum{display:flex}}，箭头翻 180°，
     * 去掉上方虚线与负边距。
     *
     * @param persist 用户点击时存本地；启动恢复时不存（否则会把上次的值
     *                又写一遍，没有意义还可能覆盖别的窗口的改动）
     */
    private void htFoldSet(boolean collapsed, boolean persist) {
        htCollapsed = collapsed;
        if (homeOp != null) homeOp.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (htSum != null) htSum.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        if (htFoldIco != null) {
            htFoldIco.setText(collapsed ? "▾" : "▴");
        }
        if (htFoldTxt != null) {
            htFoldTxt.setText(collapsed ? "展开操作区" : "收起操作区");
        }
        /* 虚线单独一个 1dp 的 View。给 22dp 高的容器直接设虚线背景会围成一圈，
         * 那是 XML shape 的固有行为（只能整圈描边）。折叠时它整体消失、
         * 按钮也贴到上面去，对应 .home-top.collapsed .htfold{margin-top:0;border-top:none}。 */
        if (htFoldLine != null) {
            htFoldLine.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (persist) Prefs.setHtCollapsed(this, collapsed);
        renderHtSum();
    }

    /**
     * 折叠摘要那一行：网络 + 状态点 + 谁在说 + 当前发射目标。
     *
     * <p>折叠只该省屏幕，不该把收听状态一起藏起来 —— 往下滑看记录时还是要能
     * 一眼看到有没有人在说话、上一通是谁。
     */
    private void renderHtSum() {
        if (hsNet == null) return;
        hsNet.setText("bm".equals(net) ? "🌍 BM" : "🇨🇳 国内");
        hsTg.setText(Prefs.watchTg(this) <= 0 ? "—" : String.valueOf(Prefs.watchTg(this)));

        boolean rx = link != null && link.isNativeRx();
        boolean tx = link != null && link.isTransmitting();
        if (tx) {
            hsTxt.setText("发射中 → " + Prefs.watchTg(this));
            tint(hsDot, pal.tx);
        } else if (rx) {
            String who = curCall.isEmpty() ? ("ID " + curSrc) : curCall;
            hsTxt.setText("正在收听 · " + who);
            tint(hsDot, pal.ok);
        } else if (link != null && link.isConnected()) {
            hsTxt.setText("守听中");
            tint(hsDot, pal.ok);
        } else {
            hsTxt.setText("未连接主站");
            tint(hsDot, pal.border2);
        }
    }

    private void tint(View v, int color) {
        if (v == null) return;
        v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
    }

    /** 网络切换的选中态。真实链路切换归 Phase 4。 */
    private void setNet(String n) {
        net = "cn".equals(n) ? "cn" : "bm";
        if ("cn".equals(net)) {
            Toast.makeText(this,
                    "国内 DMR 链路（radiowo）归 Phase 4。现在只有国际 BrandMeister Hoseline 可用。",
                    Toast.LENGTH_LONG).show();
        }
        renderNet();
        renderHtSum();
    }

    private void renderNet() {
        boolean bm = !"cn".equals(net);
        paintSeg(btnNetBm, bm);
        paintSeg(btnNetCn, !bm);
        if (netInfo != null) {
            netInfo.setText(bm ? "BrandMeister Hoseline" : "radiowo 国内链路（Phase 4）");
        }
    }

    /**
     * 发射条上「身份」那几处的显示：DMR ID / 主站 / 连接状态。
     *
     * @param connected 传 true 时按已连接渲染（绿色），false 时看 {@code link} 的真实状态
     */
    private void renderIdentity(boolean connected) {
        if (txIdQuick != null && !txIdQuick.hasFocus()) {
            int id = Prefs.dmrId(this);
            txIdQuick.setText(id == 0 ? "" : String.valueOf(id));
        }
        if (txHostQuick != null) {
            String h = Prefs.host(this);
            /* 只显示第一段，这一段本来就窄，全域名会被省略号吃掉后半截，
             * 而用户真正想确认的正是「是哪个主站号」。 */
            int dot = h.indexOf('.');
            txHostQuick.setText(dot > 0 ? h.substring(0, dot) + "…" : h);
        }
        boolean ok = link != null && link.isConnected();
        if (txIdState != null) {
            txIdState.setText(ok ? "已连接" : "未连接");
            txIdState.setTextColor(ok ? pal.ok : pal.dim);
        }
        if (btnTxConn != null) {
            btnTxConn.setEnabled(!ok);
            btnTxConn.setAlpha(ok ? 0.5f : 1f);
        }
        if (btnTxDisc != null) {
            btnTxDisc.setEnabled(ok);
            btnTxDisc.setAlpha(ok ? 1f : 0.5f);
        }
        /* 已连接但声码器没起来 —— 这是最容易被忽略的半残状态：
         * 能听、按钮也亮着，按下去却一个字都发不出去。
         * 挂在警告条上，而不是等用户按了 PTT 才发现。 */
        if (ok && link != null && !link.isVocoderReady()) {
            showWarn("⚠ 声码器未就绪：能收听，但按 PTT 发不出去。"
                    + "点右上「⚙️ 设置」→「诊断」看具体原因。");
        } else {
            hideWarn();
        }

        renderPttButton();
        renderHtSum();
    }

    /**
     * 顶栏下面那条警告条。对应 WebView 版 .warnbar（默认 display:none，
     * 有事才占位，而且是 tx2 底色 + tx 字，一眼是「警告」不是「错误弹窗」）。
     */
    private void showWarn(String text) {
        if (warnbar == null) return;
        warnbar.setText(text);
        warnbar.setVisibility(View.VISIBLE);
    }

    private void hideWarn() {
        if (warnbar == null) return;
        if (warnbar.getVisibility() != View.GONE) warnbar.setVisibility(View.GONE);
    }

    /* ==================== 按住说话 ==================== */

    /**
     * PTT 用 {@code OnTouchListener} 而不是 {@code OnClickListener}：
     * 对讲要的是「按下开始、松开结束」，点击事件只有「抬起」这一个时刻，
     * 拿不到按住时长。这也是所有对讲 App 的做法。
     *
     * <p>三个动作都要处理：{@code ACTION_CANCEL} 在手指滑出按钮、来电打断、
     * 系统回收触摸流时都会来 —— 不处理就会出现「松手了还在发」，
     * 那是能占死话组的故障。
     */
    private void bindPtt() {
        if (pttBtn == null) return;

        pttBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startPtt();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopPtt("松开");
                    /* 消费掉，否则 UP 之后还会走一次 click */
                    return true;
                default:
                    return false;
            }
        });

        /* 点「话组名」换发射目标 —— 和 WebView 版 .ptxnm[data-click] 一致 */
        txTgName.setOnClickListener(v -> pickPttTg());
        renderPttTg();
    }

    private void startPtt() {
        if (link == null || pttDown) return;
        if (!ensureMicPermission()) return;

        int tg = Prefs.watchTg(this);
        if (tg <= 0) {
            Toast.makeText(this, "先在「发射到」里填一个话组号", Toast.LENGTH_SHORT).show();
            return;
        }

        String err = link.ptt(true, tg, Prefs.slot(this), Prefs.colorCode(this), false);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
            return;
        }
        pttDown = true;
        /* WebView 版按下时是 transform:scale(.94) —— 缩一点，像真的按进去了。
         * 文字不动（圆钮上永远是「组号 + PTT」）。 */
        pttBtn.setScaleX(0.94f);
        pttBtn.setScaleY(0.94f);
        pttBtn.setElevation(dp(2));
    }

    private void stopPtt(String reason) {
        if (link == null || !pttDown) return;
        pttDown = false;
        link.ptt(false, 0, 0, 0, false);
        pttBtn.setScaleX(1f);
        pttBtn.setScaleY(1f);
        pttBtn.setElevation(dp(8));
        renderPttButton();
    }

    /** 点「TG —」换发射目标。只列已订阅的组 + 手动输入的入口。 */
    private void pickPttTg() {
        final List<Integer> ids = new ArrayList<>(subs);
        if (ids.isEmpty()) {
            Toast.makeText(this, "还没有订阅任何话组，先在下面的列表里勾一个",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            String n = nameOf(id);
            labels[i] = (n == null ? "TG " + id : n + "  ·  " + id);
        }
        int cur = Prefs.watchTg(this);
        int checked = ids.indexOf(cur);

        new AlertDialog.Builder(this)
                .setTitle("发射到哪个话组")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    int tg = ids.get(which);
                    Prefs.setWatchTg(this, tg);
                    if (link != null) link.setWatchTg(tg);
                    renderPttTg();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 刷新「发射到哪个话组」这一处显示。
     *
     * <p>WebView 版这里是三件东西并排：输入框 {@code #txTg}、话组名 {@code #txTgName}、
     * 圆钮上的组号 {@code #pttTg}。三处必须同时改 —— 只改一处会出现
     * 「输入框写着 460、圆钮上还是 91」，按下去发到哪个组全靠猜。
     */
    private void renderPttTg() {
        int tg = Prefs.watchTg(this);
        String name = tg <= 0 ? null : nameOf(tg);

        if (txTg != null) {
            /* 只在没聚焦时回写，否则用户正在输入会被打断 */
            if (!txTg.hasFocus()) txTg.setText(tg <= 0 ? "" : String.valueOf(tg));
            txTg.setHint("91");
        }
        if (txTgName != null) {
            txTgName.setText(tg <= 0 ? "未选话组"
                    : (name == null ? "TG " + tg : name));
            txTgName.setTextColor(tg <= 0 ? pal.dim : pal.muted);
        }
        if (pttTgNum != null) {
            pttTgNum.setText(tg <= 0 ? "—" : String.valueOf(tg));
        }
        if (hsTg != null) hsTg.setText(tg <= 0 ? "—" : String.valueOf(tg));
        renderPttButton();
    }

    /**
     * 悬浮 PTT 钮的三态外观。
     *
     * <p>WebView 版：未连接是灰渐变（{@code .ptt:disabled}），
     * 正在接收 MMDVM 那一路时染绿（{@code .ptt.rx}），其余是红渐变。
     * 绿色那档很重要 —— 它让「现在有人在说话」在拇指下面就能看见，
     * 不用回头看播报条。
     */
    private void renderPttButton() {
        if (pttBtn == null) return;
        int bg;
        if (link == null || !link.isConnected()) {
            bg = R.drawable.bg_ptt_off;
        } else if (link.isNativeRx()) {
            bg = R.drawable.bg_ptt_rx;
        } else {
            bg = R.drawable.bg_ptt;
        }
        pttBtn.setBackgroundResource(bg);
        pttBtn.setElevation(dp(link != null && link.isConnected() ? 8 : 0));
    }

    /** dp → px。几处要跟代码算的尺寸都走这里，别到处写 getResources().getDisplayMetrics()。 */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ==================== 录音权限 ==================== */

    /**
     * Android 6+ 要运行时申请。返回 false 表示「这次不能发」，
     * 但已经把申请弹窗挂出去了，用户同意后下次按就会成功。
     */
    private boolean ensureMicPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - permAskedAt > 1500) {
            permAskedAt = now;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(req, perms, granted);
        boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_MIC) {
            Toast.makeText(this, ok ? "麦克风权限已授予，可以按住说话了"
                    : "没有麦克风权限，只能收听不能发射", Toast.LENGTH_LONG).show();
        } else if (req == REQ_NOTIF && !ok) {
            Toast.makeText(this, "没给通知权限 —— 切到后台仍然在守听，只是看不到那条常驻通知",
                    Toast.LENGTH_LONG).show();
        }
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
        /* 选中项套一层实心底（bg_seg_on），未选中透明 —— 这是分段控件的样子。
         * 只改文字颜色的话，三个页签看起来还是「三颗按钮」，不是一组。 */
        b.setBackground(on ? getDrawable(R.drawable.bg_seg_on) : null);
        b.getPaint().setFakeBoldText(true);
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
        /* 话组表到了，PTT 那颗按钮上的组名才能从「TG 46001」换成「中国」 */
        renderPttTg();
        if (source == TalkgroupRepo.SRC_ONLINE) {
            Toast.makeText(this, "话组表已更新：" + allTgs.size() + " 个", Toast.LENGTH_SHORT).show();
        }
    }

    private String nameOf(int id) {
        for (Talkgroups.Tg t : allTgs) if (t.id == id) return t.name;
        return null;
    }

    /**
     * 重建「⭐ 我的收听」这一栏。
     *
     * <p>★ 这一栏**只列已订阅的组**。上一版把全部 1873 个话组都塞进这一个列表，
     * 靠一个「只看已订阅」复选框来筛 —— 那是「一个列表兼两用」的做法，
     * 而 WebView 版本来就分成了两栏：「我的收听」= 订阅，「话组库」= 全部。
     * 复选框因此被删掉，两个列表各管一件事。
     */
    private void rebuildShown() {
        if (adapter == null) return;
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Talkgroups.Tg t : allTgs) {
            if (!subs.contains(t.id)) continue;
            if (!q.isEmpty() && !match(t, q)) continue;
            shown.add(t);
        }
        adapter.notifyDataSetChanged();
        if (mineCount != null) mineCount.setText(String.valueOf(subs.size()));
        rebuildLib();
    }

    /** 话组号 / 名称 / 说明任一命中。 */
    private static boolean match(Talkgroups.Tg t, String q) {
        return String.valueOf(t.id).contains(q)
                || t.name.toLowerCase(Locale.ROOT).contains(q)
                || t.desc.toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * 重建「📚 话组库」那一栏 —— 全部话组，带自己的搜索框。
     *
     * <p>库里的条目点一下就是「加入收听 / 从收听移除」，和「我的收听」里
     * 勾选框的效果一致：这里不做第三个概念，只有「在听」和「不在听」。
     */
    private void rebuildLib() {
        if (libAdapter == null) return;
        String q = libSearch == null ? ""
                : libSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        libShown.clear();
        for (Talkgroups.Tg t : allTgs) {
            if (!q.isEmpty() && !match(t, q)) continue;
            libShown.add(t);
            /* 库有 1800+ 条，全塞进一个 LinearLayout/ListView 会让滚动卡顿。
             * 搜了就给 200 条上限，没搜也一样 —— 剩下的靠搜索找。 */
            if (libShown.size() >= 200) break;
        }
        libAdapter.notifyDataSetChanged();
        if (libCount != null) {
            libCount.setText(q.isEmpty() ? String.valueOf(libShown.size())
                    : libShown.size() + "/" + allTgs.size());
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
            showIdle("🎧 还没有收听任何话组 · 去「话组库」搜一个加进来");
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
        if (on) npVu.reset();
        renderMute();
    }

    private void renderMute() {
        /* 顶栏那颗是「开始 / 停止收听」，不是「静音」—— WebView 版 #btnPlay 的语义。
         * 文案跟着状态走，否则用户会以为它是一个切换开着的开关。 */
        btnMute.setText(muted ? "🔇 已停听" : "🔊 开始收听");
        if (muted) {
            showIdle("🔇 已静音 · 仍会记录通联");
        } else {
            renderNow();
        }
        KeepAliveService.update(this, keepAliveText());
    }

    /* ==================== 播报条 ==================== */

    /**
     * 播报条切到「有通话」形态。
     *
     * <p>对应 WebView 版 {@code #npMain}：第一行呼号 + 台站名 + 电平 + 时长，
     * 第二行话组胶囊 + 回复 / 录音。空态（{@code #npIdle}）反过来。
     */
    private void showNowPlaying(int tg, String tgName, String call, String name) {
        if (npMain == null) return;
        npIdle.setVisibility(View.GONE);
        npMain.setVisibility(View.VISIBLE);

        npTgLbl.setText("话组");
        npTgNum.setText(tg > 0 ? String.valueOf(tg) : "—");
        npTgName.setText(tgName == null || tgName.isEmpty() ? "—" : tgName);

        npCall.setText(call == null || call.isEmpty() ? "—" : call);
        npName.setText(name == null ? "" : name);
    }

    /**
     * 播报条回到空态。
     *
     * @param why 给用户看的一句话。**不要**写「暂无通话」这种没有信息量的，
     *            要说清「下一步该干什么」。
     */
    private void showIdle(String why) {
        if (npMain == null) return;
        npMain.setVisibility(View.GONE);
        npIdle.setVisibility(View.VISIBLE);
        npIdle.setText(why);
    }

    /** 个呼 / 组呼的胶囊配色：个呼走红色，一眼能和组呼分开。 */
    private void markTgPillPrivate(boolean priv) {
        if (npTg == null) return;
        npTg.setBackgroundResource(priv ? R.drawable.bg_reply : R.drawable.bg_tg_pill);
        npTgNum.setTextColor(priv ? pal.tx : pal.text);
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
        /* 原生链路正在出声时，HOSE 的帧已经被丢掉了（见 onAudio），
         * 这里如果还更新卡片，就会出现「卡片写着 A、耳朵里是 B」。
         * 注意只在这个窗口内让位 —— 判据是「正在收」，不是「已连接」。 */
        if (link != null && link.isNativeRx()) return;
        curTg = tg;
        curSrc = srcId;
        curCall = "";
        curName = "";
        curStart = System.currentTimeMillis();
        npVu.reset();
        renderNow();
        syncAwake();
    }

    @Override
    public void onCallEnd(int tg) {
        if (link != null && link.isNativeRx()) return;
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
        if (link != null && link.isNativeRx()) return;
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
        /* ★ 半双工闸门的第二半：原生链路在收的时候，HOSE 让位。
         *
         * 判据是 link.isNativeRx()（= rxOn && 已连接），两个条件都得成立 ——
         * 「只发不收」的用户关掉了 rxOn，那时原生不播，如果 HOSE 还让位，
         * 就是全静音。v2.5.x 在 WebView 版里踩过这个坑，注释写在 DmrLink 类头。
         *
         * 注意这里丢的是「帧」而不是「压增益」：两条链共用同一个 AudioOut 队列，
         * 所以不存在「两句叠着放」，也就完全不需要在句子中间做归属判断。 */
        if (link != null && link.isNativeRx()) return;

        short[] pcm = new short[ulaw.length];
        MuLaw.decode(ulaw, 0, ulaw.length, pcm);
        audio.feed(pcm, ulaw.length);
    }

    @Override
    public void onMeter(int tg, int db) { /* 波形来自真实 PCM 的 RMS，不用官方电平表 */ }

    private void renderNow() {
        if (muted) {
            showIdle("🔇 已静音 · 仍会记录通联");
            return;
        }
        if (curTg == 0) {
            showIdle("🎧 暂无通话 · 从「实时活动」或「话组库」添加话组开始收听");
            return;
        }
        /* 第一行是呼号 + 台站名，第二行是话组胶囊 —— 和 WebView 版 #npMain 一致。
         * 没有呼号时用 ID 顶上，不能让第一行空着：空着看起来像「没在收听」。 */
        String call = curCall.isEmpty() ? "ID " + curSrc : curCall;
        StringBuilder name = new StringBuilder();
        if (!curName.isEmpty()) name.append(curName);
        if (name.length() > 0) name.append(" · ");
        name.append("ID ").append(curSrc);

        markTgPillPrivate(false);
        showNowPlaying(curTg, nameOf(curTg), call, name.toString());
        /* 锁屏时瞄一眼通知就知道在听谁。文案没变时 update() 自己会跳过。 */
        KeepAliveService.update(this, keepAliveText());
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

    /**
     * 刷新顶栏那行统计，以及「📡 实时活动」栏的计数与列表。
     *
     * <p>上一版把「活动 N · 收听 M 组」塞在顶栏状态灯的后面，字一多就挤成一团。
     * 现在活动数归它自己那一栏的标题胶囊，顶栏只留一句最要紧的。
     */
    private void renderLiveState() {
        int n = live.activeCount();
        if (liveCount != null) liveCount.setText(String.valueOf(n));
        if (tabCount != null) tabCount.setText(String.valueOf(n));
        if (stateText != null) {
            stateText.setText(String.format(Locale.US, "收听 %d 组", sentSubs.length));
        }
        renderLiveList();
    }

    /**
     * 重建「📡 实时活动」列表。
     *
     * <p>手机上一屏放不下 12 条，所以只画最近 8 条；点一条就把它的话组加进收听
     * （和看板里那张「实时活动」卡片是同一个动作）。
     */
    private void renderLiveList() {
        if (liveList == null) return;
        liveList.removeAllViews();
        List<LiveStore.Session> list = live.activeList();
        int max = Math.min(8, list.size());
        if (max == 0) {
            TextView tv = new TextView(this);
            tv.setText("还没有活动。等一会儿，或者去「话组库」手动加一个组。");
            tv.setTextColor(pal.dim);
            tv.setTextSize(11.5f);
            tv.setPadding(dp(4), dp(6), dp(4), dp(6));
            liveList.addView(tv);
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        for (int i = 0; i < max; i++) {
            LiveStore.Session s = list.get(i);
            TextView tv = new TextView(this);
            String nm = nameOf((int) s.tg);
            String who = (s.call == null || s.call.isEmpty()) ? ("ID " + s.srcId) : s.call;
            /* s.start 是 epoch 秒，不是毫秒 —— 早先这里按毫秒算过，
             * 结果每条都显示「1750 万秒前」。 */
            long age = Math.max(0, now - s.start);
            tv.setText(String.format(Locale.US, "%s  ·  TG %d %s  ·  %ds",
                    who, s.tg, nm == null ? "" : nm, age));
            tv.setTextColor(s.stop == 0 ? pal.text : pal.muted);
            tv.setTextSize(11.5f);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setPadding(dp(4), dp(7), dp(4), dp(7));
            final int tg = (int) s.tg;
            final String name = nm;
            tv.setOnClickListener(v -> addTgFromDash(tg, name == null ? "" : name));
            liveList.addView(tv);
        }
    }

    /* ==================== DmrLink.Listener ==================== */

    /**
     * 原生链路的事件都在**收包线程 / 发射线程**上，所以这里一律
     * {@code ui.post} 再碰 View。频率不高（只在句子边界、状态变化时来），
     * 不像 LH 那样每秒十几条，所以 post 是安全的。
     */
    @Override
    public void onState(MmdvmSession.State s, String detail) {
        final String label = DmrLink.describe(s)
                + (detail == null || detail.isEmpty() ? "" : " · " + detail);
        final boolean ok = s == MmdvmSession.State.CONNECTED;
        final boolean bad = s == MmdvmSession.State.ERROR;
        ui.post(() -> {
            /* ★ 状态行在这里定下来，之后 tick 只往后面追加读数，不再覆盖它 */
            txHeadline = label;
            txHeadlineOk = ok;

            /* 主页发射条那三处（状态字、连接/断开按钮、PTT 圆钮）统一跟着走 */
            renderIdentity(ok);
            if (bad && txIdState != null) {
                txIdState.setText("连接失败");
                txIdState.setTextColor(pal.bad);
            }
            if (settingsPage != null) {
                /* 先撤忙再改文案：setTxBusy(true) 之后只有状态回调能把它解掉，
                 * 而 MmdvmSession 有一条「已经在连接中，忽略重复请求」的静默 return
                 * （running==true 时），走到那条路上就没有任何回调。这里兜住。 */
                settingsPage.setTxBusy(false);
                settingsPage.setTxConnected(ok);
            }
            /* ERROR 弹一次 Toast：错误原因不能只躺在那一行小字里 */
            if (bad && detail != null && !detail.isEmpty()) {
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
            }
            updateTxDiag();
        });
    }

    @Override
    public void onLog(String line) {
        if (line == null || line.isEmpty()) return;
        ui.post(() -> {
            txLog.addLast(line);
            while (txLog.size() > 40) txLog.removeFirst();
        });
    }

    @Override
    public void onTx(boolean on, int dstId, String reason) {
        ui.post(() -> {
            if (on) {
                txStarted = true;
                /* 圆钮上不换文案（它一直是「组号 + PTT」），只换底色和缩放 ——
                 * 这正是 WebView 版 .ptt.live 的做法：按下时 scale(.94)，
                 * 按钮内容一个字都不变。 */
                pttLbl.setText("发射中");
            } else {
                /* 发射是异步启动的，失败时不会有 onTx(true) 先到。
                 * 这里必须复位 pttDown —— 否则用户松手之后再按就再也压不下去了，
                 * 而他看到的只是按钮闪了一下，完全不知道发生了什么。 */
                pttDown = false;
                pttLbl.setText("PTT");
                pttBtn.setScaleX(1f);
                pttBtn.setScaleY(1f);
                renderPttButton();
                if (reason != null && !"松开 PTT".equals(reason)) {
                    /* 「发射结束：声码器未就绪」读起来像说完了，实际一个字都没发出去 ——
                     * 按有没有真的开起来分两种说法。 */
                    String head = txStarted ? "发射中断：" : "没发出去：";
                    Toast.makeText(this, head + reason, Toast.LENGTH_LONG).show();
                }
                txStarted = false;
            }
            updateTxDiag();
        });
    }

    @Override
    public void onRx(boolean on, int srcId, int dstId, int slot, boolean priv) {
        if (!on) return;
        ui.post(() -> {
            nativeRxOn = true;
            curTg = dstId;
            curSrc = srcId;
            curCall = "";
            curName = priv ? "个呼" : "";
            curStart = System.currentTimeMillis();
            npVu.reset();
            /* 主卡片改成显示原生这一路 —— 用户不需要知道「谁在放声音」，
             * 只要看到「现在谁在说」。
             * 时隙号放进台站名那一行（TS1/TS2 对收听方是有用信息：
             * 两个时隙可能同时有人在讲）。 */
            markTgPillPrivate(priv);
            showNowPlaying(dstId, nameOf(dstId),
                    "ID " + srcId,
                    (priv ? "个呼" : "") + "  ·  TS" + slot);
            renderPttButton();
        });
    }

    @Override
    public void onRxLevel(float dbfs) {
        lastRxDb = dbfs;
    }

    @Override
    public void onTxLevel(int level, float dbfs) {
        ui.post(() -> {
            txLevel = level;
            txDb = dbfs;
        });
    }

    @Override
    public void onBusy(int srcId, int dstId, boolean txing) {
        ui.post(() -> {
            if (txing) {
                /* 自己在发，同时这个组有人在讲 —— 会互相盖住，提醒一句 */
                if (!busyWarned) {
                    busyWarned = true;
                    Toast.makeText(this, "TG " + dstId + " 有人正在讲话（ID " + srcId
                            + "），你现在发射会互相盖住", Toast.LENGTH_LONG).show();
                }
            } else {
                busyWarned = false;
            }
        });
    }

    @Override
    public void onBlocked(int srcId, int dstId) {
        ui.post(() -> Toast.makeText(this, "已屏蔽 ID " + srcId + "（TG " + dstId + "）",
                Toast.LENGTH_SHORT).show());
    }

    /** 原生接收这一句说完了 → 把主卡片收回「守听中」。 */
    private void clearNativeRx() {
        nativeRxOn = false;
        curStart = 0;
        curTg = 0;
        curSrc = 0;
        curCall = "";
        curName = "";
        renderNow();
        npTime.setText("");
    }

    /* ==================== tick ==================== */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickCount++;
            if (curStart > 0) {
                /* 秒数带一位小数（0.0s / 12.4s）—— WebView 版就是这么显示的。
                 * 只到整数秒的话，短通联（DMR 一句话常常 1~2 秒）看起来像没跳。 */
                double s = (System.currentTimeMillis() - curStart) / 1000.0;
                npTime.setText(String.format(Locale.US, "%.1fs", s));
            } else {
                npTime.setText("");
            }
            /* 接收电平：DMRD 包不带 RSSI，这是解出来 PCM 的电平（dBFS），
             * 不是空口信号强度 —— 播报条那个 tooltip 的说明就是这个意思。 */
            if (curStart > 0) {
                float db = lastRxDb;
                npDb.setText(db <= -120f ? "—" : String.format(Locale.US, "%.0f dB", db));
                npDb.setTextColor(db > -12f ? pal.tx : db > -30f ? pal.ok
                        : db > -60f ? pal.muted : pal.dim);
            }
            npVu.decay();
            /* 悬浮钮的红/绿、折叠摘要那一行都跟着收/发状态变，500 ms 刷一次足够 */
            if (tickCount % 2 == 0) renderPttButton();
            if (htCollapsed) renderHtSum();

            /* 1 秒：看板（只在可见时刷）+ 实时活动的过期清理 */
            if (tickCount % 2 == 0) {
                live.prune();
                if ("dash".equals(view) && dashPage != null) dashPage.refresh();
                renderLiveState();
                /* 原生那一句说完了就把主卡片收回去。判据在 DmrLink 里：
                 * 360 ms 没有新包 = 一句结束，和 DmrAudio 的 GAP_MS 一致。 */
                if (nativeRxOn && link != null && link.pollRxSentenceEnded()) clearNativeRx();
            }

            /* 3 秒：重算一次生效订阅（扫描会自动跟进新冒出来的活跃组） */
            if (tickCount % 6 == 0 && Prefs.scanOn(MainActivity.this)) syncSubs();

            /* 4 秒：音频自检读数 + 收发读数 */
            if (tickCount % 8 == 0) {
                updateAudioDiag();
                updateTxDiag();
            }

            ui.postDelayed(this, 500);
        }
    };

    private void updateAudioDiag() {
        if (settingsPage == null || audio == null) return;
        String gate = (link != null && link.isNativeRx()) ? "原生" : "HOSE";
        settingsPage.setAudioDiag(String.format(Locale.US,
                "环=%s · 闸=%s · 8kHz · 队列=%d · 丢帧=%d · 音量=%d%% · 增强=%d× · 窄带=%s",
                audio.isRunning() ? "on" : "off",
                gate,
                audio.getQueuedFrames(),
                audio.getDroppedFrames(),
                Prefs.volume(this),
                Prefs.gain(this),
                Prefs.presence(this) ? "开" : "关"));
    }

    /**
     * 收发那一行的读数。2 秒刷一次。
     *
     * <p>★ 结构是「状态行 + 读数 + 最近日志」，状态行来自 {@link #onState}
     * 并且**不会被这里覆盖**。之前这里直接按 {@code isConnected()} 重新拼一句话，
     * 结果是出错原因在界面上最多活 4 秒就被换成「未连接。填好主站…」——
     * 用户只记得「点了连接没反应」，报给我的也就只有这四个字。
     */
    private void updateTxDiag() {
        if (settingsPage == null || link == null) return;
        StringBuilder sb = new StringBuilder();

        /* ① 状态行 */
        sb.append(txHeadline);

        /* ② 读数 */
        if (link.isConnected()) {
            sb.append("\n登录 ").append(link.getLoginId());
            if (link.getEssid() > 0) sb.append("（ESSID ").append(link.getEssid()).append('）');
            sb.append(" · pong ").append(link.getPongCount())
              .append(" · 收 ").append(link.getRxPackets()).append(" 包");
            if (link.getRxPackets() > 0) {
                sb.append(" 丢 ").append(String.format(Locale.US, "%.1f%%", link.getRxLoss()));
            }
            if (link.isTransmitting()) {
                sb.append("\n发射中 · 输入 ").append(txLevel).append("% · ")
                  .append(String.format(Locale.US, "%.1f dBFS", txDb));
            }
        } else {
            sb.append("\n").append(Prefs.host(this)).append(':').append(Prefs.port(this))
              .append(" · ID ").append(Prefs.dmrId(this) == 0 ? "未填" : String.valueOf(Prefs.dmrId(this)))
              .append(" · 密码").append(Prefs.password(this).isEmpty() ? "未填" : "已填");
        }
        sb.append("\n声码器：").append(link.isVocoderReady() ? "就绪（可发射）"
                : "未就绪（只能听，不能发）");

        /* ③ 最近三条日志 —— 登录过程的关键信息（解析到哪些 IP、本机 UDP 端口、
         * 发了 RPTL、主站回了什么）全在日志里。不显示出来的话，
         * 用户能提供的唯一信息就是「连不上」。 */
        if (!txLog.isEmpty()) {
            sb.append("\n— 日志 —");
            int skip = Math.max(0, txLog.size() - 3);
            int i = 0;
            for (String line : txLog) {
                if (i++ < skip) continue;
                sb.append('\n').append(line.length() > 90 ? line.substring(0, 90) + "…" : line);
            }
        }

        settingsPage.setTxState(sb.toString(), txHeadlineOk);
        settingsPage.setTxConnected(link.isConnected());
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

        @Override public void onTxConnect() { txConnect(); }

        /**
         * 连接 / 断开主站。
         *
         * <p>从 {@link SettingsPage.Listener#onTxConnect()} 调，也从「地址写错了 →
         * 一键修正」的对话框回调里调 —— 所以必须是一个方法，不能inline在匿名类里。
         */
        private void txConnect() {
            if (link == null) return;
            if (link.isConnected()) {
                /* 断开要 join 发射线程（最多 600 ms），也放后台，别冻界面。
                 * 这里不置忙：置忙的文案是「正在登录…」，断开时说这句是错的，
                 * 而 600 ms 的空窗用户也感觉不到。 */
                new Thread(() -> {
                    link.disconnect();
                    ui.post(() -> {
                        Toast.makeText(MainActivity.this, "已断开主站",
                                Toast.LENGTH_SHORT).show();
                        updateTxDiag();
                    });
                }, "dmr-disconnect").start();
                return;
            }

            String host = Prefs.host(MainActivity.this);
            int id = Prefs.dmrId(MainActivity.this);
            if (host.trim().isEmpty()) {
                Toast.makeText(MainActivity.this, "先填主站地址（中国区填 4602.master.brandmeister.network）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (id < 1000) {
                Toast.makeText(MainActivity.this, "DMR ID 至少要 4 位（一般是 7 位）",
                        Toast.LENGTH_LONG).show();
                return;
            }

            /* ★ 地址格式本地先查一遍，错的就拦住，别浪费一次 DNS + 握手。
             *
             * 真机上出过 4602.ma4614ster.brandmeister.network（4614 是 DMR ID 的前四位，
             * 被插进了 master 中间）。这种地址的唯一后果是 DNS 查不到，而界面上只会显示
             * 「Load failed : Host not found」—— 一句话都不提「你地址写错了」。
             * 能猜出正确写法的时候，直接给一个「改好并连接」的按钮。 */
            HostCheck.Result hc = HostCheck.check(host);
            if (hc.bad()) {
                if (hc.suggestion != null) {
                    askFixHost(host, hc);
                } else {
                    Toast.makeText(MainActivity.this, hc.message, Toast.LENGTH_LONG).show();
                }
                return;
            }
            /* ★ 这里**不要**申请麦克风权限。
             *
             * 登录（UDP 62031 握手）和麦克风没有任何关系 —— 只有按 PTT 才需要。
             * 之前这里挡了一道 ensureMicPermission()，后果是：没给权限的用户
             * 点「连接主站」只会看到一个权限框，然后什么都不发生；
             * 他要是随手点了「拒绝」，就永远连不上，而且界面上没有任何一句话
             * 说明是因为权限。这是「连不上主站」最隐蔽的一个成因。
             *
             * 麦克风在按下 PTT 的那一刻才申请（见 startPtt）。 */

            if (settingsPage != null) settingsPage.setTxBusy(true);
            Toast.makeText(MainActivity.this, "正在登录 " + host, Toast.LENGTH_SHORT).show();

            /* ★ 必须放后台线程。
             * MmdvmSession.connect() 里第一件事是 InetAddress.getAllByName() ——
             * DNS 解析，域名拼错或者当前没网时会阻塞好几秒。
             * 在主线程调它，表现就是「点了连接，界面卡住不动，过一会儿弹一句超时」，
             * 用户会以为按钮没生效而反复点。
             *
             * ★ 也要 try/catch：这个线程里抛出去的异常只会打到 logcat，
             * 界面上什么都不会发生 —— 那正是最难查的一种「连不上」。 */
            new Thread(() -> {
                try {
                    link.connect(host, Prefs.port(MainActivity.this), id,
                            Prefs.essid(MainActivity.this), Prefs.password(MainActivity.this),
                            Prefs.callsign(MainActivity.this), "", "");
                } catch (Throwable t) {
                    final String msg = "登录过程抛异常：" + t;
                    ui.post(() -> {
                        txHeadline = msg;
                        txHeadlineOk = false;
                        updateTxDiag();
                        showLongText("登录失败", msg + "\n\n" + android.util.Log.getStackTraceString(t));
                    });
                }
                ui.post(MainActivity.this::updateTxDiag);
            }, "dmr-connect").start();

            /* 看门狗：握手超时是 24 s（HANDSHAKE_TIMEOUT_MS），加上断开后等待的 2 s，
             * 最坏 26 s 才等到 ERROR。留 4 s 余量，30 s 还没结果就把按钮放回来 ——
             * 宁可让用户再点一次，也不要让他对着一颗灰掉的「正在登录…」发呆。 */
            ui.postDelayed(() -> {
                if (settingsPage != null && !link.isConnected()) {
                    settingsPage.setTxBusy(false);
                }
            }, 30_000);
            updateTxDiag();
        }

        /**
         * 「地址看起来写错了」—— 给出正确写法，一键替换。
         *
         * <p>为什么要弹框而不是直接 Toast 一句原因：这条路径 90% 的情况都是
         * 打错了一两个字（多加数字、漏了 master、全角句号），而正确写法是能算出来的。
         * 让用户自己去输入框里找那个错字，比给他一个按钮难得多 ——
         * 尤其是 {@code 4602.ma4614ster} 这种，肉眼扫十遍也未必看得出。
         */
        private void askFixHost(String bad, HostCheck.Result hc) {
            String fix = hc.suggestion;
            String msg = hc.message
                    + "\n\n现在填的：\n" + bad
                    + "\n\n改成这个：\n" + fix;

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("主站地址看起来写错了")
                    .setMessage(msg)
                    .setPositiveButton("改好并连接", (d, w) -> {
                        applyHost(fix);
                        txConnect();
                    })
                    .setNeutralButton("只改地址", (d, w) -> {
                        applyHost(fix);
                        Toast.makeText(MainActivity.this,
                                "地址已改好，确认其它设置后再点「连接主站」",
                                Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        /** 写进 Prefs + 同步输入框，两处都要改否则下次进来还是旧的。 */
        private void applyHost(String host) {
            Prefs.setHost(MainActivity.this, host);
            if (settingsPage != null) settingsPage.setHostText(host);
        }

        @Override public void onTxSelfTest() {
            if (link == null) return;
            Toast.makeText(MainActivity.this, "正在自检…", Toast.LENGTH_SHORT).show();
            /* 自检要 dlopen 声码器插件、跑 FEC 链、跑 216 次单 bit 往返，
             * 放主线程会有明显卡顿。放后台，跑完再弹窗。 */
            new Thread(() -> {
                String report;
                try {
                    report = "=== 声码器 ===\n" + link.loadVocoder()
                            + "\n=== FEC 链（发射帧组装）===\n" + link.fecSelfTest()
                            + "\n=== 接收链（交织往返 + 解码）===\n" + link.rxSelfTest();
                } catch (Throwable t) {
                    report = "自检抛异常：" + t;
                }
                final String body = report + "\n=== 最近日志 ===\n"
                        + (txLog.isEmpty() ? "（无）" : String.join("\n", txLog));
                ui.post(() -> {
                    updateTxDiag();
                    showLongText("收发自检", body);
                });
            }, "dmr-selftest").start();
        }

        @Override public void onTxDiagnose() {
            if (link == null) return;
            /* 诊断里含 DNS 解析（可能阻塞几秒）和 dlopen，必须放后台 */
            new Thread(() -> {
                String body;
                try {
                    body = link.diagnose(Prefs.host(MainActivity.this),
                            Prefs.port(MainActivity.this),
                            Prefs.dmrId(MainActivity.this),
                            Prefs.essid(MainActivity.this));
                } catch (Throwable t) {
                    body = "诊断抛异常：" + t;
                }
                final String text = body;
                ui.post(() -> showLongText("连接诊断", text));
            }, "dmr-diag").start();
        }

        @Override public void onTxOptionChanged() {
            if (link == null) return;
            link.setRxOn(Prefs.rxOn(MainActivity.this));
            link.setRxOnlyWatchTg(Prefs.onlyWatchTg(MainActivity.this));
            link.setMicGainDb(Prefs.micGainDb(MainActivity.this));
            link.setTxParams(Prefs.slot(MainActivity.this), Prefs.colorCode(MainActivity.this));
            updateTxDiag();
        }
    };

    /**
     * 弹一屏可滚动的长文本。
     *
     * <p>自检报告动辄四五十行，{@code AlertDialog.setMessage} 会把它挤成一坨；
     * 这里套一个 ScrollView + 等宽字体，用户能直接长按选中复制发给我。
     */
    private void showLongText(String title, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(pal.text);
        int p = Ui.dpI(this, 12);
        tv.setPadding(p, p, p, p);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    /* ==================== 保活 / 常亮 ==================== */

    /**
     * 同步「保持屏幕常亮」与保活服务。
     *
     * <h3>两件事，别混在一起</h3>
     * <ul>
     *   <li><b>屏幕常亮</b>是给用户看的：守听时不想屏幕熄掉。用
     *       {@code FLAG_KEEP_SCREEN_ON} —— 系统自动跟随窗口生命周期，
     *       不需要权限，也不会在忘记释放时耗一整晚电。</li>
     *   <li><b>链路保活</b>是给进程用的：{@link KeepAliveService} 持
     *       Partial WakeLock + WifiLock + 前台通知。这件事<b>和设置无关</b> ——
     *       只要在守听就该保活，否则熄屏几分钟后心跳就掉了。</li>
     * </ul>
     * 之前把两者合成一个 30 分钟超时的 PARTIAL_WAKE_LOCK，结果是：
     * 熄屏能听（CPU 没睡）但切后台会断（进程优先级没提），
     * 而且那个「保持屏幕常亮」的开关其实一点也没让屏幕亮着。
     */
    private void syncAwake() {
        if (Prefs.awake(this)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        syncKeepAlive();
    }

    /** 起 / 停保活服务，并刷新通知文案。 */
    private void syncKeepAlive() {
        String text = keepAliveText();
        if (KeepAliveService.isRunning()) {
            KeepAliveService.update(this, text);
        } else {
            KeepAliveService.start(this, text);
        }
    }

    /** 通知上那一行：尽量说清「现在在干什么」。 */
    private String keepAliveText() {
        if (muted) return "已静音 · 仍在守听";
        if (curTg > 0) {
            String name = nameOf(curTg);
            StringBuilder sb = new StringBuilder();
            sb.append(name == null ? "TG " + curTg : name + " · " + curTg);
            if (!curCall.isEmpty()) sb.append("  ").append(curCall);
            else if (curSrc > 0) sb.append("  ID ").append(curSrc);
            return sb.toString();
        }
        int n = sentSubs.length;
        return n > 0 ? "守听中 · " + n + " 个话组" : "守听中";
    }

    /**
     * Android 13+ 要运行时申请通知权限。
     *
     * <p>不给也不会崩 —— 服务照样跑、音频照样出，只是那条常驻通知不显示。
     * 但通知是这个 App 唯一的「我还在后台工作」的提示，所以值得问一次。
     */
    private void ensureNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } catch (Throwable ignored) { }
    }

    /* ==================== 列表 ==================== */

    /**
     * 话组列表项。
     *
     * <p>★ 带一个 {@code data} 参数而不是固定读 {@link #shown} ——
     * 「我的收听」和「话组库」是两个独立列表，共用一个 Adapter 类没问题，
     * 但各自看自己的数据源。上一版只读 field，加第二栏时就得整个复制一遍。
     */
    private final class TgAdapter extends BaseAdapter {
        private final List<Talkgroups.Tg> data;

        TgAdapter(List<Talkgroups.Tg> data) { this.data = data; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return data.get(i).id; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tg, parent, false);
            }
            Talkgroups.Tg t = data.get(pos);
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
