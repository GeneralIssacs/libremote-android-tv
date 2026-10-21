package app.libremotetvservice.protocol

import app.libremotetvservice.protocol.FileTransferTest.MemorySink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New phones are confirmed on the TV: a link from a key the receiver doesn't know pairs nothing until the user
 * allows it there, and a phone that was removed stays removed until allowed again. Real TLS on loopback.
 */
class PhoneConfirmationTest {

    private companion object {
        val phone = ClientIdentity.generate("libremote/test-phone")
        val otherPhone = ClientIdentity.generate("libremote/other-phone")
        val receiverIdentity = ClientIdentity.generate("libremote-receiver/test")
        val phoneKey = publicKeyPin(phone.certificate)
    }

    private class Handler : ReceiverServer.Handler {
        val sink = MemorySink()
        override fun sink(phone: ReceiverServer.Phone, update: Boolean): FileReceiver.Sink = sink
        override fun apps(): List<InstalledApp> = emptyList()
    }

    private val store = PhoneRegistry.MemoryStore()
    private val registry = PhoneRegistry(store)
    private val handler = Handler()
    private val servers = mutableListOf<ReceiverServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun server(registry: PhoneRegistry = this.registry) = ReceiverServer(receiverIdentity, registry, handler, port = 0).also {
        it.start(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        servers += it
    }

    private fun file(name: String, data: ByteArray) = OutgoingFile(name, data.size.toLong()) { ByteArrayInputStream(data) }

    @Test
    fun aNewPhoneWaitsUntilTheTvAllowsIt() {
        val server = server()
        val invite = ReceiverInvite.create(phoneKey, name = "Pixel 8")
        val request = registry.accept(invite)!!
        assertEquals("Pixel 8", request.name)
        val codes = CopyOnWriteArrayList<String>()
        // The user picks Allow on the TV once the phone shows its code.
        val client = ReceiverClient.connect(
            "127.0.0.1", phone, pin = null, secret = invite.secret, phoneName = "Pixel 8", port = server.localPort,
            confirming = { code ->
                codes += code
                registry.decide(registry.pending()!!, allow = true)
            },
        )
        client.use {
            assertTrue(it.provedSecret)
            it.send(listOf(file("a.txt", "hi".encodeToByteArray())))
        }
        // Both ends showed the same code.
        assertEquals(listOf(request.code), codes)
        assertTrue(Regex("\\d{3}\u00A0\\d{3}").matches(request.code))
        assertEquals(listOf("a.txt"), handler.sink.complete)
        assertEquals(listOf(phoneKey), registry.all().map { it.key })
    }

    @Test
    fun aPhoneTheUserTurnsDownGetsNothingThrough() {
        val server = server()
        val invite = ReceiverInvite.create(phoneKey, name = "Pixel 8")
        registry.accept(invite)!!
        try {
            connect(server, invite, confirming = { registry.decide(registry.pending()!!, allow = false) })
            fail("A phone the user turned down was accepted")
        } catch (e: ReceiverDeclinedException) {
            assertEquals("This phone wasn't allowed on the TV.", e.message)
        }
        assertTrue(registry.all().isEmpty())
        assertNull(registry.pending())
        // And it can't simply connect again: the TV no longer asks about it.
        assertRefused(server, invite)
        assertTrue(handler.sink.complete.isEmpty())
    }

    @Test
    fun aQuestionNobodyAnswersLapses() {
        val registry = PhoneRegistry(store, confirmMs = 500)
        val server = server(registry)
        val invite = ReceiverInvite.create(phoneKey)
        val asked = registry.accept(invite)!!
        try {
            connect(server, invite)
            fail("A phone nobody allowed was accepted")
        } catch (e: ReceiverDeclinedException) {
            // Expected.
        }
        assertNull(registry.pending())
        // Allowing it once the question lapsed pairs nothing.
        assertFalse(registry.decide(asked, allow = true))
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun aPhoneWithAVersion1LinkIsTurnedAwayUntilAllowedThenTriesAgain() {
        // Libremote before 1.0.1 can't wait: it is refused while the TV asks, and its retries get in once allowed.
        val server = server()
        val link = ReceiverInvite.create(phoneKey)
        val invite = ReceiverInvite.parse(phoneKey, link.secret.toHex(), name = null, version = "1")!!
        assertNotNull(registry.accept(invite))
        assertRefused(server, invite)
        registry.decide(registry.pending()!!, allow = true)
        connect(server, invite).use { assertTrue(it.provedSecret) }
    }

    @Test
    fun anotherNewPhoneCantTakeOverAnOpenQuestion() {
        val server = server()
        val invite = ReceiverInvite.create(phoneKey, name = "Pixel 8")
        val asked = registry.accept(invite)!!
        // Another app on the TV opens a link with its own key, under the same name.
        val forged = ReceiverInvite.create(publicKeyPin(otherPhone.certificate), name = "Pixel 8")
        assertNull(registry.accept(forged))
        assertEquals(phoneKey, registry.pending()!!.key)
        assertRefused(server, forged, identity = otherPhone)
        // Its code could never match the real phone's: it doesn't know that link's secret.
        assertNotEquals(asked.code, forged.code)
    }

    @Test
    fun aPairedPhoneIsNotAskedAboutAgain() {
        val server = server()
        val first = ReceiverInvite.create(phoneKey)
        registry.accept(first)!!
        registry.decide(registry.pending()!!, allow = true)
        val next = ReceiverInvite.create(phoneKey)
        assertNull(registry.accept(next))
        assertNull(registry.pending())
        connect(server, next, confirming = { fail("Asked about a paired phone") }).use { assertTrue(it.provedSecret) }
    }

    @Test
    fun aRemovedPhoneStaysRemovedUntilAllowedAgain() {
        registry.accept(ReceiverInvite.create(phoneKey, name = "Pixel 8"))!!
        registry.decide(registry.pending()!!, allow = true)
        registry.remove(phoneKey)
        // The TV restarts: a new registry over what was saved.
        val restarted = PhoneRegistry(store)
        val server = server(restarted)
        assertFalse(restarted.isPaired(phoneKey))
        // Its next link (it is still paired with the TV itself, so it can send one) only asks again.
        val again = ReceiverInvite.create(phoneKey, name = "Pixel 8")
        val asked = restarted.accept(again)!!
        assertTrue(asked.wasRemoved)
        restarted.decide(restarted.pending()!!, allow = false)
        assertRefused(server, again)
        // Turned down again, it is still marked as removed; allowed, it pairs and the mark goes.
        val third = ReceiverInvite.create(phoneKey)
        assertTrue(restarted.accept(third)!!.wasRemoved)
        connect(server, third, confirming = { restarted.decide(restarted.pending()!!, allow = true) }).use { assertTrue(it.provedSecret) }
        restarted.remove(phoneKey)
        restarted.accept(ReceiverInvite.create(phoneKey))!!
        restarted.decide(restarted.pending()!!, allow = true)
        assertNull(PhoneRegistry(store).accept(ReceiverInvite.create(phoneKey)))
    }

    @Test
    fun removingAPhoneThatIsBeingAskedAboutTurnsItDown() {
        val invite = ReceiverInvite.create(phoneKey)
        registry.accept(invite)!!
        registry.remove(phoneKey)
        assertNull(registry.pending())
        assertNull(registry.takeSecret(phoneKey))
    }

    @Test
    fun aPhoneCanStopWaiting() {
        val server = server()
        val invite = ReceiverInvite.create(phoneKey)
        registry.accept(invite)!!
        val cancelled = AtomicBoolean(false)
        val started = System.nanoTime()
        try {
            ReceiverClient.connect(
                "127.0.0.1", phone, pin = null, secret = invite.secret, phoneName = "Pixel 8", port = server.localPort,
                confirming = { Thread { Thread.sleep(300); cancelled.set(true) }.start() }, cancelled = cancelled::get,
            )
            fail("Expected a cancellation")
        } catch (e: CancellationException) {
            // Within about a second of Cancel, not when the TV gives up.
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }

    @Test
    fun thePhonesNameTravelsInTheLink() {
        val invite = ReceiverInvite.create(phoneKey, name = "  Ana’s phone & tablet+  ")
        val link = invite.link()
        assertTrue(link, link.startsWith("libremote://receive?v=2&k=$phoneKey&s="))
        assertFalse(link, link.contains(' ') || link.contains('+'))
        val query = link.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val parsed = ReceiverInvite.parse(query["k"], query["s"], URLDecoder.decode(query["n"], "UTF-8"), query["v"])!!
        assertEquals("Ana’s phone & tablet+", parsed.name)
        assertTrue(parsed.canWait)
        assertEquals(invite.code, parsed.code)
        // What the TV shows is cleaned up: no control characters, and not too long.
        assertEquals("ab", ReceiverInvite.parse(phoneKey, query["s"], "a\u0000\nb", "2")!!.name)
        assertEquals(ReceiverInvite.MAX_NAME, ReceiverInvite.parse(phoneKey, query["s"], "x".repeat(500), "2")!!.name!!.length)
        assertNull(ReceiverInvite.parse(phoneKey, query["s"], "   ", "2")!!.name)
        // A link without a version is from before versions 2.
        assertFalse(ReceiverInvite.parse(phoneKey, query["s"])!!.canWait)
    }

    @Test
    fun anAnswerToAnOlderQuestionDoesNotDecideTheNewerOne() {
        // The same phone sent another link (say a sync while a send waited): the TV asks again, with its code.
        val older = registry.accept(ReceiverInvite.create(phoneKey))!!
        val newer = registry.accept(ReceiverInvite.create(phoneKey))!!
        assertFalse(registry.decide(older, allow = false))
        assertTrue(registry.pending() === newer)
        assertFalse(registry.decide(older, allow = true))
        assertTrue(registry.all().isEmpty())
        assertTrue(registry.decide(newer, allow = true))
        assertEquals(listOf(phoneKey), registry.all().map { it.key })
    }

    @Test
    fun aConnectionWaitingOnAReplacedQuestionWaitsForTheNewOne() {
        val server = server()
        val first = ReceiverInvite.create(phoneKey)
        registry.accept(first)!!
        val result = java.util.concurrent.CompletableFuture<Throwable?>()
        Thread {
            result.complete(runCatching { connect(server, first, confirming = { registry.accept(ReceiverInvite.create(phoneKey)) }).close() }.exceptionOrNull())
        }.start()
        // The waiting connection isn't told "not allowed" because a newer link replaced its question.
        Thread.sleep(1_000)
        assertFalse(result.isDone)
        registry.decide(registry.pending()!!, allow = true)
        // Allowed: the phone is paired. That connection made its link before the newer one, so the proof (of the
        // newer secret) doesn't verify for it; the newer one's connection would.
        assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS) is ReceiverUnverifiedException)
        assertTrue(registry.isPaired(phoneKey))
    }

    @Test
    fun aPinnedConnectionDoesNotWaitWhileTheTvAsks() {
        // A removed phone first tries its pin, with no link and no code to show: it is turned away at once, and
        // sends a link (which the TV asks about) instead of waiting in silence.
        val server = server()
        registry.accept(ReceiverInvite.create(phoneKey))!!
        val started = System.nanoTime()
        try {
            ReceiverClient.connect("127.0.0.1", phone, pin = "00".repeat(32), secret = null, phoneName = "Pixel 8", port = server.localPort).close()
            fail("A pinned connection got in while the TV asked")
        } catch (e: ReceiverRefusedPhoneException) {
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun theCodeIsAsciiDigitsWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val code = ReceiverInvite.create(phoneKey).code
            assertTrue(code, Regex("[0-9]{3} [0-9]{3}").matches(code))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    private fun connect(server: ReceiverServer, invite: ReceiverInvite, identity: ClientIdentity = phone, confirming: (String) -> Unit = {}) =
        ReceiverClient.connect(
            "127.0.0.1", identity, pin = null, secret = invite.secret, phoneName = "Pixel 8", port = server.localPort,
            confirming = confirming,
        )

    private fun assertRefused(server: ReceiverServer, invite: ReceiverInvite, identity: ClientIdentity = phone) {
        try {
            connect(server, invite, identity).close()
            fail("The phone was accepted")
        } catch (e: ReceiverRefusedPhoneException) {
            // Turned away during the handshake.
        }
    }
}
