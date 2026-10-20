package app.libremotereceiver

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets the app that opens a file the receiver keeps (the package installer, a player) read it:
 *
 * - On Android 8 and 9, received files, which are plain files in Download/Libremote:
 *   `content://app.libremotereceiver.files/downloads/<path in the folder>`.
 * - On every version, an update of the receiver that a phone sent (SelfUpdate), which stays in the app's cache:
 *   `content://app.libremotereceiver.files/update`.
 *
 * Not exported; access comes only with the Intent that opens a file (FLAG_GRANT_READ_URI_PERMISSION), and only to
 * read those files.
 */
class FilesProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = fileOf(context!!, uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun getType(uri: Uri): String? {
        if (uri.pathSegments == listOf(UPDATE)) return APK_TYPE
        val name = uri.lastPathSegment ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Received files are read-only")
        val file = fileOf(context!!, uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val DOWNLOADS = "downloads"
        private const val UPDATE = "update"

        /** The URI that opens [file], which must be in Download/Libremote. */
        fun uriFor(context: Context, file: File): Uri {
            val path = file.canonicalFile.relativeTo(Downloads.folder().canonicalFile).invariantSeparatorsPath
            return base(context).path("$DOWNLOADS/$path").build()
        }

        /** The URI that opens the update a phone sent last. */
        fun updateUri(context: Context): Uri = base(context).path(UPDATE).build()

        private fun base(context: Context) = Uri.Builder().scheme("content").authority("${context.packageName}.files")

        /** The file [uri] names, if it is one inside Download/Libremote, or the update. */
        private fun fileOf(context: Context, uri: Uri): File? {
            val segments = uri.pathSegments
            if (segments == listOf(UPDATE)) return SelfUpdate.file(context).takeIf { it.isFile }
            if (segments.size < 2 || segments[0] != DOWNLOADS) return null
            val root = Downloads.folder().canonicalFile
            val file = File(root, segments.drop(1).joinToString("/")).canonicalFile
            return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
        }
    }
}
