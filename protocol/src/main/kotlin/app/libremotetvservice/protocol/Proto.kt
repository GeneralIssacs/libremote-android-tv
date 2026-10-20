package app.libremotetvservice.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Malformed or unexpected data from the TV. */
open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

private const val VARINT = 0
private const val FIXED64 = 1
private const val LENGTH_DELIMITED = 2
private const val FIXED32 = 5

/** Largest message accepted from a TV; real ones are a few hundred bytes. */
private const val MAX_MESSAGE_SIZE = 1 shl 20

/**
 * Writes protocol-buffers wire format. The TV speaks two small protobuf protocols; this, [ProtoMessage] and the
 * field numbers in [Polo] and [Remote] replace generated code and the protobuf runtime.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        tag(field, VARINT)
        writeVarint(out, value)
    }

    /** int32, uint32, enum: negative values take ten bytes, as protobuf sign-extends them. */
    fun int(field: Int, value: Int) = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray) = apply {
        tag(field, LENGTH_DELIMITED)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.encodeToByteArray())

    fun message(field: Int, value: ProtoWriter) = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(out, (field.toLong() shl 3) or wireType.toLong())
}

/** A parsed message: field number to values, in arrival order. Unknown fields are kept and ignored. */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = field in fields

    /** Field numbers present, lowest first: a wrapper message's single oneof payload is the one that is set. */
    val fieldNumbers: List<Int> get() = fields.keys.sorted()

    fun long(field: Int): Long = last(field) as? Long ?: 0L

    fun int(field: Int): Int = long(field).toInt()

    fun bool(field: Int) = long(field) != 0L

    fun bytes(field: Int): ByteArray? = last(field) as? ByteArray

    fun string(field: Int): String? = bytes(field)?.decodeToString()

    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)

    fun messages(field: Int): List<ProtoMessage> = values(field).filterIsInstance<ByteArray>().map(::parse)

    fun strings(field: Int): List<String> = values(field).filterIsInstance<ByteArray>().map { it.decodeToString() }

    private fun values(field: Int): List<Any> = fields[field].orEmpty()

    private fun last(field: Int): Any? = fields[field]?.lastOrNull()

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw ProtocolException("Truncated varint")
                    val b = data[pos++].toInt()
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw ProtocolException("Malformed varint")
                }
            }
            fun take(count: Int): ByteArray {
                if (count < 0 || count > data.size - pos) throw ProtocolException("Truncated field")
                return data.copyOfRange(pos, pos + count).also { pos += count }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                if (field <= 0) throw ProtocolException("Invalid field number")
                val value: Any = when ((key and 7).toInt()) {
                    VARINT -> varint()
                    FIXED64 -> take(8)
                    LENGTH_DELIMITED -> {
                        val length = varint()
                        if (length > Int.MAX_VALUE) throw ProtocolException("Field too long")
                        take(length.toInt())
                    }
                    FIXED32 -> take(4)
                    else -> throw ProtocolException("Unsupported wire type ${key and 7}")
                }
                fields.getOrPut(field) { ArrayList(1) }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

internal fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (v and 0x7fL.inv() != 0L) {
        out.write(((v and 0x7f) or 0x80).toInt())
        v = v ushr 7
    }
    out.write(v.toInt())
}

/** Writes one varint-length-prefixed message in a single write, so it leaves in one TLS record. */
internal fun OutputStream.writeDelimited(message: ByteArray) {
    val frame = ByteArrayOutputStream(message.size + 5)
    writeVarint(frame, message.size.toLong())
    frame.write(message)
    write(frame.toByteArray())
    flush()
}

/** Reads one varint-length-prefixed message, or null when the stream ends cleanly between messages. */
internal fun InputStream.readDelimited(): ByteArray? {
    var b = read()
    if (b == -1) return null
    var size = b and 0x7f
    var shift = 7
    while (b and 0x80 != 0) {
        b = read()
        if (b == -1) throw EOFException("Stream ended inside a length prefix")
        if (shift > 28) throw ProtocolException("Malformed length prefix")
        size = size or ((b and 0x7f) shl shift)
        shift += 7
    }
    if (size < 0 || size > MAX_MESSAGE_SIZE) throw ProtocolException("Message of $size bytes is too large")
    val message = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = read(message, read, size - read)
        if (n == -1) throw EOFException("Stream ended inside a message")
        read += n
    }
    return message
}
