package net.beacon.dmr;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * DMR 接收音频播放：抖动缓冲 + AudioTrack。
 *
 * <p>主站每 60 ms 推来一个语音包，每个包解出 480 个 8 kHz 采样。网络抖动会让包
 * 时快时慢，直接写 AudioTrack 就会一顿一顿。所以中间垫一层缓冲：
 *
 * <pre>
 *   DmrRx.decode() ──► feed() ──► [队列] ──► 写线程 ──► AudioTrack
 *                                    ↑
 *                          先攒够 jitterFrames 再开声
 * </pre>
 *
 * <p><b>预填充是抖动缓冲的全部意义</b>：第一个包到达时先不出声，攒到
 * {@code jitterMs} 那么厚再开始播。攒得越厚越抗抖动，代价是延迟越大 ——
 * 参考设备上那个「网络不稳时加长接收缓冲」就是调这个值。
 *
 * <p>一句话播完（360 ms 没有新包）就复位，下一句重新预填充，
 * 免得上一句的余音和下一句的首字粘在一起。
 *
 * <p>音量在这里做，而不是在 {@link DmrRx} —— 音量要能实时改，
 * 而解码结果是一次性的。增益允许 &gt; 1（弱信号听得见），代价是可能削顶。
 */
public final class DmrAudio {

    private static final String TAG = "DmrAudio";

    private static final int SAMPLE_RATE = 8000;
    /** 一个包的采样数 */
    private static final int FRAME_SAMPLES = DmrRx.PCM_PER_BUNDLE;   // 480
    /** 队列容量（包数）。200 × 60 ms = 12 秒，远超任何合理抖动 */
    private static final int QUEUE_PACKETS = 200;
    /** 多久没新包就算一句说完（毫秒） */
    private static final int GAP_MS = 360;

    public interface Listener {
        /** 开始播一句（抖动缓冲刚攒够） */
        void onPlayStart();

        /** 一句播完、缓冲复位 */
        void onPlayStop();

        /** 播放电平 0~1，已节流 */
        void onLevel(float level);
    }

    private final Listener listener;

    /** 是否播放（对讲页的「接收」开关） */
    private volatile boolean enabled = true;
    /** 线性增益，0~2。1 表示原样 */
    private volatile float gain = 1.0f;
    /** 抖动缓冲厚度（毫秒） */
    private volatile int jitterMs = 240;

    private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(QUEUE_PACKETS);
    private final List<short[]> prefill = new ArrayList<>();

    private volatile boolean running;
    private Thread thread;
    /** volatile：flush() 可能从界面线程调进来，而写线程正拿着它 */
    private volatile AudioTrack track;
    private volatile boolean playing;

    /** 写线程内的临时缓冲（音量缩放用），只有写线程碰 */
    private short[] scratch;

    private long lastLevelAt;
    private float peakSinceLevel;

    public DmrAudio(Listener listener) {
        this.listener = listener;
    }

    /* ---------------- 配置 ---------------- */

