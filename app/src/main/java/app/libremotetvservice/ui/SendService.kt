package app.libremotetvservice.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import app.libremotetvservice.R
import app.libremotetvservice.libremote
import app.libremotetvservice.net.SendController.Problem
import app.libremotetvservice.net.SendController.State

/**
 * Keeps a batch of files going while Libremote is in the background, as a foreground service of type dataSync,
 * with a notification: the file, the percentage and Cancel, then "Sent 3 files to <TV>". It follows
 * SendController and stops itself when the batch ends. Without the notification permission (Android 13+) it runs
 * all the same; the progress then shows only in the app.
 */
class SendService : Service() {

    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val listener: (State) -> Unit = { update(it) }
    private var listening = false
    private var lastNotifyMs = 0L
    private var lastStep = ""
    private var lastState: Class<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            libremote.sends.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        // startForeground must come first even if the batch already ended (it failed at once, say); the listener,
        // called straight away with the current state, then ends the service.
        val notification = progressNotification(libremote.sends.state) ?: builder().setContentTitle(getString(R.string.notification_channel)).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID_PROGRESS, notification)
        } catch (e: RuntimeException) {
            // Not allowed now (the app went to the background first, say): the batch goes on without the service.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            listening = true
            libremote.sends.addListener(listener)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: a dataSync service may run 6 hours a day. A batch that long is cancelled, not left half-sent. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        libremote.sends.cancel()
        finish()
    }

    override fun onDestroy() {
        if (listening) libremote.sends.removeListener(listener)
        super.onDestroy()
    }

    private fun update(state: State) {
        when (state) {
            is State.Preparing, is State.OpeningReceiver, is State.Confirming, is State.Sending -> {
                // The system drops updates that come too fast: show each step, each new percent at most four times
                // a second, and otherwise once a second.
                val step = (state as? State.Sending)?.let { "${it.file}:${percent(it)}" } ?: state.javaClass.simpleName
                val now = System.nanoTime() / 1_000_000
                if (state.javaClass == lastState && (step == lastStep && now - lastNotifyMs < UPDATE_MS || now - lastNotifyMs < MIN_UPDATE_MS)) return
                lastNotifyMs = now
                lastStep = step
                lastState = state.javaClass
                progressNotification(state)?.let { notifications.notify(ID_PROGRESS, it) }
            }
            is State.Sent -> {
                val text = getString(R.string.notification_sent_text, size(state.total))
                finish(result(resources.getQuantityString(R.plurals.notification_sent_title, state.count, state.count, state.tv.name), text, open = false))
            }
            is State.Failed -> {
                // A lost connection says which files arrived; anything else, only that it failed.
                val summary = state.lost?.takeIf { state.problem == Problem.LOST }?.let(resources::lostSummary).orEmpty()
                finish(
                    if (summary.isEmpty()) result(getString(R.string.notification_failed_title, state.tv.name), getString(R.string.notification_failed_text), open = true)
                    else result(getString(R.string.send_lost_title, state.tv.name), getString(R.string.notification_lost_text, summary), open = true),
                )
            }
            // Cancelled.
            is State.Idle -> finish()
        }
    }

    private fun finish(result: Notification? = null) {
        if (listening) {
            listening = false
            libremote.sends.removeListener(listener)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        result?.let { notifications.notify(ID_RESULT, it) }
        stopSelf()
    }

    private fun progressNotification(state: State): Notification? {
        val (tv, text, percent) = when (state) {
            is State.Preparing -> Triple(state.tv, getString(R.string.send_preparing), null)
            is State.OpeningReceiver -> Triple(state.tv, getString(R.string.send_opening_receiver), null)
            is State.Confirming -> Triple(state.tv, getString(R.string.notification_confirm, state.code), null)
            is State.Sending -> {
                val name = if (state.names.size == 1) state.names[0]
                else getString(R.string.send_current_file, state.names[state.file], state.file + 1, state.names.size)
                Triple(state.tv, getString(R.string.notification_progress, name, percent(state)), percent(state))
            }
            else -> return null
        }
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, SendService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return builder()
            .setContentTitle(getString(R.string.send_title_sending, tv.name))
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun result(title: String, text: String, open: Boolean): Notification =
        builder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (open) setContentIntent(openApp()) }
            .build()

    private fun builder() =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setColor(getColor(R.color.brand))
            .setShowWhen(false)

    /** The sending screen, which shows the batch (its progress, or why it failed and Try again). */
    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, SendActivity::class.java).putExtra(SendActivity.EXTRA_SHOW, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        notifications.createNotificationChannel(channel)
    }

    private fun percent(state: State.Sending) = if (state.total > 0) (state.sent * 100 / state.total).toInt() else 100

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    companion object {
        private const val CHANNEL = "sending"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
        private const val ACTION_CANCEL = "app.libremotetvservice.action.CANCEL_SEND"
        private const val UPDATE_MS = 1_000L
        private const val MIN_UPDATE_MS = 250L

        /** Called as a batch starts, from the app in front (a foreground service can't start from the background). */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SendService::class.java)) }
        }
    }
}
