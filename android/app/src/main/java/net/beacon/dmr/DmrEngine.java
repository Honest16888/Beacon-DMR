package net.beacon.dmr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import net.beacon.dmr.vocoder.Vocoder;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 发射功能的门面：把声码器、MMDVM 会话、音频链路串起来，对界面只暴露几个动作。
 *
 * <p>事件统一以 JSON 形式回调给 WebView，界面不需要了解任何协议细节。
 * 所有回调都切回主线程，因为 WebView 的 evaluateJavascript 只能在主线程调。
 *
 * <p>三条独立链路的分工：
 * <pre>
 *   HOSE (wss)     —— 收听，WebView 自己管
 *   MMDVM (udp)    —— 发射 + 判断话组忙闲，本类管
 *   声码器 (so)    —— PCM ↔ AMBE，本类管
 * </pre>
 * 发射时界面会收到 tx=on 事件，据此静音 HOSE 播放，形成半双工闸门。
 */
public final class DmrEngine implements MmdvmSession.Listener, DmrTransmitter.Listener {

    /** 与界面约定的回调：一个 JSON 字符串 */
    public interface Callback {
        void onEvent(String json);
    }

    private final Context ctx;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final MmdvmSession session;
    private final DmrTransmitter transmitter;

    /** 接收链第一段：DMRD 载荷 → 解交织 → AMBE → PCM */
    private final DmrRx rx = new DmrRx();
    /** 接收链第二段：抖动缓冲 + AudioTrack 播放 */
    private final DmrAudio audio;

    private volatile boolean vocoderReady;
    private volatile String vocoderReport = "";

    /** 当前发射目标，用于判断收到的 DMRD 是不是本话组的 */
    private volatile int txDstId = 0;
    private volatile int myId = 0;

    /* ---- 接收侧状态（对讲页要用） ---- */
    /** 是否解码并播放主站推来的语音 */
    private volatile boolean rxOn = true;
    /** 当前/最近一次说话的来源与目标。onPlayStart 时上报给界面 */
    private volatile int curSrcId = 0;
    /** 最近一次接收的音频电平（dBFS）。−120 表示还没收到过 */
    private volatile float lastRxDb = -120f;
    private volatile int curDstId = 0;
    private volatile int curSlot = 1;
    private volatile boolean curPrivate = false;

    /* ---- 接收过滤：黑名单 ---- */
    /**
     * 被屏蔽的源 ID 集合。空集合 = 不过滤。
     *
     * <p>用不可变集合整体替换（而不是原地增删）—— 接收线程在读它的同时，
     * 界面线程可能正在改它，整体替换天然没有并发问题。
     */
    private volatile java.util.Set<Integer> rxBlock = java.util.Collections.emptySet();
    /** 被黑名单挡掉的包数，界面据此显示「已屏蔽 N 包」 */
    private volatile long rxBlockedCount = 0;
    /** 上一次报给界面的被屏蔽来源，用来做「换人了才提示一次」的去重 */
    private volatile int rxBlockedLastSrc = 0;

    /* ---- 接收过滤：按话组静音 ---- */
    /**
     * 被静音的话组 ID 集合。
     *
     * <p>和「屏蔽呼号」是两个维度：屏蔽是按<b>谁在说</b>，静音是按<b>在哪个组说</b>。
     * 典型用法是订阅了多个话组，其中某个组一直有人闲聊，但你还想留在这个组上。
     * 同样用不可变集合整体替换，理由见 {@link #rxBlock}。
     */
    private volatile java.util.Set<Integer> rxMuteTg = java.util.Collections.emptySet();
    /** 被静音话组挡掉的包数 */
    private volatile long rxMutedCount = 0;

    /* ---- 接收过滤：单流锁定 ---- */
    /**
     * 只收当前话组。主站会同时推多路（多个订阅话组、组呼 + 个呼），
     * 全部塞进同一个抖动缓冲就是一锅粥。开着它只放 dstId == txDstId 的那一路。
     */
    private volatile boolean rxOnlyWatchTg = false;
    /** 当前锁定在播的那一路：来源 + 目标 + 时隙三元组 */
    private volatile int lockSrc = 0;
    private volatile int lockDst = 0;
    private volatile int lockSlot = 0;
    /** 上一次收到锁定流的时间。静默超过 STREAM_HOLD_MS 就允许换人 */
    private volatile long lockAtMs = 0;
    /**
     * 一条流静默多久算「这句说完了」，可以让别的流接手。
     *
     * <p>取 400 ms：比抖动缓冲的 GAP_MS（360 ms）略长一点。太短会在同一句话的
     * 换气间隙被别的流抢走，听感是「半句就断了」；太长则别人插话要等半天。
     */
    private static final int STREAM_HOLD_MS = 400;

