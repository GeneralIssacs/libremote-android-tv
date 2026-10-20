package app.libremotetvservice.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import app.libremotetvservice.BuildConfig
import app.libremotetvservice.R
import app.libremotetvservice.data.Tv
import app.libremotetvservice.libremote
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A download that failed its checks; the message says which, as a sentence. */
internal class UpdateCheckException(message: String) : IOException(message)

/**
 * Updates from Libremote's GitHub releases, for copies that no app store keeps up to date: Libremote itself, and
 * Libremote Receiver on paired TVs (ReceiverUpdate), which never goes online by itself. Offered only when no store
 * installed Libremote and this copy carries the release signature (anything else couldn't take a GitHub APK). It
 * does nothing until the user asks: automatic checks are opt-in, and every install needs Android's confirmation.
 * Google Play builds get a stub instead (src/play).
 */
object Updater {

    /** App stores and update managers: when one of them installed Libremote, it also updates it. */
    private val STORES = setOf(
        "com.android.vending",                                  // Google Play
        "org.fdroid.fdroid", "org.fdroid.basic",                // the F-Droid client, whatever the repository
        "com.looker.droidify", "com.machiav3lli.fdroid",        // Droid-ify, Neo Store
        "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid",  // Obtainium
        "cm.aptoide.pt", "cm.aptoidetv.pt",                     // Aptoide, Aptoide TV
        "com.aurora.store", "app.accrescent.client", "com.amazon.venezia",
    )
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR
    private const val MAX_APK = 50L * 1024 * 1024
    const val RECEIVER_PACKAGE = "app.libremotereceiver"

    /** A GitHub release: this app's APK and the receiver's, either of which may be missing. */
    class Release(val version: String, val notes: String, val app: Releases.Asset?, val receiver: Releases.ReceiverAsset?)

    private var offered: Boolean? = null
    /** When this process last tried an automatic check. */
    private var triedAt = 0L
    /** A download is under way (this app or a receiver): its folder in the cache stays. */
    @Volatile internal var downloading = false

    fun offered(context: Context): Boolean = offered ?: (installer(context) !in STORES &&
        BuildConfig.RELEASE_CERT_SHA256 in certificates(installedInfo(context))).also { offered = it }

    // Settings

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Check once a day when the remote opens. Off unless the user turns it on. */
    fun autoCheck(context: Context) = prefs(context).getBoolean("autoCheck", false)

    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoCheck", on).apply()

    /** When the last check succeeded, or 0. */
    fun lastCheck(context: Context) = prefs(context).getLong("lastCheck", 0)

    // Checking

