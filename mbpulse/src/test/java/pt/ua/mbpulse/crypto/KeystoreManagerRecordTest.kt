package pt.ua.mbpulse.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Tests for the AES-256-GCM record envelope. [KeystoreManager.encryptRecord] and
 * [KeystoreManager.decryptRecord] take the raw DEK directly and never touch the
 * Android Keystore, so they are exercisable on the plain JVM. (The RSA wrap /
 * unwrap and key generation paths require the hardware Keystore and are covered
 * by instrumented tests instead.)
 */
class KeystoreManagerRecordTest {

    private val manager = KeystoreManager()

    private fun aesKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `record encrypts and decrypts back to plaintext`() {
        val dek = aesKey()
        val plaintext = """{"heart_rate":62.3}""".toByteArray()

        val ciphertext = manager.encryptRecord(plaintext, dek)
        val roundTripped = manager.decryptRecord(ciphertext, dek)

        assertArrayEquals(plaintext, roundTripped)
    }

    @Test
    fun `each encryption uses a fresh IV`() {
        val dek = aesKey()
        val pt = "identical-plaintext".toByteArray()

        val a = manager.encryptRecord(pt, dek)
        val b = manager.encryptRecord(pt, dek)

        // Layout is IV(12) || ciphertext+tag; the IV prefix must differ.
        assertFalse(
            "IV must be random per record",
            a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12))
        )
    }

    @Test
    fun `decrypting with the wrong key fails authentication`() {
        val ciphertext = manager.encryptRecord("secret".toByteArray(), aesKey())
        assertThrows(AEADBadTagException::class.java) {
            manager.decryptRecord(ciphertext, aesKey())
        }
    }

    @Test
    fun `ciphertext shorter than the IV is rejected`() {
        val dek = aesKey()
        val ciphertext = manager.encryptRecord("data".toByteArray(), dek)
        assertThrows(IllegalArgumentException::class.java) {
            manager.decryptRecord(ciphertext.copyOfRange(0, 8), dek)
        }
    }
}
