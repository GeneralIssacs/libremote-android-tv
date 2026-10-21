package app.libremotetvservice.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The transfer inside Libremote Receiver's channel: framing, headers, and the sender and receiver against each other.
 * These run over plain loopback sockets; ReceiverChannelTest runs them inside the real TLS channel.
 */
class FileTransferTest {

    /** A receiver on a loopback port that hands its first connection to [handle]. */
    private class Loopback(private val handle: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val failures = LinkedBlockingQueue<Throwable>()

        init {
            Thread {
                runCatching { server.accept().use(handle) }.onFailure { failures.add(it) }
            }.apply { isDaemon = true }.start()
        }

        fun connect() = Socket(InetAddress.getLoopbackAddress(), server.localPort)

        override fun close() = server.close()
    }

    class MemorySink : FileReceiver.Sink {
        val files = LinkedHashMap<String, ByteArrayOutputStream>()
        val modified = HashMap<String, Long>()
        val complete = mutableListOf<String>()
        val abandoned = mutableListOf<String>()
        @Volatile var sender = ""
        var lastProgress = 0L
        var open: (String) -> OutputStream = { ByteArrayOutputStream() }

        override fun begin(sender: String, count: Int, size: Long) {
            this.sender = sender
        }

        override fun open(path: String, size: Long, lastModifiedMs: Long): OutputStream {
            modified[path] = lastModifiedMs
            return open.invoke(path).also { if (it is ByteArrayOutputStream) files[path] = it }
        }

        override fun complete(path: String) { complete += path }
        override fun abandon(path: String) { abandoned += path }
        override fun progress(received: Long, total: Long) { lastProgress = received }
    }

    private fun file(name: String, data: ByteArray, modified: Long = 0) =
        OutgoingFile(name, data.size.toLong(), modified) { ByteArrayInputStream(data) }