    public DmrEngine(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
        this.session = new MmdvmSession(this);
        this.transmitter = new DmrTransmitter(this);
        this.audio = new DmrAudio(new DmrAudio.Listener() {
            @Override
            public void onPlayStart() {
                /* 一句的起点 —— 界面在这里把「正在接收」点亮。
                 * 逐包上报太吵（每秒 17 条），所以只在句子边界报。 */
                emit("rx", "on", true, "srcId", curSrcId, "dstId", curDstId,
                        "slot", curSlot, "priv", curPrivate);
            }

            @Override
            public void onPlayStop() {
                emit("rx", "on", false);
            }

            @Override
            public void onLevel(float level, float dbfs) {
                lastRxDb = dbfs;
                /* 保留一位小数：dB 读数的意义在于「能比较」，
                 * 整数会让 −18.4 和 −18.9 看起来一样。 */
                emit("rxlevel", "v", level, "db", Math.round(dbfs * 10f) / 10.0);
            }
        });
    }

    /* ================================================================ */
    /* 声码器                                                           */
    /* ================================================================ */

    /** libdsvocoder.so 的绝对路径。文件不存在时返回空串，交给 native 侧走短名回退 */
    private String vocoderLibPath() {
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        if (dir == null) return "";
        File f = new File(dir, "libdsvocoder.so");
        return f.exists() ? f.getAbsolutePath() : "";
    }

    public boolean isVocoderReady() {
        return vocoderReady;
    }

    /**
     * 加载声码器并做一次自检。
     *
     * @return 自检报告（多行文本），可直接显示给用户
     */
    public String loadVocoder() {
        String path = vocoderLibPath();

        /* ⚠ 顺序很重要：nativeProbe 用的是**独立的临时实例**，不会填充
         * 生产用的 g_enc / g_dec。所以只跑自检的话 nativeIsReady() 永远是
         * false —— 表现就是"自检全过但声码器未加载"。必须先 nativeInit。 */
        boolean inited = false;
        try {
            inited = Vocoder.nativeInit(path);
        } catch (Throwable t) {
            vocoderReport = "nativeInit 抛异常：" + t;
            vocoderReady = false;
            emit("vocoder", "ready", false, "report", vocoderReport);
            return vocoderReport;
        }

        String report = Vocoder.nativeProbe(path);
        vocoderReady = inited && Vocoder.nativeIsReady();

        if (!inited) {
            report += "\n\n【注意】nativeInit 失败：编解码实例没能创建，无法发射。\n";
        } else if (!vocoderReady) {
            report += "\n\n【注意】实例创建了但 nativeIsReady() 为 false，状态异常。\n";
        }
        vocoderReport = report;
        emit("vocoder", "ready", vocoderReady, "report", vocoderReport);
        return vocoderReport;
    }

    /** FEC 链自检，不需要声码器，用来单独验证帧组装 */
    public String selfTestFec() {
        return DmrTx.nativeSelfTest();
    }

    /* ================================================================ */
    /* 连接                                                             */
    /* ================================================================ */

    /**
     * 登录主站并准备发射。
     *
     * @param essid 热点别名 ID（ESSID），1~99；0 表示不使用别名，
     *              直接用裸 DMR ID 登录。见 {@link MmdvmSession} 的说明 ——
     *              同一个 DMR ID 已经有一台热点在线时，必须靠它才能并存。
     */
    public void connect(String host, int port, int dmrId, int essid, String password,
                        String callsign, String location, String description) {
        myId = dmrId;

        if (!vocoderReady) {
            loadVocoder();
        }
        if (!vocoderReady) {
            emitError("声码器未就绪，无法发射。请先查看自检报告。");
            return;
        }

        /* 这里以前传的是 "BeaconDMR"，被 sanitizeSoftwareId() 判为非法日期后
         * 回落到默认值 —— 功能没问题，但每次连接都会多打一条警告日志，
         * 真机上看起来像是出了错。直接传默认值，日志干净。
         * 注意：softwareId 是 BrandMeister 配置阶段的校验点之一，必须是
         * 合法日期 YYYYMMDD，别改成品牌名。 */
        session.setStationInfo(callsign, location, description, "",
                MmdvmSession.DEFAULT_SWID, 0);
        session.configure(host, port, dmrId, essid, password);
        audio.start();
        session.connect();
    }

