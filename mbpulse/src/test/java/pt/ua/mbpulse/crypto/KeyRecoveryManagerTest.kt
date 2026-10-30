package pt.ua.mbpulse.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Pure-JVM tests for the passphrase-based recovery blob. No Android Keystore is
 * involved here — only PBKDF2 + AES-GCM + Base64 — so these run as fast unit
 * tests. The first test is the regression that the `recoverDek` size-check bug
 * would have failed.
 */
class KeyRecoveryManagerTest {

    private val manager = KeyRecoveryManager()

    private fun randomDek(): ByteArray =
        ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `blob round-trips back to the original DEK`() {
        val dek = randomDek()
        val passphrase = "correct horse battery staple"

        val blob = manager.createRecoveryBlob(dek, passphrase.toCharArray())
        val recovered = manager.recoverDek(blob, passphrase.toCharArray())

        assertArrayEquals(dek, recovered)
    }

    @Test
    fun `wrong passphrase fails authentication`() {
        val dek = randomDek()
        val blob = manager.createRecoveryBlob(dek, "right-passphrase-123".toCharArray())

        assertThrows(AEADBadTagException::class.java) {
            manager.recoverDek(blob, "wrong-passphrase-123".toCharArray())
        }
    }

    @Test
    fun `tampered blob fails authentication`() {
        val dek = randomDek()
        val pass = "passphrase-to-tamper"
        val blob = manager.createRecoveryBlob(dek, pass.toCharArray())

        // Flip a bit in the trailing GCM tag and re-encode.
        val raw = Base64.getDecoder().decode(blob)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(raw)

        assertThrows(AEADBadTagException::class.java) {
            manager.recoverDek(tampered, pass.toCharArray())
        }
    }

    /**
     * Regression test: a blob containing only salt + iv (no ciphertext/tag) must
     * be rejected as malformed. The previous `blob.size == SALT + IV` check
     * inverted this — it rejected every *real* blob and accepted this empty one.
     */
    @Test
    fun `too-short blob is rejected as malformed`() {
        val saltPlusIvOnly = Base64.getEncoder().encodeToString(ByteArray(32 + 12))
        assertThrows(IllegalArgumentException::class.java) {
            manager.recoverDek(saltPlusIvOnly, "whatever".toCharArray())
        }
    }

    @Test
    fun `each blob uses a fresh salt and iv`() {
        val dek = randomDek()
        val pass = "stable-passphrase-xyz"
        val a = manager.createRecoveryBlob(dek, pass.toCharArray())
        val b = manager.createRecoveryBlob(dek, pass.toCharArray())
        assertNotEquals("Blobs must differ due to random salt/IV", a, b)
    }

    @Test
    fun `passphrase strength is graded`() {
        assertEquals(
            KeyRecoveryManager.Strength.WEAK,
            manager.evaluatePassphraseStrength("short".toCharArray())
        )
        assertEquals(
            KeyRecoveryManager.Strength.STRONG,
            manager.evaluatePassphraseStrength("LongPass1!word".toCharArray())
        )
    }
}
