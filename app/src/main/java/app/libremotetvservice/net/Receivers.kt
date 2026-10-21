package app.libremotetvservice.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.data.Tv
import app.libremotetvservice.protocol.InstalledApp
import app.libremotetvservice.protocol.ReceiverClient
import app.libremotetvservice.protocol.ReceiverInvite
import app.libremotetvservice.protocol.ReceiverRefusedPhoneException
import app.libremotetvservice.protocol.ReceiverUnavailableException
import app.libremotetvservice.protocol.ReceiverUnverifiedException
import app.libremotetvservice.protocol.publicKeyPin
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Whether a TV has Libremote Receiver, as far as this phone has seen. */
enum class ReceiverState { UNKNOWN, PRESENT, ABSENT }

/** An app on the TV, as Libremote Receiver reports it. */
data class TvAppInfo(val packageName: String, val label: String)

/** Libremote Receiver isn't on the TV: the link that opens it failed, now or the last time (see [Receivers.open]). */
class ReceiverMissingException : IOException("Libremote Receiver isn't on the TV")

/** The TV couldn't be asked to open Libremote Receiver: there was no remote connection to it in time. */
class ReceiverLinkUnavailableException : IOException("Couldn't ask the TV to open Libremote Receiver")

/**
 * Libremote Receiver on each paired TV, as the rest of the phone app sees it: whether the TV has it, the link that
 * opens an app through it, and the TV's app list it shares with paired phones. Listener calls come on the main thread.
 *
 * Shared contract between the Send files work (which implements the receiver side and the sync) and the shortcuts
 * (which use it). Keep the signatures.
 *
 * The channel to the receiver (ReceiverChannel.kt in :protocol, SENDFILES.md) uses this phone's own identity, the
 * one TVs pair with. The receiver's key is pinned per TV; the app list and icons are cached per TV in the app's
 * files.
 */
