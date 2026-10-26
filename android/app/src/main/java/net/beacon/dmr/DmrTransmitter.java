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

        float[] fir = resample ? designLowPass(31, 3400.0 / rate) : null;
        float[] hist = resample ? new float[31] : null;

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
