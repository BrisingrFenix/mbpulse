package pt.ua.mbpulse.crypto

import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pseudonymised recovery username. The plaintext username never leaves the
 * device: the app sends only this token, and the backend stores only
 * `HMAC-SHA256(pepper, token)` with a secret pepper kept in Supabase Vault
 * (rls_policies.sql, `participant_username_token`). So:
 *  - the participants table holds no username, and cannot be linked back to
 *    one without the separately kept pepper (GDPR Art. 4(5) pseudonymisation);
 *  - even someone observing the API sees only a slow, salted hash.
 *
 * Deterministic (app-wide salt, no per-user salt) because recovery must find
 * the account from the username alone, on any device. Usernames are
 * normalised first — trimmed, Unicode NFKC, lowercase — so "Alice " and
 * "alice" are the same account.
 *
 * **A contract:** changing the salt, iterations or normalisation makes every
 * existing account unrecoverable — bump the version label only with a migration.
 * Pure JVM (`javax.crypto`), unit-testable.
 */
internal object UsernameToken {

    const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private val SALT = "mbpulse-username-token-v1".toByteArray(Charsets.UTF_8)

    fun normalize(username: String): String =
        Normalizer.normalize(username.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** Base64 PBKDF2-HMAC-SHA256 of the normalised username (~0.1–0.3 s on a phone — call off the main thread). */
    fun of(username: String): String {
        val chars = normalize(username).toCharArray()
        val spec = PBEKeySpec(chars, SALT, ITERATIONS, KEY_BITS)
        try {
            val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(hash)
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