    public void disconnect() {
        transmitter.stop("用户断开");
        session.disconnect();
        audio.flush();
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    /** 实际用于登录的 ID（含 ESSID 别名）。未配置时等于本机 DMR ID */
    public int getLoginId() {
        return session.getLoginId();
    }

    /** 热点别名 ID（ESSID），0 表示未使用别名 */
    public int getEssid() {
        return session.getEssid();
    }

    /** 已建立会话之后收到的 MSTNAK 条数（都被有意忽略了，仅作排障依据） */
    public int getLateNakCount() {
        return session.getLateNakCount();
    }

    /** 最近一次接收的音频电平（dBFS）。
     *
     *  <p>注意这是<b>解码后音频</b>的电平，不是空口信号强度 ——
     *  DMRD 包里没有 RSSI 字段，主站也不会下发，这个链路上不存在
     *  「信号几格」这种量。−40 以下基本是底噪，−20 ~ −12 是正常说话。 */
    public float getRxDb() {
        return lastRxDb;
    }

    /** 累计收到 MSTPONG 的次数；0 表示主站不回 pong（有些主站确实不回） */
    public int getPongCount() {
        return session.getPongCount();
    }

    /* ================================================================ */
    /* 发射                                                             */
    /* ================================================================ */

    public boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 按下 / 松开 PTT。
     *
     * @return 失败原因，成功返回 null
     */
    public String ptt(boolean down, int tgid, int slot, int colorCode, boolean privateCall) {
        if (!down) {
            transmitter.stop("松开 PTT");
            return null;
        }

        if (!hasAudioPermission()) return "缺少录音权限";
        if (!session.isConnected()) return "尚未连接主站";
        if (!vocoderReady) return "声码器未就绪";

        txDstId = tgid;

        /* repeaterId 传登录 ID（含 ESSID 别名）—— 主站据此把通联归到本机热点；
         * srcId 仍是裸 DMR ID，呼号归属不变。 */
        String err = transmitter.start(session, myId, session.getLoginId(), tgid, slot, colorCode,
                privateCall ? DmrTx.FLCO_USER_USER : DmrTx.FLCO_GROUP);
        return err;
    }

    public boolean isTransmitting() {
        return transmitter.isTransmitting();
    }

    /** 用户麦克风增益，单位 dB，范围 −12 ~ +12，0 为不改动。
     *
     *  <p>作用点在自动电平（AGC）**之后** —— 放在它前面会被 AGC
     *  原样抵消，滑块拖到底也听不出差别。 */
    public void setMicGainDb(float db) {
        transmitter.setMicGainDb(db);
    }

    public float getMicGainDb() {
        return transmitter.getMicGainDb();
    }

    /* ================================================================ */
    /* 接收                                                             */
    /* ================================================================ */

    /** 开关接收播放（对讲页的「接收」开关）。关掉就只发不收，省电 */
    public void setRxOn(boolean on) {
        rxOn = on;
        audio.setEnabled(on);
    }

    public boolean isRxOn() {
        return rxOn;
    }

    /** 接收增益，线性 0~2（1 为原样）。>1 能让弱信号听得见，代价是可能削顶 */
    public void setRxVolume(float g) {
        audio.setGain(g);
    }

    public float getRxVolume() {
        return audio.getGain();
    }

    /**
     * 抖动缓冲厚度（毫秒）。网络不稳时加长，代价是延迟变大。
     * 参考设备上那个「接收缓冲」调的就是这个值。
     */
    public void setRxJitter(int ms) {
        audio.setJitterMs(ms);
    }

    public int getRxJitter() {
        return audio.getJitterMs();
    }

    /* ---------------- 接收丢包统计（排障用） ---------------- */

    /** 一句话统计，例如「收 1200 包 · 丢 7 包（0.6%）」 */
    public String getRxStats() {
        return session.getRxStats();
    }

    public long getRxPackets() {
        return session.getRxPacketCount();
    }

    public double getRxLoss() {
        return session.getRxLossPercent();
    }

    /* ---------------- 接收过滤 ---------------- */

    /**
     * 设置屏蔽名单（DMR ID）。传空数组等于取消屏蔽。
     *
     * <p>屏蔽在 {@link #onDmrd} 最前面生效：被挡的包不解码、不进抖动缓冲。
     */
    public void setRxBlock(int[] ids) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        if (ids != null) {
            for (int id : ids) {
                if (id > 0) s.add(id);
            }
        }
        java.util.Set<Integer> next = s.isEmpty()
                ? java.util.Collections.<Integer>emptySet()
                : java.util.Collections.unmodifiableSet(s);
        /* 内容没变就别动、也别打日志 —— 页面启动时会无条件推一次名单，
         * 每次进 App 都刷一条「接收屏蔽名单：空」纯属噪音。 */
        if (next.equals(rxBlock)) return;
        rxBlock = next;
        /* 名单变了，去重标记也清掉 —— 否则把某人从黑名单移除再加回来，
         * 界面不会重新提示 */
        rxBlockedLastSrc = 0;
        emit("log", "line", "接收屏蔽名单：" + (s.isEmpty() ? "空（不过滤）" : s.toString()));
    }

