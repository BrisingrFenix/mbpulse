package pt.ua.mbpulse.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Creates and parses the passphrase-encrypted DEK backup blob that is stored
 * on the Supabase backend.
 *
 * This solves the device-loss problem: the backend stores the blob; on a new
 * device the participant downloads it, enters their passphrase, and recovers
 * the raw DEK — no server-side key material ever needed.
 *
 * The blob format is:
 *
 *   Base64( salt [32 bytes] || iv [12 bytes] || encrypted_dek )
 *
 * Key derivation: PBKDF2WithHmacSHA256, 600 000 iterations (OWASP),
 * 32-byte salt, 256-bit output key.
 * Encryption: AES-256-GCM with the derived key and a fresh 12-byte IV.
 *
 * Security properties:
 *   - The passphrase never leaves the device in any form.
 *   - The backend holds only the encrypted blob; it cannot derive the key.
 *   - GCM authentication tag detects any tampering with the blob.
 *   - Iterative KDF makes brute-force attacks on the passphrase expensive.
 */
internal class KeyRecoveryManager {

    companion object {
        private const val SALT_BYTES = 32
        private const val IV_BYTES = 12
        private const val PBKDF2_ITERATIONS = 600_000
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val CIPHER = "AES/GCM/NoPadding"
    }

    // -------------------------------------------------------------------------
    // Blob creation (called during onboarding, after the DEK is generated)
    // -------------------------------------------------------------------------

    /**
     * Encrypts [dekBytes] with a key derived from [passphrase] and returns
     * a Base64-encoded blob suitable for upload to Supabase.
     *
     * @param dekBytes   The raw DEK from [KeystoreManager.WrappedDek.rawDekBytes].
     * @param passphrase The participant's recovery passphrase as a CharArray.
     *                   The caller should zero-fill this array after the call.
     */
    fun createRecoveryBlob(dekBytes: ByteArray, passphrase: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derivedKey = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey)

        val iv = cipher.iv                          // 12 bytes, GCM standard
        val encryptedDek = cipher.doFinal(dekBytes)

        // Package: salt || iv || ciphertext+tag
        val blob = salt + iv + encryptedDek
        return Base64.getEncoder().encodeToString(blob)
    }

    // -------------------------------------------------------------------------
    // DEK recovery (called on new-device login, before data access)
    // -------------------------------------------------------------------------

    /**
     * Decrypts a recovery blob previously created by [createRecoveryBlob].
     *
     * @param recoveryBlob  The Base64 blob downloaded from Supabase.
     * @param passphrase    The participant's recovery passphrase.
     *                      Zero-fill after the call.
     * @return              The raw DEK bytes, ready to pass to
     *                      [KeystoreManager.encryptRecord] /
     *                      [KeystoreManager.decryptRecord].
     * @throws javax.crypto.AEADBadTagException if the passphrase is wrong or
     *         the blob has been tampered with. Catch this to show an error.
     */
    fun recoverDek(recoveryBlob: String, passphrase: CharArray): ByteArray {
        val blob = Base64.getDecoder().decode(recoveryBlob)

        val minSize = SALT_BYTES + IV_BYTES + (GCM_TAG_BITS / 8)
        require(blob.size >= minSize) { "Recovery blob is malformed." }

        val salt = blob.copyOfRange(0, SALT_BYTES)
        val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val encryptedDek = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)

        val derivedKey = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(GCM_TAG_BITS, iv))

        // AEADBadTagException thrown here if passphrase is wrong.
        return cipher.doFinal(encryptedDek)
    }

    // -------------------------------------------------------------------------
    // Passphrase strength helper
    // -------------------------------------------------------------------------

    /**
     * Returns a rough passphrase strength level.
     * Enforce [Strength.WEAK] rejection in the UI before calling
     * [createRecoveryBlob].
     */
    fun evaluatePassphraseStrength(passphrase: CharArray): Strength {
        val len = passphrase.size
        val hasUpper = passphrase.any { it.isUpperCase() }
        val hasLower = passphrase.any { it.isLowerCase() }
        val hasDigit = passphrase.any { it.isDigit() }
        val hasSymbol = passphrase.any { !it.isLetterOrDigit() }
        val varietyScore = listOf(hasUpper, hasLower, hasDigit, hasSymbol).count { it }

        return when {
            len < 8 -> Strength.WEAK
            len < 12 && varietyScore < 3 -> Strength.FAIR
            len >= 12 && varietyScore >= 3 -> Strength.STRONG
            else -> Strength.FAIR
        }
    }

    enum class Strength { WEAK, FAIR, STRONG }

    // -------------------------------------------------------------------------
    // Key derivation
    // -------------------------------------------------------------------------

    /**
     * Derives a 256-bit AES key from [passphrase] and [salt] using PBKDF2.
     * The [PBEKeySpec] is explicitly cleared after use to remove the passphrase
     * from heap memory as early as possible.
     */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()    // zero-fills the password array in the spec
        }
    }
}