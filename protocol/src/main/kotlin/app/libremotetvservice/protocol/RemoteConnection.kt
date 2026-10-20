package app.libremotetvservice.protocol

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLSocket

/** The TV no longer trusts this phone (it was never paired, or the pairing was removed on the TV). */
class NotPairedException(cause: Throwable?) : ProtocolException("The TV does not recognise this phone", cause)

/** The TV reported [error] and dropped the connection. */
class TvErrorException(val error: TvError) : ProtocolException("The TV reported $error")

/**
 * A remote-control connection to a paired TV on port 6466. [run] connects and then handles the TV's messages
 * until the connection ends; command methods may be called from any thread once [Listener.onReady] has run,
 * and are dropped before that. Listener calls come from the connection's reader thread.
 */
class RemoteConnection(
    private val host: String,
    private val tvPin: String,
    private val identity: ClientIdentity,
    private val client: ClientInfo,
    private val listener: Listener,
    private val port: Int = REMOTE_PORT,
    private val wantedFeatures: Int = Features.WANTED,
) : Closeable {

    interface Listener {
        /** The TV accepted the connection; [features] are the ones in use. */
        fun onReady(tv: TvInfo, features: Int) {}

        /** Whether the TV's screen is on. */
        fun onPower(on: Boolean) {}

        fun onVolume(volume: VolumeState) {}

        /**
         * The focused text field changed ([newField]) or its text did. [newField] is also true when only the
         * app in front changed: then [TvTextField.isEditable] is false. Text the phone just typed is not echoed.
         */
        fun onTextField(field: TvTextField, newField: Boolean) {}

        /** The TV wants its keyboard shown or hidden; the phone's keyboard stands in for it. */
        fun onKeyboardRequest(show: Boolean) {}

        /** The connection ended: [error] is null after [close], else why it failed. Called exactly once. */
        fun onClosed(error: Throwable?) {}
    }

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "libremote-writer") }
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile var features = 0
        private set

    /** Set by CONFIGURE, reported once the TV activates the connection. */
    private var tv: TvInfo? = null

    private val lock = Any()
    private var volume: VolumeState? = null
    private var field: TvTextField? = null

    /** The TV's version of the field text (TextStatus counter), needed to edit a field another remote touched. */
    private var textCounter = 0

    /** The field text as the phone last knew it: the base for computing what changed. */
    private var knownText = ""

    /** Connects and handles messages until the connection ends. Blocks; call it on a background thread. */
    fun run() {
        var error: Throwable? = null
        var receivedAny = false
        val trust = PinnedTrustManager(tvPin)
        try {
            val raw = Tls.open(host, port, HANDSHAKE_TIMEOUT_MS)
            val socket = try {
                Tls.secure(raw, host, port, identity, trust)
            } catch (e: IOException) {
                throw if (trust.mismatch) TvIdentityChangedException() else NotPairedException(e)
            }
            this.socket = socket
            output = socket.outputStream
            if (closed) return
            val input = BufferedInputStream(socket.inputStream)
            while (!closed) {
                val data = try {
                    input.readDelimited()
                } catch (e: IOException) {
                    // Under TLS 1.3 a TV rejects an unknown certificate after the handshake has seemed to work:
                    // the first read then fails with an alert, a reset or a closed stream.
                    if (!receivedAny && e !is SocketTimeoutException) throw NotPairedException(e)
                    throw e
                } ?: if (receivedAny) throw EOFException("The TV closed the connection") else throw NotPairedException(null)
                receivedAny = true
                handle(ProtoMessage.parse(data), socket)
            }
        } catch (e: Throwable) {
            error = if (closed) null else e
        } finally {
            closed = true
            ready = false
            runCatching { socket?.close() }
            writer.shutdown()
            listener.onClosed(error)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
    }

    /** Presses and releases [keyCode] (an Android KeyEvent code). */
    fun press(keyCode: Int) = key(keyCode, Remote.KEY_PRESS)

    /** Holds [keyCode] until [release]: the TV sees a long press. */
    fun hold(keyCode: Int) = key(keyCode, Remote.KEY_DOWN)

    fun release(keyCode: Int) = key(keyCode, Remote.KEY_UP)

    private fun key(keyCode: Int, direction: Int) =
        send(Features.KEYS, Remote.KEY_INJECT, ProtoWriter().int(1, keyCode).int(2, direction))

    /**
     * Opens [url] on the TV (Android's ACTION_VIEW), in [packageName] when given. The TV's service checks that the
     * package is installed first, but it can only see the few apps Android lets it see; for any other it does
     * nothing, or opens the Play Store page when [storePageIfMissing] (an error if the TV has no Play Store).
     */
    fun openLink(url: String, packageName: String? = null, storePageIfMissing: Boolean = false) {
        val link = ProtoWriter().string(1, url)
        if (packageName != null) link.string(2, packageName).bool(3, storePageIfMissing)
        send(Features.APP_LINKS, Remote.APP_LINK, link)
    }

    fun volumeUp() = adjustVolume(Remote.VOLUME_RAISE, KeyCodes.VOLUME_UP)

    fun volumeDown() = adjustVolume(Remote.VOLUME_LOWER, KeyCodes.VOLUME_DOWN)

    fun toggleMute() = adjustVolume(Remote.VOLUME_TOGGLE_MUTE, KeyCodes.VOLUME_MUTE)

    /** Sets an absolute volume, when [VolumeState.absolute]. */
    fun setVolume(level: Int) {
        val device = synchronized(lock) { volume }?.takeIf { it.absolute } ?: return
        send(Features.VOLUME, Remote.SET_VOLUME_LEVEL, ProtoWriter().int(1, device.deviceId).int(2, level.coerceIn(0, device.max)))
    }

    /**
     * Volume through the volume feature when the TV reported an output that supports it, else as a key, which
     * also reaches TVs that pass volume on to an amplifier over HDMI-CEC.
     */
    private fun adjustVolume(direction: Int, fallbackKey: Int) {
        val device = synchronized(lock) { volume }
        if (features and Features.VOLUME != 0 && device != null && device.control != VolumeState.Control.NONE) {
            send(Features.VOLUME, Remote.ADJUST_VOLUME_LEVEL, ProtoWriter().int(1, device.deviceId).int(2, direction))
        } else {
            press(fallbackKey)
        }
    }

    /** Makes the TV's own remote beep, where the TV supports it. */
    fun findRemote(ring: Boolean) =
        send(Features.FIND_MY_REMOTE, Remote.FIND_MY_REMOTE, ProtoWriter().int(1, if (ring) 1 else 2))

    /**
     * Makes the focused field's text [text], sending only what changed. Without a known field, types blind: the
     * TV deletes and types characters at its cursor, which is right when the cursor is at the end.
     */
    fun setText(text: String) {
        val edit = synchronized(lock) {
            val old = knownText
            if (old == text) return
            knownText = text
            val current = field
            if (current != null && current.isEditable) {
                val change = TextChange.between(old, text)
                // Select the changed range first: the TV commits text over the selection, and a pure insertion
                // would otherwise land wherever the TV's cursor happens to be.
                batchEdit(
                    current.fieldId, textCounter,
                    ProtoWriter().message(1, ProtoWriter().int(2, change.start).int(3, change.end)),
                    ProtoWriter().message(2, ProtoWriter().int(1, change.start).int(2, change.end).string(3, change.inserted)),
                )
            } else {
                // Blind typing (field 0): the TV presses Delete (end - start) times, then types the text.
                val prefix = TextChange.commonPrefix(old, text)
                val blind = ProtoWriter().int(1, 0).int(2, old.length - prefix).string(3, text.substring(prefix))
                batchEdit(0, 0, ProtoWriter().message(2, blind))
            }
        }
        send(Features.IME, Remote.IME_BATCH_EDIT, edit)
    }

    /** The keyboard's Enter: the field's action (Search, Go, Send…) when it has one, else the Enter key. */
    fun submitText() {
        val edit = synchronized(lock) {
            val current = field ?: return@synchronized null
            val action = current.imeOptions and IME_MASK_ACTION
            val noEnterAction = current.imeOptions and IME_FLAG_NO_ENTER_ACTION != 0
            if (!current.isEditable || action !in IME_ACTION_GO..IME_ACTION_PREVIOUS || noEnterAction) return@synchronized null
            batchEdit(current.fieldId, textCounter, ProtoWriter().message(5, ProtoWriter().int(1, action)))
        }
        if (edit != null && features and Features.IME != 0) {
            send(Features.IME, Remote.IME_BATCH_EDIT, edit)
        } else {
            press(KeyCodes.ENTER)
        }
    }

    /** IME_BATCH_EDIT{1: field id, 2: text counter, 3: edits}; each edit is 1: select, 2: replace or 5: action. */
    private fun batchEdit(fieldId: Int, counter: Int, vararg edits: ProtoWriter): ProtoWriter {
        val batch = ProtoWriter().int(1, fieldId).int(2, counter)
        edits.forEach { batch.message(3, it) }
        return batch
    }

    private fun handle(message: ProtoMessage, socket: SSLSocket) {
        val type = message.fieldNumbers.firstOrNull() ?: return
        val body = message.message(type) ?: return
        when (type) {
            Remote.CONFIGURE -> {
                // DeviceInfo{1: model, 2: manufacturer, 3: os, 4: os version, 5: app name, 6: app version}; the
                // service reports itself as the app ("com.google.android.tv.remote.service", "5.99.551144881").
                val device = body.message(2)
                val offered = body.int(1)
                tv = TvInfo(offered, device?.string(1), device?.string(2), device?.string(4), device?.string(5), device?.string(6))
                // Asking for a feature the TV did not offer makes it drop the connection.
                features = wantedFeatures and offered
                sendNow(Remote.CONFIGURE, ProtoWriter().int(1, features).message(2, clientInfo()))
            }
            Remote.SET_ACTIVE -> {
                sendNow(Remote.SET_ACTIVE, ProtoWriter().int(1, features))
                socket.soTimeout = IDLE_TIMEOUT_MS
                ready = true
                tv?.let { listener.onReady(it, features) }
            }
            Remote.PING_REQUEST -> sendNow(Remote.PING_RESPONSE, ProtoWriter().int(1, body.int(1)))
            Remote.ERROR -> throw TvErrorException(TvError.of(body.int(1)))
            Remote.POWER -> listener.onPower(body.bool(1))
            Remote.VOLUME -> {
                val state = VolumeState(
                    deviceId = body.int(1),
                    deviceName = body.string(3),
                    control = when (body.int(4)) {
                        2 -> VolumeState.Control.ABSOLUTE
                        1 -> VolumeState.Control.RELATIVE
                        else -> VolumeState.Control.NONE
                    },
                    level = body.int(7),
                    max = body.int(6),
                    muted = body.bool(8),
                )
                synchronized(lock) { volume = state }
                listener.onVolume(state)
            }
            Remote.IME_FIELD -> {
                // EditorInfo{1: field id, 2: inputType, 3: imeOptions, 10: hint, 11: label, 12: package}
                // and, for editable fields, TextStatus{1: counter, 2: text, 3: selection start, 4: end, 6: hint}.
                val editor = body.message(1) ?: return
                val status = body.message(2)
                val focused = TvTextField(
                    fieldId = editor.int(1),
                    inputType = editor.int(2),
                    imeOptions = editor.int(3),
                    hint = editor.string(10) ?: status?.string(6),
                    label = editor.string(11),
                    packageName = editor.string(12),
                    text = status?.string(2) ?: "",
                    selectionStart = status?.int(3) ?: 0,
                    selectionEnd = status?.int(4) ?: 0,
                )
                synchronized(lock) {
                    field = focused
                    textCounter = status?.int(1) ?: 0
                    knownText = focused.text
                }
                listener.onTextField(focused, newField = true)
            }
            Remote.IME_TEXT -> {
                // {1: the change came from this phone, 2: TextStatus}
                val status = body.message(2) ?: return
                val updated = synchronized(lock) {
                    textCounter = status.int(1)
                    val current = field ?: return
                    current.copy(text = status.string(2) ?: "", selectionStart = status.int(3), selectionEnd = status.int(4))
                        .also { field = it; knownText = it.text }
                }
                if (!body.bool(1)) listener.onTextField(updated, newField = false)
            }
            Remote.IME_SHOW -> listener.onKeyboardRequest(body.bool(1))
        }
    }

    private fun clientInfo() = ProtoWriter()
        .string(1, client.model)
        .string(2, client.manufacturer)
        .int(3, Remote.OS_ANDROID)
        .string(4, client.osVersion)
        .string(5, client.appName)
        .string(6, client.appVersion)

    /**
     * Sends a command that belongs to [feature]. Dropped before the TV is ready and when the feature is not in
     * use: the TV answers either with an error and drops the connection.
     */
    private fun send(feature: Int, type: Int, payload: ProtoWriter) {
        if (ready && features and feature != 0) sendNow(type, payload)
    }

    private fun sendNow(type: Int, payload: ProtoWriter) {
        val data = ProtoWriter().message(type, payload).toByteArray()
        try {
            writer.execute {
                try {
                    output?.writeDelimited(data)
                } catch (e: IOException) {
                    // The reader sees the broken connection and reports it.
                    runCatching { socket?.close() }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000

        /** The TV pings every 5 s while a phone is active, so silence this long means the link is gone. */
        const val IDLE_TIMEOUT_MS = 30_000

        // android.view.inputmethod.EditorInfo constants.
        const val IME_MASK_ACTION = 0xff
        const val IME_ACTION_GO = 2
        const val IME_ACTION_PREVIOUS = 7
        const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
    }
}

/** Replacing [start, end) of the old text with [inserted] gives the new text. */
internal data class TextChange(val start: Int, val end: Int, val inserted: String) {
    companion object {
        fun between(old: String, new: String): TextChange {
            val prefix = commonPrefix(old, new)
            var suffix = 0
            val maxSuffix = minOf(old.length, new.length) - prefix
            while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            // A suffix starting with a low surrogate would split a pair: give that char back to the change.
            if (suffix > 0 && Character.isLowSurrogate(old[old.length - suffix])) suffix--
            return TextChange(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        }

        /** Length of the shared prefix, never ending inside a surrogate pair. */
        fun commonPrefix(a: String, b: String): Int {
            var i = 0
            val max = minOf(a.length, b.length)
            while (i < max && a[i] == b[i]) i++
            if (i > 0 && Character.isHighSurrogate(a[i - 1])) i--
            return i
        }
    }
}
