package app.libremotetvservice.net

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import app.libremotetvservice.LibremoteApp
import app.libremotetvservice.data.Tv
import app.libremotetvservice.protocol.FileUnreadableException
import app.libremotetvservice.protocol.OutgoingFile
import app.libremotetvservice.protocol.ReceiverClient
import app.libremotetvservice.protocol.ReceiverDeclinedException
import app.libremotetvservice.protocol.ReceiverRefusedPhoneException
import app.libremotetvservice.protocol.ReceiverUnavailableException
import app.libremotetvservice.protocol.ReceiverUnverifiedException
import app.libremotetvservice.protocol.TransferLostException
import app.libremotetvservice.protocol.TransferRefusedException
import app.libremotetvservice.protocol.TransferStalledException
import app.libremotetvservice.ui.SendService
import java.io.File
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * Sends files to a TV, one batch at a time. Google's remote service can't take files, so they go to Libremote
 * Receiver on the TV, over its encrypted channel (see SENDFILES.md); when it isn't open, the TV is asked to open it
 * over the remote connection. A foreground service (SendService) keeps a batch going, with a notification, when
 * the app leaves the screen. States and listener calls come on the main thread.
 */
class SendController(private val app: LibremoteApp) {

    sealed interface State {
        data object Idle : State
        data class Preparing(val tv: Tv) : State
        data class OpeningReceiver(val tv: Tv) : State
        /** The TV asks whether to allow this phone; it shows [code] too. */
        data class Confirming(val tv: Tv, val code: String) : State
        data class Sending(val tv: Tv, val file: Int, val names: List<String>, val sent: Long, val total: Long) : State
        data class Sent(val tv: Tv, val count: Int, val total: Long) : State
        /** [lost]: for a connection that broke mid-batch, how far it got. */
        data class Failed(val tv: Tv, val problem: Problem, val detail: String?, val lost: LostBatch? = null) : State
    }

    enum class Problem {
        /** Libremote Receiver isn't on the TV: the link that opens it failed. */
        NO_RECEIVER,
        /** The TV couldn't be asked to open the receiver: no remote connection to it. */
        CANT_OPEN,
        /** The receiver opened but never answered: an old version, or another app holds its port. */
        NOT_LISTENING,
        /** The receiver couldn't prove it is the one on the TV; nothing was sent. */
        UNVERIFIED,
        /** The TV asked whether to allow this phone, and the user didn't. */
        DECLINED,
        /** The TV doesn't answer at its address. */
        UNREACHABLE,
        /** A file on this phone couldn't be read; the detail is its name. */
        UNREADABLE,
        /** The receiver turned the files down; the detail is its reason. */
        REFUSED,
        /** The receiver stopped taking data or never confirmed: often a full TV, or a receiver that was closed. */
        STALLED,
        /** The connection broke; the detail says how, and the state which files arrived (LostBatch). */
        LOST,
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("sends", android.content.Context.MODE_PRIVATE)
    private val listeners = mutableListOf<(State) -> Unit>()
    @Volatile private var client: ReceiverClient? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false

    var state: State = State.Idle
        private set

    val busy get() = state is State.Preparing || state is State.OpeningReceiver || state is State.Confirming || state is State.Sending

    /** The files of the latest batch, less those that arrived before its connection broke: what Try again sends. */
    var lastUris: List<Uri> = emptyList()
        private set

    /** Whether the notification permission was asked for already: only on the first send. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(value) = prefs.edit().putBoolean("askedNotifications", value).apply()

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    /**
     * Starts sending [uris] to [tv]; false while another batch is still on its way. While [tv] is known to have no
     * receiver, the TV is asked to open it again only when [retryAbsent] (the user's "Try again").
     */
    fun send(tv: Tv, uris: List<Uri>, retryAbsent: Boolean = false): Boolean {
        if (busy || uris.isEmpty()) return false
        cancelled = false
        lastUris = uris
        setState(State.Preparing(tv))
        SendService.start(app)
        worker = Thread({ run(tv, uris, retryAbsent) }, "libremote-send").apply { start() }
        return true
    }

    fun cancel() {
        cancelled = true
        client?.close()
        worker?.interrupt()
    }

    /** Forgets a finished batch, so the next screen starts fresh. */
    fun dismiss() {
        if (!busy) setState(State.Idle)
    }

