package pt.ua.mbpulse.data.repository

import pt.ua.mbpulse.auth.ParticipantCredentialStore
import pt.ua.mbpulse.data.local.EncryptedRecordEntity
import pt.ua.mbpulse.ui.screens.consent.ConsentRecord

/** Everything [MeasurementRepository.enrollParticipant] stores, as sent (base64 = standard, no wrap). */
internal data class ParticipantEnrollment(
    val pseudoId: String,
    val studyId: String,
    /** Client username token (`UsernameToken.of`) — the username itself never leaves the device. */
    val usernameToken: String,
    val publicKeyB64: String,
    val wrappedDekB64: String,
    val recoveryBlob: String,
    val rotationKeyB64: String,
    val consent: ConsentRecord,
    val consentSignatureB64: String,
    /** From [MeasurementRepository.issueJoinTicket]; required by the backend when the study has a join code. */
    val joinTicket: String? = null,
    /** This phone's device key (`DeviceBindingAuth`) — binds the participant to this phone's session. */
    val deviceKeyB64: String? = null,
    /** `ParticipantCredentials.passphraseKey` — the passphrase proof for later recoveries. */
    val passphraseKeyB64: String? = null
)

/**
 * This phone is no longer the participant's device: the account was recovered
 * on another phone, whose session the backend now accepts instead (device
 * binding, `bind_session`). Nothing more can be sent or read from here.
 */
internal class DeviceReplacedException : Exception("The account is now used on another device")

/** The study does not take enrolments (enrolment closed, or the study ended). */
internal class StudyClosedException : Exception("Study not open for enrolment")

/** Enrolment refused the join ticket (expired, used, or never issued) — the join code must be entered again. */
internal class JoinTicketInvalidException : Exception("Join ticket invalid or expired")

/** No participant account on this device (never enrolled, or signed out by withdrawal/reset). */
internal class NotSignedInException : Exception("No participant account on this device")

/** Sign-up found the account already exists and the credentials do not open it. */
internal class UsernameTakenException : Exception("Username already taken")

/** Sign-in refused: wrong username or passphrase. */
internal class InvalidCredentialsException : Exception("Incorrect username or passphrase")

/**
 * Backend access. Participant-specific calls run as the participant's
 * Supabase account (docs/design/participant-accounts.md): the session is
 * restored or silently re-established before each, and the database checks
 * that the account owns the pseudonym. Throw [NotSignedInException] when the
 * device has no account.
 */
internal interface MeasurementRepository {
    /**
     * The backend's API version (`backend_version()`); api 0 when the backend
     * predates versioning. Throws when the server cannot be reached.
     */
    suspend fun getBackendVersion(): BackendVersion

    suspend fun getStudyConfig(selectedStudyId: String): StudyConfig

    // ---- account ------------------------------------------------------------

    /** Login email for a client username token (anon RPC `participant_login_id`). */
    suspend fun participantEmail(usernameToken: String): String

    /**
     * Creates the participant's account and signs in. If it already exists and
     * these credentials open it (a retry after a lost response), signs in
     * instead. Throws [UsernameTakenException] otherwise.
     */
    suspend fun signUpParticipant(email: String, authSecret: String)

    /** Signs in to an existing account (recovery). Throws [InvalidCredentialsException]. */
    suspend fun signInParticipant(email: String, authSecret: String)

    /** Keeps the credentials (sealed) for silent re-login — call once enrolment/recovery succeeded. */
    suspend fun rememberCredentials(credentials: ParticipantCredentialStore.Credentials)

    /** Keeps this phone's device key (sealed) — call once enrolment/recovery succeeded. */
    suspend fun rememberDeviceKey(deviceKey: ByteArray)

    /** Ends the session on this device and forgets the stored credentials and device key (withdrawal, reset). */
    suspend fun signOutParticipant()

    /** Revokes every other login of the account (after a recovery: the replaced phone's). Best effort. */
    suspend fun signOutOtherSessions()

