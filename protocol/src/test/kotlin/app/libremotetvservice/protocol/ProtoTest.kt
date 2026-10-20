package app.libremotetvservice.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtoTest {

    @Test
    fun encodesLikeProtobuf() {
        // OuterMessage{protocol_version: 2, status: 200, pairing_request_ack(11): {server_name: "TV"}}
        val bytes = ProtoWriter().int(1, 2).int(2, 200).message(11, ProtoWriter().string(1, "TV")).toByteArray()
        val expected = byteArrayOf(0x08, 0x02, 0x10, 0xc8.toByte(), 0x01, 0x5a, 0x04, 0x0a, 0x02, 'T'.code.toByte(), 'V'.code.toByte())
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun negativeIntsTakeTenBytes() {
        val bytes = ProtoWriter().int(1, -1).toByteArray()
        assertEquals(11, bytes.size)
        assertEquals(-1, ProtoMessage.parse(bytes).int(1))
    }

    @Test
    fun parsesNestedAndRepeatedFields() {
        val bytes = ProtoWriter()
            .message(3, ProtoWriter().int(1, 7))
            .message(3, ProtoWriter().int(1, 8))
            .string(4, "héllo")
            .bool(5, true)
            .toByteArray()
        val message = ProtoMessage.parse(bytes)
        assertEquals(listOf(7, 8), message.messages(3).map { it.int(1) })
        assertEquals("héllo", message.string(4))
        assertTrue(message.bool(5))
        assertFalse(message.has(6))
        assertEquals(0, message.int(6))
        assertEquals(listOf(3, 4, 5), message.fieldNumbers)
    }

    @Test
    fun skipsFixedWidthFields() {
        // A float (fixed32) and a double (fixed64) before a varint.
        val bytes = byteArrayOf(0x0d, 0, 0, 0x80.toByte(), 0x3f, 0x11, 0, 0, 0, 0, 0, 0, 0xf0.toByte(), 0x3f, 0x18, 0x05)
        assertEquals(5, ProtoMessage.parse(bytes).int(3))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsTruncatedMessages() {
        ProtoMessage.parse(byteArrayOf(0x0a, 0x05, 1, 2))
    }

    @Test
    fun framesMessagesWithVarintLengths() {
        val big = ByteArray(300) { it.toByte() }
        val out = ByteArrayOutputStream()
        out.writeDelimited(byteArrayOf(1, 2, 3))
        out.writeDelimited(big)
        val framed = out.toByteArray()
        assertEquals(0xac.toByte(), framed[4]) // 300 = 0xac 0x02
        val input = ByteArrayInputStream(framed)
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readDelimited())
        assertArrayEquals(big, input.readDelimited())
        assertNull(input.readDelimited())
    }
}
