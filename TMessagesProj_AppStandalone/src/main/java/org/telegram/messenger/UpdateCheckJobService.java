package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Periodically asks GithubUpdaterController whether a newer Tejar release exists and, if so,
 * raises a notification. Without this the updater only ever runs when DialogsActivity is created,
 * i.e. on a cold start — someone who leaves the app running for weeks would never learn about a
 * new build, which matters for a fork distributed outside any app store.
 */
public class UpdateCheckJobService extends JobService {

    private static final int JOB_ID = 7302;
    private static final long INTERVAL = 1000L * 60 * 60 * 6; // 6 hours

    private static final String CHANNEL_ID = "tejar_update_available";
    private static final int NOTIFICATION_ID = 7302;

    /** Settings id routed through ApplicationLoader.openSettings() to open AboutAppActivity. */
    public static final int OPEN_SETTINGS_ABOUT_APP = 16;

    public static void schedule(Context context) {
        try {
            final JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            // Re-scheduling restarts the period, so an app that is launched often would keep
            // pushing the next check back and effectively never run it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && scheduler.getPendingJob(JOB_ID) != null) {
                return;
            }
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL)
                .setPersisted(true)
                .build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // force = true: JobScheduler already paces us, so the controller's own interval would
        // only ever skip the check we were woken up to perform.
        GithubUpdaterController.getInstance().checkForUpdate(true, () -> {
            try {
                notifyIfUpdateAvailable();
            } catch (Exception e) {
                FileLog.e(e);
            }
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule: the check was cut short, not completed
    }

    private void notifyIfUpdateAvailable() {
        final GithubUpdaterController controller = GithubUpdaterController.getInstance();
        final BetaUpdate update = controller.getUpdate();
        if (update == null || !controller.shouldNotifyAboutUpdate()) {
            return;
        }

        final Intent intent = new Intent(this, LaunchActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.putExtra("open_settings", OPEN_SETTINGS_ABOUT_APP);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, NOTIFICATION_ID, intent, flags);

        ensureChannel();
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(LocaleController.getString(R.string.SettingsUpdateAvailableTitle))
            .setContentText(LocaleController.formatString("SettingsUpdateAvailableText", R.string.SettingsUpdateAvailableText, update.version))
            .setSmallIcon(R.drawable.ic_header_update)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);

        // Only announce each release once, otherwise every six hours would bring the same alert.
        controller.setUpdateNotified();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Tejar updates", NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }
}
