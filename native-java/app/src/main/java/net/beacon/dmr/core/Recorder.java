package net.beacon.dmr.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 通话录音 —— 把收到的语音直接写成 WAV。
 *
 * <p>★ <b>录的是「解码后的 PCM」，不是原始 μ-law 字节，也不经过音量/增益/软削波。</b>
 * 这一点很重要：WebView 版有一条踩坑记录 —— 接收增益还默认 2× 的时候，
 * 喇叭里听是「发炸」的，而<b>录音却是清晰的</b>，因为录音抓的是增益之前的那份。
 * 排查时正是靠这个反差定位到「增益推过头撞软限幅」。
 *
 * <p>格式：<b>8 kHz / 16 bit / 单声道 / PCM</b>，标准 44 字节 canonical WAV 头。
 * 之所以用 WAV 而不是 AAC/M4A：编码要给系统交一份码率/延迟的账，
 * 而我们手上就是现成的 PCM —— 加一层编码只会引入失败点，不会让文件变小到有意义
 * （一条 12 分钟的通话 WAV 约 11 MB，AAC 大概 5 MB，差别不值得）。
 *
 * <p>★ <b>边收边写，不在内存里攒。</b> WebView 版攒在数组里，
 * 40 条 × 12 分钟最坏能占 439 MB 把页面拖崩 —— 那边为此专门加了
 * 「48 MB 总量上限」的裁剪逻辑。原生能直接落盘，没必要再赌内存。
 * 落盘之后总量上限反而更好做：按文件大小算。
 *
 * <p>★ <b>静音时不录。</b> 用户点了静音就是不想留这段，录下来反而侵犯。
 */
public final class Recorder {

    private static final String TAG = "Recorder";

    /** 8 kHz / 16 bit 单声道。 */
    public static final int RATE = 8000;
    private static final int BITS = 16;
    private static final int CHANNELS = 1;
    /** 一帧样本数（20 ms）。 */
    private static final int FRAME = 160;

    /** 单条上限：12 分钟。到顶就收尾，本次通话剩下的不再录。 */
    private static final long MAX_MS = 12L * 60 * 1000;
    /** 保留条数上限。 */
    public static final int MAX_FILES = 40;
    /** 保留总量上限：48 MB。 */
    private static final long MAX_BYTES = 48L * 1024 * 1024;
    /** 短于这个的不留（0.6 秒）—— 那是点一下 PTT 的杂音。 */
    private static final long MIN_MS = 600;

    /** 一条录音。 */
    public static final class Rec {
        public final File file;
        public final String name;
        public final int tg;
        public final String call;
        public final long at;
        public final long bytes;
        /** 时长，秒。 */
        public final int dur;

        Rec(File f, String name, int tg, String call, long at, long bytes, int dur) {
            this.file = f;
            this.name = name;
            this.tg = tg;
            this.call = call == null ? "" : call;
            this.at = at;
            this.bytes = bytes;
            this.dur = dur;
        }
    }

    private static Recorder active;
    private RandomAccessFile out;
    private File curFile;
    private String curName;
    private int curTg;
    private String curCall = "";
    private long curStart;
    private long samples;

    private Recorder() {
    }

    /** 是否正在录。 */
    public static boolean isRecording() {
        return active != null;
    }

    /** 当前这一条已录多久（秒）。 */
    public static int recSeconds() {
        Recorder r = active;
        if (r == null) return 0;
        return (int) (r.samples / RATE);
    }