    /** [size] zero bytes, made as they are read. */
    private fun zeros(name: String, size: Long) = OutgoingFile(name, size) {
        object : InputStream() {
            var left = size
            override fun read() = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                b.fill(0, off, off + n)
                left -= n
                return n
            }
        }
    }

    private fun packets(bytes: ByteArray): List<Packet> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { input.readPacket() }.toList()
    }

    /** What ReceiverServer does with a send request: read its header, then the files; on failure, drain. */
    private fun receive(socket: Socket, sink: FileReceiver.Sink) {
        socket.soTimeout = 10_000
        val header = FileReceiver.readHeader(socket.getInputStream())
        assertEquals("send", header["request"])
        try {
            FileReceiver(sink).receive(header, "Test phone", socket.getInputStream(), socket.getOutputStream())
        } catch (e: IOException) {
            socket.shutdownOutput()
            socket.soTimeout = 3_000
            runCatching { while (socket.getInputStream().read(ByteArray(65_536)) != -1) continue }
        }
    }

    private fun send(
        socket: Socket,
        files: List<OutgoingFile>,
        stallTimeoutMs: Long = 30_000,
        cancelled: () -> Boolean = { false },
        confirmEach: Boolean = false,
        progress: SendProgress? = null,
    ) = socket.use { FileSender(it, stallTimeoutMs, cancelled).send(files, progress, confirmEach = confirmEach) }

    @Test
    fun packetsUseTheSendFilesToTvFraming() {
        val out = ByteArrayOutputStream()
        out.writePacket(FilePacket.JSON, "{}".encodeToByteArray())
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte()), out.toByteArray())
        val success = ByteArrayOutputStream().apply { writePacket(FilePacket.SUCCESS) }.toByteArray()
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), success)
    }

    @Test
    fun sendsARequestAndItsFiles() {
        val raw = ByteArrayOutputStream()
        val tv = Loopback { socket ->
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            // Request header, two file headers, one data packet: the empty file has none.
            repeat(4) { input.readPacket()!!.let { raw.writePacket(it.type, it.payload) } }
            socket.getOutputStream().writePacket(FilePacket.SUCCESS)
        }
        tv.use { send(tv.connect(), listOf(file("Holiday/a=1.txt", "hello".encodeToByteArray(), 1_700_000_000_000), file("empty", ByteArray(0)))) }
        val sent = packets(raw.toByteArray())
        assertEquals(listOf(FilePacket.JSON, FilePacket.JSON, FilePacket.BINARY, FilePacket.JSON), sent.map { it.type })
        assertEquals("""{"request":"send","count":"2","size":"5"}""", sent[0].text)
        assertEquals("""{"type":"file","name":"Holiday/a=1.txt","size":"5","last_modified":"1700000000000"}""", sent[1].text)
        assertEquals("hello", sent[2].text)
        assertEquals("""{"type":"file","name":"empty","size":"0","last_modified":"0"}""", sent[3].text)
    }

    @Test
    fun senderAndReceiverAgree() {
        val sink = MemorySink()
        val big = Random(7).nextBytes(300_000) // Several data packets.
        val tv = Loopback { receive(it, sink) }
        val progress = mutableListOf<Long>()
        tv.use {
            send(
                tv.connect(),
                listOf(file("Photos/beach.jpg", big, 1_600_000_000_000), file("notes.txt", "hi".encodeToByteArray()), file("empty.txt", ByteArray(0))),
            ) { _, sent, _ -> progress += sent }
        }
        assertEquals("Test phone", sink.sender)
        assertEquals(listOf("Photos/beach.jpg", "notes.txt", "empty.txt"), sink.complete)
        assertArrayEquals(big, sink.files["Photos/beach.jpg"]!!.toByteArray())
        assertEquals("hi", sink.files["notes.txt"]!!.toString())
        assertEquals(0, sink.files["empty.txt"]!!.size())
        assertEquals(1_600_000_000_000, sink.modified["Photos/beach.jpg"])
        assertEquals(big.size + 2L, progress.last())
        assertEquals(big.size + 2L, sink.lastProgress)
    }

    @Test
    fun receiverReadsNumbersAsStringsOrNumbers() {
        val wire = ByteArrayOutputStream().apply {
            writePacket(FilePacket.JSON, """{"last_modified":"1700000000000","size":"4","name":"x=1 it's.apk","type":"file"}""".encodeToByteArray())
            writePacket(FilePacket.BINARY, "abcd".encodeToByteArray())
            writePacket(FilePacket.JSON, """{"type":"file","name":"b.txt","size":3}""".encodeToByteArray())
            writePacket(FilePacket.BINARY, "efg".encodeToByteArray())
        }.toByteArray()
        val sink = MemorySink()
        val reply = ByteArrayOutputStream()
        FileReceiver(sink).receive(mapOf("count" to "2", "size" to "7"), "Pixel 9", ByteArrayInputStream(wire), reply)
        assertEquals("Pixel 9", sink.sender)
        assertEquals(listOf("x=1 it's.apk", "b.txt"), sink.complete)
        assertEquals(1_700_000_000_000, sink.modified["x=1 it's.apk"])
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), reply.toByteArray())
    }

    @Test
    fun receiverConfirmsEachFileOnlyWhenAsked() {
        val wire = ByteArrayOutputStream().apply {
            writePacket(FilePacket.JSON, """{"type":"file","name":"a.txt","size":"2"}""".encodeToByteArray())
            writePacket(FilePacket.BINARY, "ab".encodeToByteArray())
            writePacket(FilePacket.JSON, """{"type":"file","name":"empty","size":"0"}""".encodeToByteArray())
        }.toByteArray()
        fun replies(header: Map<String, Any?>) = ByteArrayOutputStream().also {
            FileReceiver(MemorySink()).receive(header, "x", ByteArrayInputStream(wire), it)
        }.toByteArray().let(::packets)
        // A phone before 1.0.1 doesn't ask, and gets only the SUCCESS it expects.
        assertEquals(listOf(FilePacket.SUCCESS), replies(mapOf("count" to "2", "size" to "2")).map { it.type })
        val confirmed = replies(mapOf("count" to "2", "size" to "2", "arrived" to "1"))
        assertEquals(listOf(FilePacket.JSON, FilePacket.JSON, FilePacket.SUCCESS), confirmed.map { it.type })
        assertEquals("""{"arrived":"0"}""", confirmed[0].text)
        assertEquals("""{"arrived":"1"}""", confirmed[1].text)
    }

    @Test
    fun aSenderThatAsksSendsTheMarkAndReadsTheConfirmations() {
        val sink = MemorySink()
        val requests = LinkedBlockingQueue<Map<String, Any?>>()
        val tv = Loopback { socket ->
            socket.soTimeout = 10_000
            val header = FileReceiver.readHeader(socket.getInputStream())
            requests.add(header)
            FileReceiver(sink).receive(header, "Test phone", socket.getInputStream(), socket.getOutputStream())
        }
        tv.use { send(tv.connect(), listOf(file("a.txt", "a".encodeToByteArray()), zeros("b.bin", 300_000)), confirmEach = true) }
        assertEquals("1", requests.poll(5, TimeUnit.SECONDS)!!["arrived"])
        assertEquals(listOf("a.txt", "b.bin"), sink.complete)
    }

    @Test
    fun everyFileConfirmedCountsAsSentEvenIfTheLastAnswerIsLost() {
        // The receiver confirmed each file, then the connection broke before its SUCCESS: they all arrived.
        val tv = Loopback { socket ->
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            input.readPacket()
            repeat(2) { index ->
                input.readPacket()
                input.readPacket()
                output.writeJson(mapOf("arrived" to index.toString()))
            }
            output.flush()
        }
        tv.use { send(tv.connect(), listOf(file("a.txt", "a".encodeToByteArray()), file("b.txt", "b".encodeToByteArray())), confirmEach = true) }
    }

    @Test
    fun aBrokenConnectionSaysHowFarTheTransferGot() {
        // The receiver confirms the first file, then goes away in the middle of the second.
        val tv = Loopback { socket ->
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            input.readPacket()
            input.readPacket()
            input.readPacket()
            output.writeJson(mapOf("arrived" to "0"))
            output.flush()
            input.readPacket()
            repeat(10) { input.readPacket() }
            socket.setSoLinger(true, 0) // A reset, as when the TV vanishes.
        }
        tv.use {
            try {
                send(tv.connect(), listOf(file("a.txt", "a".encodeToByteArray()), zeros("b.bin", 1L shl 30), file("c.txt", "c".encodeToByteArray())), confirmEach = true)
                fail("Expected a lost connection")
            } catch (e: TransferLostException) {
                assertEquals(1, e.arrived)
                assertEquals(2, e.started)
            }
        }
    }

    @Test
    fun namesStayInsideTheReceivingFolder() {
        assertEquals("etc/passwd", safeTransferPath("../../etc/passwd"))
        assertEquals("abs/x.txt", safeTransferPath("/abs/./x.txt"))
        assertEquals("a/b", safeTransferPath("a\\..\\b"))
        assertEquals("tab.txt", safeTransferPath("\ttab.txt\u0000"))
        assertNull(safeTransferPath("../.."))
        assertNull(safeTransferPath(""))
        val long = "é".repeat(300)
        assertEquals(127, safeTransferPath(long)!!.length) // 254 bytes: one more é would pass 255.
        assertEquals("😀".repeat(63), safeTransferPath("😀".repeat(100))) // Emoji are not split.

        val wire = ByteArrayOutputStream().apply {
            writePacket(FilePacket.JSON, """{"type":"file","name":"../../Android/data/evil","size":"1"}""".encodeToByteArray())
            writePacket(FilePacket.BINARY, byteArrayOf(1))
        }.toByteArray()
        val sink = MemorySink()
        FileReceiver(sink).receive(mapOf("count" to "1", "size" to "1"), "x", ByteArrayInputStream(wire), ByteArrayOutputStream())
        assertEquals(listOf("Android/data/evil"), sink.complete)
    }

    @Test
    fun receiverReportsFailuresToTheSender() {
        val sink = MemorySink().apply { open = { throw IOException("Not enough space on the TV") } }
        val tv = Loopback { receive(it, sink) }
        tv.use {
            try {
                send(tv.connect(), listOf(zeros("movie.mkv", 400_000_000)))
                fail("Expected a refusal")
            } catch (e: TransferRefusedException) {
                assertEquals("Not enough space on the TV", e.message)
            }
        }
        assertEquals(listOf("movie.mkv"), sink.abandoned)
    }

    @Test
    fun receiverRejectsMalformedTransfers() {
        fun receive(vararg packets: Pair<Int, String>, header: Map<String, Any?> = mapOf("count" to "1", "size" to "3")): ByteArray {
            val wire = ByteArrayOutputStream().apply { packets.forEach { (type, body) -> writePacket(type, body.encodeToByteArray()) } }
            val reply = ByteArrayOutputStream()
            try {
                FileReceiver(MemorySink()).receive(header, "x", ByteArrayInputStream(wire.toByteArray()), reply)
                fail("Expected a failure")
            } catch (e: ProtocolException) {
                // Expected.
            }
            return reply.toByteArray()
        }
        val reply = receive(FilePacket.JSON to """{"type":"file","name":"a","size":"2"}""", FilePacket.BINARY to "abc")
        assertEquals(FilePacket.ERROR, packets(reply).single().type)
        receive(FilePacket.BINARY to "data first")
        receive(FilePacket.JSON to """{"type":"url","name":"a","size":"0"}""")
        receive(FilePacket.JSON to """{"type":"file","name":"a","size":"-1"}""")
        receive(FilePacket.JSON to """{"type":"file","name":"a","size":"3"}""", FilePacket.BINARY to "ab")
        receive(header = mapOf("count" to "x", "size" to "3"))
        try {
            Json.parseObject("""{"name":"x","count":"1","size":"3"} trailing""")
            fail("Accepted trailing data")
        } catch (e: ProtocolException) {
            // Expected.
        }
    }

    @Test
    fun senderNoticesAReceiverThatNeverConfirms() {
        val tv = Loopback { socket ->
            val input = socket.getInputStream()
            while (input.read() != -1) continue
        }
        tv.use {
            try {
                send(tv.connect(), listOf(file("a.txt", "abc".encodeToByteArray())), stallTimeoutMs = 500)
                fail("Expected a stall")
            } catch (e: TransferStalledException) {
                assertTrue(e.message!!.contains("confirm"))
            }
        }
    }

    @Test
    fun senderNoticesAReceiverThatStopsReading() {
        val release = LinkedBlockingQueue<Unit>()
        val tv = Loopback { release.poll(10, TimeUnit.SECONDS) }
        tv.use {
            try {
                send(tv.connect(), listOf(zeros("big.bin", 1L shl 30)), stallTimeoutMs = 500)
                fail("Expected a stall")
            } catch (e: TransferStalledException) {
                assertTrue(e.message!!.contains("stopped"))
            } finally {
                release.add(Unit)
            }
        }
    }

    @Test
    fun cancellingStopsTheTransfer() {
        val release = LinkedBlockingQueue<Unit>()
        val tv = Loopback { release.poll(10, TimeUnit.SECONDS) }
        val cancelled = AtomicBoolean(false)
        val socket = tv.connect()
        Thread {
            Thread.sleep(300)
            cancelled.set(true)
            socket.close() // As ReceiverClient.close does: a blocked write only ends this way.
        }.start()
        tv.use {
            try {
                send(socket, listOf(zeros("big.bin", 1L shl 30)), cancelled = cancelled::get)
                fail("Expected a cancellation")
            } catch (e: CancellationException) {
                // Expected.
            } finally {
                release.add(Unit)
            }
        }
    }

    @Test
    fun aFileShorterThanItsSizeStopsTheTransfer() {
        val tv = Loopback { receive(it, MemorySink()) }
        tv.use {
            try {
                send(tv.connect(), listOf(OutgoingFile("short.txt", 10) { ByteArrayInputStream(ByteArray(5)) }))
                fail("Expected a read failure")
            } catch (e: FileUnreadableException) {
                assertEquals("short.txt", e.fileName)
            }
        }
    }

    @Test
    fun refusesOversizedPackets() {
        val header = byteArrayOf(0x02, 0x00, 0x50, 0x00, FilePacket.BINARY.toByte()) // 5 MiB.
        try {
            ByteArrayInputStream(header).readPacket()
            fail("Expected a refusal")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("out of range"))
        }
        assertNull(ByteArrayInputStream(ByteArray(0)).readPacket())
    }

    @Test
    fun jsonRoundTripsAndReadsGsonEscapes() {
        val fields = linkedMapOf<String, Any?>("name" to "a \"quoted\" \\ name\n\u0001", "size" to "12", "directory" to false, "n" to null)
        assertEquals(fields, Json.parseObject(Json.encode(fields)))
        val nested = linkedMapOf<String, Any?>("apps" to listOf(linkedMapOf("package" to "a.b", "label" to "Ünïcode ✓"), linkedMapOf<String, Any?>()))
        assertEquals(nested, Json.parseObject(Json.encode(nested)))
        assertEquals(mapOf("name" to "a=b<c>", "size" to "12", "list" to listOf("1", true)), Json.parseObject("""{"name":"a=b<c>","size":12,"list":[1,true]}"""))
        for (bad in listOf("""{"a":1""", """{"a":tru}""", """["a"]""", """{"a":1}x""", """{"a":"\x"}""")) {
            try {
                Json.parseObject(bad)
                fail("Accepted $bad")
            } catch (e: ProtocolException) {
                assertFalse(e.message.isNullOrEmpty())
            }
        }
    }
}
