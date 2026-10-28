package net.beacon.dmr;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import net.beacon.dmr.vocoder.Vocoder;

/**
 * DMR 发射链路：麦克风 → 声码器 → DMRD 包 → 主站。
 *
 * <p>时序（这是 DMR 最容易出错的地方）：
 * <pre>
 *   按下 PTT
 *     ├─ 立即发 1 个语音 LC 头帧（seqNo=0）
 *     └─ 采集线程：每读满 160 个 8 kHz 样本（20 ms）编出 9 字节 AMBE，
 *                  攒够 3 帧（27 字节 / 60 ms）打一个 DMRD 包发出去
 *   松开 PTT
 *     └─ 发 1 个终止帧（DT_TERMINATOR_WITH_LC）
 * </pre>
 *
 * <p>采集用阻塞式 {@link AudioRecord#read}，而不是定时器轮询。
 * DroidStar 用 19 ms 定时器 + 非阻塞读，靠「问得比音频时钟快」来避免欠载；
 * 阻塞读直接跟音频设备的时钟对齐，没有漂移，也不需要那个技巧。
 *
 * <p>采样率：优先 8 kHz 直采。少数设备不提供 8 kHz 输入，
 * 此时回退到 48 kHz 采集再做 FIR 低通 + 6 倍抽取，避免混叠。
 */
public final class DmrTransmitter {

    private static final int TARGET_RATE = 8000;
    private static final int FALLBACK_RATE = 48000;
    private static final int FRAME_SAMPLES = 160;          // 20 ms @ 8 kHz

    /** 抗混叠 FIR 的抽头数。
     *
     *  原来是 31 抽头 + Hann 窗，过渡带宽约 3.1·fs/N = 3.1·48000/31 ≈ 4800 Hz ——
     *  而 8 kHz 输出只有 3400→4000 这 600 Hz 的余量。31 抽头根本压不住
     *  4 kHz 以上的成分，全折叠回话带里，听感是「金属味、嘶嘶声」。
     *
     *  127 抽头时过渡带压到 1170 Hz，但配 3400 Hz 截止后阻带边缘落在
     *  3400 + 1170/2 ≈ 3986 Hz —— 离 4 kHz 只剩 14 Hz，混叠压不干净。
     *  159 抽头 + 3200 Hz 截止：过渡带 936 Hz，阻带边缘 3668 Hz，留足余量。
     *  代价 8 kHz × 159 ≈ 127 万 MAC/s，对手机可以忽略。 */
    private static final int FIR_TAPS = 159;
    private static final int FRAMES_PER_PACKET = 3;        // 一个 DMRD 包承载 3 帧

    /** 单次发射的硬上限，防止 PTT 卡住把话组占死 */
    private static final int MAX_TX_MS = 180_000;

    /* ---- 「有信号但码字全 0」的探针 ----
     *
     * 这是 v2.3 那个静音 bug 留下的教训：删掉一行 arraycopy，包照发、
     * 电平条照跳、日志全干净，对方一个字都听不到。这类故障在本机没有任何
     * 可观测信号，只能在运行时主动比对「麦克风电平」和「码字内容」。
     *
     * 800 ≈ −32 dBFS：正常说话的 RMS 在 1000~8000，这个阈值不会误报。
     * AMBE 的静音帧不是全零（46 44 60 20 …），所以真有信号时出现全零码字
     * 必然意味着链路断了。 */
    private static final float ZERO_CODE_RMS = 800f;
    private static final int ZERO_CODE_FRAMES = 25;   // 25 × 20 ms = 0.5 秒

    /* ---- 用户麦克风增益 ----
     *
     * ★ 必须在 AGC 算完增益**之后**、软限幅**之前**。
     *
     * 这两个约束看起来矛盾，其实说的是两件不同的事：
     *
     *   · 不能在 AGC 之前 —— AGC 的目标电平固定（−16 dBFS），你在它前面
     *     放大 6 dB，它就在后面把增益降 6 dB，输出一个 bit 都不变，
     *     滑块能拖但没反应；
     *   · 不能在软限幅之后 —— 限幅产生的谐波已经烙在信号里了，
     *     再整体乘小只是把「炸」的音量调小，毛刺一个没少。
     *
     * v2.4.2 把增益放在限幅之后，用户反馈「−12 dB 好了一点但不够」——
     * 就是这个原因。现在把它并进 AGC 的输出乘数（{@code agcGain * micGain}），
     * 乘完再限幅：调小增益 → 送进限幅的信号本来就小 → 限幅根本不介入 →
     * 失真跟着一起消失，而不只是变小声。 */
    private static final float MIC_GAIN_MIN_DB = -24f;
    private static final float MIC_GAIN_MAX_DB = 12f;
    /** 前级削波判定线：|sample| 到这个值就算贴满量程。
     *
     *  取 32500 而不是 32767，是为了抓住「已经贴顶但差几个 LSB」的那部分 ——
     *  手机 MIC 过载时波形顶端是平的，采样值会在满量程附近反复出现，
     *  只数正好等于 32767 会明显低估。 */
    private static final int CLIP_LEVEL = 32500;

    public interface Listener {
        void onTxStart(int dstId);

        void onTxStop(String reason);

        /** 输入电平 0..100，用于界面上的电平条
         *  @param dbfs 同一帧的 dBFS，用于「发射电平」读数（−16 附近正常，
         *              超过 −6 就快削波了）。调麦克风增益时全靠它判断该往哪边调。 */
        void onLevel(int level, float dbfs);

        void onLog(String line);
    }

    private final Listener listener;

    private volatile boolean transmitting;

    /** 用户麦克风增益（线性）。1.0 = 不增不减，发射中也能实时改。 */
    private volatile float micGain = 1.0f;
    private Thread txThread;
    private AudioRecord recorder;
    /** 当前发射所依附的会话，用于发终止帧 */
    private volatile MmdvmSession session;

    private int srcId, dstId, slot, colorCode, flco;
    /**
     * DMRD 头里的 repeaterId —— 配了 ESSID 别名 ID 时是 9 位别名 ID，
     * 否则与本机 DMR ID 相同。见 {@link DmrTx#nativeSetRepeaterId}。
     */
    private int repeaterId;

    public DmrTransmitter(Listener listener) {
        this.listener = listener;
        /* 系数只跟采样率有关，构造时算一次即可 —— 发射循环里不做三角函数 */
        float[] hp = designHighPass(HP_CUTOFF_HZ, TARGET_RATE);
        hpB0 = hp[0];
        hpB1 = hp[1];
        hpB2 = hp[2];
        hpA1 = hp[3];
        hpA2 = hp[4];
        float[] pk = designPeaking(PK_FREQ_HZ, PK_Q, PK_GAIN_DB, TARGET_RATE);
        pkB0 = pk[0];
        pkB1 = pk[1];
        pkB2 = pk[2];
        pkA1 = pk[3];
        pkA2 = pk[4];
    }

    public boolean isTransmitting() {
        return transmitting;
    }

    /**
     * 设置麦克风增益（dB），−24 ~ +12。发射过程中改也立即生效。
     *
     * <p>作用点见 {@link #MIC_GAIN_MIN_DB} 上方的说明：AGC 算完增益之后、
     * 软限幅之前。所以调小它是真的「少失真」，不只是「变小声」。
     */
    public void setMicGainDb(float db) {
        if (db < MIC_GAIN_MIN_DB) db = MIC_GAIN_MIN_DB;
        if (db > MIC_GAIN_MAX_DB) db = MIC_GAIN_MAX_DB;
        micGain = (float) Math.pow(10.0, db / 20.0);
    }

    public float getMicGainDb() {
        return (float) (20.0 * Math.log10(Math.max(1e-6f, micGain)));
    }

