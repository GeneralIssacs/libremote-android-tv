package app.libremotetvservice.protocol

import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Libremote Receiver's channel: TLS with both sides authenticated, on [RECEIVER_PORT], between a phone and the
 * receiver app on its TV. SENDFILES.md describes the construction and the threat model; in short:
 *
 * - The phone opens the receiver with a link sent over its paired connection to the TV's remote service, which
 *   only phones paired with the TV can do. The link ([ReceiverInvite]) carries the phone's key hash, which the
 *   receiver then accepts as a TLS client, and a fresh one-time secret.
 * - The receiver accepts only client certificates whose key it has been given that way.
 * - The receiver proves to the phone that it got the secret, bound to both keys of the TLS session
 *   ([ReceiverProof]); the phone then pins the receiver's key and needs no secret until that key changes.
 *
 * Inside TLS, packets use the framing in FileTransfer.kt.
 */
const val RECEIVER_PORT = 6469

/**
 * What a phone puts in the link that opens Libremote Receiver: [phoneKey], the SHA-256 of its certificate's
 * SubjectPublicKeyInfo in lowercase hex (see publicKeyPin), a fresh random [secret], and from version 2 the
 * phone's [name], for the TV to show when it asks whether to allow a new phone.
 *
 * [version] 2 also means the phone can wait while the TV asks (see ReceiverServer); a version 1 link (Libremote
 * before 1.0.1) from a new phone is turned away until the user allows it, and the phone tries again.
 */
class ReceiverInvite(val phoneKey: String, val secret: ByteArray, val name: String? = null, val version: Int = VERSION) {

