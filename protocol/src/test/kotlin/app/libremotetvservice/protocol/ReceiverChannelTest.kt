package app.libremotetvservice.protocol

import app.libremotetvservice.protocol.FileTransferTest.MemorySink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Libremote Receiver's channel: pairing by link, both ends verified, and the requests, over real TLS on loopback. */
class ReceiverChannelTest {

    /** Identities take a moment to make; these tests share a few. */
    private companion object {
        val phone = ClientIdentity.generate("libremote/test-phone")
        val otherPhone = ClientIdentity.generate("libremote/other-phone")
        val receiverIdentity = ClientIdentity.generate("libremote-receiver/test")
        val otherReceiverIdentity = ClientIdentity.generate("libremote-receiver/other")
        val phoneKey = publicKeyPin(phone.certificate)
        val receiverKey = publicKeyPin(receiverIdentity.certificate)
    }

    /** What the TV app keeps: paired phones, the pending secret of each, and the names they gave. */
    private class Phones : ReceiverServer.Phones {
        val paired = ConcurrentHashMap<String, Boolean>()
        val secrets = ConcurrentHashMap<String, ByteArray>()
        val names = ConcurrentHashMap<String, String>()

        /** What opening `libremote://receive?k=…&s=…` does on the TV. */
        fun accept(invite: ReceiverInvite) {
            paired[invite.phoneKey] = true
            secrets[invite.phoneKey] = invite.secret
        }

        override fun isPaired(phoneKey: String) = paired[phoneKey] == true
        override fun takeSecret(phoneKey: String): ByteArray? = secrets.remove(phoneKey)
        override fun named(phoneKey: String, name: String) {
            names[phoneKey] = name
        }
    }

    private class Handler : ReceiverServer.Handler {
        val sink = MemorySink()
        val requests = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        var apps = listOf<InstalledApp>()

        override fun sink(phone: ReceiverServer.Phone, update: Boolean): FileReceiver.Sink =
            sink.also { requests += if (update) "update" else "send" }
        override fun apps(): List<InstalledApp> = apps.also { requests += "apps" }
        override fun closeRequested(phone: ReceiverServer.Phone) {
            requests += "close"
            closed.countDown()
        }
    }

