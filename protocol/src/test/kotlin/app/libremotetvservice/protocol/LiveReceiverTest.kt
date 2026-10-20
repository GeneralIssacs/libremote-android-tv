package app.libremotetvservice.protocol

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Pairs with Libremote Receiver on a TV and sends it two small files, when run with -Plibremote.receiver=host[:port].
 * The test writes the link that opens and pairs the receiver to build/live/receiver-link.txt and waits a minute for
 * it to be opened, as the phone app would do over the remote connection. For the Android TV emulator:
 * `adb emu redir add tcp:6469:6469`, then `adb shell am start -a android.intent.action.VIEW -d "$(cat …)"`.
 */
class LiveReceiverTest {
    private val target = System.getProperty("libremote.receiver")
    private val work = File(System.getProperty("libremote.work") ?: "build/live")

    @Test
    fun pairsAndSendsFilesToTheReceiver() {
        assumeTrue("Set -Plibremote.receiver to run against a receiver", !target.isNullOrBlank())
        val parts = target!!.split(':')
        val port = parts.getOrNull(1)?.toInt() ?: RECEIVER_PORT
        work.mkdirs()
        val identity = ClientIdentity.generate("libremote/live-receiver-test")
        val invite = ReceiverInvite.create(publicKeyPin(identity.certificate))
        File(work, "receiver-link.txt").writeText(invite.link())
        println("LIVE: open ${invite.link()}")
        val deadline = System.currentTimeMillis() + 60_000
        var client: ReceiverClient? = null
        while (client == null) {
            try {
                client = ReceiverClient.connect(parts[0], identity, null, invite.secret, "Libremote test", port)
            } catch (e: ProtocolException) {
                if (e !is ReceiverUnavailableException && e !is ReceiverRefusedPhoneException || System.currentTimeMillis() > deadline) throw e
                Thread.sleep(1_000)
            }
        }
        client.use {
            println("LIVE: verified the receiver ${it.receiverKey} over ${it.tls}")
            val stamp = System.currentTimeMillis()
            val text = "Sent by Libremote's LiveReceiverTest at $stamp\n".encodeToByteArray()
            val files = listOf(
                OutgoingFile("libremote-live-$stamp.txt", text.size.toLong(), stamp) { ByteArrayInputStream(text) },
                OutgoingFile("libremote-live/empty-$stamp.txt", 0) { ByteArrayInputStream(ByteArray(0)) },
            )
            it.send(files) { file, sent, total -> println("LIVE: file $file, $sent of $total bytes") }
            println("LIVE: the receiver confirmed ${files.map { file -> file.name }}")
            // -Plibremote.send=path,path…: real files as well, such as an APK or a large file.
            val extra = System.getProperty("libremote.send")?.split(',')?.filter { path -> path.isNotBlank() }.orEmpty().map(::File)
            if (extra.isNotEmpty()) {
                val started = System.nanoTime()
                it.send(extra.map { file -> OutgoingFile(file.name, file.length(), file.lastModified()) { file.inputStream() } })
                println("LIVE: sent ${extra.map { file -> file.name }} in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            val apps = it.apps()
            println("LIVE: ${apps.size} apps: " + apps.joinToString { app -> "${app.label} (${app.packageName}, ${app.icon?.size ?: 0} B icon)" })
        }
        // The receiver is still open: a phone it never got a link from is turned away during the handshake...
        try {
            ReceiverClient.connect(parts[0], ClientIdentity.generate("libremote/live-unpaired"), null, null, "Unpaired", port).close()
            fail("The receiver accepted a phone it never paired")
        } catch (e: ReceiverRefusedPhoneException) {
            println("LIVE: an unpaired phone was refused: ${e.cause}")
        }
        // ...and the paired one can't take the receiver for verified with a secret it never sent.
        try {
            ReceiverClient.connect(parts[0], identity, null, ReceiverInvite.create(invite.phoneKey).secret, "Libremote test", port).close()
            fail("A wrong secret verified the receiver")
        } catch (e: ReceiverUnverifiedException) {
            println("LIVE: a wrong secret didn't verify the receiver")
        }
    }
}
