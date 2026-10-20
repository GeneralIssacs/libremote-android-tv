package app.libremotetvservice.protocol

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

const val PAIRING_PORT = 6467

/** The TV ended pairing: it rejected a message ([status] 400–402), or the user cancelled it on the TV. */
class PairingException(message: String, val status: Int = 0) : ProtocolException(message)

/**
 * Field numbers of the pairing protocol ("Polo") on port 6467, as Google's TV Remote Service implements it.
 * Every message is an OuterMessage{1: protocol_version, 2: status, <payload field>: payload}.
 */
internal object Polo {
    const val VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    const val PAIRING_REQUEST = 10
    const val PAIRING_REQUEST_ACK = 11
    const val OPTIONS = 20
    const val CONFIGURATION = 30
    const val CONFIGURATION_ACK = 31
    const val SECRET = 40
    const val SECRET_ACK = 41

    /** The only encoding TVs accept: six hexadecimal symbols, shown by the TV and typed on the phone. */
    const val ENCODING_HEXADECIMAL = 3
    const val SYMBOL_LENGTH = 6

    /** The phone takes the input role; the TV then generates and shows the code. */
    const val ROLE_INPUT = 1

    /** Only logged by the TV. */
    const val SERVICE_NAME = "atvremote"

    fun encoding() = ProtoWriter().int(1, ENCODING_HEXADECIMAL).int(2, SYMBOL_LENGTH)
}

/** Turns the code on the TV screen into the secret the TV expects back. */
object PairingCode {
    const val LENGTH = Polo.SYMBOL_LENGTH

    /** Keeps the hexadecimal characters of [raw], upper-cased, as the TV shows them. */
    fun normalize(raw: String): String =
        raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(LENGTH)

    /**
     * The secret for [code], or null when the code cannot be this TV's. The TV draws two random bytes, hashes
     * them with both public keys, and shows the first hash byte followed by the two random bytes. The phone
     * redoes the hash: a mismatching first byte means a typo (or a different TV), caught before anything is sent.
     */
    fun secret(code: String, client: X509Certificate, server: X509Certificate): ByteArray? {
        require(code.length == LENGTH && code == normalize(code)) { "Not a pairing code: $code" }
        val bytes = ByteArray(LENGTH / 2) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        for (certificate in listOf(client, server)) {
            val key = certificate.publicKey as? RSAPublicKey ?: throw PairingException("Pairing needs RSA keys")
            digest.update(key.modulus.magnitude())
            digest.update(key.publicExponent.magnitude())
        }
        digest.update(bytes, 1, bytes.size - 1)
        val hash = digest.digest()
        return hash.takeIf { it[0] == bytes[0] }
    }

    /** Big-endian bytes without leading zeros, as the TV hashes them. */
    private fun BigInteger.magnitude(): ByteArray {
        val bytes = abs().toByteArray()
        val start = bytes.indexOfFirst { it != 0.toByte() }.let { if (it < 0) bytes.size else it }
        return bytes.copyOfRange(start, bytes.size)
    }
}

/**
 * One pairing attempt over port 6467. When [start] returns, the TV shows a code; pass what the user types to
 * [submit]. A TV pauses pairing after repeated failures (30 s after five, growing to a day), and while it pauses
 * it does not answer at all, so [start] can take until the socket times out.
 */
class PairingSession private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val identity: ClientIdentity,
    private val serverCertificate: X509Certificate,
    /** The TV's name, when it gave one. */
    val tvName: String?,
) : Closeable {
    private val output: OutputStream = socket.outputStream

    /** Identifies this TV from now on; store it with the TV. */
    val tvPin: String = publicKeyPin(serverCertificate)

    /**
     * Sends the secret for [code]. Returns false, with the session still open, when the code fails its
     * checksum, so the user can correct a typo without the TV counting a failed attempt. Returns true once
     * the TV has paired; throws [PairingException] when the TV rejects the code or the user cancelled.
     */
    fun submit(code: String): Boolean {
        val secret = PairingCode.secret(code, identity.certificate, serverCertificate) ?: return false
        send(output, Polo.SECRET, ProtoWriter().bytes(1, secret))
        receive(input, Polo.SECRET_ACK)
        return true
    }

    override fun close() = socket.close()

    companion object {
        /** Connects and asks the TV to show a code; blocks, so call it off the main thread. */
        fun start(
            host: String,
            identity: ClientIdentity,
            clientName: String,
            port: Int = PAIRING_PORT,
            timeoutMs: Int = 60_000,
        ): PairingSession {
            val trust = PairingTrustManager()
            val socket = Tls.connect(host, port, identity, trust, timeoutMs)
            try {
                val serverCertificate = trust.serverCertificate ?: throw PairingException("The TV sent no certificate")
                val input = BufferedInputStream(socket.inputStream)
                val output = socket.outputStream
                send(output, Polo.PAIRING_REQUEST, ProtoWriter().string(1, Polo.SERVICE_NAME).string(2, clientName))
                val tvName = receive(input, Polo.PAIRING_REQUEST_ACK).string(1)?.takeIf { it.isNotBlank() }
                send(output, Polo.OPTIONS, ProtoWriter().message(1, Polo.encoding()).int(3, Polo.ROLE_INPUT))
                receive(input, Polo.OPTIONS)
                send(output, Polo.CONFIGURATION, ProtoWriter().message(1, Polo.encoding()).int(2, Polo.ROLE_INPUT))
                receive(input, Polo.CONFIGURATION_ACK)
                // The TV now shows the code and waits for the secret with no timeout of its own.
                return PairingSession(socket, input, identity, serverCertificate, tvName)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        private fun send(output: OutputStream, type: Int, payload: ProtoWriter) {
            val message = ProtoWriter().int(1, Polo.VERSION).int(2, Polo.STATUS_OK).message(type, payload)
            output.writeDelimited(message.toByteArray())
        }

        private fun receive(input: InputStream, type: Int): ProtoMessage {
            val data = input.readDelimited() ?: throw PairingException("The TV ended pairing")
            val message = ProtoMessage.parse(data)
            val status = message.int(2)
            if (status != Polo.STATUS_OK) {
                val reason = if (status == Polo.STATUS_BAD_SECRET) "The TV rejected the code" else "The TV refused pairing"
                throw PairingException("$reason (status $status)", status)
            }
            return message.message(type) ?: throw PairingException("Unexpected pairing message ${message.fieldNumbers}")
        }
    }
}
