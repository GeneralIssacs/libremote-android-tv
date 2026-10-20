package app.libremotetvservice.protocol

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to a real TV, or the Android TV emulator, when run with -Plibremote.tv=host[:remotePort[:pairingPort]].
 * The first run pairs: write the code the TV shows into build/live/code.txt. Later runs reuse the pairing.
 *
 * For the emulator, forward the ports with `adb emu redir add tcp:16466:6466` (and 16467:6467), then use
 * -Plibremote.tv=127.0.0.1:16466:16467. adb forward does not work: the TV ignores connections from itself.
 */
class LiveTvTest {
    private val target = System.getProperty("libremote.tv")
    private val work = File(System.getProperty("libremote.work") ?: "build/live")

    @Test
    fun pairsAndControlsTheTv() {
        assumeTrue("Set -Plibremote.tv to run against a TV", !target.isNullOrBlank())
        val parts = target!!.split(':')
        val host = parts[0]
        val remotePort = parts.getOrNull(1)?.toInt() ?: REMOTE_PORT
        val pairingPort = parts.getOrNull(2)?.toInt() ?: PAIRING_PORT
        work.mkdirs()

        val keyFile = File(work, "identity.key")
        val certFile = File(work, "identity.crt")
        val identity = if (keyFile.exists()) {
            ClientIdentity.decode(keyFile.readBytes(), certFile.readBytes())
        } else {
            ClientIdentity.generate("libremote/live-test-${System.nanoTime()}").also {
                keyFile.writeBytes(it.privateKey.encoded)
                certFile.writeBytes(it.certificate.encoded)
            }
        }

        val pinFile = File(work, "tv.pin")
        if (!pinFile.exists()) {
            val codeFile = File(work, "code.txt").apply { delete() }
            PairingSession.start(host, identity, "Libremote test", port = pairingPort).use { session ->
                println("LIVE: TV '${session.tvName}' shows a code; write it to ${codeFile.absolutePath}")
                val deadline = System.currentTimeMillis() + 180_000
                while (!codeFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(250)
                val code = PairingCode.normalize(codeFile.readText())
                check(session.submit(code)) { "Code $code failed its checksum" }
                pinFile.writeText(session.tvPin)
                println("LIVE: paired, TV pin ${session.tvPin}")
            }
        }

        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val listener = object : RemoteConnection.Listener {
            override fun onReady(tv: TvInfo, features: Int) {
                println("LIVE: ready: $tv, features in use $features")
                ready.countDown()
            }
            override fun onPower(on: Boolean) = println("LIVE: power on=$on")
            override fun onVolume(volume: VolumeState) = println("LIVE: volume $volume")
            override fun onTextField(field: TvTextField, newField: Boolean) = println("LIVE: field (new=$newField) $field")
            override fun onKeyboardRequest(show: Boolean) = println("LIVE: keyboard show=$show")
            override fun onClosed(error: Throwable?) {
                println("LIVE: closed: $error")
                closed.countDown()
            }
        }
        val info = ClientInfo("JVM", "Libremote", System.getProperty("java.version"), "app.libremotetvservice.test", "0")
        val connection = RemoteConnection(host, pinFile.readText(), identity, info, listener, port = remotePort)
        Thread(connection::run).start()
        assertTrue("TV not ready", ready.await(15, TimeUnit.SECONDS))

        // Home, then down and right along the launcher, then back home; ping replies keep the session alive.
        val steps = (System.getProperty("libremote.keys") ?: "3,20,22,22,3").split(',').map { it.trim().toInt() }
        for (key in steps) {
            connection.press(key)
            Thread.sleep(700)
        }
        // -Plibremote.text=…: type into the focused field letter by letter, as the keyboard would, then submit.
        System.getProperty("libremote.text")?.let { text ->
            for (end in 1..text.length) {
                connection.setText(text.substring(0, end))
                Thread.sleep(150)
            }
            Thread.sleep(1500)
            println("LIVE: typed '$text'")
            if (System.getProperty("libremote.submit") == "true") connection.submitText()
        }
        Thread.sleep((System.getProperty("libremote.stay") ?: "8").toLong() * 1000)
        connection.close()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }
}
