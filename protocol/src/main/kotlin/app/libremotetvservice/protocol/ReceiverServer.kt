package app.libremotetvservice.protocol

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Libremote Receiver's end of its channel (ReceiverChannel.kt): accepts paired phones on [port], proves the latest
 * link's secret to each, then answers their requests: "send" (files, see FileReceiver; marked "update" for a new
 * version of the receiver itself), "apps" (the TV's app list) and "close". Several phones may be connected; one
 * transfer runs at a time.
 */
class ReceiverServer(
    private val identity: ClientIdentity,
    private val phones: Phones,
    private val handler: Handler,
    private val port: Int = RECEIVER_PORT,
    /** A phone that sends nothing for this long is dropped, so it can't hold the receiver. */
    private val idleTimeoutMs: Int = 30_000,
    /** TLS versions to offer; tests narrow it to check TLS 1.2, which Android 8 and 9 use. */
    private val protocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
    /** This receiver's versionCode, which phones read to offer updates; null leaves it out, as receivers before 1.0.0. */
    private val appVersion: Long? = null,
    /** Whether to offer confirming each file of a send (from 1.0.1); tests turn it off to act as an older receiver. */
    private val confirmsEachFile: Boolean = true,
) : Closeable {

    /** The phones this receiver accepts (PhoneRegistry). Called from connection threads. */
    interface Phones {
        fun isPaired(phoneKey: String): Boolean

        /** [phoneKey] isn't paired, but the TV is asking whether to allow it and it can wait for the answer. */
        fun isWaiting(phoneKey: String): Boolean = false

        /** Waits up to [timeoutMs] for the user's answer about [phoneKey]: true when it may pair. */
        fun awaitApproval(phoneKey: String, timeoutMs: Long): Boolean = false

        /** The secret from the latest link [phoneKey] sent, if not yet used. It is used once, then forgotten. */
        fun takeSecret(phoneKey: String): ByteArray?

        /** [phoneKey] gave its name. */
        fun named(phoneKey: String, name: String) {}
    }

    /** What the receiver does for phones. Called from connection threads. */
    interface Handler {
        /**
         * Where the files of a send request from [phone] go. [update]: the phone marked it as a new version of the
         * receiver itself (one file, an APK); the handler checks that it is.
         */
        fun sink(phone: Phone, update: Boolean): FileReceiver.Sink

        /** A send request from [phone] ended: [error] is null when every file arrived. */
        fun sent(phone: Phone, error: Throwable?) {}

        /** The TV's launchable apps, for an "apps" request. */
        fun apps(): List<InstalledApp>

        /** [phone] has what it opened the receiver for, and asks it to close. */
        fun closeRequested(phone: Phone) {}

        /** [phone]'s connection ended. */
        fun disconnected(phone: Phone) {}

        /** A connection ended with [error]: a phone that went away, one that isn't paired, or a TLS problem. */
        fun failed(error: Throwable) {}
    }

    /** A connected phone: its key hash, and the name it gave (empty until it does). */
    class Phone(val key: String, @Volatile var name: String = "")

    private val receiverKey = publicKeyPin(identity.certificate)
    private val context = ReceiverTls.context(identity, PairedPhonesTrustManager { phones.isPaired(it) || phones.isWaiting(it) })
    private val connections = HashMap<Socket, String?>()
    private val transferring = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    /** Starts listening on all interfaces. Throws IOException when the port is taken. */
    fun start(address: InetSocketAddress = InetSocketAddress(port)) {
        if (server != null) return
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(address)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        server = socket
        Thread({ accept(socket) }, "receiver-accept").start()
    }

    /** The port in use, once started (the one given, unless that was 0). */
    val localPort get() = server?.localPort ?: -1

    /** Stops listening and drops every connection. */
    override fun close() {
        server?.let { runCatching { it.close() } }
        server = null
        synchronized(connections) { connections.keys.toList() }.forEach { runCatching { it.close() } }
    }

    /** Drops the connections of [phoneKey], which is no longer paired. */
    fun disconnect(phoneKey: String) {
        synchronized(connections) { connections.filterValues { it == phoneKey }.keys.toList() }.forEach { runCatching { it.close() } }
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val raw = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            val full = synchronized(connections) {
                (connections.size >= MAX_CONNECTIONS).also { if (!it) connections[raw] = null }
            }
            if (full) {
                runCatching { raw.close() }
                continue
            }
            Thread({
                try {
                    serve(raw)
                } catch (e: Exception) {
                    handler.failed(e)
                } finally {
                    synchronized(connections) { connections.remove(raw) }
                    runCatching { raw.close() }
                }
            }, "receiver-connection").start()
        }
    }

    private fun serve(raw: Socket) {
        raw.tcpNoDelay = true
        raw.soTimeout = HANDSHAKE_TIMEOUT_MS
        val socket = ReceiverTls.wrap(context, raw, client = false, protocols)
        // The trust manager turns away phones that aren't paired (or waiting to be), during the handshake.
        socket.startHandshake()
        val phone = Phone(ReceiverTls.peerKey(socket))
        synchronized(connections) { connections[raw] = phone.key }
        if (!phones.isPaired(phone.key) && !(phones.isWaiting(phone.key) && confirm(phone, socket, raw))) return
        try {
            serve(phone, socket, raw)
        } finally {
            handler.disconnected(phone)
        }
    }

    /**
     * A new phone, while the TV asks whether to allow it: a first hello says so ("confirm"), so the phone shows the
     * code and waits; the real hello follows once the user allows it, or an ERROR when they don't. Nothing else is
     * read or answered meanwhile. True when it was allowed.
     */
    private fun confirm(phone: Phone, socket: SSLSocket, raw: Socket): Boolean {
        socket.outputStream.writeJson(mapOf("receiver" to ReceiverClient.RECEIVER_NAME, "version" to "1", "confirm" to "1"))
        socket.outputStream.flush()
        if (phones.awaitApproval(phone.key, CONFIRM_TIMEOUT_MS)) return true
        refuse(socket, raw, NOT_ALLOWED)
        return false
    }

    private fun serve(phone: Phone, socket: SSLSocket, raw: Socket) {
        val input = socket.inputStream
        val output = socket.outputStream
        val hello = linkedMapOf<String, Any?>("receiver" to ReceiverClient.RECEIVER_NAME, "version" to "1")
        appVersion?.let { hello["app"] = it.toString() }
        if (confirmsEachFile) hello["arrived"] = "1"
        phones.takeSecret(phone.key)?.let { hello["proof"] = ReceiverProof.compute(it, phone.key, receiverKey).toHex() }
        output.writeJson(hello)
        output.flush()
        raw.soTimeout = idleTimeoutMs
        while (true) {
            val request = try {
                FileReceiver.readHeader(input)
            } catch (e: ProtocolException) {
                return // The phone closed the connection, or sent nonsense.
            }
            when (request["request"]) {
                "hello" -> (request["name"] as? String)?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }?.let {
                    phone.name = it
                    phones.named(phone.key, it)
                }
                "send" -> if (!receive(phone, request, socket, raw)) return
                "apps" -> sendApps(output)
                "close" -> {
                    output.writePacket(FilePacket.SUCCESS)
                    output.flush()
                    handler.closeRequested(phone)
                    return
                }
                else -> {
                    output.writePacket(FilePacket.ERROR, "Unknown request ${request["request"]}".encodeToByteArray())
                    output.flush()
                }
            }
        }
    }

    /** Handles a send request; false when it failed, which leaves the connection unusable. */
    private fun receive(phone: Phone, request: Map<String, Any?>, socket: SSLSocket, raw: Socket): Boolean {
        if (!transferring.compareAndSet(false, true)) {
            refuse(socket, raw, BUSY)
            return false
        }
        try {
            val update = request["update"] == "1"
            if (update && request["count"] != "1") {
                refuse(socket, raw, ONE_UPDATE)
                return false
            }
            FileReceiver(handler.sink(phone, update)).receive(request, phone.name, socket.inputStream, socket.outputStream)
            handler.sent(phone, null)
            return true
        } catch (e: Exception) {
            handler.sent(phone, e)
            // FileReceiver told the phone why; it reads that while still sending.
            drain(socket, raw)
            return false
        } finally {
            transferring.set(false)
        }
    }

    private fun sendApps(output: OutputStream) {
        val apps = runCatching { handler.apps() }.getOrElse {
            output.writePacket(FilePacket.ERROR, (it.message ?: "Couldn't list the apps").encodeToByteArray())
            output.flush()
            return
        }
        val list = apps.map {
            linkedMapOf("package" to it.packageName, "label" to it.label, "icon" to (it.icon?.size ?: 0).toString())
        }
        output.writePacket(FilePacket.JSON, Json.encode(mapOf("apps" to list)).encodeToByteArray())
        apps.forEach { app -> app.icon?.takeIf { it.isNotEmpty() }?.let { output.writePacket(FilePacket.BINARY, it) } }
        output.flush()
    }

    private fun refuse(socket: SSLSocket, raw: Socket, reason: String) {
        runCatching {
            socket.outputStream.writePacket(FilePacket.ERROR, reason.encodeToByteArray())
            socket.outputStream.flush()
        }
        drain(socket, raw)
    }

    /**
     * Before closing a connection whose phone is still sending: closing with data unread resets the connection,
     * and a reset can discard the ERROR packet before the phone reads it. So stop writing, and read on for a moment.
     */
    private fun drain(socket: SSLSocket, raw: Socket) {
        runCatching {
            raw.shutdownOutput()
            raw.soTimeout = DRAIN_MS
            val input = socket.inputStream
            val scrap = ByteArray(FilePacket.CHUNK)
            val deadline = System.nanoTime() + DRAIN_MS * 1_000_000L
            while (System.nanoTime() < deadline && input.read(scrap) != -1) continue
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val DRAIN_MS = 3_000
        const val MAX_NAME = 100
        const val BUSY = "This TV is receiving files from another phone. Try again once they have arrived."
        const val ONE_UPDATE = "An update is one APK."
        const val NOT_ALLOWED = "This phone wasn't allowed on the TV."

        /** The registry gives up sooner (PhoneRegistry.CONFIRM_MS); this only bounds a connection's wait. */
        const val CONFIRM_TIMEOUT_MS = 120_000L
    }
}
