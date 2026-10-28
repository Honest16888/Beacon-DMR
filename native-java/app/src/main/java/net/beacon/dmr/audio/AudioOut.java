package net.beacon.dmr.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

import net.beacon.dmr.core.MuLaw;

import java.util.ArrayDeque;

/**
 * 收听侧音频输出：8 kHz / 单声道 / 16 bit PCM，AudioTrack 直出。
 *
 * <p>这是纯 Java 版最关键的一块 —— WebView 版那套「8 kHz AudioContext + AudioWorklet
 * 环形缓冲 + 软削波 + 自动播放策略」在这里**全部不需要**：
 * <ul>
 *   <li>没有重采样：AudioTrack 本身就按 8000 Hz 建，喂什么放什么</li>
 *   <li>没有帧边界问题：写进去的 PCM 在同一个硬件流里是连续的</li>
 *   <li>没有自动播放策略：AudioTrack 不需要用户手势</li>
 * </ul>
 * 也就是说，之前五个版本反复修的「噼啪」「刷新后没声音」，在这个架构下
 * 根本不存在成因。
 *
 * <p>抖动缓冲：先用 {@link #PRE_ROLL} 帧垫底再开始写，网络抖一下不会断音；
 * 队列超过 {@link #MAX_QUEUE} 帧就丢最旧的 —— 宁可丢 20 ms，也不要让延迟
 * 越积越大（对讲最怕延迟）。
 *
 * <p>音量 / 增强 / 窄带增强都交给 {@link AudioChain}，写线程只管「取出帧、
 * 过一遍链路、写进 AudioTrack」。参数从主线程设，链路内部按样本斜坡过去，
 * 拖滑块不会「啪」一声。
 */
public final class AudioOut {

    /** 垫底帧数：4 帧 = 80 ms。足够吃掉常见的网络抖动，又不至于让人耳察觉延迟。 */
    private static final int PRE_ROLL = 4;
    /** 队列上限：25 帧 = 500 ms。超了丢最旧。 */
    private static final int MAX_QUEUE = 25;

    public interface LevelListener {
        /** 每次成功写出一帧回调一次，rms 已按满刻度归一化。 */
        void onLevel(float rms);
    }

    private AudioTrack track;
    private Thread writer;
    private volatile boolean running;

    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private final Object lock = new Object();

    /** 增益 / 滤波链。只在写线程上 process，参数是 volatile。 */
    private final AudioChain chain = new AudioChain();

    private volatile boolean muted = false;
    private volatile int jitterFrames = PRE_ROLL;

    private LevelListener levelCb;

    /** 丢帧计数：队列溢出、或者垫底期间被 flush。用来判断网络是否跟不上。 */
    private volatile long dropped = 0;
    private volatile long written = 0;

    public void setLevelListener(LevelListener l) { this.levelCb = l; }

    public synchronized boolean start() {
        if (running) return true;
        int minBuf = AudioTrack.getMinBufferSize(MuLaw.RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = MuLaw.FRAME * 2 * 8;          // 兜底：160 ms
        int bufBytes = Math.max(minBuf, MuLaw.FRAME * 2 * 8);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MuLaw.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            track = null;
            return false;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try { track.release(); } catch (Exception ignore) {}
            track = null;
            return false;
        }

        running = true;
        synchronized (lock) { queue.clear(); }
        /* 参数直接到位，不做斜坡：第一帧就该是用户设定的音量，
         * 否则每次重连都从 100% 爬过去，头半秒明显偏轻。 */
        chain.snap();
        track.play();

        writer = new Thread(this::loop, "audio-writer");
        writer.setDaemon(true);
        writer.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        Thread t = writer;
        writer = null;
        if (t != null) {
            try { t.join(400); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        }
        if (track != null) {
            try { track.pause(); } catch (Exception ignore) {}
            try { track.flush(); } catch (Exception ignore) {}
            try { track.release(); } catch (Exception ignore) {}
            track = null;
        }
        synchronized (lock) { queue.clear(); lock.notifyAll(); }
    }

    /** 喂一帧 PCM（160 样本）。队列满了丢最旧的。 */
    public void feed(short[] pcm, int len) {
        if (!running || len <= 0) return;
        if (muted) return;                       // 静音期间连队列都不进，恢复时不会放旧语音
        short[] copy = new short[len];
        System.arraycopy(pcm, 0, copy, 0, len);
        synchronized (lock) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(copy);
            lock.notifyAll();
        }
    }

    /** 丢掉已排队的帧。切话组、静音、断流恢复时用。 */
    public void flush() {
        synchronized (lock) { queue.clear(); lock.notifyAll(); }
        /* 滤波器里还留着上一段的尾巴，一起清掉，否则恢复瞬间会带出一个低频「噗」。 */
        chain.reset();
        AudioTrack t = track;
        if (t != null) { try { t.pause(); t.flush(); t.play(); } catch (Exception ignore) {} }
    }

    /** 收听音量 0~150（100 = 原始电平）。 */
    public void setVolume(int pct) { chain.setVolume(pct); }

    /** 音量增强 1 / 2 / 3。 */
    public void setBoost(int g) { chain.setGain(g); }

    /** 窄带增强（2.6 kHz +2.5 dB）。 */
    public void setPresence(boolean on) { chain.setPresence(on); }

    public void setMuted(boolean on) {
        muted = on;
        if (on) flush();
    }

    public boolean isMuted() { return muted; }

    public long getDroppedFrames() { return dropped; }
    public long getWrittenFrames() { return written; }
    public int getQueuedFrames() { synchronized (lock) { return queue.size(); } }
    public boolean isRunning() { return running && track != null; }

    /* ==================== 写线程 ==================== */

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        /* 垫底：先等队列攒够 PRE_ROLL 帧再开始写。
         *
         * 这里必须是「等队列变长」，不能是「空转几次就开写」——
         * 后者在微秒内就跑完了，一帧都没等到，等于没有抖动缓冲：
         * 网络每抖一下就是一次断音。
         * 400 ms 是上限：真的一帧都等不到（网络刚断）也不能卡死在这里，
         * 到点就开始写，让上层自己去重连。 */
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 400;
            while (running && queue.size() < jitterFrames) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(Math.min(50, left)); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        while (running) {
            short[] frame;
            synchronized (lock) {
                while (running && queue.isEmpty()) {
                    try { lock.wait(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!running) return;
                frame = queue.pollFirst();
            }
            if (frame == null) continue;
            /* 音量 / 增强 / 高通 / presence / 软削波全在 chain 里，原地改写。
             * 这里不再自己乘增益、也不再硬夹 —— 链路出口已经保证 |y| ≤ 0.98，
             * 硬夹只会把「本来不该被削的样本」削平（那正是旧版本发炸的成因）。 */
            chain.process(frame, frame.length);
            AudioTrack t = track;
            if (t == null) return;
            try {
                int off = 0;
                while (off < frame.length && running) {
                    int n = t.write(frame, off, frame.length - off);
                    if (n <= 0) break;          // 写失败（音频焦点被抢）→ 丢掉这一帧
                    off += n;
                }
            } catch (Exception e) {
                /* AudioTrack 已经被 release（切后台 / 音频焦点丢失）→ 线程退出，
                 * 由 MainActivity 的 onResume 重新 start()。 */
                return;
            }
            written++;
            LevelListener l = levelCb;
            if (l != null) l.onLevel(MuLaw.rms(frame, frame.length));
        }
    }
}
