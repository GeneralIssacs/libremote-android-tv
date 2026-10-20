package app.libremotetvservice.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A file to send. [name] may contain "/"-separated folders, which the receiver recreates. [size] must be exact:
 * the receiver counts bytes to find where each file ends. [open] is called once, on the sending thread.
 */
class OutgoingFile(
    val name: String,
    val size: Long,
    val lastModifiedMs: Long = 0,
    val open: () -> InputStream,
)

/** Reading [fileName] on this device failed, so the transfer stopped. */
class FileUnreadableException(val fileName: String, cause: Throwable) : IOException("Couldn't read $fileName", cause)

/** Called after each piece of data, on the sending thread. [file] is the index of the file being sent. */
fun interface SendProgress {
    fun onProgress(file: Int, sent: Long, total: Long)
}

/**
 * Sends one batch of files as a "send" request on an open receiver channel ([socket], already verified), then
 * waits for the receiver's answer. The answer is read while sending, so a receiver that turns the files down (no
 * space left, say) stops the transfer at once. [cancelled] is checked between pieces of data; closing [socket]
 * from another thread stops a blocked write.
 */
internal class FileSender(
    private val socket: Socket,
    /** How long the receiver may stop taking data, or take to confirm, before the transfer counts as failed. */
    private val stallTimeoutMs: Long,
    private val cancelled: () -> Boolean,
) {
    @Volatile private var stalled = false
    @Volatile private var lastActivityMs = 0L

    /** [update] marks the request as a new version of the receiver itself (ReceiverClient.sendUpdate). */
    fun send(files: List<OutgoingFile>, progress: SendProgress?, update: Boolean = false) {
        require(files.isNotEmpty()) { "Nothing to send" }
        require(files.all { it.size >= 0 }) { "File sizes must be known" }
        val reply = Reply(socket.getInputStream())
        val watchdog = Watchdog()
        val readTimeout = socket.soTimeout
        try {
            // The reply may take as long as the transfer; the watchdog and the wait below guard against stalls.
            socket.soTimeout = 0
            reply.start()
            watchdog.start()
            try {
                write(files, progress, update)
            } catch (e: IOException) {
                // A receiver that gives up says why first; the reader has it, or gets it in a moment.
                if (e !is FileUnreadableException && !cancelled() && !stalled) reply.refusal(REFUSAL_WAIT_MS)?.let { throw it }
                throw e
            }
            watchdog.stop()
            val packet = reply.await(stallTimeoutMs) ?: throw TransferStalledException("The receiver didn't confirm the transfer")
            when (packet.type) {
                FilePacket.SUCCESS -> Unit
                FilePacket.ERROR -> throw TransferRefusedException(packet.text)
                else -> throw ProtocolException("Unexpected packet ${packet.type} from the receiver")
            }
        } catch (e: IOException) {
            when {
                cancelled() -> throw CancellationException("Cancelled")
                stalled -> throw TransferStalledException("The receiver stopped taking data")
                else -> throw e
            }
        } finally {
            watchdog.stop()
            runCatching { socket.soTimeout = readTimeout }
        }
    }

    private fun write(files: List<OutgoingFile>, progress: SendProgress?, update: Boolean) {
        val out = socket.getOutputStream()
        val total = files.sumOf { it.size }
        val request = linkedMapOf<String, Any?>("request" to "send", "count" to files.size.toString(), "size" to total.toString())
        if (update) request["update"] = "1"
        out.writeJson(request)
        val buffer = ByteArray(FilePacket.HEADER_SIZE + FilePacket.CHUNK)
        var sent = 0L
        for ((index, file) in files.withIndex()) {
            val name = safeTransferPath(file.name) ?: "file"
            // Numbers as strings, as in the "Send files to TV" headers this framing comes from.
            out.writeJson(
                linkedMapOf(
                    "type" to "file",
                    "name" to name,
                    "size" to file.size.toString(),
                    "last_modified" to file.lastModifiedMs.coerceAtLeast(0).toString(),
                ),
            )
            touch()
            progress?.onProgress(index, sent, total)
            if (file.size == 0L) continue // An empty file has no data packet.
            val input = try {
                file.open()
            } catch (e: Exception) {
                throw FileUnreadableException(name, e)
            }
            input.use {
                var remaining = file.size
                while (remaining > 0) {
                    if (cancelled()) throw CancellationException("Cancelled")
                    val n = try {
                        input.read(buffer, FilePacket.HEADER_SIZE, minOf(FilePacket.CHUNK.toLong(), remaining).toInt())
                    } catch (e: IOException) {
                        throw FileUnreadableException(name, e)
                    }
                    if (n == -1) throw FileUnreadableException(name, IOException("It ended $remaining bytes early"))
                    if (n == 0) continue
                    FilePacket.header(buffer, FilePacket.BINARY, n)
                    out.write(buffer, 0, FilePacket.HEADER_SIZE + n)
                    remaining -= n
                    sent += n
                    touch()
                    progress?.onProgress(index, sent, total)
                }
            }
        }
        out.flush()
    }

    private fun touch() {
        lastActivityMs = System.nanoTime() / 1_000_000
    }

    /**
     * Reads the receiver's one reply on its own thread, while the files go out. An ERROR before the end closes the
     * connection, which stops the sending thread at once.
     */
    private inner class Reply(private val input: InputStream) {
        private val done = CountDownLatch(1)
        @Volatile private var packet: Packet? = null
        @Volatile private var failure: Throwable? = null

        fun start() {
            Thread({
                try {
                    packet = input.readPacket(MAX_REPLY) ?: throw EOFException("The receiver closed the connection without confirming the transfer")
                    if (packet?.type == FilePacket.ERROR) runCatching { socket.close() }
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    done.countDown()
                }
            }, "libremote-send-reply").apply {
                isDaemon = true
                start()
            }
        }

        /** The reply, or null if none came within [timeoutMs]. */
        fun await(timeoutMs: Long): Packet? {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            failure?.let { throw it as? IOException ?: IOException(it) }
            return packet
        }

        /** The receiver's reason for stopping, if it gave one within [timeoutMs]. */
        fun refusal(timeoutMs: Long): TransferRefusedException? {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            return packet?.takeIf { it.type == FilePacket.ERROR }?.let { TransferRefusedException(it.text) }
        }
    }

    /** Closes the connection when the receiver takes no data for [stallTimeoutMs]: a blocked write never times out. */
    private inner class Watchdog {
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            touch()
            running = true
            thread = Thread({
                while (running) {
                    try {
                        Thread.sleep(WATCHDOG_TICK_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    if (running && System.nanoTime() / 1_000_000 - lastActivityMs > stallTimeoutMs) {
                        stalled = true
                        runCatching { socket.close() }
                        return@Thread
                    }
                }
            }, "libremote-send-watchdog").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
            thread?.interrupt()
        }
    }

    private companion object {
        const val WATCHDOG_TICK_MS = 250L
        const val REFUSAL_WAIT_MS = 2_000L

        /** Replies are empty or a short reason. */
        const val MAX_REPLY = 64 * 1024
    }
}
