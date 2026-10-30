package pt.ua.mbpulse.crypto

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof that a device replacing a participant's key pair (account recovery)
 * really decrypted their recovery blob, i.e. knows their DEK.
 *
 * At enrolment the app derives a **rotation key** from the DEK
 * ([rotationKey], one-way: it reveals nothing about the DEK) and registers it
 * with the backend. To rotate, the backend issues a one-time nonce and the
 * device answers with [proof] — an HMAC under the rotation key over the nonce
 * AND the new key material, so a captured answer cannot be replayed or have
 * its keys swapped. Without the passphrase there is no DEK, no rotation key
 * and no valid proof, so nobody can hijack an account by rotating its keys
 * (which would let them sign consent or withdraw the participant).
 *
 * The backend (`rotate_participant_keys` in rls_policies.sql) recomputes the
 * proof — **keep [proofMessage] in sync with it**. Pure JVM (`javax.crypto`),
 * unit-testable.
 */
internal object KeyRotationAuth {

    private const val HMAC = "HmacSHA256"
    private const val ROTATION_KEY_LABEL = "mbpulse-key-rotation-v1"

    /** HMAC-SHA256(DEK, label) — the per-participant rotation key (32 bytes). */
    fun rotationKey(dekBytes: ByteArray): ByteArray = hmac(dekBytes, ROTATION_KEY_LABEL.toByteArray())

    /**
     * The exact message the proof covers:
     * `rotate|<pseudo id, lowercase>|<nonce>|<new public key b64>|<new wrapped DEK b64>`.
     */
    fun proofMessage(
        pseudoId: String,
        nonce: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String
    ): String = "rotate|${pseudoId.lowercase()}|$nonce|$newPublicKeyB64|$newWrappedDekB64"

    /** Base64 HMAC-SHA256(rotationKey, [proofMessage]). */
    fun proof(
        rotationKey: ByteArray,
        pseudoId: String,
        nonce: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String
    ): String = Base64.getEncoder().encodeToString(
        hmac(rotationKey, proofMessage(pseudoId, nonce, newPublicKeyB64, newWrappedDekB64)
            .toByteArray(Charsets.UTF_8))
    )

    /**
     * The second proof (backend API 4): keyed by the PASSPHRASE key
     * (`ParticipantCredentials.passphraseKey`, never stored on a phone) instead
     * of the DEK, which every enrolled phone holds — so a phone replaced by a
     * recovery elsewhere cannot rotate the account back. Also covers the new
     * device key: `rotate-passphrase|<pseudo id>|<nonce>|<public key>|<wrapped DEK>|<device key b64>`.
     */
    fun passphraseProofMessage(
        pseudoId: String,
        nonce: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String,
        deviceKeyB64: String
    ): String = "rotate-passphrase|${pseudoId.lowercase()}|$nonce|$newPublicKeyB64|$newWrappedDekB64|$deviceKeyB64"

    /** Base64 HMAC-SHA256(passphraseKey, [passphraseProofMessage]). */
    fun passphraseProof(
        passphraseKey: ByteArray,
        pseudoId: String,
        nonce: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String,
        deviceKeyB64: String
    ): String = Base64.getEncoder().encodeToString(
        hmac(passphraseKey, passphraseProofMessage(pseudoId, nonce, newPublicKeyB64, newWrappedDekB64, deviceKeyB64)
            .toByteArray(Charsets.UTF_8))
    )

    internal fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(message)
        }
}
