package app.libremotereceiver

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import app.libremotetvservice.protocol.FileReceiver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** A received file, in Download/Libremote. [file] is set on Android 8 and 9, where files are written directly. */
data class ReceivedFile(val uri: Uri, val name: String, val size: Long, val mimeType: String, val addedMs: Long, val file: File? = null) {
    val isApk get() = mimeType == APK_TYPE
}

internal const val APK_TYPE = "application/vnd.android.package-archive"

/**
 * Received files live in Download/Libremote, where other apps on the TV (players, file managers) find them.
 *
 * - Android 10 and later: written through MediaStore, which needs no permission. The app sees the files it wrote.
 * - Android 8 and 9: written to the folder directly, which needs the storage permission. Each file is written under
 *   a hidden temporary name and renamed once complete, then added to the media index; other apps open it through
 *   [FilesProvider].
 */
object Downloads {
    private val FOLDER = "${Environment.DIRECTORY_DOWNLOADS}/Libremote"

    /** Before Android 10, files are written to the folder directly. */
    val direct get() = Build.VERSION.SDK_INT < 29

    /** Whether files can be saved: always from Android 10, with the storage permission before. */
    fun canSave(context: Context) =
        !direct || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Download/Libremote on Android 8 and 9. */
    @Suppress("DEPRECATION") // Deprecated from Android 10, where MediaStore is used instead.
    fun folder() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Libremote")

    /** The latest files received, newest first. */
    fun list(context: Context, limit: Int = 200): List<ReceivedFile> =
        if (direct) listFolder(context, limit) else listMediaStore(context, limit)

