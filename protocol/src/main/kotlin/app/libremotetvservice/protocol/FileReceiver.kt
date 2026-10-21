package app.libremotetvservice.protocol

import java.io.InputStream
import java.io.OutputStream

/**
 * Receives one "send" request on Libremote Receiver's channel (FileSender writes them). Names never leave the
 * receiving folder (safeTransferPath), and a failure is reported to the sender with an ERROR packet.
 */
class FileReceiver(private val sink: Sink) {

    /** Where received files go. Calls come from the thread running [receive]. */
    interface Sink {
        /** A transfer from [sender] begins: [count] files, [size] bytes in all. Throw to refuse it. */
        fun begin(sender: String, count: Int, size: Long) {}

        /** Where to write [path], made safe and "/"-separated, of [size] bytes. Throw to refuse it. */
        fun open(path: String, size: Long, lastModifiedMs: Long): OutputStream

        /** [path] arrived whole. */
        fun complete(path: String) {}

        /** [path] did not arrive whole: remove what was written. */
        fun abandon(path: String) {}

        fun progress(received: Long, total: Long) {}
    }

    /**
     * Handles the request whose [header] was already read, from [sender], until the last file arrived, then
     * answers SUCCESS. A request with "arrived":"1" also gets {"arrived":"<index>"} as each file is saved, so a
     * phone whose connection breaks knows which ones are on the TV. On failure, answers ERROR with the reason and
     * throws; the connection is then out of step and must be closed. Closes neither stream.
     */
    fun receive(header: Map<String, Any?>, sender: String, input: InputStream, output: OutputStream) {
        var current: String? = null
        var stream: OutputStream? = null
        try {
            val count = number(header, "count")?.toIntOrNull()?.takeIf { it >= 0 } ?: throw ProtocolException("Bad file count")
            val total = number(header, "size")?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ProtocolException("Bad transfer size")
            sink.begin(sender, count, total)
            var received = 0L
            val confirmEach = header["arrived"] == "1"
            repeat(count) { index ->
                val item = readHeader(input)
                val size = number(item, "size")?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ProtocolException("Bad file size")
                val type = item["type"] as? String ?: "file"
                if (type != "file") throw ProtocolException("Unknown item type $type")
                val path = safeTransferPath(item["name"] as? String ?: "") ?: throw ProtocolException("A file has no name")
                val modified = number(item, "last_modified")?.toLongOrNull() ?: 0L
                current = path
                val out = sink.open(path, size, modified).also { stream = it }
                var remaining = size
                while (remaining > 0) {
                    val packet = input.readPacket() ?: throw ProtocolException("The sender stopped in the middle of $path")
                    if (packet.type == FilePacket.ERROR) throw TransferRefusedException(packet.text)
                    if (packet.type != FilePacket.BINARY) throw ProtocolException("Expected file data, got packet ${packet.type}")
                    if (packet.payload.size > remaining) throw ProtocolException("More data than $path should have")
                    out.write(packet.payload)
                    remaining -= packet.payload.size
                    received += packet.payload.size
                    sink.progress(received, total)
                }
                out.close()
                stream = null
                current = null
                sink.complete(path)
                if (confirmEach) {
                    output.writeJson(mapOf("arrived" to index.toString()))
                    output.flush()
                }
            }
            output.writePacket(FilePacket.SUCCESS)
            output.flush()
        } catch (e: Exception) {
            runCatching { stream?.close() }
            current?.let { runCatching { sink.abandon(it) } }
            // Tell the sender why. It may be gone already.
            runCatching {
                output.writePacket(FilePacket.ERROR, (e.message ?: "The transfer failed").encodeToByteArray())
                output.flush()
            }
            throw e
        }
    }

    /** A number, which senders write as a string (as "Send files to TV" did) or as a JSON number. */
    private fun number(fields: Map<String, Any?>, key: String) = fields[key] as? String

    internal companion object {
        /** Headers are a few hundred bytes; a data packet can be 4 MiB. */
        const val MAX_HEADER = 64 * 1024

        fun readHeader(input: InputStream): Map<String, Any?> {
            val packet = input.readPacket(MAX_HEADER) ?: throw ProtocolException("The sender closed the connection")
            if (packet.type == FilePacket.ERROR) throw TransferRefusedException(packet.text)
            if (packet.type != FilePacket.JSON) throw ProtocolException("Expected a header, got packet ${packet.type}")
            return Json.parseObject(packet.text)
        }
    }
}
