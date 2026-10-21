package app.libremotetvservice.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The receiver turned the request down; the message is its reason, as it sent it. */
class TransferRefusedException(message: String) : ProtocolException(message)

/** The receiver stopped reading, or never confirmed the transfer. It may be out of space, or gone. */
class TransferStalledException(message: String) : ProtocolException(message)

/**
 * The connection broke during a transfer ([cause] says how). [arrived] files, the first ones, are on the TV: the
 * receiver confirmed each. It is null when the receiver doesn't confirm each file (before 1.0.1), so which ones
 * arrived is unknown. [started] is how many files this phone had begun to send, the last of them perhaps in part.
 */
class TransferLostException(val arrived: Int?, val started: Int, cause: IOException) :
    IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Every packet of Libremote Receiver's channel, in both directions, inside its TLS connection (ReceiverChannel.kt).
 * The framing is the one the "Send files to TV" app inherited from NitroShare (SENDFILES.md): the payload size plus
 * one as a little-endian int32, a type byte, then the payload. Requests and replies are JSON, file data is BINARY,
 * and a request ends with SUCCESS (empty) or ERROR with a UTF-8 reason.
 */
internal object FilePacket {
    const val SUCCESS = 0
    const val ERROR = 1
    const val JSON = 2
    const val BINARY = 3

    const val HEADER_SIZE = 5

    /** The largest payload accepted, as "Send files to TV" had it (Packet.MAX_PACKET_SIZE). */
    const val MAX_PAYLOAD = 4 shl 20

    /** File data per BINARY packet. */
    const val CHUNK = 64 * 1024

    /** Writes a packet header for [length] payload bytes into the first [HEADER_SIZE] bytes of [buffer]. */
    fun header(buffer: ByteArray, type: Int, length: Int) {
        val size = length + 1
        buffer[0] = size.toByte()
        buffer[1] = (size ushr 8).toByte()
        buffer[2] = (size ushr 16).toByte()
        buffer[3] = (size ushr 24).toByte()
        buffer[4] = type.toByte()
    }
}

internal class Packet(val type: Int, val payload: ByteArray) {
    val text get() = payload.decodeToString()
}

/** Writes one packet in a single write. */
internal fun OutputStream.writePacket(type: Int, payload: ByteArray = ByteArray(0)) {
    val frame = ByteArray(FilePacket.HEADER_SIZE + payload.size)
    FilePacket.header(frame, type, payload.size)
    payload.copyInto(frame, FilePacket.HEADER_SIZE)
    write(frame)
}

internal fun OutputStream.writeJson(fields: Map<String, Any?>) = writePacket(FilePacket.JSON, Json.encode(fields).encodeToByteArray())

/** Reads one packet, or null when the stream ends cleanly before it. Payloads over [maxPayload] are refused. */
internal fun InputStream.readPacket(maxPayload: Int = FilePacket.MAX_PAYLOAD): Packet? {
    val header = ByteArray(FilePacket.HEADER_SIZE)
    val got = readFully(header)
    if (got == 0) return null
    if (got < header.size) throw EOFException("Stream ended inside a packet header")
    val size = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8) or
        ((header[2].toInt() and 0xff) shl 16) or ((header[3].toInt() and 0xff) shl 24)
    val length = size - 1
    if (length < 0 || length > maxPayload) throw ProtocolException("Packet of $length bytes is out of range")
    val payload = ByteArray(length)
    if (readFully(payload) < length) throw EOFException("Stream ended inside a packet")
    return Packet(header[4].toInt() and 0xff, payload)
}

/** Fills [buffer] unless the stream ends first; returns how much it read. */
private fun InputStream.readFully(buffer: ByteArray): Int {
    var read = 0
    while (read < buffer.size) {
        val n = read(buffer, read, buffer.size - read)
        if (n == -1) break
        read += n
    }
    return read
}

/**
 * Makes a file name from a transfer safe to send or to store: "/"-separated folders are kept, but empty, "." and
 * ".." parts, backslashes and control characters are not, so the name stays inside the receiving folder. Each
 * part is cut to 255 bytes, the usual limit for a file name. Null when nothing is left.
 *
 * The "Send files to TV" receiver does none of this: a name with "../" in it is written outside its folder.
 */
fun safeTransferPath(name: String): String? {
    val parts = name.replace('\\', '/').split('/')
        .map { part -> part.filter { it >= ' ' && it != '\u007f' }.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .map(::cutToBytes)
    return parts.joinToString("/").ifEmpty { null }
}

/** The longest start of [part] that fits in [MAX_NAME_BYTES] of UTF-8, without splitting a character. */
private fun cutToBytes(part: String): String {
    var bytes = 0
    var end = 0
    while (end < part.length) {
        val c = part.codePointAt(end)
        bytes += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c < 0x10000 -> 3
            else -> 4
        }
        if (bytes > MAX_NAME_BYTES) break
        end += Character.charCount(c)
    }
    return part.substring(0, end)
}

private const val MAX_NAME_BYTES = 255