    /**
     * Whether this phone's session still acts for the participant (device
     * binding): re-attaches a new login of this phone, registers the device of
     * a participant enrolled before binding existed, and reports
     * [pt.ua.mbpulse.crypto.DeviceBindingAuth.Binding.REPLACED]
     * when another phone took over. Throws when the server is unreachable.
     */
    suspend fun checkDeviceBinding(): pt.ua.mbpulse.crypto.DeviceBindingAuth.Binding

    // ---- enrolment / consent -------------------------------------------------

    /**
     * Enrols the signed-in participant in ONE server transaction: participant
     * row (bound to the account), rotation key and signed consent record — all
     * or nothing. Idempotent: resending the identical enrolment succeeds
     * without changing anything. Throws on any failure.
     */
    suspend fun enrollParticipant(enrollment: ParticipantEnrollment)

    /**
     * Stores [record] with the participant's signature over its
     * [pt.ua.mbpulse.crypto.ConsentSignature] signing string. The
     * backend accepts it only from the owning account under the registered key,
     * and never lets it be changed afterwards.
     */
    suspend fun insertConsentRecord(record: ConsentRecord, signature: ByteArray, signerPublicKeyBytes: ByteArray)

    /** The signed-in participant's consent records, oldest first (signed or not). */
    suspend fun fetchMyConsentRecords(): List<pt.ua.mbpulse.crypto.SignedConsent>

    // ---- data -------------------------------------------------------------------

    suspend fun uploadRecord(record: EncryptedRecordEntity)

    /**
     * Uploads [records] in one request; already-uploaded ones count as uploaded.
     * Throws if the batch could not be stored (nothing is marked by the caller).
     */
    suspend fun uploadRecords(records: List<EncryptedRecordEntity>)
    suspend fun getLatestExport(studyId: String): DataExport?

    suspend fun getActiveStudies(): List<StudySummary>

    /**
     * Trades the join code for a single-use join ticket (7 days) that
     * enrolment must present — the backend enforces the code, not the app.
     * Null when the code is wrong (or rate-limited); throws
     * [StudyClosedException] when the study takes no enrolments. Studies
     * without a code get a ticket for any input.
     */
    suspend fun issueJoinTicket(studyId: String, enteredCode: String): String?

    /** When the study ended (epoch ms), or null while it runs. Throws when the server is unreachable. */
    suspend fun getStudyEndedAt(studyId: String): Long?

    suspend fun isUsernameAvailable(username: String): Boolean

    /** The signed-in participant's own encrypted records. */
    suspend fun fetchMyEncryptedRecords(): List<EncryptedRecordImport>

    // ---- recovery -----------------------------------------------------------------

    /** Recovery material of the signed-in participant, or null if the account has no participant. */
    suspend fun findMyRecoveryMaterial(): RecoveryParticipant?

    /** One-time nonce (5 min) that a key rotation must answer. */
    suspend fun requestKeyRotationChallenge(pseudoId: String): String

    /**
     * Replaces the participant's device key pair. Requires the owning account
     * AND [proofB64] = `KeyRotationAuth.proof(...)` over [nonce] and the new
     * key material, AND — once the participant has one registered —
     * [passphraseProofB64] = `KeyRotationAuth.passphraseProof(...)`. Registers
     * [deviceKeyB64] and binds this session: every other phone is locked out.
     * [passphraseKeyB64] is registered when none is yet (enrolled before API 4).
     */
    suspend fun rotateKeys(
        pseudoId: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String,
        nonce: String,
        proofB64: String,
        deviceKeyB64: String,
        passphraseKeyB64: String,
        passphraseProofB64: String
    )

    // ---- withdrawal ---------------------------------------------------------------

    /** One-time withdrawal challenge for [pseudoId] (owning account only). */
    suspend fun requestWithdrawalChallenge(pseudoId: String): String

    /**
     * Submits the [signatureB64] over a withdrawal challenge. The Edge Function
     * checks the account owns [pseudoId] and the signature against its device
     * key; only then it erases the data and the account. Returns the signed
     * [DeletionReceipt]. Throws if rejected.
     */
    suspend fun confirmWithdrawal(pseudoId: String, signatureB64: String): DeletionReceipt
}
