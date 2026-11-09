package nrl.pulse.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 后台保活服务 —— 前台服务，保持 MQTT 连接不断。
 *
 * 对应 OpenRF 的 GuardService（libnrlguard.so 参与守卫逻辑）。
 */
public class GuardService extends Service {

    private static final String CHANNEL_ID = "fmo_guard";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FMO Pulse")
            .setContentText("保持连接中…")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "FMO 保活", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
