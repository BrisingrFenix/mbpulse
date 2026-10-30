package pt.ua.mbpulse.data.repository

import android.util.Base64
import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.collection.EncryptedRecordDto
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import pt.ua.mbpulse.crypto.SignedConsent
import pt.ua.mbpulse.crypto.SignedStudyDefinition
import pt.ua.mbpulse.crypto.StudyDefinitionHash
import pt.ua.mbpulse.crypto.StudyDefinitionSignature
import pt.ua.mbpulse.auth.DeviceKeyStore
import pt.ua.mbpulse.auth.ParticipantCredentialStore
import pt.ua.mbpulse.crypto.DeviceBindingAuth
import pt.ua.mbpulse.crypto.ParticipantCredentials
import pt.ua.mbpulse.crypto.UsernameToken
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.ua.mbpulse.data.local.EncryptedRecordEntity
import pt.ua.mbpulse.ui.screens.consent.ConsentDataType
import pt.ua.mbpulse.ui.screens.consent.ConsentDecision
import pt.ua.mbpulse.ui.screens.consent.ConsentRecord
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupabaseRepo"
private const val MAPPING_TAG = "SchemaMapping"

/** Postgres `unique_violation` — PostgREST returns it as the error code. */
private const val UNIQUE_VIOLATION = "23505"

/** Postgres `insufficient_privilege` — a row-level-security policy refused the row. */
private const val RLS_VIOLATION = "42501"


// ---------------------------------------------------------------------------
// Retrieve DTOs
// ---------------------------------------------------------------------------

@Serializable
private data class ActiveStudyDto(
    @SerialName("study_id")                val studyId: String,
    @SerialName("study_name")              val studyName: String? = null,
    @SerialName("study_version")           val studyVersion: Double,
    @SerialName("requires_join_code")      val requiresJoinCode: Boolean = false,
    @SerialName("essential_data_type_ids") val essentialDataTypeIds: List<String> = emptyList()
)

@Serializable
private data class StudyConfigDto(
    @SerialName("studyId")      val studyId: String,
    @SerialName("studyName")    val studyName: String,
    @SerialName("studyVersion") val studyVersion: Double,
    @SerialName("dataTypes")    val dataTypes: List<DataTypeDto>,
    @SerialName("piPublicKey")  val piPublicKey: String? = null,
    @SerialName("signedDefinition")    val signedDefinition: String? = null,
    @SerialName("definitionSignature") val definitionSignature: String? = null,
    @SerialName("definitionSha256")    val definitionSha256: String? = null,
    /** Not part of the signed definition: availability, like "isActive" (00_schema.sql). */
    @SerialName("endedAt")             val endedAt: String? = null,
    /** Server copy of schemaToConsentType (retention); only compared, never trusted ([SchemaMappingCheck]). */
    @SerialName("schemaDataTypes")     val schemaDataTypes: JsonObject? = null
)

@Serializable
private data class StudyEndDto(@SerialName("endedAt") val endedAt: String? = null)

@Serializable
private data class BackendVersionDto(
    val api: Int? = null,
    val compatibleSince: Int? = null,
    val missing: List<String> = emptyList()
)

/** A Postgres `timestamptz` as PostgREST writes it (ISO 8601 with offset) → epoch ms. */
internal fun parseTimestampMs(value: String?): Long? = value?.let {
    runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        .recoverCatching { _ -> java.time.Instant.parse(it).toEpochMilli() }
        .getOrNull()
}

@Serializable
private data class DataTypeDto(
    val id: String,
    val name: String,
    val description: String,
    val purpose: String,
    @SerialName("retentionDays")   val retentionDays: Int,
    @SerialName("isEssential")     val isEssential: Boolean,
    @SerialName("samplingSeconds") val samplingSeconds: Int? = null
)

// enroll_participant RPC parameters (rls_policies.sql).
@Serializable
private data class EnrollParams(
    @SerialName("p_pseudo_id")               val pseudoId: String,
    @SerialName("p_study_id")                val studyId: String,
    @SerialName("p_username_token")          val usernameToken: String,
    @SerialName("p_public_key_b64")          val publicKeyB64: String,
    @SerialName("p_wrapped_dek_b64")         val wrappedDekB64: String,
    @SerialName("p_recovery_blob")           val recoveryBlob: String,
    @SerialName("p_rotation_key_b64")        val rotationKeyB64: String,
    @SerialName("p_consent_study_version")   val consentStudyVersion: String,
    @SerialName("p_consent_completed_at_ms") val consentCompletedAtMs: Long,
    @SerialName("p_consent_decisions")       val consentDecisions: JsonElement,
    @SerialName("p_consent_signature_b64")   val consentSignatureB64: String,
    @SerialName("p_consent_definition_sha256") val consentDefinitionSha256: String,
    @SerialName("p_join_ticket")             val joinTicket: String?,
    @SerialName("p_device_key_b64")          val deviceKeyB64: String?,
    @SerialName("p_passphrase_key_b64")      val passphraseKeyB64: String?
)

