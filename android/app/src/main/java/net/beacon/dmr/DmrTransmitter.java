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

    public interface Listener {
        void onTxStart(int dstId);

        void onTxStop(String reason);

        /** 输入电平 0..100，用于界面上的电平条 */
        void onLevel(int level);

        void onLog(String line);
    }

    private final Listener listener;

    private volatile boolean transmitting;
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

        /* 连续编码失败计数，用来只在第一次失败时写一条日志，不刷屏 */
        int encodeFail = 0;

        float[] fir = resample ? designLowPass(FIR_TAPS, 3200.0 / rate) : null;
        float[] hist = resample ? new float[FIR_TAPS] : null;

        long startedAt = System.currentTimeMillis();

        /* ---- 发射健康度 ----
         * 「话音断续」最常见的原因不是 DSP 算法，而是**线程跟不上音频时钟**：
         * 每 20 ms 必须交出一帧，一旦某一帧的处理超过 20 ms，积压就开始了，
         * AudioRecord 的缓冲被填满后直接覆盖未读采样 —— 丢的是麦克风侧音频，
         * 网络上却一个包都不丢，极难从外部判断。
         *
         * 这里量的是**读完之后**的纯处理耗时（DSP + 声码器编码），不含阻塞读，
         * 每 5 秒报一次。峰值一旦逼近 20 ms 就是 CPU 瓶颈，跟算法无关。 */
        long procMaxMs = 0, procSumMs = 0;
        int procFrames = 0;
        long lastReport = startedAt;

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

            int got = 0;
            while (got < readPerFrame && transmitting) {
                int n = recorder.read(raw, got, readPerFrame - got);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        if (listener != null) listener.onTxStop("录音读取错误 " + n);
                        transmitting = false;
                    }
                    break;
                }
                got += n;
            }
            if (!transmitting) break;
            if (got < readPerFrame) continue;

            final long procT0 = System.nanoTime();

            if (resample) {
                decimate(raw, readPerFrame, pcm, fir, hist, decim);
            } else {
                System.arraycopy(raw, 0, pcm, 0, FRAME_SAMPLES);
            }

            /* 链序：高通 → 高频补偿 → 量电平 → AGC。
             *
             * 「量电平」单独抽出来，是因为 AGC 必须知道**处理前**这一帧有多响：
             * 拿处理后的结果去反推增益，高通和 EQ 的衰减会被算进「信号很轻」里，
             * 增益就会一路偏高。 */
            highPass(pcm);   /* 切掉 280 Hz 以下：低频是 AMBE 最大的比特黑洞 */
            presence(pcm);   /* 2 kHz +3 dB：可懂度核心区，补回被低频挤掉的辅音能量 */
            final float frameRms = rmsOf(pcm);
            agc(pcm, frameRms);   /* 电平归一化，MIC 源各机差异太大 */
            noiseGate(pcm, frameRms);  /* ★ 必须放在 AGC 之后 */

            if (listener != null) listener.onLevel(levelOf(pcm));

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
                encodeFail++;
                if (encodeFail == 1) log("声码器编码失败，沿用上一帧");
            } else {
                encodeFail = 0;
            }

            /* ---- 健康度统计：纯处理耗时（不含阻塞读） ---- */
            long procMs = (System.nanoTime() - procT0) / 1_000_000L;
            procSumMs += procMs;
            procFrames++;
            if (procMs > procMaxMs) procMaxMs = procMs;
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastReport >= 5000) {
                float avg = procFrames > 0 ? (float) procSumMs / procFrames : 0f;
                /* 20 ms 是每帧的预算。峰值超过预算 = 线程跟不上音频时钟，
                 * 积压 → AudioRecord 覆盖未读采样 → 对方听到「断续」。 */
                String verdict = (procMaxMs >= 20) ? "  ⚠ 超过 20ms 预算，会积压"
                        : (procMaxMs >= 14 ? "  （接近预算）" : "");
                log(String.format(java.util.Locale.US,
                        "发射健康：%d 帧，处理 平均 %.1f ms / 峰值 %d ms（预算 20 ms）%s",
                        procFrames, avg, procMaxMs, verdict));
                procMaxMs = 0;
                procSumMs = 0;
                procFrames = 0;
                lastReport = nowMs;
            }

            System.arraycopy(ambe, 0, bundle, bundleFill, Vocoder.CODEC_BYTES);
            bundleFill += Vocoder.CODEC_BYTES;

            if (bundleFill >= DmrTx.AMBE_BUNDLE) {
                bundleFill = 0;
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

        if (transmitting) {
            // 由循环内部条件跳出，补一次收尾
            transmitting = false;
            if (listener != null) listener.onTxStop("异常中断");
        }
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
    /** 目标 RMS，约 −16 dBFS。
     *
     *  v2.0 用的是 4000（−18.3 dBFS）。调高到 5200 的依据来自声码器自检：
     *  vocoder_jni.cpp 的 makeSpeechLike() 把测试信号归一化到**峰值 24000**，
     *  注释写的是「留 3 dB 余量，避免定点分析前端溢出」—— 那是这个 IMBE
     *  定点前端验证过的工作点。按语音 12 dB 峰值因数反推，RMS 4000 对应
     *  峰值约 15900，比工作点低 4 dB：定点前端的有效位没用满，量化噪声占比
     *  偏高，听感就是「闷、糊」。5200 对应峰值约 20800（−3.9 dBFS），
     *  仍在软限幅拐点（−0.7 dBFS）之下，既吃满分辨率又不削波。 */
    private static final float AGC_TARGET_RMS = 5200f;
    /** 高通砍掉 14 dB 低频能量后，需要更大的补偿量才追得回来 */
    private static final float AGC_MAX = 8.0f;        // 最多提 18 dB
    /** 允许的衰减量。只提不压的话，MIC 电平偏高的机器会一路顶到满量程 ——
     *  旧代码就是这样，正常说话就能削掉几百个样本。0.3 = 最多压 10 dB，
     *  普通说话（贴着目标电平）增益仍稳定在 1 倍，动态不受影响。 */
    private static final float AGC_MIN = 0.3f;
    private static final float AGC_GATE_RMS = 100f;   // −50 dBFS 以下当静音
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
    private void agc(short[] pcm, float frameRms) {
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
         * 就先把增益顶到 8× 等着，开口第一下必炸。 */
        if (agcFrames == 1 && rmsAvg > AGC_GATE_RMS) {
            agcGain = want;
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
            float k = (want < agcGain) ? 0.35f : 0.08f;
            agcGain += (want - agcGain) * k;
        }

        if (agcGain > 0.99f && agcGain < 1.02f) return;   // 基本是 1 倍就别白算一遍

        final float KNEE = 30146f;       // 0.92 × 32768
        final float SPAN = 2622f;        // 到满量程的余量
        for (int i = 0; i < pcm.length; i++) {
            float x = pcm[i] * agcGain;
            float a = x < 0 ? -x : x;
            if (a > KNEE) {
                float over = (a - KNEE) / SPAN;
                a = KNEE + SPAN * (1f - (float) Math.exp(-over));
                x = x < 0 ? -a : a;
            }
            int v = (int) x;
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
        }
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
    /** 最大衰减 14 dB。再多就不像「安静」而像「断线」了 */
    private static final float NG_MAX_CUT_DB = 14f;

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
        int lv = (int) (rmsOf(pcm) / 32768.0 * 400);      // 经验缩放，满量程约 100
        return Math.max(0, Math.min(100, lv));
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