    private fun run(tv: Tv, uris: List<Uri>, retryAbsent: Boolean) {
        val files = mutableListOf<Prepared>()
        var delivered = false
        var mine: ReceiverClient? = null
        try {
            uris.forEach { files += prepare(it) }
            val outgoing = files.map { it.outgoing }
            val names = outgoing.map { it.name }
            if (cancelled) throw CancellationException()
            app.receivers.open(
                tv, syncOnly = false, retryAbsent,
                opening = { post(State.OpeningReceiver(tv)) },
                confirming = { post(State.Confirming(tv, it)) },
                cancelled = { cancelled },
            ).use { session ->
                mine = session.client
                client = mine
                if (cancelled) throw CancellationException()
                var lastPost = 0L
                session.client.send(outgoing) { file, sent, total ->
                    // Often enough for a smooth bar, rarely enough not to flood the main thread.
                    val now = System.nanoTime() / 1_000_000
                    if (now - lastPost >= PROGRESS_MS || sent == total) {
                        lastPost = now
                        post(State.Sending(tv, file, names, sent, total))
                    }
                }
                post(State.Sent(tv, files.size, outgoing.sumOf { it.size }))
                delivered = true
                // The channel is open: keep the TV's app list fresh for shortcuts while at it.
                runCatching { app.receivers.fetchApps(tv, session) }
            }
        } catch (e: Throwable) {
            if (delivered) return
            post(
                when {
                    cancelled || e is CancellationException || e is InterruptedException -> State.Idle
                    e is FileUnreadableException -> State.Failed(tv, Problem.UNREADABLE, e.fileName)
                    e is ReceiverMissingException -> State.Failed(tv, Problem.NO_RECEIVER, null)
                    e is ReceiverLinkUnavailableException -> State.Failed(tv, Problem.CANT_OPEN, null)
                    e is ReceiverUnavailableException || e is ReceiverRefusedPhoneException -> State.Failed(tv, Problem.NOT_LISTENING, null)
                    e is ReceiverUnverifiedException -> State.Failed(tv, Problem.UNVERIFIED, null)
                    e is ReceiverDeclinedException -> State.Failed(tv, Problem.DECLINED, null)
                    e is TransferRefusedException -> State.Failed(tv, Problem.REFUSED, e.message)
                    e is TransferStalledException -> State.Failed(tv, Problem.STALLED, null)
                    e is TransferLostException -> {
                        val lost = LostBatch(files.map { it.outgoing.name }, e.arrived, e.started)
                        // Before the state goes out, so Try again picks up only what didn't arrive.
                        lastUris = uris.drop(lost.skip)
                        State.Failed(tv, Problem.LOST, e.message, lost)
                    }
                    e is SocketTimeoutException || e is NoRouteToHostException || e is UnknownHostException ->
                        State.Failed(tv, Problem.UNREACHABLE, null)
                    else -> State.Failed(tv, Problem.LOST, e.message ?: e.javaClass.simpleName)
                },
            )
        } finally {
            // A newer batch may have started while this one fetched the app list.
            if (client === mine) client = null
            files.forEach { it.close() }
        }
    }

    /**
     * A file opened up front, so it stays readable even if the app that shared it takes its permission back when
     * the share screen closes. Streams (no size) are copied to the cache first: the TV needs every size in advance.
     */
    private class Prepared(val outgoing: OutgoingFile, private val descriptor: ParcelFileDescriptor, private val copy: File?) {
        fun close() {
            runCatching { descriptor.close() }
            copy?.delete()
        }
    }

    private fun prepare(uri: Uri): Prepared {
        val resolver = app.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
        var descriptor = try {
            resolver.openFileDescriptor(uri, "r") ?: throw IOException("No file")
        } catch (e: Exception) {
            throw FileUnreadableException(name, e)
        }
        var copy: File? = null
        if (descriptor.statSize < 0) {
            try {
                copy = File(app.cacheDir, "send").apply { mkdirs() }.let { File.createTempFile("send", null, it) }
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> copy.outputStream().use { input.copyTo(it) } }
                descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                copy?.delete()
                throw FileUnreadableException(name, e)
            }
        }
        val source = descriptor
        // Read from the start through a descriptor of its own.
        val outgoing = OutgoingFile(name, source.statSize) {
            val own = source.dup()
            Os.lseek(own.fileDescriptor, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.AutoCloseInputStream(own)
        }
        return Prepared(outgoing, source, copy)
    }

    private fun post(state: State) = main.post { setState(state) }

    private fun setState(state: State) {
        this.state = state
        listeners.toList().forEach { it(state) }
    }

    private companion object {
        const val PROGRESS_MS = 100L
    }
}