@Serializable
private data class RecoveryParticipantDto(
    @SerialName("pseudo_id")       val pseudoId: String,
    @SerialName("study_id")        val studyId: String,
    @SerialName("recovery_blob")   val recoveryBlob: String,
    @SerialName("wrapped_dek_b64") val wrappedDekB64: String
)

// ---------------------------------------------------------------------------
// Insert DTOs
// ---------------------------------------------------------------------------

@Serializable
private data class DecisionDto(
    @SerialName("dataTypeId")   val dataTypeId: String,
    @SerialName("dataTypeName") val dataTypeName: String,
    @SerialName("accepted")     val accepted: Boolean,
    @SerialName("timestampMs")  val timestampMs: Long
)

// submit_consent_record RPC parameters (rls_policies.sql).
@Serializable
private data class SubmitConsentParams(
    @SerialName("p_pseudo_id")             val participantPseudoId: String,
    @SerialName("p_study_id")              val studyId: String,
    @SerialName("p_study_version")         val studyVersion: String,
    @SerialName("p_completed_at_ms")       val completedAtMs: Long,
    @SerialName("p_decisions")             val decisions: JsonElement,
    @SerialName("p_signature_b64")         val signatureB64: String,
    @SerialName("p_signer_public_key_b64") val signerPublicKeyB64: String,
    @SerialName("p_definition_sha256")     val definitionSha256: String
)

// get_my_consent_records RPC row.
@Serializable
private data class ConsentRecordDto(
    @SerialName("participant_pseudo_id") val participantPseudoId: String,
    @SerialName("study_id")              val studyId: String,
    @SerialName("study_version")         val studyVersion: String,
    @SerialName("completed_at_ms")       val completedAtMs: Long,
    @SerialName("decisions")             val decisions: List<DecisionDto>,
    @SerialName("signature_b64")         val signatureB64: String? = null,
    @SerialName("signer_public_key_b64") val signerPublicKeyB64: String? = null,
    @SerialName("definition_sha256")     val definitionSha256: String? = null
)

// ---------------------------------------------------------------------------
// RPC parameter payloads — JSON keys map to the SQL function argument names
// ---------------------------------------------------------------------------

@Serializable
private data class UsernameParam(@SerialName("p_username_token") val usernameToken: String)

@Serializable
private data class PseudoIdParam(@SerialName("p_pseudo_id") val pseudoId: String)

@Serializable
private data class RotateKeysParams(
    @SerialName("p_pseudo_id")       val pseudoId: String,
    @SerialName("p_public_key_b64")  val publicKeyB64: String,
    @SerialName("p_wrapped_dek_b64") val wrappedDekB64: String,
    @SerialName("p_nonce")           val nonce: String,
    @SerialName("p_proof_b64")       val proofB64: String,
    @SerialName("p_device_key_b64")       val deviceKeyB64: String,
    @SerialName("p_passphrase_key_b64")   val passphraseKeyB64: String,
    @SerialName("p_passphrase_proof_b64") val passphraseProofB64: String
)

// my_session_status() (rls_policies.sql, device binding).
@Serializable
private data class SessionStatusDto(
    val pseudoId: String,
    val session: String,
    val deviceKey: Boolean = false
)

@Serializable
private data class BindSessionParams(
    @SerialName("p_pseudo_id") val pseudoId: String,
    @SerialName("p_nonce")     val nonce: String,
    @SerialName("p_proof_b64") val proofB64: String
)

@Serializable
private data class RegisterDeviceKeyParams(
    @SerialName("p_pseudo_id")      val pseudoId: String,
    @SerialName("p_device_key_b64") val deviceKeyB64: String
)

@Serializable
private data class VerifyJoinCodeParams(
    @SerialName("p_study_id") val studyId: String,
    @SerialName("p_code")     val code: String
)

// Body for the confirm-withdrawal Edge Function (JSON keys match index.ts).
@Serializable
private data class ConfirmWithdrawalParams(
    val pseudoId: String,
    val signatureB64: String
)

@Serializable
private data class ConfirmWithdrawalResponseDto(
    val ok: Boolean = false,
    val receipt: DeletionReceiptDto? = null
)

@Serializable
private data class DeletionReceiptDto(
    val receiptId: String,
    val pseudoIdHash: String? = null,
    val studyId: String? = null,
    val deletedAt: String,
    val recordsDeleted: Int,
    val consentDeleted: Int,
    val participantDeleted: Int,
    val accountDeleted: Int = 0,
    val version: String = "v1",
    val signatureB64: String? = null
)

/** Server-issued proof of what a withdrawal erased. */
internal data class DeletionReceipt(
    val receiptId: String,
    val pseudoIdHash: String?,
    val studyId: String?,
    val deletedAt: String,
    val recordsDeleted: Int,
    val consentDeleted: Int,
    val participantDeleted: Int,
    /** 1 when the participant's Supabase account was deleted too (receipt format v2). */
    val accountDeleted: Int,
    /** Signing-string format: "v2" (with [accountDeleted]) or "v1" (older backends). */
    val version: String,
    /** Backend RSA signature over the receipt (base64), or null if unsigned. */
    val signatureB64: String?
)

