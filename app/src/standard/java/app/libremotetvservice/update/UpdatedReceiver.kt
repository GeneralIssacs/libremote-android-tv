package app.libremotetvservice.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.libremotetvservice.BuildConfig
import app.libremotetvservice.R
import app.libremotetvservice.ui.DevicesActivity

/**
 * Libremote was just replaced by a newer version, which closed it. When the user updated it from the app a moment
 * ago, this brings it back: Android 8 and 9 let it open again at once; from Android 10, which doesn't let an app
 * start itself from the background, a notification says it's updated and opens it (if notifications are allowed).
 * An update from anywhere else (adb, another installer) shows nothing.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Updater.takeRelaunch(context)) return
        val open = Intent(context, DevicesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT < 29) {
            runCatching { context.startActivity(open) }
            return
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifications.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_update)
                .setColor(context.getColor(R.color.brand))
                .setContentTitle(context.getString(R.string.update_done_title, BuildConfig.VERSION_NAME))
                .setContentText(context.getString(R.string.update_done_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL = "updates"
        /** Apart from SendService's (1 and 2). */
        const val ID = 3
    }
}
