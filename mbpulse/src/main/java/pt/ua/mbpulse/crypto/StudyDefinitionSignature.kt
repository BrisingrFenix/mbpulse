package pt.ua.mbpulse.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * A study definition exactly as the PI signed it in the setup wizard
 * (`setup-wizard/.../StudySigning.kt` — keep the format in sync). When the app
 * pins a study-signing key, this — not the plain `StudyConfig` columns — is the
 * source of truth for the PI key, the consent wording, retention and sampling.
 */
@Serializable
internal data class SignedStudyDefinition(
    val format: String,
    val studyId: String,
    val issuedAt: String,
    val studyName: String,
    val studyVersion: Double,
    val piPublicKey: String,
    val dataTypes: List<SignedDataType>,
    val schemaDataTypes: Map<String, String>? = null
) {
    val issuedAtInstant: Instant get() = Instant.parse(issuedAt)
}

@Serializable
internal data class SignedDataType(
    val id: String,
    val name: String,
    val description: String,
    val purpose: String,
    @SerialName("retentionDays") val retentionDays: Int,
    @SerialName("isEssential") val isEssential: Boolean,
    @SerialName("samplingSeconds") val samplingSeconds: Int? = null
)

/**
 * Verifies PI-signed study definitions: SHA256withRSA over the UTF-8 bytes of
 * the stored text, checked against the study-signing key pinned in the app
 * build (`MbPulseConfig.studySigningPublicKeyB64`). The key must come from the
 * build, never from the server — otherwise whoever controls the backend could
 * sign with their own key. Pure JVM, unit-testable.
 */
internal object StudyDefinitionSignature {

    const val FORMAT = "mbpulse-study-v1"

    sealed class Result {
        data class Verified(val definition: SignedStudyDefinition) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(
        signedDefinition: String?,
        signatureB64: String?,
        pinnedPublicKeyB64: String,
        expectedStudyId: String
    ): Result {
        if (signedDefinition.isNullOrEmpty() || signatureB64.isNullOrEmpty()) {
            return Result.Rejected("the study definition is not signed")
        }
        val valid = try {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pinnedPublicKeyB64.trim())))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(signedDefinition.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signatureB64))
            }
        } catch (e: Exception) {
            false
        }
        if (!valid) return Result.Rejected("the signature does not match the research team's key")

        val definition = try {
            json.decodeFromString(SignedStudyDefinition.serializer(), signedDefinition)
        } catch (e: Exception) {
            return Result.Rejected("the signed definition cannot be read (${e::class.simpleName})")
        }
        return when {
            definition.format != FORMAT -> Result.Rejected("unknown definition format '${definition.format}'")
            !definition.studyId.equals(expectedStudyId, ignoreCase = true) ->
                Result.Rejected("the signed definition belongs to a different study")
            runCatching { definition.issuedAtInstant }.isFailure ->
                Result.Rejected("the signed definition has no valid issue date")
            else -> Result.Verified(definition)
        }
    }

    /**
     * Rollback check: a definition older than one already accepted for the
     * study is a replay (e.g. an earlier version with longer retention).
     */
    fun isRollback(candidate: SignedStudyDefinition, lastAcceptedIssuedAt: Instant?): Boolean =
        lastAcceptedIssuedAt != null && candidate.issuedAtInstant.isBefore(lastAcceptedIssuedAt)
}
