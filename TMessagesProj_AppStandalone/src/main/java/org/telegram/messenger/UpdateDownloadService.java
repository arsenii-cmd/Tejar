package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the process alive (and exempt from Doze/App Standby network throttling) for the
 * duration of an update download, so GithubUpdaterController's AsyncTask-based download
 * survives backgrounding, screen-off and battery saver instead of silently stalling.
 */
public class UpdateDownloadService extends Service implements NotificationCenter.NotificationCenterDelegate {

    private static final String CHANNEL_ID = "tejar_update_download";
    private static final int NOTIFICATION_ID = 7301;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, UpdateDownloadService.class));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateLoading);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateAvailable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(0));
        if (!GithubUpdaterController.getInstance().isDownloading()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateLoading);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateAvailable);
        super.onDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appUpdateLoading) {
            if (GithubUpdaterController.getInstance().isDownloading()) {
                int percent = (int) (GithubUpdaterController.getInstance().getDownloadingProgress() * 100);
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(percent));
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            stopSelf();
        }
    }

    private Notification buildNotification(int percent) {
        ensureChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(LocaleController.getString(R.string.SettingsUpdateDownloadingNotification))
            .setSmallIcon(R.drawable.ic_header_update)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, false)
            .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Tejar updates", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
        }
    }
}