    private val phones = Phones()
    private val handler = Handler()
    private val servers = mutableListOf<ReceiverServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun server(
        identity: ClientIdentity = receiverIdentity,
        protocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
        appVersion: Long? = null,
        confirmsEachFile: Boolean = true,
    ) =
        ReceiverServer(identity, phones, handler, port = 0, protocols = protocols, appVersion = appVersion, confirmsEachFile = confirmsEachFile).also {
            it.start(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            servers += it
        }

    private fun connect(server: ReceiverServer, pin: String?, secret: ByteArray?, identity: ClientIdentity = phone) =
        ReceiverClient.connect("127.0.0.1", identity, pin, secret, "Test phone", port = server.localPort, timeoutMs = 5_000)

    private fun file(name: String, data: ByteArray) = OutgoingFile(name, data.size.toLong()) { ByteArrayInputStream(data) }

    @Test
    fun aGoodSecretPairsThenThePinIsEnough() {
        val server = server()
        val invite = ReceiverInvite.create(phoneKey)
        phones.accept(invite)
        val data = Random(1).nextBytes(200_000)
        connect(server, pin = null, secret = invite.secret).use { client ->
            assertTrue(client.provedSecret)
            assertEquals(receiverKey, client.receiverKey)
            client.send(listOf(file("a/b.bin", data), file("c.txt", "hi".encodeToByteArray())))
            // The same connection takes further requests.
            assertTrue(client.apps().isEmpty())
        }
        assertEquals(listOf("a/b.bin", "c.txt"), handler.sink.complete)
        assertArrayEquals(data, handler.sink.files["a/b.bin"]!!.toByteArray())
        assertEquals("Test phone", handler.sink.sender)
        assertEquals("Test phone", phones.names[phoneKey])
        // The secret was used once. Later connections need only the pin.
        assertNull(phones.secrets[phoneKey])
        connect(server, pin = receiverKey, secret = null).use { assertFalse(it.provedSecret) }
    }

    @Test
    fun aWrongSecretIsRefusedBeforeAnythingIsSent() {
        val server = server()
        phones.accept(ReceiverInvite.create(phoneKey))
        try {
            connect(server, pin = null, secret = ReceiverInvite.create(phoneKey).secret)
            fail("Accepted a receiver that didn't prove the secret")
        } catch (e: ReceiverUnverifiedException) {
            assertFalse(e.pinMismatch)
        }
        // No proof at all: the secret was used up by the first connection.
        try {
            connect(server, pin = null, secret = ByteArray(32))
            fail("Accepted a receiver without a proof")
        } catch (e: ReceiverUnverifiedException) {
            assertFalse(e.pinMismatch)
        }
        Thread.sleep(200)
        assertTrue(handler.requests.isEmpty())
        assertNull(phones.names[phoneKey]) // Not even the phone's name went out.
    }

    @Test
    fun anUnpairedPhoneIsRefused() {
        val server = server()
        phones.accept(ReceiverInvite.create(phoneKey))
        val invite = ReceiverInvite.create(publicKeyPin(otherPhone.certificate))
        // otherPhone never sent a link: the TV doesn't know its key, whatever secret it brings.
        try {
            connect(server, pin = null, secret = invite.secret, identity = otherPhone)
            fail("An unpaired phone was accepted")
        } catch (e: ReceiverRefusedPhoneException) {
            // Refused during the handshake (TLS 1.2) or at the first read (TLS 1.3).
        }
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun anUnpairedPhoneIsRefusedOverTls12Too() {
        val server = server(protocols = listOf("TLSv1.2"))
        try {
            connect(server, pin = null, secret = null, identity = otherPhone)
            fail("An unpaired phone was accepted")
        } catch (e: ReceiverRefusedPhoneException) {
            // Expected.
        }
    }

    @Test
    fun aChangedReceiverKeyIsRefusedUntilItProvesANewSecret() {
        // The phone pinned receiverIdentity; now something else answers with another key.
        val server = server(identity = otherReceiverIdentity)
        phones.paired[phoneKey] = true
        try {
            connect(server, pin = receiverKey, secret = null)
            fail("Accepted a receiver whose key changed")
        } catch (e: ReceiverUnverifiedException) {
            assertTrue(e.pinMismatch)
        }
        // A secret it doesn't know doesn't help it.
        try {
            connect(server, pin = receiverKey, secret = ReceiverInvite.create(phoneKey).secret)
            fail("Accepted a receiver whose key changed")
        } catch (e: ReceiverUnverifiedException) {
            assertTrue(e.pinMismatch)
        }
        assertTrue(handler.requests.isEmpty())
        // The receiver was reinstalled, say: a fresh link pairs the phone again, and the new key is proven.
        val invite = ReceiverInvite.create(phoneKey)
        phones.accept(invite)
        connect(server, pin = receiverKey, secret = invite.secret).use { client ->
            assertTrue(client.provedSecret)
            assertEquals(publicKeyPin(otherReceiverIdentity.certificate), client.receiverKey)
        }
    }

    @Test
    fun aProofMadeForAnotherPhoneDoesNotVerify() {
        val secret = ReceiverInvite.create(phoneKey).secret
        val proof = ReceiverProof.compute(secret, phoneKey, receiverKey)
        assertTrue(ReceiverProof.verify(proof, secret, phoneKey, receiverKey))
        assertFalse(ReceiverProof.verify(proof, secret, publicKeyPin(otherPhone.certificate), receiverKey))
        assertFalse(ReceiverProof.verify(proof, secret, phoneKey, publicKeyPin(otherReceiverIdentity.certificate)))
        assertFalse(ReceiverProof.verify(proof, ReceiverInvite.create(phoneKey).secret, phoneKey, receiverKey))
    }

    @Test
    fun listsTheTvsAppsWithTheirIcons() {
        val server = server()
        phones.paired[phoneKey] = true
        val icon = Random(2).nextBytes(9_000)
        handler.apps = listOf(
            InstalledApp("com.google.android.youtube.tv", "YouTube", icon),
            InstalledApp("org.jellyfin.androidtv", "Jellyfin «TV» ✓", null),
            InstalledApp("app.sofabrowser", "Sofa", byteArrayOf(1, 2, 3)),
        )
        connect(server, pin = receiverKey, secret = null).use { client ->
            val apps = client.apps()
            assertEquals(listOf("com.google.android.youtube.tv", "org.jellyfin.androidtv", "app.sofabrowser"), apps.map { it.packageName })
            assertEquals(listOf("YouTube", "Jellyfin «TV» ✓", "Sofa"), apps.map { it.label })
            assertArrayEquals(icon, apps[0].icon)
            assertNull(apps[1].icon)
            assertArrayEquals(byteArrayOf(1, 2, 3), apps[2].icon)
            // And again on the same connection, then a send.
            assertEquals(3, client.apps().size)
            client.send(listOf(file("x.txt", "x".encodeToByteArray())))
        }
        assertEquals(listOf("apps", "apps", "send"), handler.requests)
    }

    @Test
    fun aSyncOpenAsksTheReceiverToClose() {
        val server = server()
        phones.paired[phoneKey] = true
        connect(server, pin = receiverKey, secret = null).use { it.closeReceiver() }
        assertTrue(handler.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun worksOverTls12WithForwardSecretAeadSuites() {
        // What Android 8 and 9 speak.
        val server = server(protocols = listOf("TLSv1.2"))
        val invite = ReceiverInvite.create(phoneKey)
        phones.accept(invite)
        connect(server, pin = null, secret = invite.secret).use { client ->
            val (protocol, suite) = client.tls.split(' ')
            assertEquals("TLSv1.2", protocol)
            assertTrue(suite, suite.startsWith("TLS_ECDHE_RSA_WITH_") && (suite.contains("_GCM_") || suite.contains("CHACHA20_POLY1305")))
            client.send(listOf(file("x.txt", "x".encodeToByteArray())))
        }
    }

    @Test
    fun usesTls13WhereBothEndsHaveIt() {
        val server = server()
        phones.paired[phoneKey] = true
        connect(server, pin = receiverKey, secret = null).use { assertTrue(it.tls, it.tls.startsWith("TLSv1.3 ")) }
    }

    @Test
    fun aRefusalReachesThePhoneWhileItIsStillSending() {
        val server = server()
        phones.paired[phoneKey] = true
        handler.sink.open = { throw IOException("Not enough space on the TV") }
        connect(server, pin = receiverKey, secret = null).use { client ->
            try {
                client.send(listOf(OutgoingFile("big.bin", 2_000_000_000) { ZeroStream() }))
                fail("Expected a refusal")
            } catch (e: TransferRefusedException) {
                assertEquals("Not enough space on the TV", e.message)
            }
        }
        assertEquals(listOf("big.bin"), handler.sink.abandoned)
    }

    /** A sink where the connection drops once [path] has got [after] bytes, as when the phone's Wi-Fi goes off. */
    private fun dropIn(server: ReceiverServer, path: String, after: Int) {
        handler.sink.open = { name ->
            if (name != path) java.io.ByteArrayOutputStream() else object : java.io.ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    if (size() >= after) server.disconnect(phoneKey)
                }
            }
        }
    }

    private val batch = listOf(
        file("a-first.bin", Random(4).nextBytes(10_000)),
        OutgoingFile("b-big.bin", 2_000_000_000) { ZeroStream() },
        file("c-last.bin", "c".encodeToByteArray()),
    )

    @Test
    fun aLostConnectionSaysWhichFilesArrived() {
        val server = server(appVersion = 10001)
        phones.paired[phoneKey] = true
        dropIn(server, "b-big.bin", after = 1_000_000)
        connect(server, pin = receiverKey, secret = null).use { client ->
            assertTrue(client.confirmsEachFile)
            try {
                client.send(batch)
                fail("Expected a lost connection")
            } catch (e: TransferLostException) {
                assertEquals(1, e.arrived) // a-first.bin, confirmed by the receiver.
                assertEquals(2, e.started) // b-big.bin was under way; c-last.bin never started.
            }
        }
        assertEquals(listOf("a-first.bin"), handler.sink.complete)
        assertEquals(listOf("b-big.bin"), handler.sink.abandoned)
    }

    @Test
    fun aReceiverBefore101DoesNotSayWhichFilesArrived() {
        // Its hello doesn't offer it, so the phone doesn't ask, and knows only how far it got itself.
        val server = server(appVersion = 10000, confirmsEachFile = false)
        phones.paired[phoneKey] = true
        dropIn(server, "b-big.bin", after = 1_000_000)
        connect(server, pin = receiverKey, secret = null).use { client ->
            assertFalse(client.confirmsEachFile)
            try {
                client.send(batch)
                fail("Expected a lost connection")
            } catch (e: TransferLostException) {
                assertNull(e.arrived)
                assertEquals(2, e.started)
            }
        }
        assertEquals(listOf("a-first.bin"), handler.sink.complete)
    }

    @Test
    fun theReceiverSaysWhichVersionItIs() {
        val server = server(appVersion = 10001)
        phones.paired[phoneKey] = true
        connect(server, pin = receiverKey, secret = null).use { assertEquals(10001L, it.receiverVersion) }
    }

    @Test
    fun aReceiverOlderThan100SaysNoVersion() {
        // Receivers before 1.0.0 sent only "receiver" and "version" (the channel's): the phone knows nothing more.
        val server = server(appVersion = null)
        phones.paired[phoneKey] = true
        connect(server, pin = receiverKey, secret = null).use { client ->
            assertNull(client.receiverVersion)
            client.send(listOf(file("x.txt", "x".encodeToByteArray())))
        }
    }

    @Test
    fun anUpdateIsMarkedAsOne() {
        val server = server(appVersion = 10000)
        phones.paired[phoneKey] = true
        val apk = Random(3).nextBytes(300_000)
        connect(server, pin = receiverKey, secret = null).use { client ->
            client.sendUpdate(file("libremote-receiver-1.0.1.apk", apk))
            // A plain send on the same connection is not.
            client.send(listOf(file("photo.jpg", "jpg".encodeToByteArray())))
        }
        assertEquals(listOf("update", "send"), handler.requests)
        assertArrayEquals(apk, handler.sink.files["libremote-receiver-1.0.1.apk"]!!.toByteArray())
        assertEquals(listOf("libremote-receiver-1.0.1.apk", "photo.jpg"), handler.sink.complete)
    }

    @Test
    fun anUpdateOfSeveralFilesIsRefused() {
        val server = server(appVersion = 10000)
        phones.paired[phoneKey] = true
        connect(server, pin = receiverKey, secret = null).use { client ->
            try {
                client.transfer(listOf(file("a.apk", ByteArray(10)), file("b.apk", ByteArray(10))), null, update = true)
                fail("Expected a refusal")
            } catch (e: TransferRefusedException) {
                assertEquals("An update is one APK.", e.message)
            }
        }
        // Turned down before the handler was asked for a place to put them.
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun theReceiverSaysWhyItTurnsAnUpdateDown() {
        val server = server(appVersion = 10001)
        phones.paired[phoneKey] = true
        // What the TV says when the APK isn't a newer Libremote Receiver signed like it.
        handler.sink.open = { throw IOException("The TV already has Libremote Receiver 1.0.1 or later.") }
        connect(server, pin = receiverKey, secret = null).use { client ->
            try {
                client.sendUpdate(file("libremote-receiver-1.0.1.apk", ByteArray(100_000)))
                fail("Expected a refusal")
            } catch (e: TransferRefusedException) {
                assertEquals("The TV already has Libremote Receiver 1.0.1 or later.", e.message)
            }
        }
        assertEquals(listOf("update"), handler.requests)
    }

    @Test
    fun nothingListeningIsReportedAsSuch() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            ReceiverClient.connect("127.0.0.1", phone, receiverKey, null, "Test phone", port = closedPort)
            fail("Expected nothing to receive")
        } catch (e: ReceiverUnavailableException) {
            // Refused.
        }
        // Through the emulator's port forwarding, a closed port accepts and then hangs up straight away.
        val hangUp = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread { runCatching { hangUp.accept().close() } }.start()
        hangUp.use {
            try {
                ReceiverClient.connect("127.0.0.1", phone, receiverKey, null, "Test phone", port = hangUp.localPort)
                fail("Expected nothing to receive")
            } catch (e: ReceiverUnavailableException) {
                // Accepted, then closed.
            }
        }
    }

    @Test
    fun invitesRoundTripThroughTheLink() {
        val invite = ReceiverInvite.create(phoneKey)
        val link = invite.link()
        assertTrue(link, link.startsWith("libremote://receive?v=2&k=$phoneKey&s="))
        assertTrue(invite.link(syncOnly = true).startsWith("libremote://sync?"))
        val query = link.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val parsed = ReceiverInvite.parse(query["k"], query["s"])!!
        assertEquals(phoneKey, parsed.phoneKey)
        assertArrayEquals(invite.secret, parsed.secret)
        assertNull(ReceiverInvite.parse(phoneKey.uppercase(), query["s"]))
        assertNull(ReceiverInvite.parse(phoneKey, query["s"]!!.drop(2)))
        assertNull(ReceiverInvite.parse(phoneKey, "zz" + query["s"]!!.drop(2)))
        assertNull(ReceiverInvite.parse(null, query["s"]))
    }

    private class ZeroStream : java.io.InputStream() {
        override fun read() = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill(0, off, off + len)
            return len
        }
    }
}
