package net.beacon.dmr.mmdvm;

import android.content.Context;

import net.beacon.dmr.audio.AudioOut;
import net.beacon.dmr.mmdvm.vocoder.Vocoder;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * MMDVM 收发的接线层：把 {@link MmdvmSession}、{@link DmrTransmitter}、
 * {@link DmrRx} 和<b>已有的那条</b> {@link AudioOut} 串起来。
 *
 * <h3>为什么没有搬 DmrEngine 和 DmrAudio</h3>
 * WebView 版里这两条收听路径是<b>各自一条 AudioTrack</b>：
 * <pre>
 *   HOSE   → WebAudio (AudioContext 48 kHz + AudioWorklet)
 *   MMDVM  → DmrAudio (AudioTrack 8 kHz)          ← 独立的一条
 * </pre>
 * v2.4.x～v2.5.x 修的「两条路叠着放」「噼啪」「切换瞬间的咔哒」全部来自这个分裂 ——
 * 归属必须在句子中间抢、增益必须在两条链路之间来回压，压早了丢字、压晚了重叠。
 *
 * <p>纯 Java 版把这件事从架构上消掉了：<b>AMBE 解出来的 PCM 和 HOSE 解出来的
 * PCM 都进同一个 {@link AudioOut}</b>。于是：
 * <ul>
 *   <li>队列只有一个 —— 两条路不可能同时出声，物理上做不到</li>
 *   <li>音量 / 增强 / 高通 / 软削波只有一份 —— 不存在「哪条链的增益没跟上」</li>
 *   <li>半双工闸门只需要一个动作：发射时 {@code flush()}</li>
 * </ul>
 * 所以这里只保留 DmrEngine 里真正有信息量的部分：<b>过滤与去重策略</b>
 * （屏蔽 / 静音 / 单流锁定 / 话组忙去重），把它们从「拼 JSON 抛给 WebView」
 * 改成类型化回调。
 *
 * <h3>与 HOSE 的分工</h3>
 * {@link #isNativeRx()} 为真时，{@code MainActivity.onAudio()} 直接丢掉 HOSE 的
 * μ-law 帧 —— 原生链路正在出声，一个队列里不能插进两路声音。
 * 判据是「最近 500 ms 收到过语音帧」，<b>不是</b>「已连接」：
 * 手机当热点时主站并不一定往这边推音频，按「已连接」掐掉 HOSE 会变成
 * 「连上主站反而什么都听不见」。详见 {@link #isNativeRx()} 的说明。
 */
public final class DmrLink implements MmdvmSession.Listener, DmrTransmitter.Listener {

    /** 一条流静默多久算「这句说完了」，可以让别的流接手。 */
    private static final int STREAM_HOLD_MS = 400;
    /** 「话组忙」去重的兜底间隔。 */
    private static final int BUSY_REPEAT_MS = 3000;
    /** 一句话播完（这么久没有新包）就报 onRx(false)。 */
    private static final int RX_IDLE_MS = 360;
    /**
     * 原生让 HOSE 让位的窗口。
     *
     * <p>比 {@link #RX_IDLE_MS} 稍长一点：句子之间的换气间隙里不要来回切，
     * 否则 HOSE 会在每个停顿处挤进来一句。
     */
    private static final int RX_TAKEOVER_MS = 500;

    /**
     * 原生链路接管时的抖动缓冲厚度。
     *
     * <p>主站每 60 ms 推一个包，每个包解 480 采样 —— 我们按 160 采样
     * （20 ms）切给 {@link AudioOut}，所以 12 帧 = 240 ms。
     * 和 WebView 版 {@code DmrAudio} 的默认值一致：那个数是实测调出来的，
     * 比 HOSE 的 80 ms 厚，因为 BrandMeister 的抖动确实更大。
     */
    private static final int NATIVE_PRE_ROLL = 12;
    /** HOSE 自己的垫底帧数（4 × 20 ms = 80 ms）。 */
    private static final int HOSE_PRE_ROLL = 4;

    public interface Listener {
        void onState(MmdvmSession.State s, String detail);

        void onLog(String line);

        /** 发射开关。{@code dstId} 在关闭时无意义，{@code reason} 在开启时为 null */
        void onTx(boolean on, int dstId, String reason);

        /** 一句接收开始。只在句子边界报，不逐包报 */
        void onRx(boolean on, int srcId, int dstId, int slot, boolean priv);

        /** 接收音频电平（dBFS），已节流 */
        void onRxLevel(float dbfs);

        /** 发射输入电平，0~100 + dBFS */
        void onTxLevel(int level, float dbfs);

        /** 当前关注的话组有人在讲 */
        void onBusy(int srcId, int dstId, boolean txing);

        /** 某个来源被屏蔽名单挡掉了 */
        void onBlocked(int srcId, int dstId);
    }

    private final Context ctx;
    private final AudioOut audio;
    private final Listener cb;

    private final MmdvmSession session;
    private final DmrTransmitter transmitter;
    private final DmrRx rx = new DmrRx();

    private volatile boolean vocoderReady;
    private volatile String vocoderReport = "";

    private volatile int myId = 0;
    /** 当前关注 / 发射的目标话组。0 = 还没选 */
    private volatile int txDstId = 0;

    /* ---- 接收侧 ---- */
    private volatile boolean rxOn = true;
    private volatile int curSrcId, curDstId, curSlot;
    private volatile boolean curPrivate;
    private volatile float lastRxDb = -120f;
    private volatile long lastRxAtMs = 0;
    private volatile boolean rxSentenceOn = false;

    /* ---- 过滤：黑名单 ---- */
    private volatile Set<Integer> rxBlock = Collections.emptySet();
    private volatile long rxBlockedCount = 0;
    private volatile int rxBlockedLastSrc = 0;

    /* ---- 过滤：按话组静音 ---- */
    private volatile Set<Integer> rxMuteTg = Collections.emptySet();
    private volatile long rxMutedCount = 0;

    /* ---- 过滤：单流锁定 ---- */
    private volatile boolean rxOnlyWatchTg = false;
    private volatile int lockSrc, lockDst, lockSlot;
    private volatile long lockAtMs = 0;

    /* ---- 话组忙去重 ---- */
    private int busySrc, busyDst;
    private boolean busyTxing;
    private long busyAtMs = 0;

    /** 电平节流（收包线程上调用） */
    private long levelAtMs = 0;

    public DmrLink(Context ctx, AudioOut audio, Listener cb) {
        this.ctx = ctx.getApplicationContext();
        this.audio = audio;
        this.cb = cb;
        this.session = new MmdvmSession(this);
        this.transmitter = new DmrTransmitter(this);
    }

    /* ================================================================ */
    /* 声码器                                                           */
    /* ================================================================ */

    /**
     * 插件 .so 的绝对路径。文件不存在时返回空串，让 native 侧走短名回退
     * （从 APK 的 lib 目录直接 dlopen）。
     */
    private String vocoderLibPath() {
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        if (dir == null) return "";
        File f = new File(dir, "libdsvocoder.so");
        return f.exists() ? f.getAbsolutePath() : "";
    }

    public boolean isVocoderReady() {
        return vocoderReady;
    }

    public String getVocoderReport() {
        return vocoderReport;
    }

    /**
     * 加载声码器并做一次自检。
     *
     * <p>⚠ 顺序不能反：{@code nativeProbe} 用的是**独立的临时实例**，不会填充
     * 生产用的编解码器。所以只跑自检的话 {@code nativeIsReady()} 永远是 false ——
     * 表现是「自检全过但声码器未加载」。必须先 {@code nativeInit}。
     */
    public String loadVocoder() {
        String path = vocoderLibPath();
        boolean inited;
        try {
            inited = Vocoder.nativeInit(path);
        } catch (Throwable t) {
            vocoderReady = false;
            vocoderReport = "nativeInit 抛异常：" + t;
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
        return vocoderReport;
    }

    /** FEC 链自检，不需要声码器。 */
    public String fecSelfTest() {
        return DmrTx.nativeSelfTest();
    }

    /** 接收链路自检（交织往返 + 解码出声音），不需要网络。 */
    public String rxSelfTest() {
        return DmrRx.selfTest();
    }

    /* ================================================================ */
    /* 连接                                                             */
    /* ================================================================ */

    public void connect(String host, int port, int dmrId, int essid, String password,
                        String callsign, String location, String description) {
        myId = dmrId;

        if (!vocoderReady) loadVocoder();
        if (!vocoderReady) {
            log("声码器未就绪，无法发射。请先看自检报告。");
            return;
        }

        /* softwareId 必须是合法日期 YYYYMMDD —— 那是 BrandMeister 配置阶段的
         * 校验点之一，传品牌名会被 sanitize 掉并多打一条警告。 */
        session.setStationInfo(callsign, location, description, "",
                MmdvmSession.DEFAULT_SWID, 0);
        session.configure(host, port, dmrId, essid, password);
        /* 原生接管期间抖动缓冲要更厚，见 NATIVE_PRE_ROLL */
        audio.setPreRollFrames(NATIVE_PRE_ROLL);
        session.connect();
    }

    public void disconnect() {
        transmitter.stop("用户断开");
        session.disconnect();
        audio.flush();
        /* 原生让位，HOSE 恢复自己的 80 ms 垫底 */
        audio.setPreRollFrames(HOSE_PRE_ROLL);
        resetStreamLock();
        rxSentenceOn = false;
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    /** 实际用于登录的 ID（含 ESSID 别名）。未配置时等于本机 DMR ID。 */
    public int getLoginId() {
        return session.getLoginId();
    }

    public int getEssid() {
        return session.getEssid();
    }

    public int getPongCount() {
        return session.getPongCount();
    }

    public int getLateNakCount() {
        return session.getLateNakCount();
    }

    /** 累计收到 MSTPONG 的次数；0 表示主站不回 pong（有些主站确实不回）。 */
    public long getRxPackets() {
        return session.getRxPacketCount();
    }

    public double getRxLoss() {
        return session.getRxLossPercent();
    }

    public String getRxStats() {
        return session.getRxStats();
    }

    /**
     * 最近一次接收音频的电平（dBFS）。
     *
     * <p>★ 这是<b>解码后音频</b>的电平，不是空口信号强度 —— DMRD 包里没有
     * RSSI 字段，主站也不下发，这个链路上不存在「信号几格」。能测的只有
     * 「解出来多响」：−40 以下基本是底噪，−20 ~ −12 是正常说话。
     */
    public float getRxDb() {
        return lastRxDb;
    }

    /** 距上一帧语音多久（毫秒）。界面据此把「正在接收」熄灭。 */
    public long msSinceLastRx() {
        long t = lastRxAtMs;
        return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /* ================================================================ */
    /* 发射                                                             */
    /* ================================================================ */

    public boolean hasAudioPermission() {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
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
        return transmitter.start(session, myId, session.getLoginId(), tgid, slot, colorCode,
                privateCall ? DmrTx.FLCO_USER_USER : DmrTx.FLCO_GROUP);
    }

    public boolean isTransmitting() {
        return transmitter.isTransmitting();
    }

    /**
     * 麦克风增益，−24 ~ +12 dB。
     *
     * <p>作用点在 AGC 之后、软限幅之前 —— 所以调小它是真的「少失真」，
     * 不只是「变小声」。
     */
    public void setMicGainDb(float db) {
        transmitter.setMicGainDb(db);
    }

    public float getMicGainDb() {
        return transmitter.getMicGainDb();
    }

    /* ================================================================ */
    /* 接收                                                             */
    /* ================================================================ */

    public void setRxOn(boolean on) {
        rxOn = on;
        if (!on) {
            rxSentenceOn = false;
            audio.flush();
        }
    }

    public boolean isRxOn() {
        return rxOn;
    }

    /**
     * 原生链路此刻是否正在出声（或刚刚还在）。
     *
     * <p>{@code MainActivity.onAudio()} 用它决定要不要丢掉 HOSE 的帧。
     *
     * <h3>为什么判「正在收」而不是「已连接」</h3>
     * 判「已连接」会把 HOSE 收听整个掐掉 —— 而手机当热点时，主站并不一定
     * 往这边推音频（MMDVM 的接收取决于主站路由），结果就是连上主站以后
     * 反而什么都听不见了。实测这种做法不可接受。
     *
     * <p>所以判据是「最近 {@link #RX_TAKEOVER_MS} 毫秒内收到过语音帧」：
     * 原生在放的时候 HOSE 让位（否则一个队列里会插进两路声音），
     * 原生没在放的时候 HOSE 照常工作。两边的空档都不会出现全静音。
     *
     * <p>{@code rxOn} 仍然要判 —— 关掉接收的用户不该被原生链路抢走 HOSE。
     */
    public boolean isNativeRx() {
        if (!rxOn || !session.isConnected()) return false;
        long t = lastRxAtMs;
        return t > 0 && System.currentTimeMillis() - t < RX_TAKEOVER_MS;
    }

    /** 原生正在收的话组；0 表示没有。 */
    public int getRxTg() {
        return curDstId;
    }

    /* ---------------- 过滤 ---------------- */

    /** 屏蔽名单（按「谁在说」）。空数组 = 不过滤。 */
    public void setRxBlock(int[] ids) {
        Set<Integer> s = new HashSet<>();
        if (ids != null) for (int id : ids) if (id > 0) s.add(id);
        Set<Integer> next = s.isEmpty()
                ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(s);
        if (next.equals(rxBlock)) return;
        rxBlock = next;
        /* 名单变了要去重标记清零，否则「移出黑名单再加回来」不会重新提示 */
        rxBlockedLastSrc = 0;
        log("接收屏蔽名单：" + (s.isEmpty() ? "空（不过滤）" : s.toString()));
    }

    public int getRxBlockSize() { return rxBlock.size(); }
    public long getRxBlockedCount() { return rxBlockedCount; }

    /** 静音的话组（按「在哪个组说」）。空数组 = 全部放开。 */
    public void setRxMuteTg(int[] ids) {
        Set<Integer> s = new HashSet<>();
        if (ids != null) for (int id : ids) if (id > 0) s.add(id);
        Set<Integer> next = s.isEmpty()
                ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(s);
        if (next.equals(rxMuteTg)) return;
        rxMuteTg = next;
        log("静音话组：" + (s.isEmpty() ? "无" : s.toString()));
    }

    public int getRxMuteTgSize() { return rxMuteTg.size(); }
    public long getRxMutedCount() { return rxMutedCount; }

    /** 只收当前话组。主站可能同时推多路，混进一个缓冲就是一锅粥。 */
    public void setRxOnlyWatchTg(boolean on) {
        if (rxOnlyWatchTg == on) return;
        rxOnlyWatchTg = on;
        if (on) resetStreamLock();
        log(on ? "接收：只收当前话组（其余丢弃）" : "接收：接受所有话组");
    }

    public boolean isRxOnlyWatchTg() { return rxOnlyWatchTg; }

    /**
     * 界面当前关注的话组。0 = 还没选，此时「只收当前话组」不生效。
     *
     * <p>和 {@link #ptt} 设的是同一个字段：界面选好话组就同步过来，
     * 不用等第一次 PTT —— 否则「刚连上还没发过话」那段时间别的组照样挤进来。
     */
    public void setWatchTg(int tgid) {
        txDstId = tgid > 0 ? tgid : 0;
    }

    public int getWatchTg() { return txDstId; }

    private void resetStreamLock() {
        lockSrc = 0;
        lockDst = 0;
        lockSlot = 0;
        lockAtMs = 0;
    }

    /* ================================================================ */
    /* MmdvmSession.Listener                                            */
    /* ================================================================ */

    @Override
    public void onState(MmdvmSession.State state, String detail) {
        if (state != MmdvmSession.State.CONNECTED && state != MmdvmSession.State.CONNECTING) {
            /* 掉线时把「正在接收」收掉，否则界面会一直亮着 */
            rxSentenceOn = false;
        }
        if (state == MmdvmSession.State.ERROR || state == MmdvmSession.State.IDLE) {
            audio.setPreRollFrames(HOSE_PRE_ROLL);
        }
        cb.onState(state, detail);
    }

    @Override
    public void onPong(int count) {
        /* 只用来判断主站在不在线，界面自己去 session 取，不用逐条上报 */
    }

    @Override
    public void onDmrd(int srcId, int dstId, int slot, boolean voice, byte[] payload, int flags) {
        /* 数据帧（LC 头 / 终止帧）没有载荷 */
        if (!voice) return;
        /* 主站会把我们发出去的流量也推回来，自己的声音不用再听一遍 */
        if (srcId == myId) return;

        /* ① 黑名单。放最前面：连解码都不做 —— 省 CPU，也不会让被屏蔽的人
         * 占用抖动缓冲（否则「屏蔽了但还是卡」）。 */
        Set<Integer> block = rxBlock;
        if (!block.isEmpty() && block.contains(srcId)) {
            rxBlockedCount++;
            if (srcId != rxBlockedLastSrc) {
                rxBlockedLastSrc = srcId;
                cb.onBlocked(srcId, dstId);
            }
            return;
        }

        /* ② 静音的话组。优先级高于「只收当前话组」—— 用户明确说了不想听这个组。
         * 个呼（目标就是我自己）不受话组静音影响：那不是组呼。 */
        boolean forMe = (myId > 0 && dstId == myId);
        Set<Integer> mute = rxMuteTg;
        if (!forMe && !mute.isEmpty() && mute.contains(dstId)) {
            rxMutedCount++;
            return;
        }

        /* ③ 只收当前话组。
         * ⚠ 个呼必须放行 —— 有人专门呼叫我，不能因为「只收当前话组」就听不见。 */
        if (rxOnlyWatchTg && !forMe) {
            int watch = txDstId;
            if (watch > 0 && dstId != watch) return;
        }

        /* ④ 单流锁定。多路同时推时混进一个缓冲就是一锅粥。
         * 规则：当前流还在响就继续它；静默超过 STREAM_HOLD_MS 才换人；
         * 但「自己关注的话组」和「点名呼我的个呼」有抢断权。 */
        long now = System.currentTimeMillis();
        boolean same = (srcId == lockSrc && dstId == lockDst && slot == lockSlot);
        if (!same) {
            boolean free = (lockSrc == 0) || (now - lockAtMs > STREAM_HOLD_MS);
            boolean preempt = forMe || (txDstId > 0 && dstId == txDstId);
            if (!free && !preempt) return;
            /* 抢断时旧流的残余还在缓冲里，不清掉会和新流叠在一起 ——
             * 这正是「两个人同时说话」的成因。 */
            if (preempt && !free) audio.flush();
            lockSrc = srcId;
            lockDst = dstId;
            lockSlot = slot;
        }
        lockAtMs = now;

        if (rxOn && payload != null && vocoderReady && !transmitter.isTransmitting()) {
            short[] pcm = new short[DmrRx.PCM_PER_BUNDLE];
            int n = rx.decode(payload, 0, pcm);
            if (n > 0) {
                curSrcId = srcId;
                curDstId = dstId;
                curSlot = slot;
                curPrivate = (flags & DmrRx.FLAG_PRIVATE) != 0;

                /* ★ 切成 160 采样（20 ms）一帧再喂。
                 * AudioOut 的抖动缓冲按「帧数」算，一帧 20 ms ——
                 * 直接塞 480 采样（60 ms）会让同一个「12 帧」变成 720 ms。
                 * 而且按帧喂之后，原生和 HOSE 走的是完全同一条队列路径。 */
                for (int off = 0; off < n; off += DmrRx.PCM_PER_FRAME) {
                    int len = Math.min(DmrRx.PCM_PER_FRAME, n - off);
                    audio.feed(pcm, off, len);
                }

                lastRxAtMs = now;
                if (!rxSentenceOn) {
                    rxSentenceOn = true;
                    cb.onRx(true, srcId, dstId, slot, curPrivate);
                }

                /* 电平：用解码后的 PCM 算 RMS，节流到 10 Hz。
                 * 这是唯一诚实的量化指标 —— 见 getRxDb() 的说明。
                 * 峰值保持 + 每秒 20 dB 衰减：直接取瞬时值会在过零点处
                 * 掉到 −60，读数抖得没法看；只取峰值又永远降不下来。 */
                if (now - levelAtMs >= 100) {
                    levelAtMs = now;
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += (double) pcm[i] * pcm[i];
                    float rms = (float) Math.sqrt(sum / Math.max(1, n));
                    float db = rms > 0.5f ? (float) (20.0 * Math.log10(rms / 32768.0)) : -120f;
                    lastRxDb = Math.max(db, lastRxDb - 2f);
                    cb.onRxLevel(db);
                }
            }
        }

        /* ⑤ 话组忙。只有落在我关注的话组上才算 —— 界面据此提示别抢话。
         * 每个包都发会把日志刷爆（一个包 60 ms，一秒十几条），
         * 所以只在「讲话人 / 话组 / 是否在发射」变化时发，否则隔 3 秒兜一次。 */
        int watch = txDstId;
        if (watch > 0 && dstId == watch) {
            boolean txingNow = transmitter.isTransmitting();
            boolean changed = (srcId != busySrc) || (dstId != busyDst) || (txingNow != busyTxing);
            if (changed || now - busyAtMs >= BUSY_REPEAT_MS) {
                busySrc = srcId;
                busyDst = dstId;
                busyTxing = txingNow;
                busyAtMs = now;
                cb.onBusy(srcId, dstId, txingNow);
            }
        }
    }

    @Override
    public void onLog(String line) {
        log(line);
    }

    /* ================================================================ */
    /* DmrTransmitter.Listener                                          */
    /* ================================================================ */

    @Override
    public void onTxStart(int dstId) {
        /* 半双工闸门：发射时把接收缓冲掐掉。
         * 否则会听见自己绕网络回来的声音（主站会把本话组的流量推回来），
         * 而这时还没松开 PTT，等于自激。 */
        audio.flush();
        rxSentenceOn = false;
        cb.onTx(true, dstId, null);
    }

    @Override
    public void onTxStop(String reason) {
        cb.onTx(false, 0, reason);
    }

    @Override
    public void onLevel(int level, float dbfs) {
        cb.onTxLevel(level, dbfs);
    }

    /* ================================================================ */

    private void log(String line) {
        cb.onLog(line);
    }

    /**
     * 把会话状态翻成中文。放在这里而不是界面，因为它是协议语义
     * （CONF 是「正在下发配置」，不是「正在连接」）。
     */
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

    /** 判断一句接收是否已经说完，供界面的 tick 调用。 */
    public boolean pollRxSentenceEnded() {
        if (!rxSentenceOn) return false;
        if (msSinceLastRx() > RX_IDLE_MS) {
            rxSentenceOn = false;
            return true;
        }
        return false;
    }
}