    /** `libremote://receive?…`, which opens the receiver's screen; `libremote://sync?…` opens it only briefly. */
    fun link(syncOnly: Boolean = false): String {
        val base = "libremote://${if (syncOnly) SYNC_HOST else RECEIVE_HOST}?$VERSION_PARAM=$version&$KEY_PARAM=$phoneKey&$SECRET_PARAM=${secret.toHex()}"
        // %20 rather than URLEncoder's +, which not every URI parser reads as a space.
        return if (name.isNullOrEmpty()) base else "$base&$NAME_PARAM=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    /** The code both ends show while the TV asks whether to allow this phone (see [ConfirmCode]). */
    val code: String get() = ConfirmCode.compute(secret, phoneKey)

    /** The TV can make the phone wait while it asks, rather than turn it away. */
    val canWait: Boolean get() = version >= 2

    companion object {
        const val RECEIVE_HOST = "receive"
        const val SYNC_HOST = "sync"
        const val VERSION_PARAM = "v"
        const val KEY_PARAM = "k"
        const val SECRET_PARAM = "s"
        const val NAME_PARAM = "n"
        const val VERSION = 2
        private const val SECRET_BYTES = 32

        /** Names are shown on the TV; a long one would only overflow the dialog. */
        const val MAX_NAME = 60

        fun create(phoneKey: String, name: String? = null, random: SecureRandom = SecureRandom()) =
            ReceiverInvite(phoneKey, ByteArray(SECRET_BYTES).also(random::nextBytes), name?.trim()?.take(MAX_NAME))

        /**
         * The invite in a link's [key], [secret], [name] (already decoded) and [version] parameters, or null when
         * they don't make a valid one. A link without a version is version 1.
         */
        fun parse(key: String?, secret: String?, name: String? = null, version: String? = null): ReceiverInvite? {
            if (key == null || secret == null || !isKeyHash(key) || secret.length != SECRET_BYTES * 2) return null
            val cleanName = name?.filter { !it.isISOControl() }?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
            return ReceiverInvite(key, secret.hexToBytes() ?: return null, cleanName, version?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
        }

        /** A SHA-256 in lowercase hex, as publicKeyPin makes them. */
        fun isKeyHash(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/**
 * The receiver's proof that it is the app the TV opened with the phone's link: HMAC-SHA256 keyed by the link's
 * secret, over a label and the key hashes of both ends of this TLS session (phone first). TLS has already proved
 * that each end holds its private key, so a proof made for one pair of keys is useless with any other.
 */
internal object ReceiverProof {
    private val LABEL = "libremote receiver proof v1".encodeToByteArray()

    fun compute(secret: ByteArray, phoneKey: String, receiverKey: String): ByteArray =
        linkMac(secret, LABEL, phoneKey, receiverKey)

    fun verify(proof: ByteArray, secret: ByteArray, phoneKey: String, receiverKey: String) =
        MessageDigest.isEqual(proof, compute(secret, phoneKey, receiverKey))
}

/**
 * The six-digit code ("123 456") the TV and the phone both show while the TV asks whether to allow a new phone:
 * HMAC-SHA256 keyed by the link's secret over a label and the phone's key hash. Only the phone that made the link
 * knows its secret, so a link that another app on the TV forged shows a different code.
 */
object ConfirmCode {
    private val LABEL = "libremote confirm code v1".encodeToByteArray()

    fun compute(secret: ByteArray, phoneKey: String): String {
        val mac = linkMac(secret, LABEL, phoneKey)
        // The first four bytes as an unsigned number.
        val number = (ByteBuffer.wrap(mac).int.toLong() and 0xffffffffL) % 1_000_000
        // ASCII digits whatever the locale, so both ends show the same; a no-break space, so it never wraps.
        return String.format(Locale.ROOT, "%06d", number).let { "${it.take(3)}\u00A0${it.drop(3)}" }
    }
}

/** HMAC-SHA256 keyed by a link's [secret], over [label], a 0x00, then each key hash (lowercase hex) as bytes. */
private fun linkMac(secret: ByteArray, label: ByteArray, vararg keyHashes: String): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        update(label)
        update(0)
        keyHashes.forEach { update(it.hexToBytes() ?: throw IllegalArgumentException("Bad key hash")) }
        doFinal()
    }

/**
 * TLS settings for both ends. TLS 1.3 where both have it (Android 10 and later, Java 11 and later); Android 8 and 9
 * have only TLS 1.2, so it is allowed too, with forward-secret AEAD suites only (ECDHE with AES-GCM or
 * ChaCha20-Poly1305). Two TLS 1.3 ends can't be pushed down to 1.2: TLS 1.3's downgrade protection catches it.
 */
internal object ReceiverTls {
    private val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
    private val SUITES = listOf(
        // TLS 1.3. Android enables all of these whatever the list says.
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        // TLS 1.2.
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    fun context(identity: ClientIdentity, trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(Tls.KeyManager(identity)), arrayOf(trust), SecureRandom()) }

    /** TLS over [raw]; the handshake runs on the first read or write, or [SSLSocket.startHandshake]. */
    fun wrap(context: SSLContext, raw: Socket, client: Boolean, protocols: List<String> = PROTOCOLS): SSLSocket {
        val socket = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        socket.useClientMode = client
        if (!client) socket.needClientAuth = true
        socket.enabledProtocols = protocols.filter { it in socket.supportedProtocols }.toTypedArray()
        socket.enabledCipherSuites = SUITES.filter { it in socket.supportedCipherSuites }.toTypedArray()
        return socket
    }

    /** The key hash (publicKeyPin) of the certificate the other end presented in [socket]'s session. */
    fun peerKey(socket: SSLSocket): String =
        (socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::publicKeyPin)
            ?: throw CertificateException("No certificate from the other end")
}

/** Accepts any certificate from the receiver and keeps it: the phone checks it against its pin or the proof. */
internal class ReceiverTrustManager : X509TrustManager {
    @Volatile var receiverCertificate: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        receiverCertificate = chain?.firstOrNull() ?: throw CertificateException("The receiver sent no certificate")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * On the receiver: accepts only phones whose key hash [isAccepted] says is paired, or waiting while the TV asks
 * whether to allow it.
 */
internal class PairedPhonesTrustManager(private val isAccepted: (String) -> Boolean) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("The phone sent no certificate")
        if (!isAccepted(publicKeyPin(certificate))) throw CertificateException("This phone is not paired with the receiver")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Server mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Nothing is receiving at the TV's address: Libremote Receiver isn't open (or isn't installed). */
class ReceiverUnavailableException(cause: Throwable) : ProtocolException("Libremote Receiver isn't open on the TV", cause)

/** The receiver answered but didn't accept this phone: it was never paired there, or was removed. */
class ReceiverRefusedPhoneException(cause: Throwable?) : ProtocolException("Libremote Receiver didn't accept this phone", cause)

/** The TV asked whether to allow this phone, and the user said no (or didn't answer in time). */
class ReceiverDeclinedException(message: String) : ProtocolException(message)

/**
 * The receiver couldn't be verified: its key is not the pinned one and it gave no valid proof of the link's secret.
 * [pinMismatch] is true when a pin existed (the receiver was reinstalled, or something else answers at its address).
 */
class ReceiverUnverifiedException(val pinMismatch: Boolean) :
    ProtocolException(if (pinMismatch) "Libremote Receiver's key changed" else "Libremote Receiver couldn't prove it got the link")

/** An app on the TV, as the receiver lists it: [icon] is a PNG, or null. */
class InstalledApp(val packageName: String, val label: String, val icon: ByteArray?)

internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        val high = Character.digit(this[2 * i], 16)
        val low = Character.digit(this[2 * i + 1], 16)
        if (high < 0 || low < 0) return null
        ((high shl 4) or low).toByte()
    }
}
