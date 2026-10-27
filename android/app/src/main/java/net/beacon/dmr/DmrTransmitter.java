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
        int bufBytes = Math.max(minBuf, FRAME_SAMPLES * 2 * 32);

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

        long startedAt = System.currentTimeMillis();

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

            if (resample) {
                decimate(raw, readPerFrame, pcm, fir, hist, decim);
            } else {
                System.arraycopy(raw, 0, pcm, 0, FRAME_SAMPLES);
            }

            highPass(pcm);   /* 切掉 280 Hz 以下：低频是 AMBE 最大的比特黑洞 */
            presence(pcm);   /* 2.6 kHz +2 dB：补回被低频挤掉的辅音能量 */
            agc(pcm);        /* 电平归一化，MIC 源各机差异太大 */

            if (listener != null) listener.onLevel(levelOf(pcm));

            if (Vocoder.nativeEncode(pcm, ambe) != Vocoder.CODEC_BYTES) {
                log("声码器编码失败，跳过该帧");
                continue;
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

    /** 高频补偿（RBJ peaking EQ）。AMBE 在低频过载时最先牺牲的是
     *  2–3 kHz 的辅音能量，而可懂度几乎全靠这一段。+2 dB 是保守值：
     *  再多 s/sh 会发刺，声码器带宽也只到 3400 Hz。 */
    private static final double PK_FREQ_HZ = 2600.0;
    private static final double PK_Q = 0.8;
    private static final double PK_GAIN_DB = 2.0;

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
    /** 目标 RMS，约 −18 dBFS。原来按峰值 20000（−4 dBFS）归一，
     *  峰值被爆破音和残余低频主导，语音主体实际只有 −20 dBFS 左右，
     *  声码器拿到的信号偏轻，量化噪声占比上升，也是「糊」的一个来源。 */
    private static final float AGC_TARGET_RMS = 4000f;
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

    /** 电平归一化（不是压缩器）。
     *
     *  手机 MIC 源的电平差异能到 20 dB：同样正常说话，有的设备 RMS 800，
     *  有的 8000。AMBE 对输入电平很敏感，太轻的输入编出来又闷又糊。
     *
     *  三个设计点，每个都对应一种失败形态：
     *    · **以只提不压为主，超出目标 10 dB 才压** —— 压会损失动态，
     *      语音变平、辨识度下降；但不压的话电平高的机器会顶穿满量程，
     *      旧代码在正常说话时就能削掉几百个样本；
     *    · **RMS 平滑到 1 s** —— 逐帧 RMS 跟着音节包络（3~5 Hz）抖，
     *      增益跟着抖就是「抽气」，这是 AGC 最典型的失败；
     *    · **限幅放在输出端，不进增益环** —— 用瞬时峰值去压增益，
     *      一个爆破音就把整句电平拉下来，下一帧再抬回去，来回摆 10 dB。
     *      改成增益只管慢电平，峰值交给软限幅（0.92 以上才动手），
     *      两者互不干扰。
     */
    private void agc(short[] pcm) {
        double sum = 0;
        for (int i = 0; i < pcm.length; i++) {
            int s = pcm[i];
            sum += (double) s * s;
        }
        float rms = (float) Math.sqrt(sum / pcm.length);

        if (agcFrames < 1) {
            rmsAvg = rms;                                   // 首帧直接对齐
        } else {
            float a = (agcFrames < AGC_FAST_FRAMES) ? AGC_FAST : AGC_SLOW;
            rmsAvg += (rms - rmsAvg) * a;
        }
        agcFrames++;

        float want = 1.0f;
        if (rmsAvg > AGC_GATE_RMS) {
            want = AGC_TARGET_RMS / rmsAvg;
            if (want > AGC_MAX) want = AGC_MAX;
            else if (want < AGC_MIN) want = AGC_MIN;
        }

        /* 提升稍快（60 ms），回落慢（330 ms）—— 句首立刻起来，句尾不「抽气」 */
        float k = (want > agcGain) ? 0.30f : 0.06f;
        agcGain += (want - agcGain) * k;

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

    private static int levelOf(short[] pcm) {
        long sum = 0;
        for (short s : pcm) sum += (long) s * s;
        double rms = Math.sqrt(sum / (double) pcm.length);
        int lv = (int) (rms / 32768.0 * 400);      // 经验缩放，满量程约 100
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
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / m);   // Hann 窗
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