    public int getRxBlockSize() {
        return rxBlock.size();
    }

    public long getRxBlockedCount() {
        return rxBlockedCount;
    }

    /**
     * 设置静音的话组。传空数组等于取消全部静音。
     *
     * <p>被静音的组在 {@link #onDmrd} 里直接丢掉，不解码、不进缓冲。
     */
    public void setRxMuteTg(int[] ids) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        if (ids != null) {
            for (int id : ids) {
                if (id > 0) s.add(id);
            }
        }
        java.util.Set<Integer> next = s.isEmpty()
                ? java.util.Collections.<Integer>emptySet()
                : java.util.Collections.unmodifiableSet(s);
        if (next.equals(rxMuteTg)) return;
        rxMuteTg = next;
        emit("log", "line", "静音话组：" + (s.isEmpty() ? "无" : s.toString()));
    }

    public int getRxMuteTgSize() {
        return rxMuteTg.size();
    }

    public long getRxMutedCount() {
        return rxMutedCount;
    }

    /** 只收当前话组（多路同时推时不混音） */
    public void setRxOnlyWatchTg(boolean on) {
        if (this.rxOnlyWatchTg == on) return;   // 同上：没变就别刷日志
        this.rxOnlyWatchTg = on;
        if (on) {
            /* 打开时把锁清掉，让下一路重新抢 —— 否则会卡在上一次锁定的流上 */
            lockSrc = 0;
            lockDst = 0;
            lockSlot = 0;
            lockAtMs = 0;
        }
        emit("log", "line", on ? "接收：只收当前话组（其余话组丢弃）" : "接收：接受所有话组");
    }

    public boolean isRxOnlyWatchTg() {
        return rxOnlyWatchTg;
    }

    /**
     * 界面当前关注的话组。0 表示还没选，此时「只收当前话组」不生效。
     *
     * <p>和 {@link #ptt} 里设置的 txDstId 是同一个字段：界面选好话组就同步过来，
     * 不用等第一次 PTT 才开始过滤 —— 否则「刚连上、还没发过话」的那段时间
     * 别的静态话组照样会挤进来。
     */
    public void setWatchTg(int tgid) {
        this.txDstId = tgid > 0 ? tgid : 0;
    }

    public boolean isRxPlaying() {
        return audio.isPlaying();
    }

    /** 接收链路自检（不需要网络） */
    public String rxSelfTest() {
        return DmrRx.selfTest();
    }

    /* ================================================================ */
    /* 事件：来自 MmdvmSession                                          */
    /* ================================================================ */

    @Override
    public void onState(MmdvmSession.State state, String detail) {
        emit("state", "value", state.name(), "detail", detail);
    }

    @Override
    public void onPong(int count) {
        emit("pong", "count", count);
    }

    @Override
    public void onDmrd(int srcId, int dstId, int slot, boolean voice, byte[] payload, int flags) {
        /* 只有语音帧才带音频；数据帧（LC 头 / 终止帧）没有载荷 */
        if (!voice) return;
        /* 主站会把我们发出去的话组流量也推回来，自己的声音不用再听一遍 */
        if (srcId == myId) return;

        /* ① 黑名单。放在最前面：连解码都不做 —— 既省 CPU，也不会让被屏蔽的人
         * 占用抖动缓冲（否则「屏蔽了但还是卡」）。 */
        java.util.Set<Integer> block = rxBlock;
        if (!block.isEmpty() && block.contains(srcId)) {
            rxBlockedCount++;
            /* 同一个人连着说话只提示一次，否则每秒 17 条刷屏 */
            if (srcId != rxBlockedLastSrc) {
                rxBlockedLastSrc = srcId;
                emit("blocked", "srcId", srcId, "dstId", dstId);
            }
            return;
        }

        /* ② 静音的话组。放在「只收当前话组」之前判 —— 两者是独立开关，
         * 静音优先级更高（用户明确说了不想听这个组）。
         * 个呼（目标就是我自己）不受话组静音影响：那不是组呼。 */
        boolean forMe = (myId > 0 && dstId == myId);
        java.util.Set<Integer> mute = rxMuteTg;
        if (!forMe && !mute.isEmpty() && mute.contains(dstId)) {
            rxMutedCount++;
            return;
        }

        /* ③ 只收当前话组。txDstId 为 0（还没选话组）时这条不生效，
         * 否则一开就什么都收不到了。
         *
         * ⚠ 个呼必须放行：目标 ID 就是我自己，说明有人专门呼叫我 ——
         * 这种不能因为「只收当前话组」就听不见，否则功能一开就漏掉点名。 */
        if (rxOnlyWatchTg && !forMe) {
            int watch = txDstId;
            if (watch > 0 && dstId != watch) return;
        }

        /* ④ 单流锁定。主站可能同时推多路，混进同一个抖动缓冲就是一锅粥 ——
         * 听起来像几个人同时说话，谁也听不清。
         * 规则：当前流还在响就继续它；静默超过 STREAM_HOLD_MS 才换人；
         * 但「自己关注的话组」有抢断权，别人占着麦也能立刻切过去。 */
        long now = System.currentTimeMillis();
        boolean same = (srcId == lockSrc && dstId == lockDst && slot == lockSlot);
        if (!same) {
            boolean free = (lockSrc == 0) || (now - lockAtMs > STREAM_HOLD_MS);
            /* 抢断权：自己关注的话组，或者点名呼我的个呼 */
            boolean preempt = forMe || (txDstId > 0 && dstId == txDstId);
            if (!free && !preempt) return;
            if (preempt && !free) {
                /* 抢断：旧流的残余还在缓冲里，不清掉会和新流叠在一起 */
                audio.flush();
            }
            lockSrc = srcId;
            lockDst = dstId;
            lockSlot = slot;
        }
        lockAtMs = now;

        if (rxOn && payload != null && vocoderReady && !transmitter.isTransmitting()) {
            /* 每个包一份新缓冲：解码完直接交给播放队列，省掉一次拷贝 */
            short[] pcm = new short[DmrRx.PCM_PER_BUNDLE];
            int n = rx.decode(payload, 0, pcm);
            if (n > 0) {
                curSrcId = srcId;
                curDstId = dstId;
                curSlot = slot;
                curPrivate = (flags & DmrRx.FLAG_PRIVATE) != 0;
                audio.feed(pcm, n);
            }
        }

        /* 只有落在我当前关注的话组上，才算「话组忙」—— 界面据此提示别抢话 */
        int watch = txDstId;
        if (watch > 0 && dstId == watch) {
            emit("busy", "srcId", srcId, "dstId", dstId, "slot", slot,
                    "txing", transmitter.isTransmitting());
        }
    }

    @Override
    public void onLog(String line) {
        emit("log", "line", line);
    }

    /* ================================================================ */
    /* 事件：来自 DmrTransmitter                                        */
    /* ================================================================ */

    @Override
    public void onTxStart(int dstId) {
        /* 半双工闸门的第一半：发射时把接收缓冲掐掉。
         * 否则会听见自己绕网络回来的声音（主站会把本话组的流量推回来），
         * 而这时还没松开 PTT，等于自激。 */
        audio.flush();
        emit("tx", "on", true, "dstId", dstId);
    }

    @Override
    public void onTxStop(String reason) {
        emit("tx", "on", false, "reason", reason);
    }

    @Override
    public void onLevel(int level, float dbfs) {
        emit("level", "v", level, "db", Math.round(dbfs * 10f) / 10.0);
    }

    /* ================================================================ */

    private void emitError(String msg) {
        emit("error", "msg", msg);
    }

    /** 组装事件 JSON 并切回主线程回调。kv 为 key/value 交替 */
    private void emit(String type, Object... kv) {
        final String json;
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            json = o.toString();
        } catch (Exception e) {
            return;
        }

        ui.post(() -> {
            try {
                cb.onEvent(json);
            } catch (Exception ignored) {
            }
        });
    }

    /* 供界面显示：把状态翻译成中文 */
    public static String describe(MmdvmSession.State s) {
        switch (s) {
            case IDLE:       return "未连接";
            case CONNECTING: return "正在登录";
            case AUTH:       return "正在验证密码";
            case CONF:       return "正在下发配置";
            case CONNECTED:  return "已连接";
            case ERROR:      return "出错";
            default:         return String.valueOf(s);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X", x));
        return s.toString();
    }
}
