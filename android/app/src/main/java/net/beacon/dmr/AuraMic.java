package net.beacon.dmr;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

/**
 * AURA 发射用的麦克风采集：8 kHz / 16 bit / 单声道，按 160 ms 一块 base64 回传。
 *
 * <p>为什么不用页面里的 <code>getUserMedia</code>：
 * <ul>
 *   <li>WebView 的 getUserMedia 依赖 onPermissionRequest + 运行时权限两层，
 *       任何一层没跟上就是 <code>NotAllowedError</code>，而错误名对用户没有意义；</li>
 *   <li>部分机型/WebView 版本压根不支持（返回 undefined），这条路是死胡同；</li>
 *   <li>原生 AudioRecord 用的就是系统给的 8 kHz，不需要重采样，也不用考虑
 *       AudioContext 的自动播放策略。</li>
 * </ul>
 * 所以 App 内优先走这条；网页版没有 Android 桥，才退回 getUserMedia。
 *
 * <p>线程模型：一个读线程；回调发生在读线程上，调用方自己切主线程。
 */
public final class AuraMic {

    public interface Sink {
        /** 一块 PCM（s16le，base64）。长度通常是 1280 采样 = 2560 字节。 */
        void onChunk(String b64);
        void onError(String msg);
    }

    private static final int RATE = 8000;
    /** 160 ms —— AURA 单帧上限 6400 字节，这里 2560 字节，留足余量。 */
    private static final int CHUNK = 1280;

    private volatile boolean running;
    private AudioRecord rec;
    private Thread th;

    public boolean isRunning() { return running; }

    public boolean start(final Sink sink) {
        if (running) return true;
        int min = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min > 0 ? min : 0, CHUNK * 2 * 4);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        } catch (Exception e) {
            sink.onError("AudioRecord 创建失败：" + e.getClass().getSimpleName());
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            sink.onError("麦克风被占用或不可用（state=" + rec.getState() + "）");
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
            return false;
        }
        running = true;
        th = new Thread(() -> {
            short[] pcm = new short[CHUNK];
            byte[] bytes = new byte[CHUNK * 2];
            try {
                rec.startRecording();
            } catch (Exception e) {
                sink.onError("开始录音失败：" + e.getClass().getSimpleName());
                running = false;
                return;
            }
            while (running) {
                int n;
                try {
                    n = rec.read(pcm, 0, CHUNK);
                } catch (Exception e) {
                    break;
                }
                if (n <= 0) continue;
                int len = n * 2;
                for (int i = 0; i < n; i++) {
                    short v = pcm[i];
                    bytes[i * 2] = (byte) (v & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                try {
                    sink.onChunk(Base64.encodeToString(bytes, 0, len, Base64.NO_WRAP));
                } catch (Exception ignored) { }
            }
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
        }, "aura-mic");
        th.setDaemon(true);
        th.start();
        return true;
    }

    public void stop() {
        running = false;
        AudioRecord r = rec;
        if (r != null) {
            /* read() 会阻塞；stop() 让它立刻返回错误值，线程才能退出 */
            try { r.stop(); } catch (Exception ignored) { }
        }
    }
}