    public void setEnabled(boolean on) {
        this.enabled = on;
        if (!on) flush();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @param g 线性增益，内部夹到 0~2 */
    public void setGain(float g) {
        this.gain = Math.max(0.0f, Math.min(2.0f, g));
    }

    public float getGain() {
        return gain;
    }

    /** @param ms 抖动缓冲厚度，内部夹到 0~600；0 表示不缓冲（最容易卡顿） */
    public void setJitterMs(int ms) {
        this.jitterMs = Math.max(0, Math.min(600, ms));
    }

    public int getJitterMs() {
        return jitterMs;
    }

    public boolean isPlaying() {
        return playing;
    }

    /* ---------------- 生命周期 ---------------- */

    public void start() {
        if (running) return;
        running = true;
        queue.clear();
        prefill.clear();
        scratch = new short[FRAME_SAMPLES];

        thread = new Thread(this::loop, "dmr-rx-audio");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
            }
        }
        stopPlayback();
        queue.clear();
        prefill.clear();
    }

    /** 丢掉当前缓冲并停声 —— 发射时用，避免听到自己绕网络回来的声音 */
    public void flush() {
        queue.clear();
        prefill.clear();
        stopPlayback();
    }

    /**
     * 喂一个解好的包。非阻塞：队列满了就丢最旧的 —— 宁可丢一句也不要卡住接收线程。
     *
     * <p><b>调用方交出所有权</b>：当 {@code len == pcm.length} 时直接入队不拷贝，
     * 所以不要复用传进来的数组（{@link DmrEngine} 每个包新分配一份）。
     */
    public void feed(short[] pcm, int len) {
        if (!enabled || !running || pcm == null || len <= 0) return;
        short[] copy;
        if (len == pcm.length) {
            copy = pcm;
        } else {
            copy = new short[len];
            System.arraycopy(pcm, 0, copy, 0, len);
        }
        if (!queue.offer(copy)) {
            /* 队列满：接收比播放快太多，说明播放线程卡了。丢最旧的腾位置 */
            queue.poll();
            queue.offer(copy);
        }
    }

    /* ---------------- 写线程 ---------------- */

    private void loop() {
        int silentTicks = 0;
        final int tickMs = 60;
        final int gapTicks = Math.max(1, (GAP_MS + tickMs - 1) / tickMs);

        while (running) {
            /* ★ 整个循环体必须包在 try 里。
             *
             * 这个线程是接收链唯一的消费者：它一死，queue 只进不出，
             * 界面还停在「正在接收」，但永远不会有声音 —— 而且没有任何
             * 报错，用户只能杀进程重启。所以这里宁可吞掉异常、复位后继续，
             * 也不能让异常把线程带走。 */
            try {
                short[] f;
                try {
                    f = queue.poll(tickMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) break;

                if (f == null) {
                    silentTicks++;
                    if (playing && silentTicks >= gapTicks) stopPlayback();
                    continue;
                }
                silentTicks = 0;

                if (!playing) {
                    prefill.add(f);
                    int need = Math.max(1, (jitterMs + 59) / 60);
                    if (prefill.size() >= need) startPlayback();
                    continue;
                }
                writeFrame(f);
            } catch (Throwable t) {
                Log.w(TAG, "接收播放线程异常，已复位继续（不让线程退出）", t);
                try {
                    stopPlayback();
                } catch (Throwable ignored) {
                }
                prefill.clear();
                queue.clear();
            }
        }
    }

    private void startPlayback() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = FRAME_SAMPLES * 2 * 4;
        /* 缓冲取「最小可用」与「抖动厚度 + 一个包」里的较大者：
         * 太小会断音，太大会白加延迟 —— 延迟已经由上面的预填充付过了。 */
        int want = (Math.max(1, (jitterMs + 59) / 60) + 1) * FRAME_SAMPLES * 2;
        int bufBytes = Math.max(minBuf, want);

        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable e) {
            Log.w(TAG, "AudioTrack 创建失败：" + e);
            prefill.clear();
            return;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack 未初始化，放弃这次播放");
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            prefill.clear();
            return;
        }

        track = t;
        try {
            t.play();
        } catch (Throwable e) {
            Log.w(TAG, "AudioTrack.play 失败：" + e);
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            track = null;
            prefill.clear();
            return;
        }

        playing = true;
        peakSinceLevel = 0f;
        if (listener != null) listener.onPlayStart();

        /* 先把预填充内容搬进局部数组再写：writeFrame 里可能因为
         * AudioTrack 状态异常而调用 stopPlayback()，那会把 prefill 清空 ——
         * 直接 for-each 遍历 prefill 会撞上 ConcurrentModificationException。 */
        short[][] items = prefill.toArray(new short[0][]);
        prefill.clear();
        for (short[] f : items) writeFrame(f);
    }

    private void stopPlayback() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
        boolean was = playing;
        playing = false;
        prefill.clear();
        if (was && listener != null) listener.onPlayStop();
    }

    private void writeFrame(short[] f) {
        AudioTrack t = track;
        if (t == null) return;

        /* ★ AudioTrack 会被系统「悄悄」停掉：来电、音频焦点被别的应用抢走、
         * 蓝牙耳机切换、音频 HAL 重启。这时 write() 往往仍然返回成功，
         * 但一点声音都没有 —— 表现就是「界面显示在接收，但听不见」。
         * 所以每帧查一次播放状态，不是 PLAYING 就重建。
         * 重建后 playing=false，下一句会重新预填充、重新开声。 */
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                Log.w(TAG, "AudioTrack 已停止（playState=" + t.getPlayState() + "），重建");
                stopPlayback();
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "AudioTrack 状态查询失败，重建：" + e);
            stopPlayback();
            return;
        }

        float g = gain;
        short[] buf = scratch;
        int n = Math.min(f.length, FRAME_SAMPLES);

        if (g == 1.0f || buf == null || buf.length < n) {
            buf = f;
        } else {
            for (int i = 0; i < n; i++) {
                int v = (int) (f[i] * g);
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                buf[i] = (short) v;
            }
        }

        try {
            /* write() 返回负值表示写入失败（ERROR_INVALID_OPERATION 等）。
             * 不检查的话就会一直「写进去但没声音」，所以这里也当成失效处理。 */
            int w = t.write(buf, 0, n);
            if (w < 0) {
                Log.w(TAG, "AudioTrack.write 返回 " + w + "，重建");
                stopPlayback();
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "AudioTrack.write 失败：" + e);
            stopPlayback();
            return;
        }

        /* 电平：给界面画个「正在接收」的条。用峰值而不是 RMS，
         * 因为语音的峰值更能反映「有人在说话」 */
        if (listener != null) {
            float peak = 0f;
            for (int i = 0; i < n; i++) {
                int a = buf[i] < 0 ? -buf[i] : buf[i];
                if (a > peak) peak = a;
            }
            float lv = Math.min(1f, peak / 32768f * 3.2f);
            if (lv > peakSinceLevel) peakSinceLevel = lv;
            long now = System.currentTimeMillis();
            if (now - lastLevelAt >= 100) {
                listener.onLevel(peakSinceLevel);
                peakSinceLevel = 0f;
                lastLevelAt = now;
            }
        }
    }
}
