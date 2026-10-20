package app.libremotetvservice.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * This phone's TLS identity: an RSA key and a self-signed certificate. Pairing teaches a TV to trust the
 * certificate, so the same identity must be used for every later connection. The TV hashes RSA key material
 * into the pairing secret, so the key has to be RSA.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    companion object {
        /**
         * Makes a new identity. [commonName] must be unique to this install: when a TV pairs a certificate, it
         * deletes every other certificate it trusts with the same subject, so two phones sharing a name would
         * keep unpairing each other.
         */
        fun generate(commonName: String, random: SecureRandom = SecureRandom()): ClientIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            return ClientIdentity(keys.private, selfSignedCertificate(keys, commonName, random))
        }

        /** Restores an identity saved from [privateKey]`.encoded` (PKCS#8) and [certificate]`.encoded`. */
        fun decode(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): ClientIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateDer.inputStream()) as X509Certificate
            return ClientIdentity(key, certificate)
        }
    }
}

private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
private const val COMMON_NAME = "2.5.4.3"
private const val DAY_MS = 24L * 60 * 60 * 1000

/** The last instant UTCTime can express (2049-12-31T23:59:59Z), which keeps the encoder to one time format. */
private const val UTC_TIME_END_MS = 2_524_607_999_000L

internal fun selfSignedCertificate(
    keys: KeyPair,
    commonName: String,
    random: SecureRandom,
    now: Long = System.currentTimeMillis(),
): X509Certificate {
    val algorithm = Der.sequence(Der.oid(SHA256_WITH_RSA), Der.NULL)
    val name = Der.sequence(Der.set(Der.sequence(Der.oid(COMMON_NAME), Der.utf8(commonName))))
    val tbs = Der.sequence(
        Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // X.509 v3
        Der.integer(BigInteger(63, random).add(BigInteger.ONE)),
        algorithm,
        name,
        Der.sequence(Der.utcTime(now - DAY_MS), Der.utcTime(minOf(now + 25 * 365 * DAY_MS, UTC_TIME_END_MS))),
        name,
        keys.public.encoded, // SubjectPublicKeyInfo, already DER
    )
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(keys.private)
        update(tbs)
        sign()
    }
    val der = Der.sequence(tbs, algorithm, Der.bitString(signature))
    return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
}

/** Just enough DER to build a certificate. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))

    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))

    fun explicit(tag: Int, content: ByteArray) = tlv(0xa0 or tag, content)

    fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

    fun utf8(value: String) = tlv(0x0c, value.encodeToByteArray())

    fun bitString(value: ByteArray) = tlv(0x03, byteArrayOf(0) + value)

    fun utcTime(epochMs: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(Date(epochMs)).encodeToByteArray())
    }

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        fun base128(value: Long) {
            var shift = 63 - 63 % 7
            while (shift > 0 && value ushr shift == 0L) shift -= 7
            while (shift > 0) {
                out.write((((value ushr shift) and 0x7f) or 0x80).toInt())
                shift -= 7
            }
            out.write((value and 0x7f).toInt())
        }
        base128(arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach(::base128)
        return tlv(0x06, out.toByteArray())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::write)
        return out.toByteArray()
    }
}