    @TargetApi(29)
    private fun listMediaStore(context: Context, limit: Int): List<ReceivedFile> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
            MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_ADDED,
        )
        val files = mutableListOf<ReceivedFile>()
        context.contentResolver.query(
            collection, columns, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$FOLDER/%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && files.size < limit) {
                val name = cursor.getString(1) ?: continue
                files += ReceivedFile(
                    ContentUris.withAppendedId(collection, cursor.getLong(0)), name, cursor.getLong(2),
                    cursor.getString(3) ?: typeOf(name), cursor.getLong(4) * 1000,
                )
            }
        }
        return files
    }

    private fun listFolder(context: Context, limit: Int): List<ReceivedFile> {
        if (!canSave(context)) return emptyList()
        return folder().walkTopDown()
            .onEnter { !it.name.startsWith('.') }
            .filter { it.isFile && !it.name.startsWith('.') }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { it.toReceived(context) }
            .toList()
    }

    /**
     * Removes what a transfer cut short by the receiver's end left behind (killed, crashed, the TV unplugged): the
     * hidden temporary files on Android 8 and 9, and on Android 10 and later the entries still marked pending, which
     * Android would otherwise keep for a week. Only what is older than [beforeMs] (when the receiver started), so a
     * transfer running now is never touched.
     */
    fun sweep(context: Context, beforeMs: Long) {
        if (direct) {
            if (!canSave(context)) return
            folder().walkTopDown()
                .filter { it.isFile && it.name.startsWith('.') && it.name.endsWith(".part") && it.lastModified() < beforeMs }
                .forEach { it.delete() }
        } else {
            sweepMediaStore(context, beforeMs)
        }
    }

    @TargetApi(29)
    private fun sweepMediaStore(context: Context, beforeMs: Long) {
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 1 AND " +
            "${MediaStore.Downloads.DATE_ADDED} < ?"
        // DATE_ADDED is in seconds.
        val args = arrayOf("$FOLDER/%", (beforeMs / 1000).toString())
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            // Pending entries are left out of queries unless asked for; the app sees only its own.
            val query = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val ids = mutableListOf<Long>()
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), query, null)?.use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
            ids.forEach { runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it), null, null) } }
        } else {
            @Suppress("DEPRECATION") // Android 10's way to include pending entries.
            runCatching { resolver.delete(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI), selection, args) }
        }
    }

    private fun File.toReceived(context: Context) =
        ReceivedFile(FilesProvider.uriFor(context, this), name, length(), typeOf(name), lastModified(), this)

    /** An APK waiting for the user to allow the receiver to install apps; see [resumeInstall]. */
    private var awaitingPermission: ReceivedFile? = null

    /** Opens [file] in the app that handles its type; an APK goes to the system installer. */
    fun open(activity: Activity, file: ReceivedFile) {
        if (file.isApk && !activity.packageManager.canRequestPackageInstalls()) {
            // Android asks once per app. Where the TV's settings lack this screen, the installer explains instead.
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            try {
                activity.startActivity(allow)
                awaitingPermission = file
                Toast.makeText(activity, R.string.install_allow, Toast.LENGTH_LONG).show()
                return
            } catch (e: ActivityNotFoundException) {
                // Fall through to the installer.
            }
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.open_none, file.name), Toast.LENGTH_LONG).show()
        }
    }

    /** Back from the "Install unknown apps" screen: if the user allowed it, the installer opens for that APK. */
    fun resumeInstall(activity: Activity) {
        val file = awaitingPermission ?: return
        awaitingPermission = null
        if (activity.packageManager.canRequestPackageInstalls()) open(activity, file)
    }

    fun delete(context: Context, file: ReceivedFile) {
        val path = file.file
        if (path == null) {
            runCatching { context.contentResolver.delete(file.uri, null, null) }
        } else if (path.delete()) {
            MediaScannerConnection.scanFile(context, arrayOf(path.path), null, null)
        }
    }

    private fun typeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /**
     * Writes one transfer into Download/Libremote, keeping the sender's folders. A file shows up only once it
     * arrived whole; a partial one is deleted.
     */
    class Sink(private val context: Context, private val events: Events) : FileReceiver.Sink {

        /** Called on the receiving thread. */
        interface Events {
            fun begin(sender: String, count: Int, size: Long)
            fun file(name: String, index: Int, count: Int)
            fun progress(received: Long, total: Long)
            fun complete(file: ReceivedFile)
        }

        /** A file being written: where it goes, and its temporary name before Android 10. */
        private class Pending(val received: ReceivedFile, val temp: File?)

        private val resolver = context.contentResolver
        private val pending = HashMap<String, Pending>()
        private var count = 0
        private var index = 0

        override fun begin(sender: String, count: Int, size: Long) {
            this.count = count
            events.begin(sender, count, size)
            if (!canSave(context)) throw IOException(context.getString(R.string.no_storage_permission))
            // Refused up front, the phone says why; the TV shows it too.
            val free = context.getExternalFilesDir(null)?.let { StatFs(it.path).availableBytes } ?: Long.MAX_VALUE
            if (size + SPARE_BYTES > free) {
                throw IOException(context.getString(R.string.no_space, size(free), size(size)))
            }
        }

        override fun open(path: String, size: Long, lastModifiedMs: Long): OutputStream {
            val name = path.substringAfterLast('/')
            val folder = path.substringBeforeLast('/', "")
            val out = if (direct) openFile(path, name, folder, size) else openMediaStore(path, name, folder, size)
            events.file(name, ++index, count)
            return out
        }

        @TargetApi(29)
        private fun openMediaStore(path: String, name: String, folder: String, size: Long): OutputStream {
            val type = typeOf(name)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, if (folder.isEmpty()) FOLDER else "$FOLDER/$folder")
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                ?: throw IOException(context.getString(R.string.cant_save, name))
            pending[path] = Pending(ReceivedFile(uri, name, size, type, System.currentTimeMillis()), null)
            return resolver.openOutputStream(uri) ?: throw IOException(context.getString(R.string.cant_save, name))
        }

        private fun openFile(path: String, name: String, folder: String, size: Long): OutputStream {
            val dir = if (folder.isEmpty()) folder() else File(folder(), folder)
            // Hidden while incomplete: file managers and the list skip names that start with a dot.
            val temp = File(dir, ".$name.part")
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No folder")
                val out = FileOutputStream(temp)
                pending[path] = Pending(ReceivedFile(Uri.EMPTY, name, size, typeOf(name), System.currentTimeMillis()), temp)
                return out
            } catch (e: IOException) {
                throw IOException(context.getString(R.string.cant_save, name), e)
            }
        }

        override fun complete(path: String) {
            val file = pending.remove(path) ?: return
            val temp = file.temp
            if (temp == null) {
                publish(file.received.uri)
                events.complete(file.received)
                return
            }
            val target = unique(File(temp.parentFile, file.received.name))
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException(context.getString(R.string.cant_save, file.received.name))
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
            events.complete(target.toReceived(context))
        }

        /** A file written through MediaStore becomes visible to other apps. */
        @TargetApi(29)
        private fun publish(uri: Uri) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }

        override fun abandon(path: String) {
            val file = pending.remove(path) ?: return
            if (file.temp != null) file.temp.delete() else runCatching { resolver.delete(file.received.uri, null, null) }
        }

        override fun progress(received: Long, total: Long) = events.progress(received, total)

        /** [file], or "name (1).ext", "name (2).ext"… when it is taken, as MediaStore names them. */
        private fun unique(file: File): File {
            if (!file.exists()) return file
            val base = file.name.substringBeforeLast('.')
            val extension = file.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == file.name) "" else ".$it" }
            return generateSequence(1) { it + 1 }.map { File(file.parentFile, "$base ($it)$extension") }.first { !it.exists() }
        }

        private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

        private companion object {
            /** Room left for the TV itself after a transfer. */
            const val SPARE_BYTES = 200L * 1024 * 1024
        }
    }
}