public data class StudySummary(
    val studyId: String,
    val studyName: String,
    val studyVersion: String,
    val hasJoinCode: Boolean,
    val essentialDataTypeIds: List<String> = emptyList()
)

internal data class StudyConfig(
    val studyId: String,
    val studyName: String,
    val studyVersion: String,
    /**
     * The exact definition in use (crypto/StudyDefinitionHash): hash of the
     * verified signed text, or the server's for an unsigned study. Consent
     * records name it; the backend keeps every definition (study_definitions).
     */
    val definitionSha256: String,
    val dataTypes: List<ConsentDataType>,
    val piPublicKeyBytes: ByteArray?,
    /** Issue time of the verified PI-signed definition; null when not verified (no pinned key). */
    val definitionIssuedAt: java.time.Instant? = null,
    /** Set when the server was unreachable: when the cached copy used instead was fetched. */
    val offlineSinceMs: Long? = null,
    /** When the research team ended the study (epoch ms), or null while it runs. */
    val endedAtMs: Long? = null
)

/** The study definition on the server is missing, unsigned, forged or a replay — collection must not proceed. */
internal class StudyDefinitionException(message: String) : Exception(message)

internal data class DataExport(
    val id: Long,
    val exportedAtMs: Long,
    val recordCount: Int,
    val note: String?
)

internal data class EncryptedRecordImport(
    val epochStartMs: Long,
    val schemaName: String,
    val ciphertextHex: String
)

@Serializable
private data class EncryptedRecordImportDto(
    @SerialName("epoch_start_ms") val epochStartMs: Long,
    @SerialName("schema_name")    val schemaName: String,
    @SerialName("ciphertext_hex") val ciphertextHex: String
)

@Serializable
private data class DataExportDto(
    val id: Long,
    @SerialName("exported_at_ms") val exportedAtMs: Long,
    @SerialName("record_count")   val recordCount: Int,
    val note: String? = null
)

internal data class RecoveryParticipant(
    val pseudoId: String,
    val studyId: String,
    val recoveryBlob: String,
    val wrappedDekB64: String
)