class Receivers(private val app: LibremoteApp) {
    private val prefs = app.getSharedPreferences("receivers", android.content.Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** App lists by TV pin, as last read from the cache; icons by TV pin and package. Main thread only. */
    private val appLists = HashMap<String, List<TvAppInfo>>()
    private val icons = HashMap<String, Bitmap?>()

    fun state(tv: Tv): ReceiverState =
        runCatching { ReceiverState.valueOf(prefs.getString("state:${tv.pin}", null) ?: "") }.getOrDefault(ReceiverState.UNKNOWN)

    fun setState(tv: Tv, state: ReceiverState) {
        if (state(tv) == state) return
        prefs.edit().putString("state:${tv.pin}", state.name).apply()
        notifyListeners()
    }

    /** The versionCode the TV's receiver reported when it last answered; null when unknown (older than 1.0.0). */
    fun version(tv: Tv): Long? = prefs.getLong("version:${tv.pin}", 0).takeIf { it > 0 }

    /** [version] as a version name: versionCodes are MAJOR * 10000 + MINOR * 100 + PATCH, so 10001 is "1.0.1". */
    fun versionName(tv: Tv): String? = version(tv)?.let { "${it / 10000}.${it / 100 % 100}.${it % 100}" }

    private fun setVersion(tv: Tv, version: Long?) {
        if (version(tv) == version) return
        prefs.edit().apply { if (version == null) remove("version:${tv.pin}") else putLong("version:${tv.pin}", version) }.apply()
        notifyListeners()
    }

    /**
     * Connects to [tv]'s receiver only if it is already open and verifies with the pin, without asking the TV to open
     * it, and returns the version it reports (after an update, say). Blocks; throws IOException when it isn't there.
     */
    fun probe(tv: Tv): Long? {
        val pin = prefs.getString("pin:${tv.pin}", null) ?: throw ReceiverUnavailableException(IOException("Never connected"))
        ReceiverClient.connect(tv.host, app.identity.get(), pin, null, phoneName()).use { client ->
            present(tv, client)
            return client.receiverVersion
        }
    }

    /**
     * The app link that makes the receiver open [packageName] on the TV, without showing itself. When the app isn't
     * there, the TV shows its Play Store page, or else names it ([name]) and shows the receiver for a moment.
     */
    fun launchLink(packageName: String, name: String): String =
        "libremote://launch?id=" + Uri.encode(packageName) + "&name=" + Uri.encode(name)

    /** The TV's launchable apps from the last sync, or empty if it never synced. */
    fun apps(tv: Tv): List<TvAppInfo> = appLists.getOrPut(tv.pin) { readApps(tv) }

    /** The icon of [packageName] from the last sync, or null. */
    fun icon(tv: Tv, packageName: String): Bitmap? {
        val key = "${tv.pin}/$packageName"
        if (key in icons) return icons[key]
        val bitmap = if (!isPackageName(packageName)) null else runCatching {
            BitmapFactory.decodeFile(File(cacheDir(tv), "icons/$packageName.png").path)
        }.getOrNull()
        icons[key] = bitmap
        return bitmap
    }

    /**
     * Fetches the TV's app list and icons through the receiver over the encrypted channel, opening the receiver for
     * a moment if it isn't open. [done] runs on the main thread: with null when the list was updated, else the reason.
     *
     * Only ever asked for by the user, so it tries even when the TV had no receiver last time: it may have one now.
     * [confirming] runs on the main thread with the code when the TV asks whether to allow this phone; the wait for
     * the answer stops once [cancelled] is true (the screen went away).
     */
    fun syncApps(tv: Tv, confirming: (code: String) -> Unit = {}, cancelled: () -> Boolean = { false }, done: (Throwable?) -> Unit) {
        Thread({
            val error = runCatching {
                open(tv, syncOnly = true, retryAbsent = true, confirming = { code -> main.post { confirming(code) } }, cancelled = cancelled)
                    .use { fetchApps(tv, it) }
            }.exceptionOrNull()
            main.post { done(error) }
        }, "libremote-sync").start()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    /** A verified connection to a TV's receiver. [openedForSync]: the receiver was opened only to fetch the app list. */
    class Session(val client: ReceiverClient, val openedForSync: Boolean) : Closeable {
        override fun close() = client.close()
    }

    /**
     * Connects to [tv]'s receiver, first asking the TV to open it (`libremote://receive`, or `libremote://sync` when
     * [syncOnly]) if it isn't listening, isn't paired with this phone yet, or has a new key. Blocks: call it on a
     * worker thread. [opening] runs on that thread just before the TV is asked. When the receiver doesn't know this
     * phone (new, or removed there), the TV asks the user whether to allow it: [confirming] gets the code the TV
     * shows too, and this waits for the answer while [cancelled] is false.
     *
     * Throws ReceiverMissingException when the TV has no receiver (no link is sent while that is known, unless
     * [retryAbsent]), ReceiverLinkUnavailableException when the TV couldn't be asked, ReceiverUnverifiedException
     * when the receiver failed verification, ReceiverDeclinedException when the user didn't allow this phone on the
     * TV, ReceiverUnavailableException when it opened but never answered, and
     * other IOExceptions when the TV can't be reached.
     */
    fun open(
        tv: Tv,
        syncOnly: Boolean,
        retryAbsent: Boolean,
        opening: () -> Unit = {},
        confirming: (code: String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Session {
        val identity = app.identity.get()
        val pin = prefs.getString("pin:${tv.pin}", null)
        val name = phoneName()
        if (pin != null) {
            try {
                return Session(ReceiverClient.connect(tv.host, identity, pin, null, name), openedForSync = false).also { present(tv, it.client) }
            } catch (e: ReceiverUnavailableException) {
                // Not open: open it below.
            } catch (e: ReceiverRefusedPhoneException) {
                // This phone was removed on the TV, or the receiver was reinstalled: the link pairs it again.
            } catch (e: ReceiverUnverifiedException) {
                // A new key: it must prove the secret of a fresh link.
            }
        }
        if (state(tv) == ReceiverState.ABSENT && !retryAbsent) throw ReceiverMissingException()
        val invite = ReceiverInvite.create(publicKeyPin(identity.certificate), name)
        opening()
        when (openLink(tv, invite.link(syncOnly))) {
            RemoteController.LinkResult.OPENED -> Unit
            RemoteController.LinkResult.FAILED -> {
                main.post { setState(tv, ReceiverState.ABSENT) }
                throw ReceiverMissingException()
            }
            RemoteController.LinkResult.UNAVAILABLE -> throw ReceiverLinkUnavailableException()
        }
        // The receiver starts listening a moment after it opens; a first start also makes its key.
        val deadline = System.nanoTime() + RECEIVER_START_MS * 1_000_000
        while (true) {
            try {
                val client = ReceiverClient.connect(tv.host, identity, pin, invite.secret, name, confirming = confirming, cancelled = cancelled)
                if (client.provedSecret) prefs.edit().putString("pin:${tv.pin}", client.receiverKey).apply()
                present(tv, client)
                return Session(client, openedForSync = syncOnly)
            } catch (e: IOException) {
                if (e !is ReceiverUnavailableException && e !is ReceiverRefusedPhoneException || System.nanoTime() > deadline) throw e
                Thread.sleep(RETRY_MS)
            }
        }
    }

    /** Fetches the app list over [session] and caches it, then closes a receiver opened only for this. */
    fun fetchApps(tv: Tv, session: Session) {
        val apps = session.client.apps()
        saveApps(tv, apps)
        if (session.openedForSync) runCatching { session.client.closeReceiver() }
        main.post {
            appLists.remove(tv.pin)
            icons.keys.removeAll { it.startsWith("${tv.pin}/") }
            notifyListeners()
        }
    }

    private fun present(tv: Tv, client: ReceiverClient) = main.post {
        setState(tv, ReceiverState.PRESENT)
        setVersion(tv, client.receiverVersion)
    }

    /** Asks the TV to open [url] over the remote connection, and waits for how that went. */
    private fun openLink(tv: Tv, url: String): RemoteController.LinkResult {
        val done = CountDownLatch(1)
        // Read after the latch, which makes the write visible.
        var result = RemoteController.LinkResult.UNAVAILABLE
        main.post {
            app.remote.openOn(tv, url) {
                result = it
                done.countDown()
            }
        }
        done.await(LINK_WAIT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    /** How the TV lists this phone among the receiver's paired phones: the name set on the phone, or its model. */
    private fun phoneName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun cacheDir(tv: Tv) = File(app.filesDir, "receivers/${tv.pin.take(32)}")

    private fun readApps(tv: Tv): List<TvAppInfo> = runCatching {
        val json = JSONArray(File(cacheDir(tv), "apps.json").readText())
        (0 until json.length()).map { json.getJSONObject(it) }.map { TvAppInfo(it.getString("package"), it.getString("label")) }
    }.getOrDefault(emptyList())

    /** Replaces [tv]'s cached list and icons as a whole, so a failed write leaves the previous one. */
    private fun saveApps(tv: Tv, apps: List<InstalledApp>) {
        val dir = cacheDir(tv)
        val next = File(dir.parentFile, "${dir.name}.new")
        next.deleteRecursively()
        val iconDir = File(next, "icons").apply { mkdirs() }
        val json = JSONArray()
        for (app in apps.filter { isPackageName(it.packageName) }) {
            json.put(JSONObject().put("package", app.packageName).put("label", app.label))
            app.icon?.let { File(iconDir, "${app.packageName}.png").writeBytes(it) }
        }
        File(next, "apps.json").writeText(json.toString())
        val old = File(dir.parentFile, "${dir.name}.old")
        old.deleteRecursively()
        dir.renameTo(old)
        if (!next.renameTo(dir)) {
            old.renameTo(dir)
            throw IOException("Couldn't save the app list")
        }
        old.deleteRecursively()
    }

    /** A package name, which is also safe as a file name. */
    private fun isPackageName(name: String) = name.length <= 255 && PACKAGE_NAME.matches(name)

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        const val LINK_WAIT_MS = 12_000L
        const val RECEIVER_START_MS = 10_000L
        const val RETRY_MS = 500L
    }
}