    /**
     * 开始发射。
     *
     * @param repeaterId DMRD 头 [11..14] 用的热点 ID（含 ESSID 别名）；
     *                   传 0 或负数则跟随 {@code srcId}
     * @return null 表示成功启动，否则返回失败原因
     */
    @SuppressLint("MissingPermission")
    public String start(MmdvmSession session, int srcId, int repeaterId, int dstId, int slot,
                        int colorCode, int flco) {
        if (transmitting) return "正在发射中";

        if (session == null || !session.isConnected()) return "尚未连接主站";
        if (!Vocoder.nativeIsReady()) return "声码器未就绪";

        if (srcId <= 1000) return "DMR ID 无效";
        if (dstId <= 0) return "目标话组/ID 无效";

        this.srcId = srcId;
        this.repeaterId = repeaterId > 0 ? repeaterId : srcId;
        this.dstId = dstId;
        this.slot = slot;
        this.colorCode = colorCode;
        this.flco = flco;
        this.session = session;

        /* ★ 每次发射都要把 AGC 状态清零。
         *
         * 这些字段挂在对象上，而对象是随引擎活着的 —— 不重置的话：
         *   · agcFrames 只增不减，`agcFrames < AGC_FAST_FRAMES` 这段快速收敛
         *     **只有开机后第一次 PTT 才会走到**，之后每次发射都从上一通残留的
         *     agcGain 起步，按 1 秒的慢速收敛；
         *   · 上一通大声说完、这一通轻声开口 → 头一秒被压得太轻；
         *     上一通轻声、这一通大声 → 头一秒直接顶到软限幅。
         * 而「呼号」恰恰就在这一秒里，听感是「一开口听不清、发炸」。
         *
         * 滤波器状态（hpX1 等）不重置：那是连续信号的状态，清零反而会在
         * 块边界造出一个阶跃。 */
        agcGain = 1.0f;
        rmsAvg = 0f;
        agcFrames = 0;
        /* 噪声门也要重估：上一通可能在嘈杂环境、这一通在安静房间，
         * 沿用旧的噪声底会一开口就把人声当噪声压掉。 */
        ngFloor = 0f;
        ngGain = 1.0f;
        ngPrev = 1.0f;
        ngHold = 0;

        // ---- 打开音频输入 ----
        int rate = TARGET_RATE;
        int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        boolean needResample = false;
        if (minBuf <= 0) {
            // 设备不支持 8 kHz 直采，退到 48 kHz
            rate = FALLBACK_RATE;
            needResample = true;
            minBuf = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                return "设备不支持 8 kHz 或 48 kHz 录音";
            }
            log("设备不支持 8 kHz 采集，回退到 48 kHz + 抽取");
        }

