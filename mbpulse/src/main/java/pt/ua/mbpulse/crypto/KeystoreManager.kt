package pt.ua.mbpulse.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import pt.ua.mbpulse.logging.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val TAG = "KeystoreManager"

internal class KeystoreManager {

    companion object {
        private const val KEY_ALIAS = "mbpulse_participant_rsa_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // RSA-OAEP cipher string. The explicit OAEPParameterSpec below
        // ensures SHA-256 is used for both the hash and the MGF1 hash,
        // which is required for interoperability with other platforms.
        private const val RSA_CIPHER = "RSA/ECB/OAEPPadding"

        // AES-GCM: 12-byte IV (96-bit, NIST recommended), 128-bit auth tag.
        private const val AES_CIPHER = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        // RSASSA-PKCS1-v1_5 with SHA-256 — used to sign the withdrawal challenge.
        // Verified server-side (Edge Function) with Web Crypto "RSASSA-PKCS1-v1_5".
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }

    // -------------------------------------------------------------------------
    // Key pair lifecycle
    // -------------------------------------------------------------------------

    /**
     * Generates the RSA-2048 key pair inside the hardware Keystore.
     * Safe to call multiple times — if the alias already exists the existing
     * key is returned unchanged.
     */
    fun generateKeyPairIfAbsent() {
        if (keyPairExists()) {
            if (keyPairSupportsSigning()) {
                Log.d(TAG, "Key pair already exists — skipping generation")
                return
            }
            // Upgrade path: a key enrolled before signing was supported is
            // encrypt/decrypt-only and cannot sign the withdrawal challenge.
            // Regenerate it with signing capability. Enrolment uploads the new
            // public key, so the backend stays in sync.
            Log.w(TAG, "Existing key pair cannot sign — regenerating with signing capability")
            deleteKeyPair()
        }
        Log.d(TAG, "Generating RSA-2048 key pair in hardware Keystore…")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT or
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
        Log.d(TAG, "RSA key pair generated successfully")
    }

    /**
     * Returns the DER-encoded public key bytes, suitable for registration
     * with the Supabase backend (store as base64).
     */
    fun getPublicKeyBytes(): ByteArray {
        Log.d(TAG, "getPublicKeyBytes called")
        return getPublicKey().encoded
    }

    /** True if the key pair has already been generated on this device. */
    fun keyPairExists(): Boolean {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val exists = ks.containsAlias(KEY_ALIAS)
        Log.d(TAG, "keyPairExists: $exists (alias: $KEY_ALIAS)")
        return exists
    }

