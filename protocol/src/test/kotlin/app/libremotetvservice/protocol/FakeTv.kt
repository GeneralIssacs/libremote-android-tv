package app.libremotetvservice.protocol

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A stand-in TV that follows what Google's TV Remote Service does (see PROTOCOL.md): Polo pairing on one port,
 * the remote protocol on another, client certificates required on both, only paired ones on the remote port.
 */
class FakeTv(private val offeredFeatures: Int = Features.WANTED or Features.VOICE) : Closeable {
    val identity = ClientIdentity.generate("atvremote/fake")
    val pin = publicKeyPin(identity.certificate)

    /** Codes the TV "shows", one per pairing attempt. */
    val shownCodes = LinkedBlockingQueue<String>()

    /** Messages phones sent on the remote port after activation, as RemoteMessage field number to body. */
    val received = LinkedBlockingQueue<Pair<Int, ProtoMessage>>()

    /** What the phone asked for in CONFIGURE and SET_ACTIVE. */
    @Volatile var configuredFeatures = -1
    @Volatile var activeFeatures = -1
    @Volatile var pingAnswered = false

    private val trusted = ConcurrentHashMap.newKeySet<X509Certificate>()
    private val pairing = serverSocket(acceptAll = true)
    private val remote = serverSocket(acceptAll = false)
    val pairingPort get() = pairing.localPort
    val remotePort get() = remote.localPort

    /** Messages the TV sends right after activation. */
    var afterActivation: List<ProtoWriter> = emptyList()

    init {
        thread("fake-tv-pairing") { while (!pairing.isClosed) runCatching { pair(pairing.accept() as SSLSocket) } }
        thread("fake-tv-remote") { while (!remote.isClosed) runCatching { serveRemote(remote.accept() as SSLSocket) } }
    }

    override fun close() {
        pairing.close()
        remote.close()
    }

    private fun pair(socket: SSLSocket) = socket.use {
        val client = socket.session.peerCertificates[0] as X509Certificate
        val input = BufferedInputStream(socket.inputStream)
        val output = socket.outputStream
        fun read(type: Int): ProtoMessage {
            val message = ProtoMessage.parse(input.readDelimited()!!)
            check(message.int(1) in 2..9 && message.int(2) == 200) { "bad header" }
            return message.message(type) ?: error("expected $type, got ${message.fieldNumbers}")
        }
        read(Polo.PAIRING_REQUEST)
        reply(output, Polo.PAIRING_REQUEST_ACK, ProtoWriter().string(1, "Fake TV"))
        read(Polo.OPTIONS)
        reply(output, Polo.OPTIONS, ProtoWriter().message(2, Polo.encoding()).int(3, 2))
        val configuration = read(Polo.CONFIGURATION)
        val encoding = configuration.message(1)!!
        if (encoding.int(1) != 3 || encoding.int(2) != 6 || configuration.int(2) != Polo.ROLE_INPUT) {
            output.writeDelimited(ProtoWriter().int(1, 2).int(2, 401).toByteArray())
            return
        }
        reply(output, Polo.CONFIGURATION_ACK, ProtoWriter())
        // As the TV does: two random bytes, shown after the first byte of their hash with both keys.
        val nonce = ByteArray(2).also(SecureRandom()::nextBytes)
        val hash = hash(client, identity.certificate, nonce)
        shownCodes.add((byteArrayOf(hash[0]) + nonce).joinToString("") { "%02X".format(it) })
        val secret = read(Polo.SECRET).bytes(1)!!
        if (secret.contentEquals(hash)) {
            trusted.add(client)
            reply(output, Polo.SECRET_ACK, ProtoWriter().bytes(1, secret))
        } else {
            output.writeDelimited(ProtoWriter().int(1, 2).int(2, 402).toByteArray())
        }
    }

    private fun serveRemote(socket: SSLSocket) = socket.use {
        socket.startHandshake()
        val input = BufferedInputStream(socket.inputStream)
        val output = socket.outputStream
        fun send(type: Int, body: ProtoWriter) = output.writeDelimited(ProtoWriter().message(type, body).toByteArray())
        fun read(): Pair<Int, ProtoMessage> {
            val message = ProtoMessage.parse(input.readDelimited() ?: error("closed"))
            val type = message.fieldNumbers.single()
            return type to message.message(type)!!
        }
        send(Remote.CONFIGURE, ProtoWriter().int(1, offeredFeatures).message(2, deviceInfo()))
        val (type, configure) = read()
        check(type == Remote.CONFIGURE)
        configuredFeatures = configure.int(1)
        if (configuredFeatures and offeredFeatures.inv() != 0) {
            send(Remote.ERROR, ProtoWriter().int(1, TvError.FEATURE_NOT_AVAILABLE.ordinal))
            return
        }
        send(Remote.SET_ACTIVE, ProtoWriter())
        val (activeType, active) = read()
        check(activeType == Remote.SET_ACTIVE)
        activeFeatures = active.int(1)
        send(Remote.PING_REQUEST, ProtoWriter().int(1, 41).varint(2, 123_456L))
        afterActivation.forEach { output.writeDelimited(it.toByteArray()) }
        // Like the TV, take messages in any order: the phone may send commands before it answers the ping.
        while (true) {
            val (type, body) = read()
            if (type == Remote.PING_RESPONSE) pingAnswered = body.int(1) == 41 else received.add(type to body)
        }
    }

    /** The TV's DeviceInfo in CONFIGURE, filled in as Google's service does: the service itself is the "app". */
    private fun deviceInfo() = ProtoWriter().string(1, "Fake").string(2, "Libremote").int(3, 1).string(4, "14")
        .string(5, "com.google.android.tv.remote.service").string(6, "5.99.551144881")

    /** Waits until [condition] holds, for the TV's view of the connection to catch up with the phone's. */
    fun await(condition: FakeTv.() -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(10)
        }
    }

    private fun reply(output: OutputStream, type: Int, body: ProtoWriter) =
        output.writeDelimited(ProtoWriter().int(1, 2).int(2, 200).message(type, body).toByteArray())

    private fun hash(client: X509Certificate, server: X509Certificate, nonce: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (certificate in listOf(client, server)) {
            val key = certificate.publicKey as RSAPublicKey
            digest.update(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
            digest.update(key.publicExponent.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        }
        digest.update(nonce)
        return digest.digest()
    }

    private fun serverSocket(acceptAll: Boolean): SSLServerSocket {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("tv", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, CharArray(0)) }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (!acceptAll && chain[0] !in trusted) throw CertificateException("Unknown remote")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
        return (context.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    }

    fun nextCode(): String = shownCodes.poll(10, TimeUnit.SECONDS) ?: error("The fake TV showed no code")

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }
}
