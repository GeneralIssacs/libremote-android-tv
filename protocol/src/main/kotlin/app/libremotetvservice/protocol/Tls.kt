package app.libremotetvservice.protocol

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The TV's certificate is not the one this TV had when it was paired. */
class TvIdentityChangedException : CertificateException("The TV's certificate changed since pairing")

/**
 * TV certificates are self-signed, so TVs are recognised by key instead: a pin is the SHA-256 of the
 * certificate's SubjectPublicKeyInfo, in lowercase hex. Pairing proves the key belongs to the TV the user
 * is looking at; afterwards only that key is accepted.
 */
fun publicKeyPin(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }

internal object Tls {
    private const val ALIAS = "libremote"

    fun connect(host: String, port: Int, identity: ClientIdentity, trust: X509TrustManager, timeoutMs: Int): SSLSocket =
        secure(open(host, port, timeoutMs), host, port, identity, trust)

    /** The TCP connection alone: failing here means the TV is off, asleep or elsewhere. */
    fun open(host: String, port: Int, timeoutMs: Int): Socket {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            return raw
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    /** TLS over [raw]: failing here means the TV did not accept the phone (or [trust] refused the TV). */
    fun secure(raw: Socket, host: String, port: Int, identity: ClientIdentity, trust: X509TrustManager): SSLSocket {
        try {
            val context = SSLContext.getInstance("TLS").apply {
                init(arrayOf(KeyManager(identity)), arrayOf(trust), SecureRandom())
            }
            val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.useClientMode = true
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    /**
     * Offers [identity]'s certificate whatever the other end asks for: the TV (or Libremote Receiver) checks it
     * against its paired list. Libremote Receiver also serves its own identity with it.
     */
    class KeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    }
}

/**
 * Used only while pairing: accepts the TV's self-signed certificate and keeps it. Accepting it is safe because the
 * pairing secret hashes both public keys, and the code on the TV screen authenticates that secret.
 */
internal class PairingTrustManager : X509TrustManager {
    @Volatile var serverCertificate: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("The TV sent no certificate")
        if (certificate.publicKey !is RSAPublicKey) throw CertificateException("The TV's key is not RSA")
        serverCertificate = certificate
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Accepts only the TV whose key matches [pin]. */
internal class PinnedTrustManager(private val pin: String) : X509TrustManager {
    @Volatile var mismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("The TV sent no certificate")
        if (publicKeyPin(certificate) != pin) {
            mismatch = true
            throw TvIdentityChangedException()
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
