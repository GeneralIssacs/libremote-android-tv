package app.libremotetvservice.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SessionTest {
    private val tv = FakeTv()
    private val phone = ClientIdentity.generate("libremote/test-phone")
    private val info = ClientInfo("Pixel", "Google", "16", "app.libremotetvservice", "1.0.0")

    @After
    fun closeTv() = tv.close()

    private fun pair(identity: ClientIdentity = phone): String {
        PairingSession.start("127.0.0.1", identity, "Test phone", port = tv.pairingPort, timeoutMs = 10_000).use { session ->
            assertEquals("Fake TV", session.tvName)
            assertTrue(session.submit(tv.nextCode()))
            return session.tvPin
        }
    }

    private inner class Events : RemoteConnection.Listener {
        val ready = CountDownLatch(1)
        val closed = LinkedBlockingQueue<Result<Unit>>()
        val volumes = LinkedBlockingQueue<VolumeState>()
        val fields = LinkedBlockingQueue<Pair<TvTextField, Boolean>>()
        @Volatile var power: Boolean? = null
        @Volatile var features = 0
        @Volatile var info: TvInfo? = null

        override fun onReady(tv: TvInfo, features: Int) {
            this.info = tv
            this.features = features
            ready.countDown()
        }
        override fun onPower(on: Boolean) { power = on }
        override fun onVolume(volume: VolumeState) { volumes.add(volume) }
        override fun onTextField(field: TvTextField, newField: Boolean) { fields.add(field to newField) }
        override fun onClosed(error: Throwable?) {
            closed.add(if (error == null) Result.success(Unit) else Result.failure(error))
        }
    }

    private fun connect(pin: String, events: Events, identity: ClientIdentity = phone) =
        RemoteConnection("127.0.0.1", pin, identity, info, events, port = tv.remotePort).also {
            Thread(it::run).start()
        }

    @Test
    fun pairsAndRemembersTheTv() {
        val pin = pair()
        assertEquals(tv.pin, pin)
    }

    @Test
    fun aTypoIsCaughtLocallyAndCanBeCorrected() {
        PairingSession.start("127.0.0.1", phone, "Test phone", port = tv.pairingPort, timeoutMs = 10_000).use { session ->
            val code = tv.nextCode()
            // Change the checksum byte: no secret can match, so nothing is sent and the session stays usable.
            val typo = (code.substring(0, 2).toInt(16) xor 0x01).let { "%02X".format(it) } + code.substring(2)
            assertFalse(session.submit(typo))
            assertTrue(session.submit(code))
        }
    }

    @Test
    fun aWrongCodeWithAMatchingChecksumIsRejectedByTheTv() {
        PairingSession.start("127.0.0.1", phone, "Test phone", port = tv.pairingPort, timeoutMs = 10_000).use { session ->
            val code = tv.nextCode()
            // Search for a different code whose checksum still matches, so it reaches the TV.
            val other = (0 until 0x10000).asSequence()
                .map { code.substring(0, 2) + "%04X".format(it) }
                .first { it != code && PairingCode.secret(it, phone.certificate, tv.identity.certificate) != null }
            try {
                session.submit(other)
                fail("The TV accepted a wrong code")
            } catch (e: PairingException) {
                assertEquals(402, e.status)
            }
        }
    }

    @Test
    fun normalizesWhatTheUserTypes() {
        assertEquals("AB12CD", PairingCode.normalize(" ab-12 cd "))
        assertEquals("A1B2C3", PairingCode.normalize("a1b2c3d4"))
        assertEquals("", PairingCode.normalize("xyz"))
    }

    @Test
    fun connectsAndNegotiatesFeatures() {
        val pin = pair()
        tv.afterActivation = listOf(
            ProtoWriter().message(Remote.POWER, ProtoWriter().bool(1, true)),
            ProtoWriter().message(
                Remote.VOLUME,
                ProtoWriter().int(1, 9).int(2, 8).string(3, "Speaker").int(4, 2).int(5, 0).int(6, 100).int(7, 30).bool(8, false),
            ),
        )
        val events = Events()
        val connection = connect(pin, events)
        assertTrue(events.ready.await(10, TimeUnit.SECONDS))
        // The fake TV also offers voice, which this app does not ask for.
        assertEquals(Features.WANTED, tv.configuredFeatures)
        assertEquals(Features.WANTED, events.features)
        tv.await { activeFeatures == Features.WANTED && pingAnswered }
        val volume = events.volumes.poll(5, TimeUnit.SECONDS)!!
        assertEquals(VolumeState(9, "Speaker", VolumeState.Control.ABSOLUTE, 30, 100, false), volume)
        assertEquals(true, events.power)

        connection.press(KeyCodes.HOME)
        connection.hold(KeyCodes.DPAD_CENTER)
        connection.release(KeyCodes.DPAD_CENTER)
        connection.volumeUp()
        connection.openLink("https://www.youtube.com", "com.google.android.youtube.tv")
        connection.findRemote(true)

        fun next() = tv.received.poll(5, TimeUnit.SECONDS)!!
        next().let { (type, body) -> assertEquals(Remote.KEY_INJECT, type); assertEquals(3, body.int(1)); assertEquals(3, body.int(2)) }
        next().let { (_, body) -> assertEquals(23, body.int(1)); assertEquals(1, body.int(2)) }
        next().let { (_, body) -> assertEquals(23, body.int(1)); assertEquals(2, body.int(2)) }
        next().let { (type, body) -> assertEquals(Remote.ADJUST_VOLUME_LEVEL, type); assertEquals(9, body.int(1)); assertEquals(0, body.int(2)) }
        next().let { (type, body) ->
            assertEquals(Remote.APP_LINK, type)
            assertEquals("https://www.youtube.com", body.string(1))
            assertEquals("com.google.android.youtube.tv", body.string(2))
        }
        next().let { (type, body) -> assertEquals(Remote.FIND_MY_REMOTE, type); assertEquals(1, body.int(1)) }

        connection.close()
        assertTrue(events.closed.poll(5, TimeUnit.SECONDS)!!.isSuccess)
    }

    @Test
    fun reportsWhatTheTvSaysAboutItself() {
        val pin = pair()
        val events = Events()
        val connection = connect(pin, events)
        assertTrue(events.ready.await(10, TimeUnit.SECONDS))
        // CONFIGURE's DeviceInfo: the TV's model, maker and Android version, and the remote service as the "app".
        assertEquals(
            TvInfo(Features.WANTED or Features.VOICE, "Fake", "Libremote", "14", "com.google.android.tv.remote.service", "5.99.551144881"),
            events.info,
        )
        assertTrue(events.info!!.supports(Features.APP_LINKS))
        connection.close()
    }

    @Test
    fun onlyAsksForFeaturesTheTvOffers() {
        tv.close()
        FakeTv(offeredFeatures = Features.PING or Features.KEYS).use { small ->
            val pin = PairingSession.start("127.0.0.1", phone, "Test", port = small.pairingPort).use {
                assertTrue(it.submit(small.nextCode()))
                it.tvPin
            }
            val events = Events()
            val connection = RemoteConnection("127.0.0.1", pin, phone, info, events, port = small.remotePort)
            Thread(connection::run).start()
            assertTrue(events.ready.await(10, TimeUnit.SECONDS))
            assertEquals(Features.PING or Features.KEYS, small.configuredFeatures)
            // No app-link feature: the command must not reach the TV, which would drop the connection.
            connection.openLink("https://example.com")
            connection.press(KeyCodes.BACK)
            assertEquals(Remote.KEY_INJECT, small.received.poll(5, TimeUnit.SECONDS)!!.first)
            connection.close()
        }
    }

    @Test
    fun editsTextFieldsWithMinimalChanges() {
        val pin = pair()
        // EditorInfo{1: field 7, 2: inputType text, 3: imeOptions search, 10: hint} + TextStatus{1: counter 5, 2: "star"}.
        tv.afterActivation = listOf(
            ProtoWriter().message(
                Remote.IME_FIELD,
                ProtoWriter()
                    .message(1, ProtoWriter().int(1, 7).int(2, 1).int(3, 3).string(10, "Search").string(12, "com.example"))
                    .message(2, ProtoWriter().int(1, 5).string(2, "star").int(3, 4).int(4, 4)),
            ),
        )
        val events = Events()
        val connection = connect(pin, events)
        val (field, newField) = events.fields.poll(10, TimeUnit.SECONDS)!!
        assertTrue(newField)
        assertEquals("star", field.text)
        assertEquals("Search", field.hint)
        assertTrue(field.isEditable)

        connection.setText("star wars")
        val (type, batch) = tv.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals(Remote.IME_BATCH_EDIT, type)
        assertEquals(7, batch.int(1))
        assertEquals(5, batch.int(2))
        val (select, replace) = batch.messages(3)
        assertEquals(4, select.message(1)!!.int(2))
        assertEquals(4, select.message(1)!!.int(3))
        replace.message(2)!!.let { assertEquals(4, it.int(1)); assertEquals(4, it.int(2)); assertEquals(" wars", it.string(3)) }

        connection.submitText()
        val (_, action) = tv.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals(3, action.messages(3).single().message(5)!!.int(1)) // IME_ACTION_SEARCH
        connection.close()
    }

    @Test
    fun anUnpairedPhoneIsTold() {
        val stranger = ClientIdentity.generate("libremote/stranger")
        val events = Events()
        connect(tv.pin, events, stranger)
        val error = events.closed.poll(10, TimeUnit.SECONDS)!!.exceptionOrNull()
        assertTrue("Got $error", error is NotPairedException)
        assertNull(tv.received.poll())
    }

    @Test
    fun aDifferentTvIsRefused() {
        pair()
        val events = Events()
        connect("00".repeat(32), events)
        val error = events.closed.poll(10, TimeUnit.SECONDS)!!.exceptionOrNull()
        assertTrue("Got $error", error is TvIdentityChangedException)
    }

    @Test
    fun identitiesSurviveEncoding() {
        val restored = ClientIdentity.decode(phone.privateKey.encoded, phone.certificate.encoded)
        assertEquals(phone.certificate, restored.certificate)
        assertEquals("CN=libremote/test-phone", phone.certificate.subjectX500Principal.name)
        phone.certificate.checkValidity()
        phone.certificate.verify(phone.certificate.publicKey)
        assertNotNull(PairingCode.normalize("ABCDEF"))
        assertNotEquals(publicKeyPin(phone.certificate), publicKeyPin(tv.identity.certificate))
    }
}
