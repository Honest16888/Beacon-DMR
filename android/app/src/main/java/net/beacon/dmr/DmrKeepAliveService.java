package net.beacon.dmr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
 * Android 8 之后，应用退到后台就是一个「cached process」：
 * <ul>
 *   <li>进程可以被<b>冻结</b>（freezer），线程不再被调度；</li>
 *   <li>Doze / App Standby 会限制它的网络；</li>
 *   <li>内存吃紧时它是第一批被杀的。</li>
 * </ul>
 *
 * 而 DMR 主站会话是一条 UDP 链路 + 每 5 秒一次心跳。进程被冻结十几秒，
 * 主站那边就判超时、把会话摘掉 —— 回到前台看到的就是「断链」，
 * 而且因为会话已经没了，重连还会被当成「同 ID 重复登录」。
 *
 * <b>Partial WakeLock 只锁 CPU，锁不住进程优先级</b> —— 之前只有 WakeLock，
 * 所以熄屏能听、切后台会断。这两件事必须一起做：
 * <ol>
 *   <li>{@code startForeground()} —— 提高进程优先级，不被冻结/回收；</li>
 *   <li>Partial WakeLock —— 熄屏后 CPU 不休眠，心跳线程照常跑；</li>
 *   <li>WifiLock（HIGH_PERF）—— 熄屏后 WiFi 射频不进省电模式。</li>
 * </ol>
 *
 * <h3>为什么是 mediaPlayback 类型</h3>
 *
 * Android 14 对 {@code dataSync} 型前台服务有「每天累计 6 小时」的上限，
 * 而这个 App 是用来长时间守听的，撞上上限会被系统直接掐掉。
 * {@code mediaPlayback} 没有时长限制，且这个 App 确实在播放接收到的语音。
 */
public class DmrKeepAliveService extends Service {

    private static final String CHANNEL_ID = "beacon_dmr_session";
    private static final int NOTIF_ID = 0x0D01;

    private static final String EXTRA_TEXT = "text";

    /** 只有页面明确说「要保活」时才持锁，避免用户点了断开还一直耗电 */
    private static volatile boolean wanted = false;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    /* ---------------- 对外入口 ---------------- */

    /** 启动（或更新文案）保活服务。可重复调用。 */
    public static void start(Context ctx, String text) {
        if (ctx == null) return;
        wanted = true;
        Intent i = new Intent(ctx, DmrKeepAliveService.class);
        i.putExtra(EXTRA_TEXT, text == null ? "守听中" : text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
            /* 后台启动前台服务在个别 ROM 上会被拒。不是致命问题 ——
             * 拒绝时链路仍能工作，只是切后台会断，用户重连即可。 */
        }
    }

    /** 停止保活。页面断开 / 退出时必须调用，否则会一直挂一个通知。 */
    public static void stop(Context ctx) {
        wanted = false;
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, DmrKeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    public static boolean isWanted() {
        return wanted;
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.isEmpty()) text = "守听中";
        Notification n = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            /* Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
             * 这时退化成「普通服务 + 持锁」，比直接崩掉好。 */
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
        /* START_STICKY：系统杀进程后会尝试重建。重建时 intent 为 null，
         * 上面的默认文案会兜住，不会出现空通知。 */
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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

    /* ---------------- 通知 ---------------- */

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

    /* ---------------- 锁 ---------------- */

    private void acquireLocks() {
        try {
            if (wake == null || !wake.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beacon:dmr-session");
                    wake.setReferenceCounted(false);
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
