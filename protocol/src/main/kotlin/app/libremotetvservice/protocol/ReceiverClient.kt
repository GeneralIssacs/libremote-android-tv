package app.libremotetvservice.protocol

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLSocket

/**
 * The phone's end of Libremote Receiver's channel (ReceiverChannel.kt): one verified connection, on which it can
 * make several requests in turn. Requests block; [close] from another thread cancels the one under way, which then
 * throws CancellationException.
 */
class ReceiverClient private constructor(
    private val socket: SSLSocket,
    /** The receiver's key hash: the pin to keep for this TV. */
    val receiverKey: String,
    /** True when the receiver proved the link's secret (its key was new or changed), false when it matched the pin. */
    val provedSecret: Boolean,
    /** The receiver's versionCode, from its hello; null for a receiver older than 1.0.0, which doesn't say. */
    val receiverVersion: Long?,
    /** Whether the receiver confirms each file as it is saved (from 1.0.1), so a broken send can say which arrived. */
    val confirmsEachFile: Boolean,
    private val stallTimeoutMs: Long,
) : Closeable {

    @Volatile private var cancelled = false
    private val input = socket.inputStream
    private val output = socket.outputStream

    /** The TLS version and cipher suite in use, such as TLSv1.3 and TLS_AES_128_GCM_SHA256. */
    val tls: String get() = socket.session.let { "${it.protocol} ${it.cipherSuite}" }

    /**
     * Sends [files] to the TV, which saves them in Download/Libremote. When the connection breaks, throws
     * TransferLostException, which says how far the batch got.
     */
    fun send(files: List<OutgoingFile>, progress: SendProgress? = null) = transfer(files, progress, update = false)

    /**
     * Sends [apk], a new version of Libremote Receiver, marked as such: the receiver checks that it is one and opens
     * the TV's installer for it at once. A receiver older than 1.0.0 doesn't know the mark and saves it like any file.
     */
    fun sendUpdate(apk: OutgoingFile, progress: SendProgress? = null) = transfer(listOf(apk), progress, update = true)

    /** A send request; internal so tests can mark any batch as an update. */
    internal fun transfer(files: List<OutgoingFile>, progress: SendProgress?, update: Boolean) {
        if (cancelled) throw CancellationException("Cancelled")
        FileSender(socket, stallTimeoutMs) { cancelled }.send(files, progress, update, confirmEach = confirmsEachFile && !update)
    }

    /** The TV's launchable apps, with their icons. */
    fun apps(): List<InstalledApp> = request {
        output.writeJson(mapOf("request" to "apps"))
        output.flush()
        val reply = input.readPacket(MAX_APP_LIST) ?: throw EOFException("The receiver closed the connection")
        if (reply.type == FilePacket.ERROR) throw TransferRefusedException(reply.text)
        if (reply.type != FilePacket.JSON) throw ProtocolException("Expected the app list, got packet ${reply.type}")
        val entries = Json.parseObject(reply.text)["apps"] as? List<*> ?: throw ProtocolException("No app list")
        entries.map { entry ->
            val fields = entry as? Map<*, *> ?: throw ProtocolException("Bad app entry")
            val packageName = fields["package"] as? String ?: throw ProtocolException("An app has no package")
            val iconSize = (fields["icon"] as? String)?.toIntOrNull() ?: 0
            // Each icon follows the list, in its order, as one BINARY packet.
            val icon = if (iconSize <= 0) null else {
                val packet = input.readPacket() ?: throw EOFException("The receiver closed the connection")
                if (packet.type != FilePacket.BINARY || packet.payload.size != iconSize) throw ProtocolException("Bad icon for $packageName")
                packet.payload
            }
            InstalledApp(packageName, fields["label"] as? String ?: packageName, icon)
        }
    }

    /** Asks a receiver that was opened only for this (a `libremote://sync` link) to close, and the TV to go back. */
    fun closeReceiver() = request {
        output.writeJson(mapOf("request" to "close"))
        output.flush()
        val reply = input.readPacket(MAX_REPLY) ?: throw EOFException("The receiver closed the connection")
        if (reply.type == FilePacket.ERROR) throw TransferRefusedException(reply.text)
    }

    override fun close() {
        cancelled = true
        runCatching { socket.close() }
    }

    private fun <T> request(block: () -> T): T {
        if (cancelled) throw CancellationException("Cancelled")
        try {
            return block()
        } catch (e: IOException) {
            if (cancelled) throw CancellationException("Cancelled")
            throw e
        }
    }

    companion object {
        /**
         * Connects to the receiver at [host] as [identity] and verifies it: its key must match [pin], or it must
         * prove [secret], the one in the link that opened it. Nothing is sent to a receiver that fails this.
         * [phoneName] is shown on the TV, which lists this phone under that name.
         *
         * When the TV asks whether to allow this phone (a new one, which sent a version 2 link with [secret]),
         * [confirming] gets the code to show, and connect waits for the answer, checking [cancelled] every second.
         *
         * Throws ReceiverUnavailableException when nothing receives there, ReceiverRefusedPhoneException when
         * the receiver doesn't accept this phone, ReceiverDeclinedException when the user didn't allow it on the
         * TV, ReceiverUnverifiedException when it can't be verified, and CancellationException.
         */
        fun connect(
            host: String,
            identity: ClientIdentity,
            pin: String?,
            secret: ByteArray?,
            phoneName: String,
            port: Int = RECEIVER_PORT,
            timeoutMs: Int = 5_000,
            stallTimeoutMs: Long = 30_000,
            confirming: (code: String) -> Unit = {},
            cancelled: () -> Boolean = { false },
        ): ReceiverClient {
            val raw = try {
                Tls.open(host, port, timeoutMs)
            } catch (e: ConnectException) {
                throw ReceiverUnavailableException(e)
            }
            val trust = ReceiverTrustManager()
            var socket: SSLSocket? = null
            try {
                socket = ReceiverTls.wrap(ReceiverTls.context(identity, trust), raw, client = true)
                val hello = try {
                    socket.startHandshake()
                    // Under TLS 1.3, a receiver that refuses this phone does so after the phone's side of the
                    // handshake is done: the first read fails.
                    socket.inputStream.readPacket(MAX_REPLY)?.takeIf { it.type == FilePacket.JSON }
                        ?: throw EOFException("The receiver closed the connection")
                } catch (e: IOException) {
                    if (e is SocketTimeoutException && trust.receiverCertificate == null) throw e
                    // A receiver that sent its certificate is there; one that didn't, isn't (or isn't open: through
                    // the emulator's port forwarding, a closed port accepts and then hangs up straight away).
                    throw if (trust.receiverCertificate != null) ReceiverRefusedPhoneException(e) else ReceiverUnavailableException(e)
                }
                val receiverKey = ReceiverTls.peerKey(socket)
                val phoneKey = publicKeyPin(identity.certificate)
                var fields = Json.parseObject(hello.text)
                if (fields["receiver"] != RECEIVER_NAME) throw ProtocolException("Not Libremote Receiver")
                if (fields["confirm"] == "1") {
                    // The TV asks about this phone, but this connection made no link (it tried the pin): it has no
                    // code to show. Turned away, the caller sends a link, whose code the TV then shows.
                    // The raw socket first: the receiver isn't reading now, and a TLS close would wait for its reply.
                    if (secret == null) {
                        runCatching { raw.close() }
                        throw ReceiverRefusedPhoneException(null)
                    }
                    confirming(ConfirmCode.compute(secret, phoneKey))
                    fields = awaitAnswer(socket, cancelled)
                }
                val proof = (fields["proof"] as? String)?.hexToBytes()
                val proved = secret != null && proof != null && ReceiverProof.verify(proof, secret, phoneKey, receiverKey)
                if (receiverKey != pin && !proved) throw ReceiverUnverifiedException(pinMismatch = pin != null)
                socket.soTimeout = stallTimeoutMs.toInt()
                socket.outputStream.writeJson(mapOf("request" to "hello", "name" to phoneName))
                socket.outputStream.flush()
                val version = (fields["app"] as? String)?.toLongOrNull()?.takeIf { it > 0 }
                return ReceiverClient(socket, receiverKey, receiverKey != pin, version, fields["arrived"] == "1", stallTimeoutMs)
            } catch (e: Throwable) {
                runCatching { socket?.close() ?: raw.close() }
                throw e
            }
        }

        /** After a "confirm" hello: the real hello once the user allowed this phone on the TV. */
        private fun awaitAnswer(socket: SSLSocket, cancelled: () -> Boolean): Map<String, Any?> {
            // Each packet is one TLS record, so a timeout between them loses nothing.
            socket.soTimeout = POLL_MS
            val deadline = System.nanoTime() + CONFIRM_WAIT_MS * 1_000_000
            while (true) {
                if (cancelled()) throw CancellationException("Cancelled")
                val reply = try {
                    socket.inputStream.readPacket(MAX_REPLY) ?: throw EOFException("The receiver closed the connection")
                } catch (e: SocketTimeoutException) {
                    if (System.nanoTime() > deadline) throw ReceiverDeclinedException("The TV didn't answer")
                    continue
                }
                if (reply.type == FilePacket.ERROR) throw ReceiverDeclinedException(reply.text)
                if (reply.type != FilePacket.JSON) throw ProtocolException("Expected the receiver's hello, got packet ${reply.type}")
                return Json.parseObject(reply.text).also {
                    if (it["receiver"] != RECEIVER_NAME) throw ProtocolException("Not Libremote Receiver")
                }
            }
        }

        internal const val RECEIVER_NAME = "Libremote Receiver"

        private const val POLL_MS = 1_000

        /** A little longer than the receiver asks (PhoneRegistry.CONFIRM_MS), so its answer arrives first. */
        private const val CONFIRM_WAIT_MS = 70_000L

        /** Replies are short; the app list is a few hundred bytes per app. */
        private const val MAX_REPLY = 64 * 1024
        private const val MAX_APP_LIST = 1 shl 20
    }
}
