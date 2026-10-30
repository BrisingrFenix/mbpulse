package pt.ua.mbpulse.crypto

import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The participant's Supabase account, derived from the recovery credentials
 * (docs/design/participant-accounts.md §4). The username and passphrase the
 * participant chose at enrolment ARE the account — but neither is ever sent:
 *
 *  - **email** = `<loginId>@participants.invalid`, where `loginId` is the
 *    server-peppered username token (`participant_login_id` RPC). A dump of
 *    `auth.users` therefore holds no username and cannot be dictionary-attacked
 *    back to one without the Vault pepper.
 *  - **password** = [authSecret]: PBKDF2 of the passphrase, salted with a
 *    fixed label and the client username token. Supabase stores
 *    `bcrypt(authSecret)`; the passphrase — which protects the recovery blob,
 *    i.e. the DEK — never leaves the device. An offline guess costs PBKDF2
 *    plus bcrypt and needs the username too: no easier than attacking the
 *    recovery blob that already exists.
 *
 * Deterministic so a new device re-derives the same account. **A contract**:
 * changing label, iterations or the email format locks every participant out.
 * Pure JVM, unit-testable.
 */
internal object ParticipantCredentials {

    const val EMAIL_DOMAIN = "participants.invalid"
    const val AUTH_ITERATIONS = 600_000
    private const val LABEL = "mbpulse-auth-v1|"
    private const val PASSPHRASE_KEY_LABEL = "mbpulse-rotate-v1|"

    /** Login email for a [loginId] returned by `participant_login_id`. */
    fun email(loginId: String): String {
        require(loginId.matches(Regex("^[0-9a-f]{40}$"))) { "unexpected login id format" }
        return "$loginId@$EMAIL_DOMAIN"
    }

    /** Base64 PBKDF2-HMAC-SHA256(passphrase, label + clientToken) — the account password (~0.6–2 s; off the main thread). */
    fun authSecret(passphrase: CharArray, clientUsernameToken: String): String =
        Base64.getEncoder().encodeToString(derive(passphrase, LABEL + clientUsernameToken))

    /**
     * PBKDF2-HMAC-SHA256(passphrase, rotation label + clientToken) — the key of
     * the passphrase proof for key rotation (`KeyRotationAuth.passphraseProof`).
     * Unlike [authSecret] it is **never stored** on the phone: computed at
     * enrolment and recovery while the passphrase is typed, registered with the
     * backend, then dropped. A phone that holds the account (authSecret, DEK) but
     * not the passphrase cannot produce it. Same cost as [authSecret].
     */
    fun passphraseKey(passphrase: CharArray, clientUsernameToken: String): ByteArray =
        derive(passphrase, PASSPHRASE_KEY_LABEL + clientUsernameToken)

    private fun derive(passphrase: CharArray, salt: String): ByteArray {
        val spec = PBEKeySpec(passphrase, salt.toByteArray(Charsets.UTF_8), AUTH_ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
