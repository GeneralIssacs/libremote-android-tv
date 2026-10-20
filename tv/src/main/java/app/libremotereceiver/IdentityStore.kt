package app.libremotereceiver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.libremotetvservice.protocol.ClientIdentity
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The receiver's TLS identity, made on first use: an RSA key and a self-signed certificate, like the phone's. Phones
 * pin its key, so it is kept for the life of the install. Stored as the phone app stores its own: encrypted with a
 * key that never leaves Android's keystore, in no-backup storage.
 */
class IdentityStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "identity.bin")
    @Volatile private var cached: ClientIdentity? = null

    /** The identity, made if needed. Making an RSA key takes a moment: call off the main thread. */
    @Synchronized
    fun get(): ClientIdentity {
        cached?.let { return it }
        val identity = load() ?: create()
        cached = identity
        return identity
    }

    private fun load(): ClientIdentity? = try {
        if (!file.exists()) null else {
            val data = DataInputStream(file.inputStream()).use { input ->
                val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
                val sealed = input.readBytes()
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
                    doFinal(sealed)
                }
            }
            DataInputStream(data.inputStream()).use { input ->
                val key = ByteArray(input.readInt()).also(input::readFully)
                ClientIdentity.decode(key, input.readBytes())
            }
        }
    } catch (e: Exception) {
        // Unreadable (keystore reset, corrupt file): phones verify a new key with the secret of their next link.
        null
    }

    private fun create(): ClientIdentity {
        val random = SecureRandom()
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val identity = ClientIdentity.generate("libremote-receiver/$id", random)
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                val key = identity.privateKey.encoded
                it.writeInt(key.size)
                it.write(key)
                it.write(identity.certificate.encoded)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.doFinal(plain)
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(temp.outputStream()).use {
            it.writeByte(cipher.iv.size)
            it.write(cipher.iv)
            it.write(sealed)
        }
        temp.renameTo(file)
        return identity
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "identity-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
