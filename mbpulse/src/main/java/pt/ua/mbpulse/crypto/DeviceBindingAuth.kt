package pt.ua.mbpulse.crypto

import java.security.SecureRandom
import java.util.Base64

/**
 * Device binding (backend API 4, `bind_session` in rls_policies.sql): only the
 * login session of the participant's CURRENT phone may act for them. That
 * phone holds a random **device key** (registered at enrolment / recovery,
 * sealed by `auth/DeviceKeyStore`); a new login on it (silent re-login)
 * re-attaches by answering a one-time nonce with [proof]. Recovery on another
 * phone registers that phone's key instead, so the previous phone — although
 * it still has the account password — can no longer attach any session.
 *
 * The backend recomputes the proof — **keep [proofMessage] in sync with it**.
 * Pure JVM, unit-testable.
 */
internal object DeviceBindingAuth {

    const val DEVICE_KEY_BYTES = 32

    /** A new random device key. */
    fun newDeviceKey(): ByteArray = ByteArray(DEVICE_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** `bind|<pseudo id, lowercase>|<nonce>`. */
    fun proofMessage(pseudoId: String, nonce: String): String = "bind|${pseudoId.lowercase()}|$nonce"

    /** Base64 HMAC-SHA256(deviceKey, [proofMessage]). */
    fun proof(deviceKey: ByteArray, pseudoId: String, nonce: String): String =
        Base64.getEncoder().encodeToString(
            KeyRotationAuth.hmac(deviceKey, proofMessage(pseudoId, nonce).toByteArray(Charsets.UTF_8))
        )

    /**
     * The `session_id` claim of a Supabase access token (JWT payload; not
     * verified — only to notice a new login), or null if absent/unreadable.
     */
    fun sessionIdOf(accessToken: String): String? = runCatching {
        val payload = accessToken.split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as kotlinx.serialization.json.JsonObject }["session_id"]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** What [pt.ua.mbpulse.data.repository.MeasurementRepository.checkDeviceBinding] found. */
    enum class Binding {
        /** This session acts for the participant. */
        BOUND,
        /** Another phone became the participant's device (a recovery there) — this one is locked out. */
        REPLACED,
        /** The account has no participant (withdrawn, never enrolled). */
        NOT_ENROLLED,
        /** The backend predates device binding (API < 4): nothing to check. */
        UNSUPPORTED
    }

    /**
     * Pure decision from `my_session_status()`: [session] "this" | "other" | "none",
     * [serverHasDeviceKey], and whether this phone holds a device key.
     */
    enum class Step { DONE, BIND, REGISTER, REPLACED }

    fun nextStep(session: String, serverHasDeviceKey: Boolean, hasLocalKey: Boolean): Step = when {
        session == "this" -> Step.DONE
        session == "none" && !serverHasDeviceKey -> Step.REGISTER   // enrolled before binding: claim it
        hasLocalKey && serverHasDeviceKey -> Step.BIND              // a new login of this phone — or a replaced one
        else -> Step.REPLACED                                       // bound elsewhere and this phone has no key
    }
}