@Singleton
internal class SupabaseMeasurementRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val supabase: SupabaseClient,
    private val credentialStore: ParticipantCredentialStore,
    private val deviceKeyStore: DeviceKeyStore,
    private val configCache: StudyConfigCache
) : MeasurementRepository {

    // ---- participant account (docs/design/participant-accounts.md) -----------

    private val sessionLock = Mutex()

    /** Login session (JWT `session_id`) last confirmed to act for the participant — guarded by [sessionLock]. */
    private var verifiedSessionId: String? = null

    /**
     * Makes sure requests run as the participant's account: the sealed session
     * is loaded at start-up and refreshed by supabase-kt; if it is gone (refresh
     * token revoked, storage cleared), signs in again silently with the sealed
     * credentials. Shared by the UI and WorkManager workers (same singleton).
     *
     * Device binding: once per login session of an enrolled phone (stored
     * credentials — not during enrolment or recovery, which bind themselves),
     * checks that the session acts for the participant and re-attaches a new
     * login ([bindingCheckLocked]); throws [DeviceReplacedException] when
     * another phone took the account over.
     */
    private suspend fun ensureSession() = sessionLock.withLock {
        val auth = supabase.auth
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) {
            val credentials = credentialStore.load() ?: throw NotSignedInException()
            Log.i(TAG, "No session — signing in again with the stored credentials")
            auth.signInWith(Email) {
                email = credentials.email
                password = credentials.authSecret
            }
        }
        val sessionId = auth.currentSessionOrNull()?.accessToken?.let { DeviceBindingAuth.sessionIdOf(it) }
            ?: return@withLock
        if (sessionId == verifiedSessionId) return@withLock
        if (credentialStore.load() == null) return@withLock   // enrolment / recovery in progress
        when (bindingCheckLocked()) {
            DeviceBindingAuth.Binding.REPLACED -> throw DeviceReplacedException()
            else -> verifiedSessionId = sessionId
        }
    }

    /**
     * `my_session_status()` → nothing to do / re-attach with the device key
     * (`bind_session`) / first binding (`register_device_key`) / replaced.
     * Caller holds [sessionLock] and has a session.
     */
    private suspend fun bindingCheckLocked(): DeviceBindingAuth.Binding {
        val status = try {
            supabase.postgrest.rpc("my_session_status").decodeAsOrNull<SessionStatusDto>()
        } catch (e: PostgrestRestException) {
            if (e.code == "PGRST202") return DeviceBindingAuth.Binding.UNSUPPORTED   // backend API < 4
            throw e
        } ?: return DeviceBindingAuth.Binding.NOT_ENROLLED
        val localKey = deviceKeyStore.load()
        return when (DeviceBindingAuth.nextStep(status.session, status.deviceKey, localKey != null)) {
            DeviceBindingAuth.Step.DONE -> DeviceBindingAuth.Binding.BOUND
            DeviceBindingAuth.Step.REGISTER -> {
                val key = localKey ?: DeviceBindingAuth.newDeviceKey().also { deviceKeyStore.save(it) }
                try {
                    supabase.postgrest.rpc("register_device_key",
                        RegisterDeviceKeyParams(status.pseudoId, Base64.encodeToString(key, Base64.NO_WRAP)))
                    Log.i(TAG, "Device registered for the participant (first binding)")
                    DeviceBindingAuth.Binding.BOUND
                } catch (e: PostgrestRestException) {
                    if (e.message?.contains("device_replaced") == true) DeviceBindingAuth.Binding.REPLACED else throw e
                }
            }
            DeviceBindingAuth.Step.BIND -> {
                val nonce = supabase.postgrest.rpc("request_session_challenge", PseudoIdParam(status.pseudoId))
                    .decodeAs<String>()
                try {
                    supabase.postgrest.rpc("bind_session",
                        BindSessionParams(status.pseudoId, nonce, DeviceBindingAuth.proof(localKey!!, status.pseudoId, nonce)))
                    Log.i(TAG, "New login attached to the participant")
                    DeviceBindingAuth.Binding.BOUND
                } catch (e: PostgrestRestException) {
                    if (e.message?.contains("device_replaced") == true) DeviceBindingAuth.Binding.REPLACED else throw e
                }
            }
            DeviceBindingAuth.Step.REPLACED -> DeviceBindingAuth.Binding.REPLACED
        }.also { if (it == DeviceBindingAuth.Binding.REPLACED) Log.w(TAG, "This phone is no longer the participant's device") }
    }

    override suspend fun checkDeviceBinding(): DeviceBindingAuth.Binding {
        sessionLock.withLock { verifiedSessionId = null }
        return try {
            ensureSession()
            DeviceBindingAuth.Binding.BOUND
        } catch (e: DeviceReplacedException) {
            DeviceBindingAuth.Binding.REPLACED
        } catch (e: NotSignedInException) {
            DeviceBindingAuth.Binding.NOT_ENROLLED
        }
    }

    /**
     * A participant request: [ensureSession], then [block]. When the backend
     * refuses ownership although the session was confirmed (another phone
     * recovered the account since), checks the binding again — throws
     * [DeviceReplacedException] if that is it, else the original error.
     */
    private suspend fun <T> asParticipant(block: suspend () -> T): T {
        ensureSession()
        return try {
            block()
        } catch (e: PostgrestRestException) {
            val refused = e.code == RLS_VIOLATION || e.message?.contains("not your participant") == true
            if (!refused) throw e
            sessionLock.withLock { verifiedSessionId = null }
            ensureSession()   // DeviceReplacedException when replaced
            throw e
        }
    }

    @Serializable
    private data class LoginIdParam(@SerialName("p_username_token") val usernameToken: String)

    override suspend fun participantEmail(usernameToken: String): String {
        val loginId = supabase.postgrest.rpc("participant_login_id", LoginIdParam(usernameToken))
            .decodeAs<String>()
        return ParticipantCredentials.email(loginId)
    }

    override suspend fun signUpParticipant(email: String, authSecret: String) {
        try {
            supabase.auth.signUpWith(Email) {
                this.email = email
                password = authSecret
            }
        } catch (e: AuthRestException) {
            if (e.errorCode != AuthErrorCode.UserAlreadyExists && e.errorCode != AuthErrorCode.EmailExists) throw e
            // Exists: either a retry after a lost response (same credentials
            // open it) or someone else's username.
            try {
                signInParticipant(email, authSecret)
            } catch (_: InvalidCredentialsException) {
                throw UsernameTakenException()
            }
        }
        checkNotNull(supabase.auth.currentSessionOrNull()) {
            "Sign-up returned no session — disable \"Confirm email\" for the Email provider in Supabase Auth"
        }
    }

    override suspend fun signInParticipant(email: String, authSecret: String) {
        try {
            supabase.auth.signInWith(Email) {
                this.email = email
                password = authSecret
            }
        } catch (e: AuthRestException) {
            if (e.errorCode == AuthErrorCode.InvalidCredentials) throw InvalidCredentialsException()
            throw e
        }
    }

    override suspend fun rememberCredentials(credentials: ParticipantCredentialStore.Credentials) =
        credentialStore.save(credentials)

    override suspend fun rememberDeviceKey(deviceKey: ByteArray) = deviceKeyStore.save(deviceKey)

    override suspend fun signOutParticipant() {
        runCatching { supabase.auth.signOut(SignOutScope.LOCAL) }
            .onFailure { Log.w(TAG, "Local sign-out failed: ${it.message}") }
        credentialStore.clear()
        deviceKeyStore.clear()
        sessionLock.withLock { verifiedSessionId = null }
    }

    override suspend fun signOutOtherSessions() {
        runCatching { supabase.auth.signOut(SignOutScope.OTHERS) }
            .onFailure { Log.w(TAG, "Could not sign out the other sessions: ${it.message}") }
    }

    override suspend fun getBackendVersion(): BackendVersion {
        val dto = try {
            supabase.postgrest.rpc("backend_version").decodeAs<BackendVersionDto>()
        } catch (e: PostgrestRestException) {
            // PGRST202 = no such function: a backend from before versioning.
            if (e.code != "PGRST202") throw e
            return BackendVersion(api = 0, compatibleSince = 0)
        }
        return BackendVersion(dto.api ?: 0, dto.compatibleSince ?: 0, dto.missing)
    }

    /**
     * The study definition from the server — or, when the server cannot be
     * reached, the last one fetched and verified on this device
     * ([StudyConfigCache]; [StudyConfig.offlineSinceMs] set). Both go through
     * the same parsing and signature verification. A definition the server
     * rejects or that fails verification is never replaced by the cache.
     */
    override suspend fun getStudyConfig(selectedStudyId: String): StudyConfig {
        val raw = try {
            supabase
                .postgrest["StudyConfig"]
                .select(
                    columns = Columns.list(
                        "studyId",
                        "studyName",
                        "studyVersion",
                        "dataTypes",
                        "piPublicKey",
                        "signedDefinition",
                        "definitionSignature",
                        "definitionSha256",
                        "endedAt",
                        "schemaDataTypes"
                    )
                ) {
                    filter { eq("studyId", selectedStudyId) }
                }.data
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = configCache.load(selectedStudyId) ?: throw e
            Log.w(TAG, "Study config unreachable (${e::class.simpleName}) — using the copy from ${cached.savedAtMs}")
            return parseStudyConfig(cached.raw, selectedStudyId).copy(offlineSinceMs = cached.savedAtMs)
        }
        val config = parseStudyConfig(raw, selectedStudyId)
        configCache.save(selectedStudyId, raw)
        return config
    }

    private val configJson = Json { ignoreUnknownKeys = true }

    private fun parseStudyConfig(raw: String, selectedStudyId: String): StudyConfig {
        Log.d("Supabase", "StudyConfig raw: $raw")

        val dto = configJson.decodeFromString<List<StudyConfigDto>>(raw).firstOrNull()
            ?: throw StudyDefinitionException(context.getString(R.string.mbpulse_study_missing))

        // With a pinned study-signing key, ONLY the PI-signed text is trusted:
        // the PI key (every new record's data key is wrapped for it), the
        // consent wording, retention and sampling. A missing or invalid
        // signature means the server's copy is not what the PI signed.
        val pinnedKey = MbPulseLibrary.config.studySigningPublicKeyB64
        if (pinnedKey != null) {
            when (val v = StudyDefinitionSignature.verify(
                dto.signedDefinition, dto.definitionSignature, pinnedKey, selectedStudyId
            )) {
                is StudyDefinitionSignature.Result.Rejected -> {
                    Log.e(TAG, "Study $selectedStudyId rejected: ${v.reason}")
                    throw StudyDefinitionException(context.getString(R.string.mbpulse_study_unverified, v.reason))
                }
                // The hash of the verified text itself — not the server's column.
                is StudyDefinitionSignature.Result.Verified -> {
                    checkSchemaMapping(selectedStudyId, v.definition.schemaDataTypes)
                    return v.definition.toStudyConfig(StudyDefinitionHash.of(dto.signedDefinition!!))
                        .copy(endedAtMs = parseTimestampMs(dto.endedAt))
                }
            }
        }

        Log.w(TAG, "No studySigningPublicKeyB64 configured — study definition is NOT verified")
        checkSchemaMapping(selectedStudyId, dto.schemaDataTypes?.let { o ->
            o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
        })
        // Consent records must name the definition: without one (backend
        // older than study_definitions) the study cannot be consented to.
        val definitionSha256 = dto.signedDefinition?.let { StudyDefinitionHash.of(it) }
            ?: dto.definitionSha256?.lowercase()?.takeIf { StudyDefinitionHash.isValid(it) }
            ?: throw StudyDefinitionException(context.getString(R.string.mbpulse_study_untracked))
        return StudyConfig(
            studyId      = dto.studyId,
            studyName    = dto.studyName,
            studyVersion = dto.studyVersion.toString(),
            definitionSha256 = definitionSha256,
            dataTypes    = dto.dataTypes.map { it.toConsentDataType() },
            piPublicKeyBytes = dto.piPublicKey?.let { parsePemPublicKey(it) },
            definitionIssuedAt = null,
            endedAtMs    = parseTimestampMs(dto.endedAt)
        )
    }

    /** The last [SchemaMappingCheck] result logged, so a reload logs only a change. */
    @Volatile private var lastMappingReport: List<String>? = null

    /**
     * Logs where the app's schemaToConsentType and the study's schemaDataTypes
     * disagree (tag `SchemaMapping`). Diagnostic only: consent filtering keeps
     * using the app's mapping, retention the server's.
     */
    private fun checkSchemaMapping(studyId: String, server: Map<String, String>?) {
        val appSchemas = pt.ua.mbpulse.data.model.OmhSchemas.ALL
            .map { "${it.namespace}:${it.name}" }
            .filterNot { pt.ua.mbpulse.data.model.QuestionnaireRegistry.isQuestionnaireSchema(it) }
        val report = SchemaMappingCheck.differences(server, appSchemas, MbPulseLibrary::consentTypeFor)
        if (report == lastMappingReport) return
        lastMappingReport = report
        if (report.isEmpty()) Log.i(MAPPING_TAG, "Study $studyId: schemaToConsentType matches the study's schemaDataTypes")
        else report.forEach { Log.w(MAPPING_TAG, "Study $studyId: $it") }
    }

    private fun SignedStudyDefinition.toStudyConfig(definitionSha256: String) = StudyConfig(
        studyId      = studyId,
        studyName    = studyName,
        studyVersion = studyVersion.toString(),
        definitionSha256 = definitionSha256,
        dataTypes    = dataTypes.map {
            ConsentDataType(it.id, it.name, it.description, it.purpose,
                it.retentionDays, it.isEssential, it.samplingSeconds)
        },
        piPublicKeyBytes   = parsePemPublicKey(piPublicKey),
        definitionIssuedAt = issuedAtInstant
    )

    /**
     * Strips PEM headers and decodes the Base64 body to DER bytes.
     * Accepts both "BEGIN PUBLIC KEY" (PKCS#8) and "BEGIN RSA PUBLIC KEY" formats.
     */
    private fun parsePemPublicKey(pem: String): ByteArray {
        val stripped = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("", "")
            .replace(" ", "")
            .trim()
        return Base64.decode(stripped, Base64.DEFAULT)
    }

    private fun DataTypeDto.toConsentDataType() = ConsentDataType(
        id = id,
        name = name,
        description = description,
        purpose = purpose,
        retentionDays = retentionDays,
        isEssential = isEssential,
        samplingSeconds = samplingSeconds
    )


    override suspend fun enrollParticipant(enrollment: ParticipantEnrollment) {
        Log.d("Supabase", "Enrolling participant: ${enrollment.pseudoId}")
        // Errors propagate: onboarding must not report success unless the
        // whole enrolment is on the server. Runs as the just-created account,
        // which the participant row is bound to.
        ensureSession()
        try {
            enroll(enrollment)
        } catch (e: PostgrestRestException) {
            // Raised by enroll_participant with these prefixes (rls_policies.sql).
            when {
                e.message?.contains("study_closed") == true -> throw StudyClosedException()
                e.message?.contains("join_ticket_invalid") == true -> throw JoinTicketInvalidException()
                else -> throw e
            }
        }
        Log.d("Supabase", "Participant enrolled")
    }

    private suspend fun enroll(enrollment: ParticipantEnrollment) {
        supabase.postgrest.rpc(
            function   = "enroll_participant",
            parameters = EnrollParams(
                pseudoId             = enrollment.pseudoId,
                studyId              = enrollment.studyId,
                usernameToken        = enrollment.usernameToken,
                publicKeyB64         = enrollment.publicKeyB64,
                wrappedDekB64        = enrollment.wrappedDekB64,
                recoveryBlob         = enrollment.recoveryBlob,
                rotationKeyB64       = enrollment.rotationKeyB64,
                consentStudyVersion  = enrollment.consent.studyVersion,
                consentCompletedAtMs = enrollment.consent.completedAtMs,
                consentDecisions     = Json.encodeToJsonElement(enrollment.consent.decisions.map { it.toDto() }),
                consentSignatureB64  = enrollment.consentSignatureB64,
                consentDefinitionSha256 = enrollment.consent.definitionSha256,
                joinTicket           = enrollment.joinTicket,
                deviceKeyB64         = enrollment.deviceKeyB64,
                passphraseKeyB64     = enrollment.passphraseKeyB64
            )
        )
    }

    override suspend fun insertConsentRecord(
        record: ConsentRecord,
        signature: ByteArray,
        signerPublicKeyBytes: ByteArray
    ) {
        Log.d("Supabase", "Submitting signed consent record for: ${record.participantPseudoId}")
        asParticipant { supabase.postgrest.rpc(
            function   = "submit_consent_record",
            parameters = SubmitConsentParams(
                participantPseudoId = record.participantPseudoId,
                studyId             = record.studyId,
                studyVersion        = record.studyVersion,
                completedAtMs       = record.completedAtMs,
                decisions           = Json.encodeToJsonElement(record.decisions.map { it.toDto() }),
                signatureB64        = Base64.encodeToString(signature, Base64.NO_WRAP),
                signerPublicKeyB64  = Base64.encodeToString(signerPublicKeyBytes, Base64.NO_WRAP),
                definitionSha256    = record.definitionSha256
            )
        ) }
        Log.d("Supabase", "Consent record stored")
    }

    override suspend fun fetchMyConsentRecords(): List<SignedConsent> {
        ensureSession()
        val result = supabase.postgrest.rpc("get_my_consent_records")
        return result.decodeList<ConsentRecordDto>().map { dto ->
            SignedConsent(
                participantPseudoId = dto.participantPseudoId,
                studyId             = dto.studyId,
                studyVersion        = dto.studyVersion,
                completedAtMs       = dto.completedAtMs,
                decisions           = dto.decisions.map {
                    ConsentDecision(it.dataTypeId, it.dataTypeName, it.accepted, it.timestampMs)
                },
                signatureB64        = dto.signatureB64,
                signerPublicKeyB64  = dto.signerPublicKeyB64,
                definitionSha256    = dto.definitionSha256
            )
        }
    }

    override suspend fun uploadRecord(record: EncryptedRecordEntity) {
        Log.d(TAG, "Uploading record id=${record.id} " +
                "schema=${record.schemaName} " +
                "participant=${record.participantPseudoId} " +
                "epochStartMs=${record.epochStartMs}")

        // The insert policy only accepts rows of the account's own participant
        // (from its bound session).
        try {
            asParticipant { supabase.postgrest["encrypted_records"].insert(value = record.toDto()) }
        } catch (e: PostgrestRestException) {
            // The server already holds this (participant, epoch, schema) — an
            // earlier upload whose response was lost, or a re-sent sensor
            // batch. Not an error: the record is on the server. (A plain insert
            // + this check, not upsert/ON CONFLICT, because ON CONFLICT needs
            // an anon SELECT policy that the hardened RLS setup removes.)
            if (e.code != UNIQUE_VIOLATION) throw e
            Log.d(TAG, "Record id=${record.id} already on the server — treating as synced")
            return
        }

        Log.d(TAG, "Upload successful for record id=${record.id}")
    }

    override suspend fun uploadRecords(records: List<EncryptedRecordEntity>) {
        if (records.isEmpty()) return
        try {
            asParticipant { supabase.postgrest["encrypted_records"].insert(records.map { it.toDto() }) }
            Log.d(TAG, "Batch upload successful — ${records.size} record(s)")
        } catch (e: PostgrestRestException) {
            // One row already on the server rejects the whole batch — fall back
            // to one-by-one, where a duplicate counts as uploaded.
            if (e.code != UNIQUE_VIOLATION) throw e
            Log.d(TAG, "Batch contains already-uploaded records — uploading one by one")
            records.forEach { uploadRecord(it) }
        }
    }

    private fun EncryptedRecordEntity.toDto() = EncryptedRecordDto(
        participantPseudoId      = participantPseudoId,
        epochStartMs             = epochStartMs,
        ciphertextHex            = ciphertextHex,
        schemaName               = schemaName,
        participantWrappedDekHex = participantWrappedDekHex,
        piWrappedDekHex          = piWrappedDekHex,
        studyId                  = studyId,
    )

    override suspend fun getLatestExport(studyId: String): DataExport? {
        return try {
            ensureSession()   // data_exports is readable by the study's participants only
            val result = supabase
                .postgrest["data_exports"]
                .select(columns = Columns.list("id", "exported_at_ms", "record_count", "note")) {
                    filter { eq("study_id", studyId) }
                    order("exported_at_ms", Order.DESCENDING)
                    limit(1)
                }

            Log.d("ExportRepository", "data_exports raw: ${result.data}")

            result.decodeList<DataExportDto>().firstOrNull()?.let {
                DataExport(
                    id           = it.id,
                    exportedAtMs = it.exportedAtMs,
                    recordCount  = it.recordCount,
                    note         = it.note
                )
            }
        } catch (e: Exception) {
            Log.e("ExportRepository", "getLatestExport failed: ${e.message}")
            null
        }
    }


    override suspend fun getActiveStudies(): List<StudySummary> {
        val result = supabase.postgrest.rpc("get_active_studies")

        Log.d("Supabase", "Active studies raw: ${result.data}")

        return result.decodeList<ActiveStudyDto>().map {
            StudySummary(
                studyId              = it.studyId,
                studyName            = it.studyName ?: context.getString(R.string.mbpulse_study_unnamed),
                studyVersion         = it.studyVersion.toString(),
                hasJoinCode          = it.requiresJoinCode,
                essentialDataTypeIds = it.essentialDataTypeIds
            )
        }
    }

    override suspend fun issueJoinTicket(studyId: String, enteredCode: String): String? {
        val result = try {
            supabase.postgrest.rpc(
                function   = "issue_join_ticket",
                parameters = VerifyJoinCodeParams(studyId = studyId, code = enteredCode)
            )
        } catch (e: PostgrestRestException) {
            if (e.message?.contains("study_closed") == true) throw StudyClosedException()
            throw e
        }
        return result.decodeAsOrNull<String>()
    }

    override suspend fun getStudyEndedAt(studyId: String): Long? {
        val rows = supabase.postgrest["StudyConfig"]
            .select(columns = Columns.list("endedAt")) { filter { eq("studyId", studyId) } }
            .decodeList<StudyEndDto>()
        return parseTimestampMs(rows.firstOrNull()?.endedAt)
    }

    private fun ConsentDecision.toDto() = DecisionDto(
        dataTypeId   = dataTypeId,
        dataTypeName = dataTypeName,
        accepted     = accepted,
        timestampMs  = timestampMs
    )

    /** The only form in which a username leaves the device (see [UsernameToken]). PBKDF2 — off the main thread. */
    private suspend fun usernameToken(username: String): String =
        withContext(Dispatchers.Default) { UsernameToken.of(username) }

    override suspend fun findMyRecoveryMaterial(): RecoveryParticipant? {
        // Only after sign-in: the blob is never served to an anonymous caller,
        // so passphrase guessing is online and rate-limited, not offline.
        ensureSession()
        val result = supabase.postgrest.rpc("get_my_recovery_material")
        return result.decodeList<RecoveryParticipantDto>().firstOrNull()?.let {
            RecoveryParticipant(
                pseudoId      = it.pseudoId,
                studyId       = it.studyId,
                recoveryBlob  = it.recoveryBlob,
                wrappedDekB64 = it.wrappedDekB64
            )
        }
    }

    override suspend fun requestKeyRotationChallenge(pseudoId: String): String {
        ensureSession()
        return supabase.postgrest.rpc(
            function   = "request_key_rotation_challenge",
            parameters = PseudoIdParam(pseudoId)
        ).decodeAs<String>()
    }

    override suspend fun rotateKeys(
        pseudoId: String,
        newPublicKeyB64: String,
        newWrappedDekB64: String,
        nonce: String,
        proofB64: String,
        deviceKeyB64: String,
        passphraseKeyB64: String,
        passphraseProofB64: String
    ) {
        Log.d("RecoveryRepository", "Rotating keys for pseudo_id: $pseudoId")
        ensureSession()
        supabase.postgrest.rpc(
            function   = "rotate_participant_keys",
            parameters = RotateKeysParams(
                pseudoId      = pseudoId,
                publicKeyB64  = newPublicKeyB64,
                wrappedDekB64 = newWrappedDekB64,
                nonce         = nonce,
                proofB64      = proofB64,
                deviceKeyB64  = deviceKeyB64,
                passphraseKeyB64   = passphraseKeyB64,
                passphraseProofB64 = passphraseProofB64
            )
        )
        Log.d("RecoveryRepository", "Keys rotated — this phone is now the participant's device")
    }

    override suspend fun isUsernameAvailable(username: String): Boolean {
        val result = supabase.postgrest.rpc(
            function   = "is_username_available",
            parameters = UsernameParam(usernameToken(username))
        )
        // Treat any failure as "unavailable" so a transient error can't let two
        // participants claim the same username.
        return result.decodeAsOrNull<Boolean>() ?: false
    }

    override suspend fun fetchMyEncryptedRecords(): List<EncryptedRecordImport> {
        ensureSession()
        val result = supabase.postgrest.rpc("get_my_encrypted_records")

        val records = result.decodeList<EncryptedRecordImportDto>()
        Log.d("RecoveryRepository", "Fetched ${records.size} encrypted records")

        return records.map {
            EncryptedRecordImport(
                epochStartMs  = it.epochStartMs,
                schemaName    = it.schemaName,
                ciphertextHex = it.ciphertextHex
            )
        }
    }

    override suspend fun requestWithdrawalChallenge(pseudoId: String): String {
        Log.d(TAG, "Requesting withdrawal challenge for: $pseudoId")
        val result = asParticipant {
            supabase.postgrest.rpc(
                function   = "request_withdrawal_challenge",
                parameters = PseudoIdParam(pseudoId)
            )
        }
        return result.decodeAs<String>()
    }

    override suspend fun confirmWithdrawal(pseudoId: String, signatureB64: String): DeletionReceipt {
        Log.d(TAG, "Confirming withdrawal for: $pseudoId")
        ensureSession()   // the Edge Function checks the caller's JWT owns pseudoId
        val response = supabase.functions.invoke(
            function = "confirm-withdrawal",
            body     = ConfirmWithdrawalParams(pseudoId = pseudoId, signatureB64 = signatureB64)
        )
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Withdrawal rejected (${response.status.value}): $bodyText")
        }
        val dto = Json { ignoreUnknownKeys = true }
            .decodeFromString(ConfirmWithdrawalResponseDto.serializer(), bodyText)
        val receipt = dto.receipt
            ?: throw IllegalStateException("Withdrawal succeeded but no receipt was returned.")
        Log.d(TAG, "Withdrawal confirmed for: $pseudoId — receipt ${receipt.receiptId}")
        return DeletionReceipt(
            receiptId          = receipt.receiptId,
            pseudoIdHash       = receipt.pseudoIdHash,
            studyId            = receipt.studyId,
            deletedAt          = receipt.deletedAt,
            recordsDeleted     = receipt.recordsDeleted,
            consentDeleted     = receipt.consentDeleted,
            participantDeleted = receipt.participantDeleted,
            accountDeleted     = receipt.accountDeleted,
            version            = receipt.version,
            signatureB64       = receipt.signatureB64
        )
    }

}
