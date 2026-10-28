package net.beacon.dmr;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用入口，只干一件事：把未捕获异常的堆栈落到文件里。
 *
 * <p><b>为什么 alpha 阶段一定要有</b>：这个版本的三页界面是在桌面上写完的，
 * 手上没有模拟器，第一次真机运行就是用户那台手机。万一启动就崩，用户能提供的
 * 只有「打不开」三个字 —— 而有了这个文件，下次打开 App 会直接把堆栈弹出来，
 * 用户截个图就够定位了。
 *
 * <p>正式版可以留着：它不影响性能（只在崩溃那一刻写一次文件），
 * 而线上崩溃反馈的价值远大于几 KB 存储。
 */
public final class App extends Application {

    private static final String FILE = "last_crash.txt";
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { save(e); } catch (Throwable ignore) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void save(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("机型：" + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("版本：" + versionName());
        pw.println();
        e.printStackTrace(pw);
        pw.flush();
        try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), FILE))) {
            o.write(sw.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Log.w(TAG, "崩溃日志写不进去", ex);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 上一次崩溃的堆栈，没有就返回 null。 */
    public static String lastCrash(android.content.Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream i = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < b.length) {
                    int n = i.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearCrash(android.content.Context c) {
        try { new File(c.getFilesDir(), FILE).delete(); } catch (Exception ignore) { }
    }
}
