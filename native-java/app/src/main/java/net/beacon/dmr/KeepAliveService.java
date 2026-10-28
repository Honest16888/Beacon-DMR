package net.beacon.dmr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 保活服务：把进程钉在「前台」优先级，切后台 / 熄屏时链路才不会被掐断。
 *
 * <h3>为什么必须有它</h3>
 *
 * Android 8 之后，应用退到后台就是一个 cached process：
 * <ul>
 *   <li>进程可以被<b>冻结</b>（freezer），线程不再被调度；</li>
 *   <li>Doze / App Standby 会限制它的网络；</li>
 *   <li>内存吃紧时它是第一批被杀的。</li>
 * </ul>
 *
 * 而这里有三条长连接同时在跑：HOSE（wss，收听）、LH（wss，看板）、
 * MMDVM（UDP，每 5 秒一次心跳）。进程被冻结十几秒，MMDVM 主站那边就判超时、
 * 把会话摘掉 —— 回到前台看到的是「断链」，而且因为会话已经没了，
 * 重连还会被当成「同 ID 重复登录」。
 *
 * <b>Partial WakeLock 只锁 CPU，锁不住进程优先级</b> —— 只有 WakeLock 的话
 * 表现是「熄屏能听、切后台会断」。三件事必须一起做：
 * <ol>
 *   <li>{@code startForeground()} —— 提高进程优先级，不被冻结 / 回收；</li>
 *   <li>Partial WakeLock —— 熄屏后 CPU 不休眠，心跳线程照常跑；</li>
 *   <li>WifiLock（HIGH_PERF）—— 熄屏后 WiFi 射频不进省电模式。</li>
 * </ol>
 *
 * <h3>为什么是 mediaPlayback 类型</h3>
 *
 * Android 14 对 {@code dataSync} 型前台服务有「每天累计 6 小时」的上限，
 * 而这个 App 是用来长时间守听的，撞上上限会被系统直接掐掉。
 * {@code mediaPlayback} 没有时长限制，且这个 App 确实在播放接收到的语音。
 *
 * <p>发射时再加上 {@code microphone} 类型 —— Android 14 起，在后台访问麦克风
 * 必须由带 microphone 类型的前台服务支撑，否则 {@code AudioRecord} 拿到的是静音。
 * 没有录音权限时不能带这个类型（会抛 SecurityException），所以按权限动态决定。
 *
 * <h3>和 WebView 版的两处不同</h3>
 * <ol>
 *   <li><b>START_NOT_STICKY 而不是 START_STICKY</b>。进程被系统杀掉后重建出来的
 *       是一个<b>空壳</b>：服务活着、通知挂着、锁也持着，但 Activity、WebSocket、
 *       UDP 会话全都没了。用户看到「守听中」却一个字也听不到，比直接看到
 *       通知消失更糟。所以宁可让通知跟着一起消失。</li>
 *   <li>通知文案由 {@link #update} 主动刷新（当前话组 / 呼号），不再是静态的
 *       「守听中」—— 锁屏时瞄一眼通知就知道在听谁。</li>
 * </ol>
 */
public final class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "beacon_dmr_session";
    private static final int NOTIF_ID = 0x0D01;
    private static final String EXTRA_TEXT = "text";

    /** 当前通知文案。用来判「要不要真的重发一次 intent」。 */
    private static volatile String curText = "";
    /** 服务是否在跑。Activity 据此决定要不要再 start 一次。 */
    private static volatile boolean running = false;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    /* ==================== 对外入口 ==================== */

    /** 启动（或更新文案）保活服务。可重复调用。 */
    public static void start(Context ctx, String text) {
        if (ctx == null) return;
        push(ctx, text);
    }

    /**
     * 只更新通知文案。文案没变就什么都不做 —— 每次通话都重发一次 intent
     * 会让通知闪一下，锁屏上看着像出了什么事。
     *
     * <p>服务没在跑时直接忽略：这个方法会被界面在渲染「正在收听」时顺手调用，
     * 要是它能把服务拉起来，用户在设置里关掉保活之后随便点两下又会把它启动。
     */
    public static void update(Context ctx, String text) {
        if (ctx == null || !running) return;
        if (text == null) text = "";
        if (text.equals(curText)) return;
        push(ctx, text);
    }

    /** 停止保活。Activity 销毁时必须调用，否则会一直挂一个通知 + 持锁耗电。 */
    public static void stop(Context ctx) {
        curText = "";
        running = false;
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static void push(Context ctx, String text) {
        curText = text == null ? "" : text;
        Intent i = new Intent(ctx, KeepAliveService.class);
        i.putExtra(EXTRA_TEXT, curText);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
            /* 后台启动前台服务在个别 ROM 上会被拒（Android 12+ 的后台启动限制）。
             * 不是致命问题 —— 拒绝时链路仍能工作，只是切后台有概率被冻。 */
        }
    }

    /* ==================== 生命周期 ==================== */

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.isEmpty()) text = curText.isEmpty() ? "守听中" : curText;
        Notification n = buildNotification(text);

        boolean started = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            /* 有录音权限才带 microphone 类型。没权限时带它必抛 SecurityException。 */
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (Build.VERSION.SDK_INT >= 34 && hasMic()) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            try {
                startForeground(NOTIF_ID, n, type);
                started = true;
            } catch (Throwable ignore) {
                /* 落到下面退化成只带 mediaPlayback */
            }
        }
        if (!started) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
                /* Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
                 * 这时退化成「普通服务 + 持锁」，比直接崩掉好。 */
            }
        }
        /* ★ START_NOT_STICKY：被系统杀掉后<b>不要</b>重建。
         * 重建出来的是空壳（链路全没了），通知却还在说「守听中」——
         * 那种「看着在守听、其实什么也收不到」比通知消失难查得多。
         * WebView 版这里用的是 START_STICKY，见类注释里的说明。 */
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        releaseLocks();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasMic() {
        try {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 通知 ==================== */

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "DMR 会话", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持 DMR 主站连接与守听，切到后台也不断");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat_dmr)
                .setContentTitle("信标 DMR")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    /* ==================== 锁 ==================== */

    private void acquireLocks() {
        try {
            if (wake == null || !wake.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beacon:dmr-session");
                    wake.setReferenceCounted(false);
                    /* 不带超时：靠 onDestroy 释放。带超时的话长时间守听会在
                     * 某个时刻静默松开锁，然后「听了两小时突然就断了」。 */
                    wake.acquire();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock == null || !wifiLock.isHeld()) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    /* HIGH_PERF：熄屏后 WiFi 射频不进省电模式。
                     * 默认的 WIFI_MODE_FULL 在部分机型上仍然会在熄屏几分钟后掉速，
                     * 对每 5 秒一次心跳的链路来说，掉一次就是一次超时。 */
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "beacon:dmr-wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseLocks() {
        if (wake != null) {
            try {
                if (wake.isHeld()) wake.release();
            } catch (Throwable ignored) {
            }
            wake = null;
        }
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) wifiLock.release();
            } catch (Throwable ignored) {
            }
            wifiLock = null;
        }
    }
}
