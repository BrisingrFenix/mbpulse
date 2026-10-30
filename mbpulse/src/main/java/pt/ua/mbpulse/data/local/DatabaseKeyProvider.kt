package pt.ua.mbpulse.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher passphrase for the local Room database.
 *
 * The plaintext display/history summaries (mean HR, sleep stage, EDA, …) are
 * cached on disk for the UI. To keep them unreadable if the database file is
 * extracted from a lost or seized device, the whole database is encrypted with
 * SQLCipher.
 *
 * Key handling:
 *   - A random 32-byte passphrase is generated once with [SecureRandom].
 *   - It is sealed with a hardware-backed AES-256-GCM key in the Android
 *     Keystore (the key never leaves secure hardware) and the resulting
 *     `IV || ciphertext` is written to app-private storage.
 *   - The plaintext passphrase exists only transiently in memory while Room
 *     opens the database.
 *
 * This intentionally avoids the deprecated `androidx.security:security-crypto`
 * library and reuses the same Keystore approach as [pt.ua.mbpulse.crypto.KeystoreManager].
 */
internal class DatabaseKeyProvider(private val context: Context) {

    /** Returns the database passphrase, creating and sealing one on first use. */
    fun getOrCreatePassphrase(): ByteArray {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        return if (keyFile.exists()) {
            decryptPassphrase(keyFile.readBytes())
        } else {
            val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            keyFile.writeBytes(sealPassphrase(passphrase))
            passphrase
        }
    }

    private fun sealPassphrase(passphrase: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher.iv + cipher.doFinal(passphrase)   // IV (12) || ciphertext+tag
    }

    private fun decryptPassphrase(blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "Database key file is corrupt." }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mbpulse_db_master_key"
        private const val KEY_FILE_NAME = "mbpulse_db.key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PASSPHRASE_BYTES = 32
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