        /* 采集缓冲：原来只给了 8 帧（160 ms），太薄。
         *
         * 发送是「采集驱动」的：每读满 3 帧就编一包发出去。编码本身要占几毫秒，
         * 系统调度、GC、后台任务都可能把这一轮拖到几十毫秒。缓冲薄于这个停顿，
         * AudioRecord 内部就会覆盖未读数据 —— 丢的是**麦克风侧**的样本，
         * 主站那边表现为音频断字，但网络统计里一个丢包都没有，很难查。
         *
         * 32 帧 = 640 ms，足够吸收常见卡顿；代价只是 PTT 松开时最多多 0.6 秒
         * 未播完的尾巴（实际会被 release() 丢弃，不影响时延）。 */
        /* 采集缓冲：按「32 帧」算，而且必须把抽取倍数算进去。
         *
         * ★ v2.1 之前的算式是 `FRAME_SAMPLES * 2 * 32` —— 这个只在 8 kHz 直采时
         * 等于 32 帧（640 ms）。走 48 kHz 抽取时一帧是 `160 × 6 = 960` 个采样，
         * 同一个算式只给出 107 ms，**比设计意图小 6 倍**。缓冲一薄，
         * 线程稍有停顿（GC、调度、编码慢一点）AudioRecord 就会覆盖还没读走的
         * 采样 —— 丢的是麦克风侧的音频，主站那边表现为「话音断续」，
         * 而网络统计里一个丢包都没有，非常难查。 */
        final int decim = needResample ? (rate / TARGET_RATE) : 1;
        int bufBytes = Math.max(minBuf, FRAME_SAMPLES * 2 * decim * 32);

        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufBytes)
                        .build();
            } else {
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            }
        } catch (Exception e) {
            recorder = null;
            return "无法打开麦克风：" + e.getMessage();
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
            return "麦克风初始化失败";
        }

        // ---- 组装首帧：语音 LC 头 ----
        DmrTx.nativeReset();
        DmrTx.nativeSetParams(srcId, dstId, slot, colorCode, flco);
        /* nativeSetParams 已把 repeaterId 默认设成 srcId；配了 ESSID 别名时
         * 这里再覆盖成 9 位别名 ID，主站才会把通联归到正确的热点上。 */
        DmrTx.nativeSetRepeaterId(this.repeaterId);

        byte[] pkt = new byte[DmrTx.PACKET_BYTES];
        if (DmrTx.nativeHeader(pkt) != DmrTx.PACKET_BYTES) {
            releaseRecorder();
            return "LC 头帧组装失败";
        }
        if (!session.sendDmrd(pkt, DmrTx.PACKET_BYTES)) {
            releaseRecorder();
            return "LC 头帧发送失败";
        }

        transmitting = true;
        final int sampleRate = rate;
        final boolean resample = needResample;

        try {
            recorder.startRecording();
        } catch (Exception e) {
            transmitting = false;
            releaseRecorder();
            return "启动录音失败：" + e.getMessage();
        }

        txThread = new Thread(() -> txLoop(session, sampleRate, resample), "dmr-tx");
        txThread.setDaemon(true);
        txThread.start();

        if (listener != null) listener.onTxStart(dstId);
        log("开始发射 → " + dstId + "（" + rate + " Hz" + (resample ? " 抽取" : "") + "）");
        return null;
    }

    /** 松开 PTT：停采集并补一个终止帧 */
    public void stop(String reason) {
        if (!transmitting) return;
        transmitting = false;

        Thread t = txThread;
        txThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        releaseRecorder();

        // 终止帧：让主站和所有收听方立刻释放这个话组
        byte[] eot = new byte[DmrTx.PACKET_BYTES];
        if (DmrTx.nativeEot(eot) == DmrTx.PACKET_BYTES) {
            // session 可能已经断开，静默失败即可
            try {
                MmdvmSession s = session;
                if (s != null) s.sendDmrd(eot, DmrTx.PACKET_BYTES);
            } catch (Exception ignored) {
            }
        }

        if (listener != null) listener.onTxStop(reason);
        log("停止发射（" + reason + "）");
    }

    /* ================================================================ */

    /** 一次发射的健康度统计。
     *
     *  单独成类是因为要在循环里每 2 秒报一次、循环退出后再报一次总结 ——
     *  局部变量没法被抽成方法复用，复制粘贴两份迟早会漂移。 */
    private static final class TxStat {
        long startedNano;
        long readSumMs; int readMaxMs; int readCalls; int shortReads;
        long procSumMs; int procMaxMs; int procFrames;
        long audioFrames;              // 已消费的 20 ms 帧
        long pktCount, pktEarly, pktLate;
        int gapMaxMs; long lastPktNano;
        int encodeFail;
        /* ---- 电平/失真三兄弟 ----
         * 这三个数合起来能唯一确定「发炸」到底发生在哪一级，缺一个就分不清：
         *   inClip   大 → 手机 MIC 本身过载（采集阶段就削平了，后级救不回来）
         *   limHits  大 → AGC 之后的信号顶到软限幅（我们后级的问题，调增益有效）
         *   两个都小   → 失真不在电平上，得往低频/声码器方向查
         */
        long inSamples, inClip;        // 采集阶段：样本总数 / 贴满量程的样本数
        long limSamples, limHits;      // 限幅级：样本总数 / 触发软限幅的样本数
        float outPeakDb = -120f;       // 本次发射的峰值（dBFS），送进编码器之前

        /* ---- AGC 自述（v2.4.6）----
         *
         * 对方录回来的音频只能证明「开口比后面响十几 dB」，但**没法区分**
         * 下面两件事 —— 它们在波形上长得一模一样，修法却完全相反：
         *   ① 开口真的冲上去了（增益在静音期被顶高，开口来不及压）
         *   ② 后面一直压得太轻（稳态远低于目标，开口只是回到了正常）
         * 猜错一次就是白改一版。所以让 AGC 把自己的增益报出来：
         *   agcFirst  首个过门限帧的增益 —— 远大于 1 就是 ① 的铁证
         *   agcLo/Hi  全程增益范围
         *   overDb    每帧「增益 × 实测 rms」相对目标的最大超出量
         *   steadyDb  过门限帧的平均输出电平（真正的稳态电平，不受开口拖累） */
        float agcFirst = -1f;          // < 0 表示本次发射一句话都没过门限
        float agcLo = 99f, agcHi = -1f;
        float overDb = -120f;
        double steadySum;
        long steadyN;
    }

    private void txLoop(MmdvmSession session, int rate, boolean resample) {
        /* 发射是硬实时的：每 20 ms 必须交出一帧。默认优先级会被 UI 线程、
         * 接收解码、GC 抢走 CPU，一旦某几帧迟到，音频时钟就对不上 ——
         * 表现是「话音断续」。提到 URGENT_AUDIO 是音频线程的标准做法。 */
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }

        final int decim = resample ? (rate / TARGET_RATE) : 1;
        final int readPerFrame = FRAME_SAMPLES * decim;

        short[] raw = new short[readPerFrame];
        short[] pcm = new short[FRAME_SAMPLES];
        byte[] ambe = new byte[Vocoder.CODEC_BYTES];

        byte[] bundle = new byte[DmrTx.AMBE_BUNDLE];
        int bundleFill = 0;

        byte[] pkt = new byte[DmrTx.PACKET_BYTES];

        float[] fir = resample ? designLowPass(FIR_TAPS, 3200.0 / rate) : null;
        float[] hist = resample ? new float[FIR_TAPS] : null;

        /* ---- 发包节拍：一个 DMRD 包 = 3 个 AMBE 帧 = 60 ms 音频 ----
         *
         * ★ 这一版最重要的改动。
         *
         * 原来的节奏完全由 `recorder.read()` 驱动：攒满 3 帧就发。这等于假设
         * 「read 一定每 20 ms 返回一帧」。但 AudioRecord 的 HAL 常常**成块交付**：
         * 缓冲里攒够几帧就一次性给出来，于是 read 连续三次立即返回，
         * 三个包在 1~2 ms 内全发出去，然后静默 59 ms。
         *
         * 空口上这就是「突发 + 静默」—— 对方听到的就是**断续**，
         * 而网络统计里一个包都没丢，从外部根本看不出来。
         *
         * 用绝对时间轴强制每 60 ms 一个包：早到就等，晚到立刻发并重新对齐。
         * 这是 MMDVMHost 的做法（它用定时器驱动），不是新发明。 */
        final long PKT_PERIOD_NS = 60_000_000L;
        long nextPktAt = 0;

        final TxStat st = new TxStat();
        st.startedNano = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        long lastReport = startedAt;

        /* 半帧累加器。★ 原来每次循环 got 从 0 重来，read 返回 0 或部分数据时
         * 已经读到的那几个采样被直接扔掉 —— 时间轴就少了那几毫秒。 */
        int rawFill = 0;
        /* 连续「read 返回 0」的计数。★ 必须放在外层循环之外 ——
         * 放在里面每次外层迭代都会清零，那个「连续 200 次就报错」的保护
         * 等于永远不成立。 */
        int zeroSpins = 0;
        /* 连续「有信号但码字全 0」的帧数，见下面的运行时兜底 */
        int zeroCode = 0;

        /* stop() 会把字段 recorder 置空，而本线程可能正卡在 read() 里。
         * 抓一份局部引用，避免下一次 read 撞上 null。 */
        final AudioRecord rec = recorder;

        while (transmitting) {
            // 主站掉线就立刻停，避免对着空气喊
            if (!session.isConnected()) {
                if (listener != null) listener.onTxStop("主站连接已断开");
                break;
            }
            if (System.currentTimeMillis() - startedAt > MAX_TX_MS) {
                if (listener != null) listener.onTxStop("超过单次发射时限");
                break;
            }

            while (rawFill < readPerFrame && transmitting) {
                long rT0 = System.nanoTime();
                int n = rec.read(raw, rawFill, readPerFrame - rawFill);
                int rMs = (int) ((System.nanoTime() - rT0) / 1_000_000L);
                st.readSumMs += rMs;
                st.readCalls++;
                if (rMs > st.readMaxMs) st.readMaxMs = rMs;

                if (n < 0) {
                    /* 任何负值都是致命错误（ERROR_INVALID_OPERATION / BAD_VALUE /
                     * DEAD_OBJECT）。原来只对前两个停，DEAD_OBJECT 会掉进
                     * `if (rawFill < readPerFrame) continue;` 变成死循环 ——
                     * 发射线程空转，一个包都不发。 */
                    if (listener != null) listener.onTxStop("录音读取错误 " + n);
                    transmitting = false;
                    break;
                }
                if (n == 0) {
                    /* read 返回 0 = 这一轮没拿到数据。别当致命错误（不少设备
                     * 在 8 kHz 下会先返回几次 0），但连续 200 次就说明录音真的停了。 */
                    st.shortReads++;
                    if (++zeroSpins > 200) {
                        if (listener != null) listener.onTxStop("麦克风无数据");
                        transmitting = false;
                    }
                    continue;
                }
                zeroSpins = 0;
                rawFill += n;
            }
            if (!transmitting) break;
            if (rawFill < readPerFrame) continue;
            rawFill = 0;            /* 只在完整消费一帧之后才清零 */

            final long procT0 = System.nanoTime();

            if (resample) {
                decimate(raw, readPerFrame, pcm, fir, hist, decim);
            } else {
                System.arraycopy(raw, 0, pcm, 0, FRAME_SAMPLES);
            }

            /* 链序：高通 → 高频补偿 → 量电平 → AGC（含用户增益 + 软限幅）→ 噪声门。
             *
             * 「量电平」单独抽出来，是因为 AGC 必须知道**处理前**这一帧有多响：
             * 拿处理后的结果去反推增益，高通和 EQ 的衰减会被算进「信号很轻」里，
             * 增益就会一路偏高。 */
            highPass(pcm);   /* 切掉 280 Hz 以下：低频是 AMBE 最大的比特黑洞 */
            presence(pcm);   /* 2 kHz +3 dB：可懂度核心区，补回被低频挤掉的辅音能量 */

            /* 前级削波统计：read() 出来的**原始采样**有多少已经贴着满量程。
             *
             * 这是唯一能区分「手机 MIC 自己过载」和「我们后级把信号搞炸」的地方。
             * 一旦在采集阶段就削平了，后面任何增益、限幅、降噪都救不回来 ——
             * 只能让用户离话筒远一点。没有这个数字，两种情况的日志长得一模一样。 */
            st.inSamples += readPerFrame;
            st.inClip += countNearFull(raw, readPerFrame);

            final float frameRms = rmsOf(pcm);
            agc(pcm, frameRms, st);   /* 电平归一化 + 用户增益 + 软限幅，见 agc() */
            noiseGate(pcm, frameRms);  /* ★ 必须放在 AGC 之后 */

            if (listener != null) {
                /* 电平在**所有增益和限幅之后**量 —— 用户要看到的是真正发出去的
                 * 那一帧有多响，否则调增益时读数不动，等于闭着眼睛调。
                 *
                 * 数字取**本次发射的峰值**而不是当前帧的瞬时值：说话总有停顿，
                 * 瞬时值会在停顿帧上掉到 −55 dBFS 那种毫无意义的值（v2.4.2 的
                 * 截图就是 −54.9 dB），用户根本没法据此判断该往哪边调。 */
                final float outRms = rmsOf(pcm);
                final float outDb = dbOfRms(outRms);
                if (outDb > st.outPeakDb) st.outPeakDb = outDb;
                listener.onLevel(levelOfRms(outRms), st.outPeakDb);
            }

            if (Vocoder.nativeEncode(pcm, ambe) != Vocoder.CODEC_BYTES) {
                /* ★ 这里不能 continue。
                 *
                 * continue 会让这一帧「消失」，而 bundleFill 不前进 ——
                 * 下一个帧直接顶上来，于是这个 60 ms 的包里装的是
                 * 「第 n 帧 + 第 n+2 帧 + 第 n+3 帧」，时间轴被压缩了 20 ms。
                 * 对方解码时就是一次跳变，连着发生就是「话音断续」。
                 *
                 * nativeEncode 只在成功时才写 ambe，所以失败时 ambe 里
                 * 还是上一帧的内容 —— 直接沿用它，时间轴保持对齐，
                 * 最多是重复了 20 ms 的音频，听不出来。
                 */
                st.encodeFail++;
                if (st.encodeFail == 1) log("声码器编码失败，沿用上一帧");
            } else {
                st.encodeFail = 0;
            }

            /* ---- 健康度：纯处理耗时（不含阻塞读） ---- */
            long procMs = (System.nanoTime() - procT0) / 1_000_000L;
            st.procSumMs += procMs;
            st.procFrames++;
            if (procMs > st.procMaxMs) st.procMaxMs = (int) procMs;

            st.audioFrames++;

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastReport >= 2000) {
                /* 2 秒一报 —— 用户的实际发射常常只有 1~5 秒，
                 * 5 秒的间隔等于永远看不到。 */
                long wallMs = (System.nanoTime() - st.startedNano) / 1_000_000L;
                long audioMs = st.audioFrames * 20L;
                float avgProc = st.procFrames > 0 ? (float) st.procSumMs / st.procFrames : 0f;
                String verdict = (st.procMaxMs >= 20) ? "  ⚠ 超预算，会积压"
                        : (st.procMaxMs >= 14 ? "  （接近预算）" : "");
                log(String.format(java.util.Locale.US,
                        "发射健康：处理 平均 %.1f / 峰值 %d ms（预算 20）｜读 平均 %d / 峰值 %d ms｜音频 %d vs 实时 %d ms（差 %+d）%s",
                        avgProc, st.procMaxMs,
                        st.readCalls > 0 ? st.readSumMs / st.readCalls : 0, st.readMaxMs,
                        audioMs, wallMs, audioMs - wallMs, verdict));
                st.procMaxMs = 0;
                st.procSumMs = 0;
                st.procFrames = 0;
                lastReport = nowMs;
            }

            /* ★ 运行时兜底：麦克风明明有信号，编出来的码字却全是 0。
             *
             * 这只有两种可能：声码器返回了空数据，或者码字没被正确装进 bundle。
             * 两种情况的表现完全一样 —— **对方听到的是静音**，而本地一切正常：
             * PTT 亮着、电平条照跳、包照发、主站照转、发射小结干干净净。
             *
             * v2.3 就是这样漏掉了一行 arraycopy，v2.3 / v2.4 发出去全是静音。
             * 这类故障没有任何本地可观测信号，所以必须在这里主动埋一个探针：
             * 真机上唯一能暴露它的地方就是这条日志。
             *
             * 注意：AMBE 的「静音帧」不是全零（自检里静音码字是 46 44 60 20 …），
             * 所以「有信号 + 全零码字」不可能是正常情况。 */
            if (frameRms > ZERO_CODE_RMS && isAllZero(ambe)) {
                zeroCode++;
                if (zeroCode == ZERO_CODE_FRAMES) {
                    log("⚠ 连续 " + ZERO_CODE_FRAMES + " 帧码字全为 0，但麦克风有信号"
                            + "（RMS " + (int) frameRms + "）—— 码字没有被装进 bundle，"
                            + "对方会听到完全的静音");
                }
            } else {
                zeroCode = 0;
            }

            /* ---- 发包：强制 60 ms 节拍 ---- */

            /* ★★ 这一行绝对不能少。
             *
             * `bundle` 是 byte[27]，Java 的初始值全是 0。少了这次拷贝，
             * 每个 DMRD 包里装的就是**三帧全零码字 = 静音**：
             * PTT 照按、包照发、主站照转、本地日志一切正常，
             * 但对方一个字都听不到 —— 而且从任何本地指标都看不出来。
             *
             * v2.3 重写这个循环时漏掉了这一行，v2.3 / v2.4 两个版本
             * 发出去的全是静音。`tests/check_tx_bundle.py` 现在守着它。 */
            System.arraycopy(ambe, 0, bundle, bundleFill, Vocoder.CODEC_BYTES);
            bundleFill += Vocoder.CODEC_BYTES;

            if (bundleFill >= DmrTx.AMBE_BUNDLE) {
                bundleFill = 0;

                long now = System.nanoTime();
                if (nextPktAt == 0) nextPktAt = now;
                long early = nextPktAt - now;      /* >0 = 比节拍早 */
                if (early > 0) {
                    st.pktEarly++;
                    if (early < PKT_PERIOD_NS) {
                        try {
                            Thread.sleep(early / 1_000_000L, (int) (early % 1_000_000L));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        now = System.nanoTime();
                        nextPktAt += PKT_PERIOD_NS;
                    } else {
                        /* 比节拍早了整整一个周期以上 —— 网格跑飞了（不该发生）。
                         * 重新对齐，否则欠账会无限累积，最后变成一整串突发。 */
                        nextPktAt = now + PKT_PERIOD_NS;
                    }
                } else if (early < -2 * PKT_PERIOD_NS) {
                    /* 落后超过两个周期：重新对齐，不累积欠账 */
                    st.pktLate++;
                    nextPktAt = now + PKT_PERIOD_NS;
                } else {
                    if (early < -5_000_000L) st.pktLate++;
                    nextPktAt += PKT_PERIOD_NS;
                }

                if (st.lastPktNano != 0) {
                    int gap = (int) ((now - st.lastPktNano) / 1_000_000L);
                    if (gap > st.gapMaxMs) st.gapMaxMs = gap;
                }
                st.lastPktNano = now;
                st.pktCount++;

                if (DmrTx.nativeVoice(bundle, pkt) == DmrTx.PACKET_BYTES) {
                    if (!session.sendDmrd(pkt, DmrTx.PACKET_BYTES)) {
                        if (listener != null) listener.onTxStop("发送失败");
                        break;
                    }
                } else {
                    log("DMRD 组装失败");
                }
            }
        }

        releaseRecorder();

        /* ---- 收尾小结 ----
         * 用户的实际发射常常只有一两秒，等不到周期性报告。
         * 这一段是「断续到底在哪」的完整证据链，每次松开 PTT 都会打出来。 */
        reportTxSummary(st);

        if (transmitting) {
            // 由循环内部条件跳出，补一次收尾
            transmitting = false;
            if (listener != null) listener.onTxStop("异常中断");
        }
    }

    /** 发射结束时的完整小结。 */
    private void reportTxSummary(TxStat st) {
        if (st.audioFrames <= 0) return;
        long wallMs = (System.nanoTime() - st.startedNano) / 1_000_000L;
        long audioMs = st.audioFrames * 20L;
        long diff = audioMs - wallMs;
        /* 采集时钟和墙上时钟的偏差。超过 3% 就说明麦克风侧在丢样本 ——
         * AudioRecord 的缓冲被覆盖时不会报错，只能这样反推。 */
        float driftPct = wallMs > 0 ? (float) (diff * 100.0 / wallMs) : 0f;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US,
                "发射小结：%.1f 秒 · %d 帧 · %d 包", wallMs / 1000.0, st.audioFrames, st.pktCount));
        sb.append(String.format(java.util.Locale.US,
                "\n  音频时钟：采集 %d ms vs 实时 %d ms（差 %+d ms，%.1f%%）%s",
                audioMs, wallMs, diff, driftPct,
                Math.abs(driftPct) >= 3f ? "  ⚠ 丢样本" : ""));
        sb.append(String.format(java.util.Locale.US,
                "\n  读      ：平均 %d / 峰值 %d ms（正常 20）· 短读 %d 次",
                st.readCalls > 0 ? st.readSumMs / st.readCalls : 0, st.readMaxMs, st.shortReads));
        sb.append(String.format(java.util.Locale.US,
                "\n  处理    ：平均 %.1f / 峰值 %d ms（预算 20）%s",
                st.procFrames > 0 ? (float) st.procSumMs / st.procFrames : 0f, st.procMaxMs,
                st.procMaxMs >= 20 ? "  ⚠ CPU 跟不上" : ""));
        sb.append(String.format(java.util.Locale.US,
                "\n  发包    ：间隔峰值 %d ms（应为 60）· 提前 %d / 迟到 %d",
                st.gapMaxMs, st.pktEarly, st.pktLate));
        sb.append(String.format(java.util.Locale.US,
                "\n  编码    ：失败 %d 帧", st.encodeFail));

        /* ---- 电平 / 失真三兄弟 ----
         *
         * 用户说「对方听着发炸」时，这三个数能把责任分到具体一级，
         * 缺一个都分不清 —— 而且处置方式完全相反，猜错就是白折腾一轮：
         *   采集削波高 → 手机 MIC 过载，只能离远点；
         *   限幅高     → 我们后级太激进，调小增益有效。 */
        final float inPct = st.inSamples > 0 ? st.inClip * 100f / st.inSamples : 0f;
        final float limPct = st.limSamples > 0 ? st.limHits * 100f / st.limSamples : 0f;
        sb.append(String.format(java.util.Locale.US,
                "\n  电平    ：送出声码器最高 %.1f dBFS（短时 RMS，−16 附近正常）", st.outPeakDb));
        sb.append(String.format(java.util.Locale.US,
                "\n  削波    ：采集 %.2f%%（前级）· 限幅 %.2f%%（后级）", inPct, limPct));

        /* ---- AGC 自述（v2.4.6）----
         * 这两行是给「对方说还炸」准备的。以前只能靠对方录音反推，
         * 而录音里「开口冲」和「稳态轻」看起来一模一样 —— 现在直接看数：
         *   开口首帧 ≫ 1   → 静音期增益被顶高了，是开口冲
         *   稳态 ≪ −18      → 平时就压太轻，开口只是回到了正常
         * 两者都不是，才轮到往声码器/接收机方向查。 */
        final float steadyDb = st.steadyN > 0 ? (float) (st.steadySum / st.steadyN) : -120f;
        sb.append(String.format(java.util.Locale.US,
                "\n  自动电平：增益 %.2f~%.2f× · 开口首帧 %s · 稳态 %.1f dBFS",
                st.agcHi < 0f ? 1f : st.agcLo,
                st.agcHi < 0f ? 1f : st.agcHi,
                st.agcFirst < 0f ? "—" : String.format(java.util.Locale.US, "%.2f×", st.agcFirst),
                steadyDb));
        sb.append(String.format(java.util.Locale.US,
                "\n  开口过冲：%+.1f dB（超出 AGC 目标的最大值）%s",
                st.overDb, st.overDb > 6f ? "  ⚠ 压不住" : ""));

        sb.append("\n  判断    ：").append(
                inPct >= 0.20f
                        ? "⚠ 麦克风输入本身已削波 —— 手机 MIC 过载，调增益救不回来，请离话筒远一点、说话轻一点"
                        : limPct >= 2.0f
                        ? "⚠ 软限幅在持续介入 —— 把麦克风增益往左调，这次会真的少失真而不只是变小声"
                        : st.overDb > 6f
                        ? "⚠ 开口瞬间超出目标 6 dB 以上 —— 静音期增益被顶高了，需要继续提门限"
                        : st.steadyN > 0 && steadyDb < -26f
                        ? "稳态电平偏低（远低于 −18）—— 不是开口冲，是平时就压太轻，该抬增益下限"
                        : st.outPeakDb < -30f
                        ? "输入电平偏低（离得远或说话轻）—— 增益可以往右抬"
                        : "电平正常，失真不在电平这一级（往低频 / 声码器方向查）");
        log(sb.toString());
    }

    /* ================================================================ */

    private void releaseRecorder() {
        AudioRecord r = recorder;
        recorder = null;
        if (r == null) return;
        try {
            if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                r.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }

    /* ---------------- 发射侧前端处理 ----------------
     *
     * 链序固定为「高通 → 高频补偿 → AGC → 声码器」，顺序不能换：
     * AGC 按能量定增益，低频多的信号会把增益压下去，语音主体
     * （300–3000 Hz）反而偏轻，编出来更闷。
     */

    /** 高通截止频率（二阶 Butterworth，12 dB/oct）。
     *
     *  原来是一阶 120 Hz，实测频响：
     *      100 Hz −4.0 dB，60 Hz −7.1 dB，40 Hz −10 dB
     *  —— 对 AMBE 来说等于没切。
     *
     *  手机在 {@code AudioSource.MIC} 下近讲效应极强：贴嘴说话时
     *  100–200 Hz 能比 1 kHz 高 10–20 dB。而 AMBE+2 只有 2.45 kbps，
     *  低频谐波占掉的每一分比特都是从清晰度里偷的 —— 表现出来就是
     *  「低音糊、闷、字咬不清」。这是 DMR 发射端最典型的一类音质问题。
     *
     *  280 Hz 二阶的实测值：
     *      100 Hz −18.0 dB（改善 14 dB）
     *       60 Hz −26.8 dB（改善 20 dB）
     *      200 Hz −6.9 dB  ← 男声基频还留着，不变薄
     *     1000 Hz −0.02 dB ← 语音主体完全无损
     *
     *  不取 300 Hz 是因为男声基频在 85–180 Hz，切太狠会「变薄」丢厚度；
     *  280 Hz 是「低频糊」和「声音单薄」之间的折中。 */
    private static final double HP_CUTOFF_HZ = 280.0;

    /** 高频补偿（RBJ peaking EQ）。
     *
     *  v2.0 打在 2600 Hz / +2 dB / Q 0.8。Q=0.8 时带宽 = f0/Q = 3250 Hz，
     *  实际是从 1 kHz 到 4.2 kHz 一个很宽的缓抬 —— 重心偏高。
     *  2.6 kHz 以上主要是 s/sh 的齿音，AMBE+2 在这一段最容易把它编成刺耳的
     *  噪声；而真正决定「字咬得清不清」的是 **1–2.5 kHz**（第二共振峰 F2
     *  的过渡段），那里才是元音之间互相区分的依据。
     *
     *  v2.1 下移到 2000 Hz / +3 dB / Q 0.9：带宽 2222 Hz，覆盖 900–3100 Hz，
     *  正好压在可懂度核心上，又不往齿音区堆能量。 */
    private static final double PK_FREQ_HZ = 2000.0;
    private static final double PK_Q = 0.9;
    private static final double PK_GAIN_DB = 3.0;

    private final float hpB0, hpB1, hpB2, hpA1, hpA2;
    private final float pkB0, pkB1, pkB2, pkA1, pkA2;

    /** 滤波器状态跨帧保留，块边界不会出现阶跃 */
    private double hpX1, hpX2, hpY1, hpY2;
    private double pkX1, pkX2, pkY1, pkY2;

    /** 二阶 Butterworth 高通，双线性变换 + 预畸变。
     *  返回 {b0,b1,b2,a1,a2}，a0 已归一化为 1。 */
    private static float[] designHighPass(double fc, int fs) {
        double wc = Math.tan(Math.PI * fc / fs);
        double wc2 = wc * wc;
        double s2wc = Math.sqrt(2.0) * wc;
        double k = 1.0 / (1.0 + s2wc + wc2);
        return new float[]{
                (float) k,
                (float) (-2.0 * k),
                (float) k,
                (float) (2.0 * (wc2 - 1.0) * k),
                (float) ((1.0 - s2wc + wc2) * k)
        };
    }

    /** RBJ cookbook 的 peaking EQ。返回 {b0,b1,b2,a1,a2}，a0 已归一化。 */
    private static float[] designPeaking(double f0, double q, double gainDb, int fs) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / fs;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cw = Math.cos(w0);
        double a0 = 1.0 + alpha / A;
        return new float[]{
                (float) ((1.0 + alpha * A) / a0),
                (float) (-2.0 * cw / a0),
                (float) ((1.0 - alpha * A) / a0),
                (float) (-2.0 * cw / a0),
                (float) ((1.0 - alpha / A) / a0)
        };
    }

    private void highPass(short[] pcm) {
        final float b0 = hpB0, b1 = hpB1, b2 = hpB2, a1 = hpA1, a2 = hpA2;
        double x1 = hpX1, x2 = hpX2, y1 = hpY1, y2 = hpY2;
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i];
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            int v = (int) y;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
        }
        hpX1 = x1;
        hpX2 = x2;
        hpY1 = y1;
        hpY2 = y2;
    }

    private void presence(short[] pcm) {
        final float b0 = pkB0, b1 = pkB1, b2 = pkB2, a1 = pkA1, a2 = pkA2;
        double x1 = pkX1, x2 = pkX2, y1 = pkY1, y2 = pkY2;
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i];
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            int v = (int) y;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
        }
        pkX1 = x1;
        pkX2 = x2;
        pkY1 = y1;
        pkY2 = y2;
    }

    private float agcGain = 1.0f;
    private float rmsAvg = 0f;
    private int agcFrames = 0;
    /** 目标 RMS，约 −17.9 dBFS（4200）。
     *
     *  <p>历史：v2.0 是 4000（−18.3 dBFS），后来调到 5200（−16.0 dBFS），
     *  理由是声码器自检把测试信号归一化到峰值 24000，按「语音 12 dB 峰值
     *  因数」反推 5200 对应峰值 20800，还在软限幅膝点（30146）之下。
     *
     *  <p><b>那个 12 dB 是整段语音的峰值因数，不是单帧的。</b> 语音的波峰
     *  集中在爆破音（p/b/t/d）和元音起始的那几毫秒里，20 ms 帧内的峰值因数
     *  能到 15~18 dB。按 16 dB 算：
     *  <pre>
     *    5200 × 6.31 = 32800  → 超过膝点 30146，限幅**持续介入**
     *    4200 × 6.31 = 26500  → 在膝点之下，限幅不介入
     *  </pre>
     *  也就是说旧目标下，正常说话的爆破音每次都会把软限幅踩住 ——
     *  而限幅产生的谐波正是用户说的「炸」。这也是为什么把用户增益拉到底
     *  只能「好一点」：限幅在它之前，谐波已经烙进去了。
     *
     *  <p>降到 4200 之后：常见语音（峰值因数 ≤16 dB）的峰值留在膝点以下，
     *  限幅只在真正的异常大电平时才动一下。代价是整体轻 1.9 dB，
     *  对端若觉得小声，抬麦克风增益即可 —— 现在那个滑块是真的能抬。 */
    private static final float AGC_TARGET_RMS = 4200f;
    /** 最大提升量。原来是 8.0（+18 dB），v2.4.5 降到 6.0（+15.6 dB）。
     *
     *  <p>★ 降低的理由不是「不需要那么多提升」，而是**静音期间会被顶到上限**。
     *  门限 100（−50 dBFS）时，手机底噪只要超过这个值，`want = 4200/底噪`
     *  就是一个很大的数，被夹在 AGC_MAX 上 —— 于是静音期增益一路爬到 8 倍，
     *  开口第一帧就是 8 倍 × 语音电平，严重过冲。仿真（tests/tx_agc_overshoot.py）
     *  量出来：过冲 +12.7 ~ +13.9 dB，要 7~8 帧才回落。
     *  6.0 把最坏情况压到 +15.6 dB，配合瞬态复位后实际过冲接近 0。 */
    private static final float AGC_MAX = 6.0f;        // 最多提 15.6 dB
    /** 允许的衰减量。只提不压的话，MIC 电平偏高的机器会一路顶到满量程 ——
     *  旧代码就是这样，正常说话就能削掉几百个样本。0.22 = 最多压 13 dB，
     *  普通说话（贴着目标电平）增益仍稳定在 1 倍，动态不受影响。
     *
     *  ★ 目标电平降到 4200 之后，这个下限必须跟着放宽。
     *  「压得动」的上限 = AGC_TARGET / AGC_MIN：旧参数是 5200/0.3 = 17333，
     *  新参数 4200/0.3 = 14000 —— 不跟着改的话，热麦克风反而更容易撞下限、
     *  压不下去，等于把「降低基础电平」这件事对最需要它的机器完全失效。
     *  4200/0.22 = 19000，比原来还多留了 1 dB 的余量。 */
    private static final float AGC_MIN = 0.22f;
    /** 静音门限：低于这个电平不参与 AGC 决策（want 保持 1.0）。
     *
     *  <p>★ 原来是 100（−50 dBFS），**太低**。手机 MIC 的底噪在安静房间就有
     *  −60 ~ −50 dBFS，嘈杂环境轻松到 −45 ~ −35 dBFS（RMS 180~580）——
     *  也就是说绝大多数时候「底噪」都被当成「有语音」，AGC 在用户还没开口时
     *  就把增益往 AGC_MAX 顶。等真正开口，增益已经高得离谱，头几帧全过冲。
     *
     *  <p>260 是 −42 dBFS：高于手机底噪的常见区间，又低于轻声说话的
     *  （RMS 600~1000）。这一档的作用是「别在没人说话时白爬增益」，
     *  真正的过冲兜底靠 {@link #AGC_JUMP_OUT} 的瞬态复位。 */
    private static final float AGC_GATE_RMS = 260f;
    /** 每次发射开头快速收敛的帧数（8 × 20 ms = 160 ms） */
    private static final int AGC_FAST_FRAMES = 8;
    private static final float AGC_FAST = 0.50f;
    /** 稳态平滑系数：0.02 ≈ 1 s 时间常数，慢到不会跟着音节走 */
    private static final float AGC_SLOW = 0.02f;
    /** 电平上升的平滑系数（≈ 80 ms）。
     *
     *  ★ 这里必须和 AGC_SLOW 不对称，这是 v2.1 修掉的第二个毛病。
     *  原来上升和下降共用 AGC_SLOW：说一句话，中间的停顿让检测器按 1 秒
     *  的速率往下掉，下一个词回来时又要 1 秒才追上 —— 听感是「每个词之间
     *  音量塌一下」，而这恰好就是「不清晰」的主因之一。
     *  改成「上升快、下降慢」之后，检测器记住的是**这段说话的峰值电平**，
     *  短停顿不会把它带下去；真遇到持续更大的电平（换人、换机器）也能
     *  在 80 ms 内跟上。这是所有电台电平器的标准做法。 */
    private static final float AGC_RISE = 0.25f;

    /** 瞬态复位阈值：按当前增益算出的输出超过目标的这个倍数时，直接把增益
     *  设成目标值，不走 slew。
     *
     *  <p>★ 这是 v2.4.5 修「一开口发炸」的关键一条。
     *
     *  <p>静音期增益可能被顶到很高（见 {@link #AGC_MAX} 的说明）。开口那一帧，
     *  平滑后的 rmsAvg 还停在低位，`want` 算出来仍然偏大，靠 slew 慢慢降 ——
     *  仿真里要 7~8 帧（140~160 ms）才回到稳态 3 dB 以内，这段时间的输出电平
     *  比正常高 12~14 dB。对方听到的就是「一开口特别冲，然后突然变小」。
     *
     *  <p>2.0 = 输出一旦会超过目标 6 dB，就不等了，直接用**当前帧**的 rms
     *  反算增益并按上去。稳态时 `agcGain × rms ≈ target`，不会触发；
     *  音节间的正常起伏（3~6 dB）也不会触发 —— 只有真正的「静音→开口」
     *  这种十几 dB 的跃变才碰得到。 */
    private static final float AGC_JUMP_OUT = 2.0f;

    /** 输出电平的**无条件**天花板（v2.4.6）。
     *
     *  <p>jump 和 slew 都是「有条件的」：jump 要超出 6 dB 才触发，slew 每帧只走
     *  35%。只要有一帧没赶上，过冲就会漏进编码器 —— 而 AMBE 一旦把削平的波形
     *  编进去，对方那边再怎么调音量都救不回来。
     *
     *  <p>所以再加一道不管来路的硬夹：只要当前帧过了门限，就用**实测 rms**
     *  反算一个增益上限，保证这一帧的输出最多到目标 +6 dB。稳态下
     *  `agcGain × rms ≈ target`，cap 远大于 agcGain，永远不会碰到。
     *
     *  <p>★ 这条的价值不在「多压了一点」，而在于它把过冲**从概率变成有界**：
     *  不管前面发生过什么、增益现在是多少，输出都超不过目标 +6 dB。 */
    private static final float AGC_CEIL = 2.0f;

    /** 电平归一化（不是压缩器）。
     *
     *  手机 MIC 源的电平差异能到 20 dB：同样正常说话，有的设备 RMS 800，
     *  有的 8000。AMBE 对输入电平很敏感，太轻的输入编出来又闷又糊。
     *
     *  四个设计点，每个都对应一种失败形态：
     *    · **以只提不压为主，超出目标 10 dB 才压** —— 压会损失动态，
     *      语音变平、辨识度下降；但不压的话电平高的机器会顶穿满量程，
     *      旧代码在正常说话时就能削掉几百个样本；
     *    · **电平检测上升快、下降慢** —— 见 AGC_RISE 的注释。对称的慢速
     *      平滑会让每个词之间音量塌陷；
     *    · **限幅放在输出端，不进增益环** —— 用瞬时峰值去压增益，
     *      一个爆破音就把整句电平拉下来，下一帧再抬回去，来回摆 10 dB。
     *      改成增益只管慢电平，峰值交给软限幅（0.92 以上才动手），
     *      两者互不干扰。
     *
     *  @param frameRms 本帧在**滤波之后、增益之前**的 RMS，由 txLoop 量好传进来
     */
    private void agc(short[] pcm, float frameRms, TxStat st) {
        float rms = frameRms;

        if (agcFrames < 1) {
            rmsAvg = rms;                                   // 首帧直接对齐
        } else {
            float a;
            if (agcFrames < AGC_FAST_FRAMES) a = AGC_FAST;  // 开头 160 ms 抢到位
            else a = (rms > rmsAvg) ? AGC_RISE : AGC_SLOW;  // 之后上升快、下降慢
            rmsAvg += (rms - rmsAvg) * a;
        }
        agcFrames++;

        float want = 1.0f;
        if (rmsAvg > AGC_GATE_RMS) {
            want = AGC_TARGET_RMS / rmsAvg;
            if (want > AGC_MAX) want = AGC_MAX;
            else if (want < AGC_MIN) want = AGC_MIN;
        }

        /* ★ 首帧直接到位，不爬。
         *
         * 重置之后 agcGain=1.0，如果靠 slew 爬上去，第一帧只到 2.99×
         * 而理想是 7.64×（差 8 dB）—— 要 160 ms 才追上。呼号就在这几帧里。
         * 发射刚开始、还没有输出，直接赋值不会有任何爆音风险。
         * 只有这一帧确实有声音（过了静音门限）才快照，否则一按 PTT
         * 就先把增益顶到上限等着，开口第一下必炸。 */
        /* ★ 瞬态复位：按当前增益算，输出会明显超过目标（>6 dB），就不走 slew，
         * 直接用**当前帧**的 rms 反算增益并按上去。
         *
         * 为什么不等等 slew 慢慢降：静音期增益可能已经被顶到 AGC_MAX，
         * 开口那一帧的 rmsAvg 还停在低位，want 算出来仍然偏大，按 0.35 的
         * 系数要 7~8 帧（140~160 ms）才收敛（v2.4.6 已把压增益的系数提到 0.55）。
         * 这段的输出比正常高 12~14 dB，对方听到的就是「一开口特别冲」。
         *
         * 为什么用当前帧 rms 而不是 rmsAvg：rmsAvg 是平滑值，这时候还挂着
         * 静音期的小数，拿它算 want 依然偏高 —— 复位了等于没复位。
         * 用瞬时值会略被爆破音带偏（偏轻一点），比过冲好得多。 */
        final boolean jump = (agcFrames == 1 && rmsAvg > AGC_GATE_RMS)
                || (rms > AGC_GATE_RMS && agcGain * rms > AGC_TARGET_RMS * AGC_JUMP_OUT);

        if (jump) {
            if (agcFrames == 1 && rmsAvg > AGC_GATE_RMS) {
                agcGain = want;            // 首帧：用平滑值，这一帧就是基准
            } else {
                float w = AGC_TARGET_RMS / rms;
                if (w > AGC_MAX) w = AGC_MAX;
                else if (w < AGC_MIN) w = AGC_MIN;
                agcGain = w;
            }
        } else {
            /* ★ 压增益要快、提增益要慢 —— v2.0 这里是反的。
             *
             * 原来写 `(want > agcGain) ? 0.30f : 0.06f`：需要**提**增益
             * （信号变轻）时快，需要**压**增益（信号变响）时慢。
             * 后果是声音突然变大时增益压不下来 —— 仿真里超出稳态
             * 10~14 dB 并持续 300 ms，听感就是「一开口发炸」，
             * 而且这段会被软限幅狠狠压平，AMBE 编出来更糊。
             *
             * 反过来才对：信号变响 → 快速压下去（≈50 ms），
             * 信号变轻 → 慢慢提上来（≈250 ms，免得把底噪抽上来）。
             * 提得慢这件事本来也不吃亏：电平检测器已经做成「上升快、
             * 下降慢」，它自己就记住了说话的峰值电平。 */
            float k = (want < agcGain) ? 0.55f : 0.08f;
            agcGain += (want - agcGain) * k;
        }

        /* ---- AGC 自述：把内部状态记下来，发射结束后打给用户看 ----
         *
         * ★ 必须记在**天花板之前**。夹完再记的话，输出恒 ≤ 目标 +6 dB，
         * 那行「⚠ 压不住」就永远不会出现 —— 统计成了恒真命题，等于没测。
         * 记夹之前的值，才能看出「AGC 本来打算放多大」：
         * 数值 > 6 dB 说明这一帧确实被硬夹住了，也就是静音期增益真的被顶高了。
         *
         * 这段不改任何音频，只做统计。它的意义在于：下次「对方说还炸」时，
         * 不用再猜是「开口冲」还是「稳态轻」—— 两个数直接摊在屏幕上。 */
        if (rms > AGC_GATE_RMS) {
            if (st.agcFirst < 0f) st.agcFirst = agcGain;
            if (agcGain < st.agcLo) st.agcLo = agcGain;
            if (agcGain > st.agcHi) st.agcHi = agcGain;
            final float ratio = agcGain * rms / AGC_TARGET_RMS;
            final float over = 20f * (float) Math.log10(Math.max(ratio, 1e-6f));
            if (over > st.overDb) st.overDb = over;
            st.steadySum += dbOfRms(agcGain * rms);
            st.steadyN++;
        }

        /* ★ 无条件天花板（v2.4.6）—— 见 {@link #AGC_CEIL}。
         *
         * jump 只在「会超出目标 6 dB」时触发，slew 每帧只走 55%，
         * 两条路都可能漏一帧。这里不再判断来路，直接按当前帧的实测 rms
         * 算一个输出增益上限，把过冲从「大概率不发生」变成「有界」。
         *
         * ★ 只夹**这一帧的输出**，不改写 agcGain。
         *
         * 一开始写的是 `agcGain = cap`，仿真里立刻看出问题：爆破音那一帧
         * 被硬压到 0.4，下一帧回到正常电平，可增益要按 0.08 的系数爬回来
         * —— 要 250 ms 才追上。听感就是「每说一个字抽一下气」。
         *
         * 夹输出则没有这个代价：agcGain 照它自己的慢轨迹继续收敛，
         * 天花板只是拦在出口上。过冲被挡住，恢复速度一点没变。 */
        float outGain = agcGain;
        if (rms > AGC_GATE_RMS) {
            float cap = AGC_TARGET_RMS * AGC_CEIL / rms;
            if (cap < AGC_MIN) cap = AGC_MIN;
            if (outGain > cap) outGain = cap;
        }

        /* ★ 用户增益并进同一个乘数，而且必须在限幅**之前**。
         *
         * v2.4.2 是「先限幅、再乘用户增益」，用户把增益拉到 −12 dB
         * 只听到「好了一点但不够」—— 因为限幅产生的谐波已经烙在信号里，
         * 再整体乘小只是把「炸」调小，毛刺一个没少。
         *
         * 并进这个乘数之后，调小增益 = 送进限幅的信号本来就小 = 限幅不介入
         * = 失真跟着消失。这才是滑块该有的行为。 */
        final float g = outGain * micGain;

        /* 总增益基本是 1 倍就别白算一遍。注意判的是**乘积**而不是 agcGain ——
         * 只判 agcGain 的话，agcGain≈1 且用户增益 −12 dB 时会直接跳过，
         * 用户增益整个失效。 */
        if (g > 0.999f && g < 1.002f) {
            st.limSamples += pcm.length;
            return;
        }

        final float KNEE = 30146f;       // 0.92 × 32768
        final float SPAN = 2622f;        // 到满量程的余量
        for (int i = 0; i < pcm.length; i++) {
            float x = pcm[i] * g;
            float a = x < 0 ? -x : x;
            if (a > KNEE) {
                /* 软限幅介入。这个计数就是「我们后级有没有把信号搞炸」的
                 * 直接证据 —— 它和采集阶段的 inClip 一起看才能分清责任。 */
                st.limHits++;
                float over = (a - KNEE) / SPAN;
                a = KNEE + SPAN * (1f - (float) Math.exp(-over));
                x = x < 0 ? -a : a;
            }
            int v = (int) x;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
        }
        st.limSamples += pcm.length;
    }

    /* ---------------- 噪声门（下行扩展器） ----------------
     *
     * 这是 v2.0 缺的那一级，也是「听感不干净」的直接来源。
     *
     * 链条是这样坏的：用户轻声说话 → AGC 判出 want = 7.4× → 增益 7.4 倍
     * 应用到整段信号上，**包括词与词之间的麦克风本底噪声**。噪声底 −65 dBFS
     * 抬到 −48 dBFS 送进声码器。AMBE+2 只有 2.45 kbps，它不区分「这是噪声」，
     * 照样分配比特去编码，结果：
     *   · 静音段被还原成一片嘶嘶声（声码器对无音帧本来就输出带通噪声）；
     *   · 说话时有效比特被噪声分走，字咬不清。
     * 商用 DMR 机在声码器前都有一级降噪 / 扩展器，这一版补上。
     *
     * 三个设计点，每个都对着一种失败形态：
     *   · **放在 AGC 之后**。放前面的话 AGC 会把它压掉的噪声原样再提回来，
     *     等于白做 —— 这是这类实现最常见的错。
     *   · **保持窗 320 ms**。没有保持窗，词间低谷会让门反复开合，
     *     听感是「吃字」；声母被削掉比有底噪难懂得多。
     *   · **只压 18 dB，不静音**。全静音像断线，而且门一开一关会有喘息。
     *     18 dB 足够把底噪压到声码器的噪声门限以下。
     *
     * 门限用**处理前**的 frameRms 判断：那是增益无关的量，AGC 怎么变
     * 都不影响「这一帧到底是人声还是噪声」的判断；而压多少是个比值，
     * 直接作用在 AGC 之后的信号上，两者互不干扰。
     */
    private float ngFloor = 0f;      // 噪声底估计（处理前电平）
    private float ngGain = 1.0f;     // 当前门增益
    private float ngPrev = 1.0f;     // 上一块的门增益，块内线性过渡用
    private int   ngHold = 0;        // 保持窗剩余帧数

    /** 门限 = 噪声底 + 9 dB。再高会开始吃轻声的声母 */
    private static final float NG_THR_MULT = 2.8f;
    /** 保持窗 30 帧 × 20 ms = 600 ms。
     *
     *  v2.1 最初是 16 帧（320 ms），**太短了**：真实说话词与词之间常有 300~500 ms
     *  的停顿，保持窗一过门就关，下一个词的声母直接被压掉 —— 声码器把那截编成
     *  静音，对方听到的就是「断续」。600 ms 覆盖绝大多数词间停顿，
     *  只有真正的换气/句末才会关门，而噪声抑制本来就只需要在这些地方生效。 */
    private static final int NG_HOLD_FRAMES = 30;
    /** 关门 ≈ 200 ms。慢到不会切掉词尾的气音 */
    private static final float NG_CLOSE = 0.10f;
    /** 最大衰减 7 dB。
     *
     *  <p>★ v2.4.7 从 14 dB 降到这里。原来那句注释写「再多就不像安静而像断线」，
     *  方向是对的，但 14 dB 本身就已经越线了 —— 对方听完三段录音的原话是
     *  「像开启了降噪的」。
     *
     *  <p>实测（11:42 三段录音，按停顿长短分组看底噪）：
     *  短停顿（<200 ms，门还没关）底噪稳定在 −50 dBFS；长停顿掉到 −62~−74。
     *  也就是说门关到底时压掉了 12~24 dB，而我们的上限只有 14 dB。
     *
     *  <p>多出来的那 10 dB 不是我们的：是 **AMBE 声码器自己的静音检测**。
     *  两级是级联的 —— 我们先把信号压到 −64，这个电平已经低于声码器的静音判据，
     *  它就跟着切到静音帧。所以真正要做的不是「少压一点」，而是
     *  <b>别把信号压进声码器的静音门里</b>。7 dB 让长停顿停在 −57 附近，
     *  仍在声码器的「有声音」判据之上。 */
    private static final float NG_MAX_CUT_DB = 7f;

    private void noiseGate(short[] pcm, float frameRms) {
        /* 噪声底：下降快跟（0.35 ≈ 60 ms），上升极慢（0.0004 ≈ 50 s）。
         * 上限锁在 AGC 语音电平估计的 30% —— 真实底噪总比语音低 10 dB 以上。
         * 没有这个上限的话，一段一直不停顿的长讲话会让估计值一路爬到
         * 语音电平上，门就永远不关了（这是降噪实现里最常见的退化）。 */
        float cap = rmsAvg * 0.30f;
        if (cap < 1f) cap = 1f;
        if (ngFloor < 1f) ngFloor = Math.max(frameRms, 1f);
        else if (frameRms < ngFloor) ngFloor += (frameRms - ngFloor) * 0.35f;
        else ngFloor += (frameRms - ngFloor) * 0.0004f;
        if (ngFloor > cap) ngFloor = cap;

        float thr = ngFloor * NG_THR_MULT;

        float want = 1.0f;
        if (frameRms >= thr) {
            ngHold = NG_HOLD_FRAMES;          // 有声音，开门并续期
        } else if (ngHold > 0) {
            ngHold--;                         // 保持期内继续全开
        } else if (frameRms > 1f) {
            float cutDb = 20f * (float) Math.log10(thr / frameRms);
            if (cutDb > NG_MAX_CUT_DB) cutDb = NG_MAX_CUT_DB;
            want = (float) Math.pow(10.0, -cutDb / 20.0);
        }

        /* ★ 开门是硬开，关门才平滑。
         *
         * 原来开门用 0.55 的系数，从 −14 dB 回到满增益要 ~70 ms ——
         * 也就是**每个词的开头 70 ms 都被压着**。而声母（b/p/d/t/g/k）
         * 本身就集中在头 30~60 ms，正好全被吃掉。声码器拿到一截几乎静音的
         * 起音，会把它判成无声帧，对方听到的就是「字头没了、说话断续」。
         *
         * 硬开不会产生咔声：关门只发生在底噪上，那一刻的绝对电平已经很低
         * （−55 dBFS × 0.2 ≈ −69 dBFS），从这么小的电平跳到语音的真实起音，
         * 听到的就是起音本身，不是阶跃。 */
        if (want > ngGain) {
            ngGain = want;
            ngPrev = want;                    // 连块内渐变也跳过，整块全开
        } else {
            ngGain += (want - ngGain) * NG_CLOSE;
        }

        /* 两块的增益都基本是 1 就别白算一遍 */
        if (ngGain > 0.999f && ngPrev > 0.999f) { ngPrev = ngGain; return; }

        /* 块内线性过渡：直接从上一块的增益跳到这一块，会留下阶跃 —— 那是「咔」 */
        final float step = (ngGain - ngPrev) / pcm.length;
        float g = ngPrev;
        for (int i = 0; i < pcm.length; i++) {
            int v = (int) (pcm[i] * g);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
            g += step;
        }
        ngPrev = ngGain;
    }

    /** 一帧的 RMS。噪声门 / AGC / 电平条三处都用它，避免各算各的。 */
    private static float rmsOf(short[] pcm) {
        long sum = 0;
        for (short s : pcm) sum += (long) s * s;
        return (float) Math.sqrt(sum / (double) pcm.length);
    }

    private static int levelOf(short[] pcm) {
        return levelOfRms(rmsOf(pcm));
    }

    /** RMS → 0..100 的电平条刻度。经验缩放，满量程约 100。 */
    private static int levelOfRms(float rms) {
        int lv = (int) (rms / 32768.0 * 400);
        return Math.max(0, Math.min(100, lv));
    }

    /** RMS → dBFS。−120 表示完全静音（log10(0) 没有意义）。 */
    private static float dbOfRms(float rms) {
        if (rms <= 0.5f) return -120f;
        return (float) (20.0 * Math.log10(rms / 32768.0));
    }

    /** 码字是否全为 0。用来发现「有信号却编出空码字」这类静默故障。 */
    private static boolean isAllZero(byte[] a) {
        if (a == null) return true;
        for (byte b : a) if (b != 0) return false;
        return true;
    }

    /* ---------------- 采集阶段的削波统计 ---------------- */

    /**
     * 数一数这批原始采样里有多少已经贴着满量程。
     *
     * <p>这是整条链上**唯一**能区分「手机 MIC 自己过载」和「我们后级把信号
     * 搞炸」的测量点，因为它是 `AudioRecord.read()` 一出来、任何处理之前
     * 的状态。一旦在这里就削平了，后面的 AGC、限幅、降噪全都无能为力 ——
     * 削掉的波形信息不可能凭空长回来。
     *
     * <p>两种情况的日志长得一模一样（都是「对方说发炸」），
     * 只有这个比例能分辨，而处置方式完全相反：
     * <ul>
     *   <li>这里高 → 让用户离话筒远一点、说话轻一点；调增益没用</li>
     *   <li>这里低而 {@code limHits} 高 → 是我们后级太激进，调小增益有效</li>
     * </ul>
     */
    private static int countNearFull(short[] a, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            int v = a[i];
            if (v >= CLIP_LEVEL || v <= -CLIP_LEVEL) c++;
        }
        return c;
    }

    /**
     * 窗函数法设计低通 FIR。用于 48 kHz → 8 kHz 抽取前的抗混叠，
     * 截止取 3.4 kHz（DMR 话音频带上限）。
     */
    private static float[] designLowPass(int taps, double cutoffNorm) {
        float[] h = new float[taps];
        int m = taps - 1;
        double sum = 0;

        for (int i = 0; i < taps; i++) {
            double x = i - m / 2.0;
            double sinc;
            if (Math.abs(x) < 1e-9) {
                sinc = 2 * cutoffNorm;
            } else {
                sinc = Math.sin(2 * Math.PI * cutoffNorm * x) / (Math.PI * x);
            }
            /* Hamming 窗，不是 Hann。
             *
             * 两者主瓣宽度差不多（过渡带都约 3.2·fs/N），但阻带差 12 dB：
             * Hann 只有 −31 dB，Hamming 有 −43 dB。这条通道的任务是压住
             * 4 kHz 以上、别让它折叠回话带 —— 手机 MIC 在 4–8 kHz 的能量
             * 一点不少，−31 dB 意味着折叠回来的混叠只比原信号低 31 dB，
             * 落在 1–4 kHz 里就是一层持续的「沙沙」，而这一段正是可懂度
             * 核心区。多花 0 dB 算力换 12 dB 混叠抑制，很划算。 */
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / m);   // Hamming 窗
            h[i] = (float) (sinc * w);
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) h[i] /= (float) sum;
        return h;
    }

    /** FIR 低通 + 抽取。
     *
     *  hist 保存上一次调用末尾的 taps 个样本，用来补卷积的左边界，
     *  这样跨块拼接处不会出现阶跃。 */
    private static void decimate(short[] in, int inLen, short[] out,
                                 float[] fir, float[] hist, int decim) {
        final int taps = fir.length;
        int o = 0;

        for (int i = decim - 1; i < inLen && o < FRAME_SAMPLES; i += decim) {
            float acc = 0f;
            for (int k = 0; k < taps; k++) {
                int idx = i - k;
                // idx < 0 时从历史尾部取：idx = -1 对应 hist[taps-1]
                float v = (idx >= 0) ? in[idx] : hist[taps + idx];
                acc += fir[k] * v;
            }
            if (acc > 32767f) acc = 32767f;
            if (acc < -32768f) acc = -32768f;
            out[o++] = (short) acc;
        }

        // 不足一帧时后面补零，保证 out 始终写满
        while (o < FRAME_SAMPLES) out[o++] = 0;

        // 滚动历史：保留 in 末尾的 taps 个样本
        for (int k = 0; k < taps; k++) {
            int idx = inLen - taps + k;
            hist[k] = (idx >= 0 && idx < inLen) ? in[idx] : 0f;
        }
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }
}
