package app.libremotereceiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.libremotetvservice.protocol.FileReceiver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A new version of the receiver itself, sent by a paired phone: Libremote on the phone keeps the receiver up to
 * date, and the receiver never goes online for it. The APK goes to the app's cache rather than Download/Libremote,
 * must be a newer Libremote Receiver signed like this one, and then opens in the system installer at once (whatever
 * the "open the installer" setting says, since the user asked on the phone). Android still asks before installing.
 */
object SelfUpdate {
    private const val NAME = "libremote-receiver.apk"

    /** The update that arrived last; the installer reads it through [FilesProvider]. */
    fun file(context: Context) = File(File(context.cacheDir, "update"), NAME)

    /**
     * At start: drops an update that is installed now (or older), so the cache doesn't keep it, and one that never
     * arrived whole: only if older than [beforeMs] (when the receiver started), so not one arriving now.
     */
    fun cleanUp(context: Context, beforeMs: Long) {
        val apk = file(context)
        File(apk.parentFile, "$NAME.part").takeIf { it.lastModified() < beforeMs }?.delete()
        if (!apk.isFile) return
        val info = archiveInfo(context, apk)
        if (info == null || versionCode(info) <= versionCode(installed(context))) apk.delete()
    }

    /**
     * Writes the one file of an update request into the cache, then checks it. A refusal (thrown from [complete])
     * goes back to the phone, which shows it; nothing is installed.
     */
    class Sink(
        private val context: Context,
        private val events: Downloads.Sink.Events,
        /** The update checked out: its version name, for the screen. */
        private val checked: (String) -> Unit,
    ) : FileReceiver.Sink {
        private var name = NAME
        private var part: File? = null

        override fun begin(sender: String, count: Int, size: Long) = events.begin(sender, count, size)

        override fun open(path: String, size: Long, lastModifiedMs: Long): OutputStream {
            name = path.substringAfterLast('/')
            val dir = file(context).parentFile!!.apply {
                deleteRecursively()
                mkdirs()
            }
            val out = try {
                FileOutputStream(File(dir, "$NAME.part").also { part = it })
            } catch (e: IOException) {
                throw IOException(context.getString(R.string.cant_save, name), e)
            }
            events.file(name, 1, 1)
            return out
        }

        override fun complete(path: String) {
            val temp = part ?: return
            val apk = file(context)
            if (!temp.renameTo(apk)) {
                temp.delete()
                throw IOException(context.getString(R.string.cant_save, name))
            }
            val version = try {
                check(apk)
            } catch (e: IOException) {
                apk.delete()
                throw e
            }
            checked(version)
            events.complete(ReceivedFile(FilesProvider.updateUri(context), name, apk.length(), APK_TYPE, System.currentTimeMillis(), apk))
        }

        override fun abandon(path: String) {
            part?.delete()
        }

        override fun progress(received: Long, total: Long) = events.progress(received, total)

        /** That [apk] is a newer Libremote Receiver, signed like this one; returns its version name. */
        private fun check(apk: File): String {
            val info = archiveInfo(context, apk)
            if (info == null || info.packageName != context.packageName) throw IOException(context.getString(R.string.update_not_receiver))
            val installed = installed(context)
            if (versionCode(info) <= versionCode(installed)) {
                throw IOException(context.getString(R.string.update_not_newer, installed.versionName))
            }
            // Android refuses an update signed by anyone else anyway; this says so before the installer opens.
            // The update may add newer certificates (key rotation), but must keep this one's.
            val signers = certificates(info)
            if (signers.isEmpty() || !signers.containsAll(certificates(installed))) {
                throw IOException(context.getString(R.string.update_not_signed))
            }
            return info.versionName ?: versionCode(info).toString()
        }
    }

    // Signatures are compared as sets of fingerprints, so a package with several signers can't slip one past.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.path, SIGNATURE_FLAGS) }.getOrNull()

    private fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS)

    /** SHA-256 fingerprints (lowercase hex) of the certificates that signed a package. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signing = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else null
        return (signing ?: info.signatures).orEmpty()
            .map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }
            .toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