    /**
     * 开始一条新录音。已经在录的先收尾。
     *
     * @return 真的开始录了返回 true；没开开关 / 参数不全返回 false
     */
    public static synchronized boolean begin(Context ctx, boolean enabled, int tg, String call) {
        if (!enabled) return false;
        finish();
        Recorder r = new Recorder();
        r.curTg = tg;
        r.curCall = call == null ? "" : call;
        r.curStart = System.currentTimeMillis();
        r.samples = 0;

        /* 文件名：YYYYMMDD-HHmmss_TG<组号>_<呼号>.wav —— 和 WebView 版一致。
         * 呼号里的非 [A-Za-z0-9_-] 一律洗掉（"ID 12345" → "ID12345"），
         * 否则文件名里带空格，有些文件管理器打不开。 */
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(r.curStart));
        String safe = r.curCall.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) safe = "ID" + tg;
        r.curName = stamp + "_TG" + tg + "_" + safe + ".wav";

        File dir = dir(ctx);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        r.curFile = new File(dir, r.curName);
        try {
            r.out = new RandomAccessFile(r.curFile, "rw");
            r.out.setLength(0);
            /* 先写 44 字节的占位头，收尾时回填长度。
             * 这样边收边写，不需要在内存里攒整条。 */
            r.out.write(new byte[44]);
        } catch (Exception e) {
            Log.w(TAG, "建录音文件失败：" + e);
            return false;
        }
        active = r;
        return true;
    }

    /** 喂一帧 PCM（16 bit）。没在录就什么也不做。 */
    public static synchronized void push(short[] pcm, int off, int len) {
        Recorder r = active;
        if (r == null || r.out == null || len <= 0) return;

        if (System.currentTimeMillis() - r.curStart > MAX_MS) {
            finish();
            return;
        }
        try {
            byte[] b = new byte[len * 2];
            for (int i = 0; i < len; i++) {
                short v = pcm[off + i];
                b[i * 2] = (byte) (v & 0xFF);
                b[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            r.out.write(b);
            r.samples += len;
        } catch (Exception e) {
            Log.w(TAG, "写录音失败：" + e);
            finish();
        }
    }

    /** 收尾：回填 WAV 头，太短的删掉。 */
    public static synchronized void finish() {
        Recorder r = active;
        active = null;
        if (r == null || r.out == null) return;

        long ms = System.currentTimeMillis() - r.curStart;
        try {
            long dataBytes = r.samples * 2;
            r.out.seek(0);
            r.out.write(wavHeader(dataBytes));
            r.out.close();
        } catch (Exception e) {
            Log.w(TAG, "收尾录音失败：" + e);
        }
        r.out = null;

        if (ms < MIN_MS || r.samples <= 0) {
            //noinspection ResultOfMethodCallIgnored
            r.curFile.delete();
            return;
        }
        trim(r.curFile.getParentFile());
    }

    /** 44 字节 canonical WAV 头。全小端。 */
    private static byte[] wavHeader(long dataBytes) {
        int byteRate = RATE * CHANNELS * BITS / 8;
        int blockAlign = CHANNELS * BITS / 8;
        byte[] h = new byte[44];
        putAscii(h, 0, "RIFF");
        putInt(h, 4, (int) (36 + dataBytes));
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putInt(h, 16, 16);              // fmt chunk 大小
        putShort(h, 20, 1);             // 1 = PCM
        putShort(h, 22, CHANNELS);      // 单声道
        putInt(h, 24, RATE);            // 8000
        putInt(h, 28, byteRate);        // 16000
        putShort(h, 32, blockAlign);    // 2
        putShort(h, 34, BITS);          // 16
        putAscii(h, 36, "data");
        putInt(h, 40, (int) dataBytes);
        return h;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    /* ==================== 文件管理 ==================== */

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "recs");
    }

    /** 列出所有录音，新的在前。 */
    public static List<Rec> list(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".wav"));
        List<Rec> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            String n = f.getName();
            int tg = 0;
            String call = "";
            /* 文件名回解：<stamp>_TG<组号>_<呼号>.wav */
            int i = n.indexOf("_TG");
            if (i > 0) {
                int j = n.indexOf('_', i + 3);
                if (j > 0) {
                    try {
                        tg = Integer.parseInt(n.substring(i + 3, j));
                    } catch (NumberFormatException ignore) { }
                    int dot = n.lastIndexOf('.');
                    call = dot > j ? n.substring(j + 1, dot) : "";
                }
            }
            long bytes = f.length();
            long dataBytes = Math.max(0, bytes - 44);
            int dur = (int) (dataBytes / 2 / RATE);
            out.add(new Rec(f, n, tg, call, f.lastModified(), bytes, dur));
        }
        return out;
    }

    public static long totalBytes(Context c) {
        long n = 0;
        for (Rec r : list(c)) n += r.bytes;
        return n;
    }

    /**
     * 裁剪：条数 + 总量双上限，从最旧的开始删。
     *
     * <p>★ 双上限是必须的：只按条数限制的话，40 条 × 12 分钟 ≈ 440 MB，
     * 挂机几小时就能把手机存储吃满。
     */
    private static void trim(File d) {
        if (d == null) return;
        File[] files = d.listFiles((x, n) -> n.endsWith(".wav"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));   // 最旧在前

        long total = 0;
        for (File f : files) total += f.length();

        int extra = files.length - MAX_FILES;
        for (int i = 0; i < files.length; i++) {
            if (extra <= 0 && total <= MAX_BYTES) break;
            long sz = files[i].length();
            if (files[i].delete()) {
                total -= sz;
                if (i < extra) extra--;
            }
        }
    }

    public static boolean delete(Rec r) {
        return r != null && r.file != null && r.file.delete();
    }

    public static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
