package app.libremotetvservice.update

import android.app.Activity
import android.app.AlertDialog
import android.text.format.Formatter
import app.libremotetvservice.R
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import app.libremotetvservice.net.ReceiverLinkUnavailableException
import app.libremotetvservice.net.ReceiverMissingException
import app.libremotetvservice.protocol.OutgoingFile
import app.libremotetvservice.protocol.ReceiverClient
import app.libremotetvservice.protocol.ReceiverDeclinedException
import app.libremotetvservice.protocol.ReceiverRefusedPhoneException
import app.libremotetvservice.protocol.ReceiverUnavailableException
import app.libremotetvservice.protocol.ReceiverUnverifiedException
import app.libremotetvservice.protocol.TransferRefusedException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Updates Libremote Receiver on [tv] from [release], once the user agrees: downloads the APK, checks it as the app's
 * own update is checked (digest, package, newer than [current], release key), then sends it over the receiver's
 * encrypted channel marked as an update. The TV checks it again and opens its installer, where the user confirms;
 * the phone then waits a while to see the new version. SENDFILES.md, "Updating the receiver".
 */
internal class ReceiverUpdate(
    private val activity: Activity,
    private val tv: Tv,
    private val release: Releases.ReceiverAsset,
    private val current: Long,
    private val notes: String,
) {
    private val app = activity.libremote
    private val cancelled = AtomicBoolean(false)
    @Volatile private var client: ReceiverClient? = null
    private lateinit var progress: UpdateProgress

    fun confirm() {
        val size = Formatter.formatShortFileSize(activity, release.asset.size.coerceAtLeast(0))
        val parts = listOf(
            activity.getString(R.string.receiver_update_versions, Releases.versionName(current), release.version),
            notes,
            activity.getString(R.string.receiver_update_how, size),
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.receiver_update_title, tv.name))
            .setMessage(parts.filter { it.isNotEmpty() }.joinToString("\n\n"))
            .setPositiveButton(R.string.update_install) { _, _ -> start() }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun start() {
        if (Updater.downloading) return
        Updater.downloading = true
        progress = UpdateProgress(activity, activity.getString(R.string.receiver_updating)) {
            cancelled.set(true)
            client?.close()
        }
        progress.step(activity.getString(R.string.receiver_update_step_download, release.version))
        thread(name = "receiver-update") {
            var file: File? = null
            try {
                file = Updater.fetchApk(activity, release.asset, cancelled) { done, total -> post { progress.download(done, total) } }
                Updater.verify(activity, file, Updater.RECEIVER_PACKAGE, current)
                if (cancelled.get()) return@thread
                val apk = file
                app.receivers.open(
                    tv, syncOnly = false, retryAbsent = false,
                    opening = { post { progress.step(activity.getString(R.string.receiver_update_step_open, tv.name)) } },
                    confirming = { code -> post { progress.step(activity.getString(R.string.send_confirm_text, code)) } },
                    cancelled = cancelled::get,
                ).use { session ->
                    client = session.client
                    if (cancelled.get()) return@thread
                    post { progress.step(activity.getString(R.string.receiver_update_step_send, tv.name)) }
                    val name = "libremote-receiver-${release.version}.apk"
                    session.client.sendUpdate(OutgoingFile(name, apk.length()) { apk.inputStream() }) { _, sent, total ->
                        post { progress.download(sent, total) }
                    }
                }
                client = null
                post {
                    progress.text(activity.getString(R.string.receiver_update_confirm, tv.name))
                    progress.step(activity.getString(R.string.receiver_update_waiting))
                }
                waitForNewVersion()
            } catch (e: Throwable) {
                if (!cancelled.get()) post { progress.finish(activity.getString(R.string.receiver_update_failed), reason(e), failed = true) }
            } finally {
                file?.delete()
                Updater.downloading = false
            }
        }
    }

    /** The receiver comes back after the update when the user opens it (the installer's Open does): then it says. */
    private fun waitForNewVersion() {
        val wanted = Releases.versionCode(release.version) ?: return
        val deadline = System.nanoTime() + WAIT_MS * 1_000_000
        while (!cancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            val version = runCatching { app.receivers.probe(tv) }.getOrNull() ?: continue
            if (version >= wanted) {
                post {
                    progress.finish(activity.getString(R.string.receiver_update_done_title),
                        activity.getString(R.string.receiver_update_done_text, tv.name, Releases.versionName(version)))
                }
                return
            }
        }
        if (!cancelled.get()) post {
            progress.finish(activity.getString(R.string.receiver_update_waited_title), activity.getString(R.string.receiver_update_waited_text, tv.name))
        }
    }

    private fun reason(e: Throwable): String = when (e) {
        is ReceiverMissingException -> activity.getString(R.string.receiver_update_missing, tv.name)
        is ReceiverLinkUnavailableException -> activity.getString(R.string.send_cant_open_text)
        is ReceiverUnavailableException, is ReceiverRefusedPhoneException -> activity.getString(R.string.receiver_update_no_answer, tv.name)
        is ReceiverUnverifiedException -> activity.getString(R.string.send_unverified_text)
        is ReceiverDeclinedException -> activity.getString(R.string.send_declined_text)
        is TransferRefusedException -> activity.getString(R.string.send_refused_text, e.message)
        // The download's own checks say what was wrong; anything else is the network's doing.
        is UpdateCheckException -> Updater.reason(e)
        else -> activity.getString(R.string.send_lost_text, Updater.reason(e))
    }

    private fun post(block: () -> Unit) = activity.runOnUiThread {
        if (!activity.isDestroyed && progress.showing) block()
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val WAIT_MS = 120_000L
    }
}