    /**
     * When the remote opens for [tv]: checks for a new release if the user turned checks on and a day has passed,
     * and offers this app's update. A receiver update for [tv] is mentioned once per version, without a dialog.
     */
    fun checkOnStart(activity: Activity, tv: Tv?) {
        if (!offered(activity)) return
        if (!downloading) File(activity.cacheDir, "update").deleteRecursively()
        val now = System.currentTimeMillis()
        // The remote starts often (back from Settings, say): offline, try again an hour later rather than each time.
        if (!autoCheck(activity) || now - lastCheck(activity) < DAY || now - triedAt < HOUR) {
            announceReceiver(activity, tv)
            return
        }
        triedAt = now
        thread(name = "update-check") {
            // Offline or rate-limited: try again next time.
            val release = runCatching { fetchLatest() }.getOrNull() ?: return@thread
            save(activity, release)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val app = release.app
                if (app != null && Releases.isNewer(release.version, BuildConfig.VERSION_NAME) &&
                    release.version != prefs(activity).getString("skipped", null)
                ) {
                    offer(activity, release, app, atStart = true)
                    // The offer mentions the receiver too.
                    tv?.let { receiverUpdate(activity, it) }?.let { prefs(activity).edit().putString("announced", it).apply() }
                } else {
                    announceReceiver(activity, tv)
                }
            }
        }
    }

    /** Settings' "Check for updates": offers this app's update, or says it is the latest. [done] runs either way. */
    fun checkNow(activity: Activity, done: () -> Unit) {
        thread(name = "update-check") {
            val result = runCatching { fetchLatest() }
            result.onSuccess { save(activity, it) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                done()
                result.onSuccess {
                    val app = it.app
                    if (app != null && Releases.isNewer(it.version, BuildConfig.VERSION_NAME)) offer(activity, it, app, atStart = false)
                    else toast(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                }.onFailure { toast(activity, activity.getString(R.string.update_check_failed, reason(it))) }
            }
        }
    }

    /** The latest release from the last check, kept so a receiver update can be offered later, offline. */
    fun latest(context: Context): Release? = runCatching {
        val o = JSONObject(prefs(context).getString("latest", null)!!)
        fun asset(key: String) = o.optJSONObject(key)?.let {
            Releases.Asset(it.getString("name"), it.getString("url"), it.getLong("size"), it.optString("sha256").ifEmpty { null })
        }
        val receiver = asset("receiver")?.let { a -> o.optString("receiverVersion").takeIf { it.isNotEmpty() }?.let { Releases.ReceiverAsset(it, a) } }
        Release(o.getString("version"), o.optString("notes"), asset("app"), receiver)
    }.getOrNull()

    private fun save(context: Context, release: Release) {
        fun asset(a: Releases.Asset) = JSONObject().put("name", a.name).put("url", a.url).put("size", a.size).put("sha256", a.sha256 ?: "")
        val o = JSONObject().put("version", release.version).put("notes", release.notes)
        release.app?.let { o.put("app", asset(it)) }
        release.receiver?.let { o.put("receiver", asset(it.asset)).put("receiverVersion", it.version) }
        prefs(context).edit().putString("latest", o.toString()).putLong("lastCheck", System.currentTimeMillis()).apply()
    }

    // Libremote Receiver on the TVs

    /** The newer receiver version known for [tv] (from the last check), or null when none is, or its version isn't. */
    fun receiverUpdate(context: Context, tv: Tv): String? {
        if (!offered(context)) return null
        val receiver = latest(context)?.receiver ?: return null
        return receiver.version.takeIf { Releases.receiverIsNewer(receiver, context.libremote.receivers.version(tv)) }
    }

    /** [receiverUpdate], unless the user dismissed its note in the TV menu. */
    fun receiverNote(context: Context, tv: Tv): String? =
        receiverUpdate(context, tv)?.takeIf { prefs(context).getString("dismissed:${tv.pin}", null) != it }

    fun dismissReceiverNote(context: Context, tv: Tv) {
        receiverUpdate(context, tv)?.let { prefs(context).edit().putString("dismissed:${tv.pin}", it).apply() }
    }

    /** Asks, then updates the receiver on [tv] to the latest release's (ReceiverUpdate). */
    fun updateReceiver(activity: Activity, tv: Tv) {
        val release = latest(activity) ?: return
        val receiver = release.receiver ?: return
        val current = activity.libremote.receivers.version(tv) ?: return
        ReceiverUpdate(activity, tv, receiver, current, release.notes).confirm()
    }

    /** Mentions a receiver update for [tv] once per version: the TV menu has the way to install it. */
    private fun announceReceiver(activity: Activity, tv: Tv?) {
        val version = tv?.let { receiverNote(activity, it) } ?: return
        val prefs = prefs(activity)
        if (prefs.getString("announced", null) == version) return
        prefs.edit().putString("announced", version).apply()
        toast(activity, activity.getString(R.string.update_announce_receiver, version, tv.name))
    }

    // This app

    private fun offer(activity: Activity, release: Release, apk: Releases.Asset, atStart: Boolean) {
        val details = activity.getString(R.string.update_details, Formatter.formatShortFileSize(activity, apk.size.coerceAtLeast(0)))
        val parts = mutableListOf(release.notes, details)
        val tvs = activity.libremote.tvs.all().filter { receiverUpdate(activity, it) != null }
        if (tvs.isNotEmpty()) parts += activity.getString(R.string.update_receiver_too, release.receiver!!.version)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(parts.filter { it.isNotEmpty() }.joinToString("\n\n"))
            .setPositiveButton(R.string.update_install) { _, _ -> download(activity, release, apk) }
            .setNegativeButton(R.string.update_later, null)
            .apply {
                if (atStart) setNeutralButton(R.string.update_skip) { _, _ -> prefs(activity).edit().putString("skipped", release.version).apply() }
            }
            .show()
    }

    private fun download(activity: Activity, release: Release, apk: Releases.Asset) {
        if (downloading) return
        downloading = true
        val cancelled = AtomicBoolean(false)
        val progress = UpdateProgress(activity, activity.getString(R.string.update_downloading, release.version)) { cancelled.set(true) }
        progress.download(0, apk.size)
        thread(name = "update-download") {
            val result = runCatching {
                val file = fetchApk(activity, apk, cancelled) { done, total -> activity.runOnUiThread { progress.download(done, total) } }
                try {
                    verify(activity, file, activity.packageName, versionCode(installedInfo(activity)))
                    if (!cancelled.get()) install(activity, file)
                } finally {
                    file.delete()
                }
            }
            downloading = false
            activity.runOnUiThread {
                progress.dismiss()
                result.onFailure { if (!cancelled.get()) toast(activity, activity.getString(R.string.update_failed, reason(it))) }
            }
        }
    }

    // Network

    private fun fetchLatest(): Release {
        val conn = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "Libremote")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            val list = (0 until (assets?.length() ?: 0)).map { i ->
                val a = assets!!.getJSONObject(i)
                Releases.Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size", -1),
                    Releases.sha256(a.optString("digest")))
            }
            val notes = if (json.isNull("body")) "" else Releases.plainNotes(json.optString("body"))
            return Release(version, notes, Releases.pickApp(list, version), Releases.pickReceiver(list))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads [apk] into the cache and checks that it is complete and intact (its size, and GitHub's SHA-256
     * digest when the release has one). [verify] then checks what it is.
     */
    internal fun fetchApk(context: Context, apk: Releases.Asset, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, apk.name.replace('/', '_'))
        val conn = URL(apk.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Libremote")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: apk.size
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw UpdateCheckException(context.getString(R.string.update_corrupt))
                        if (done - shown >= 32 * 1024) {
                            shown = done
                            progress(done, total)
                        }
                    }
                }
            }
            if (apk.size > 0 && done != apk.size) throw UpdateCheckException(context.getString(R.string.update_corrupt))
            val expected = apk.sha256
            if (expected != null && hex(sha.digest()) != expected) throw UpdateCheckException(context.getString(R.string.update_corrupt))
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // Checks and install

    /**
     * That [file] is [packageName], with a versionCode above [newerThan], signed with Libremote's release key. For
     * this app Android would refuse anything else anyway; this says so first. For the receiver, the TV checks again.
     */
    internal fun verify(context: Context, file: File, packageName: String, newerThan: Long) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
        if (info == null) throw UpdateCheckException(context.getString(R.string.update_corrupt))
        if (info.packageName != packageName) throw UpdateCheckException(context.getString(R.string.update_wrong_app))
        if (versionCode(info) <= newerThan) throw UpdateCheckException(context.getString(R.string.update_not_newer))
        if (BuildConfig.RELEASE_CERT_SHA256 !in certificates(info)) throw UpdateCheckException(context.getString(R.string.update_not_signed))
    }

    /** Hands the APK to Android's installer, which asks the user to confirm; [InstallReceiver] hears the outcome. */
    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("libremote.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // UpdatedReceiver opens Libremote again once the new version is in.
            prefs(context).edit().putLong("relaunch", System.currentTimeMillis()).apply()
            val intent = Intent(context, InstallReceiver::class.java)
            // The installer adds the status to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Whether the user started an update from the app in the last 10 minutes; true only once. */
    internal fun takeRelaunch(context: Context): Boolean {
        val at = prefs(context).getLong("relaunch", 0)
        prefs(context).edit().remove("relaunch").apply()
        return System.currentTimeMillis() - at in 0..10 * 60 * 1000L
    }

    private fun installer(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    }.getOrNull()

    // Signatures are compared as a set of fingerprints, so a package with several signers can't slip one past.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    private fun installedInfo(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS)

    /** SHA-256 fingerprints (lowercase hex) of the certificates that signed a package. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signing = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else null
        return (signing ?: info.signatures).orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    internal fun reason(e: Throwable) = e.message ?: e.javaClass.simpleName

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