    /**
     * True if the existing key pair was authorised for signing at creation.
     * Keys enrolled before signing support are encrypt/decrypt-only and return
     * false, triggering regeneration in [generateKeyPairIfAbsent].
     */
    private fun keyPairSupportsSigning(): Boolean = try {
        val privateKey = getPrivateKey()
        val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
        val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java) as KeyInfo
        (keyInfo.purposes and KeyProperties.PURPOSE_SIGN) != 0
    } catch (e: Exception) {
        Log.w(TAG, "Could not read key purposes — assuming no signing: ${e.message}")
        false
    }

    /**
     * Deletes the key pair from the Keystore.
     * Call this on participant withdrawal — once the key is deleted, all
     * wrapped DEKs (and therefore all ciphertext) become permanently
     * unreadable on this device.
     */
    fun deleteKeyPair() {
        Log.d(TAG, "deleteKeyPair called")
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
            Log.d(TAG, "Key pair deleted")
        } else {
            Log.d(TAG, "deleteKeyPair: alias not found — nothing to delete")
        }
    }

    /**
     * Signs [message] with the participant's hardware private key using
     * RSASSA-PKCS1-v1_5 over SHA-256. Used to prove key possession when
     * withdrawing — the backend verifies the signature against the stored
     * public key before erasing data.
     *
     * Only works for key pairs generated with the signing purpose (added in this
     * version). A device enrolled before that change has an encrypt/decrypt-only
     * key and will throw here — it must re-enrol to obtain a sign-capable key.
     */
    fun sign(message: ByteArray): ByteArray {
        Log.d(TAG, "sign — message: ${message.size} bytes")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(getPrivateKey())
            update(message)
            sign()
        }.also { Log.d(TAG, "sign — signature: ${it.size} bytes") }
    }

        // -------------------------------------------------------------------------
    // DEK management
    // -------------------------------------------------------------------------

    /**
     * Generates a fresh 256-bit AES DEK and immediately wraps (encrypts) it
     * with the RSA public key.
     *
     * @return [WrappedDek] containing:
     *   - [WrappedDek.rawDekBytes]     — the plaintext DEK (keep only in memory,
     *                                    pass to [encryptRecord] then discard)
     *   - [WrappedDek.encryptedDekBytes] — RSA-encrypted DEK for storage/upload
     */
    fun generateAndWrapDek(): WrappedDek {
        Log.d(TAG, "generateAndWrapDek — generating 256-bit AES DEK…")
        val rawDek = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()
        Log.d(TAG, "AES DEK generated (${rawDek.encoded.size} bytes) — wrapping with RSA public key…")
        val encryptedDek = rsaEncrypt(rawDek.encoded)
        Log.d(TAG, "DEK wrapped — encrypted size: ${encryptedDek.size} bytes")
        return WrappedDek(rawDekBytes = rawDek.encoded, encryptedDekBytes = encryptedDek)
    }

    /**
     * Wraps [rawDekBytes] under the hardware-backed RSA public key.
     * Used during recovery to re-wrap a recovered DEK under a new device's key.
     */
    fun wrapDekWithPublicKey(rawDekBytes: ByteArray): ByteArray {
        Log.d(TAG, "wrapDekWithPublicKey — ${rawDekBytes.size} bytes")
        return rsaEncrypt(rawDekBytes).also {
            Log.d(TAG, "wrapDekWithPublicKey — wrapped size: ${it.size}")
        }
    }

    /**
     * Unwraps (decrypts) an RSA-encrypted DEK using the Keystore private key.
     * The private key never leaves the hardware; only the plaintext DEK bytes
     * are returned into the JVM.
     */
    fun unwrapDek(encryptedDekBytes: ByteArray): ByteArray {
        Log.d(TAG, "unwrapDek — input size: ${encryptedDekBytes.size} bytes")
        if (encryptedDekBytes.isEmpty()) {
            Log.e(TAG, "unwrapDek called with empty byte array — wrapped DEK was never stored")
            throw IllegalArgumentException("Wrapped DEK is empty. Onboarding may not have completed.")
        }
        val result = rsaDecrypt(encryptedDekBytes)
        Log.d(TAG, "unwrapDek — decrypted DEK size: ${result.size} bytes")
        return result
    }

    // -------------------------------------------------------------------------
    // Record encryption / decryption
    // -------------------------------------------------------------------------

    /**
     * Encrypts a health record with AES-256-GCM.
     *
     * Output layout:  IV (12 bytes) || ciphertext+tag
     *
     * @param plaintext  The record bytes (e.g. JSON-serialised health data).
     * @param dekBytes   The raw DEK returned by [generateAndWrapDek] or
     *                   [unwrapDek]. Zero-fill this array after use.
     */
    fun encryptRecord(plaintext: ByteArray, dekBytes: ByteArray): ByteArray {
        Log.d(TAG, "encryptRecord — plaintext size: ${plaintext.size} bytes")
        val dek = SecretKeySpec(dekBytes, "AES")
        val cipher = Cipher.getInstance(AES_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, dek)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        Log.d(TAG, "encryptRecord — iv: ${iv.size} bytes, ciphertext: ${ciphertext.size} bytes")
        return iv + ciphertext
    }

    /**
     * Decrypts a health record previously encrypted by [encryptRecord].
     *
     * @param encryptedRecord  IV || ciphertext+tag bytes.
     * @param dekBytes         The unwrapped DEK. Zero-fill after use.
     */
    fun decryptRecord(encryptedRecord: ByteArray, dekBytes: ByteArray): ByteArray {
        require(encryptedRecord.size > GCM_IV_LENGTH) { "Ciphertext too short." }
        val iv = encryptedRecord.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedRecord.copyOfRange(GCM_IV_LENGTH, encryptedRecord.size)
        val dek = SecretKeySpec(dekBytes, "AES")
        val cipher = Cipher.getInstance(AES_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun getPublicKey(): PublicKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS)
        Log.d(TAG, "getPublicKey — cert found: ${cert != null}")
        return checkNotNull(cert?.publicKey) {
            "RSA public key not found. Call generateKeyPairIfAbsent() first."
        }
    }

    private fun getPrivateKey(): PrivateKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null)
        Log.d(TAG, "getPrivateKey — key found: ${key != null}, type: ${key?.javaClass?.simpleName}")
        return checkNotNull(key as? PrivateKey) {
            "RSA private key not found. Call generateKeyPairIfAbsent() first."
        }
    }

    /**
     * Encrypts [plaintext] with the RSA public key using OAEP/SHA-256.
     * Suitable only for small payloads (the DEK — 32 bytes).
     */
    private fun rsaEncrypt(plaintext: ByteArray): ByteArray {
        Log.d(TAG, "rsaEncrypt — plaintext size: ${plaintext.size}")
        val cipher = Cipher.getInstance(RSA_CIPHER)
        val oaepParams = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.ENCRYPT_MODE, getPublicKey(), oaepParams)
        return cipher.doFinal(plaintext).also {
            Log.d(TAG, "rsaEncrypt — output size: ${it.size}")
        }
    }

    /**
     * Verifies an RSASSA-PKCS1-v1_5 / SHA-256 [signature] over [message] using an
     * external RSA public key ([publicKeyBytes], DER X.509 SubjectPublicKeyInfo).
     * Used to check the backend's signature on a withdrawal deletion receipt.
     * Returns false on any error rather than throwing.
     */
    fun verifyWithExternalKey(
        message: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean = try {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    } catch (e: Exception) {
        Log.w(TAG, "verifyWithExternalKey failed: ${e.message}")
        false
    }

    /**
     * Wraps [dekBytes] using an external RSA public key (e.g. the PI's key).
     * Uses SHA-256/MGF1-SHA256 — compatible with Web Crypto API RSA-OAEP.
     *
     * @param dekBytes      The raw AES DEK bytes to wrap.
     * @param publicKeyBytes DER-encoded RSA public key (X.509 SubjectPublicKeyInfo).
     */
    fun wrapWithExternalKey(dekBytes: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        Log.d(TAG, "wrapWithExternalKey — dek: ${dekBytes.size} bytes, pubkey: ${publicKeyBytes.size} bytes")
        val keySpec   = X509EncodedKeySpec(publicKeyBytes)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher    = Cipher.getInstance(RSA_CIPHER)
        val oaepParams = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)
        return cipher.doFinal(dekBytes).also {
            Log.d(TAG, "wrapWithExternalKey — output size: ${it.size}")
        }
    }

        /**
     * Decrypts [ciphertext] with the RSA private key.
     * The private key is accessed in the Keystore hardware — it does not
     * enter JVM memory.
     */
    private fun rsaDecrypt(ciphertext: ByteArray): ByteArray {
        Log.d(TAG, "rsaDecrypt — ciphertext size: ${ciphertext.size}")
        val cipher = Cipher.getInstance(RSA_CIPHER)
        val oaepParams = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, getPrivateKey(), oaepParams)
        return cipher.doFinal(ciphertext).also {
            Log.d(TAG, "rsaDecrypt — output size: ${it.size}")
        }
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    data class WrappedDek(
        /** Plaintext DEK — use immediately and zero-fill when done. */
        val rawDekBytes: ByteArray,
        /** RSA-encrypted DEK — safe to store or upload. */
        val encryptedDekBytes: ByteArray
    ) {
        /** Zero-fills the raw key bytes to limit its lifetime in heap memory. */
        fun clearRaw() = rawDekBytes.fill(0)
    }
}