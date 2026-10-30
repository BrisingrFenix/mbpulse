package pt.ua.mbpulse.crypto

import pt.ua.mbpulse.data.model.Questionnaire
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies PI-signed questionnaires (`questionnaires` table; signed by the
 * setup wizard's `QuestionnaireSigning.kt` — keep the format in sync).
 *
 * Same trust model as [StudyDefinitionSignature]: SHA256withRSA over the
 * UTF-8 bytes of the stored text, against the study-signing key pinned in the
 * app build — never a key from the server, so whoever controls the backend
 * cannot put their own questions in front of participants. The `format` field
 * ([Questionnaire.FORMAT] vs the study's) keeps a signed study definition and
 * a signed questionnaire from ever being accepted as one another.
 * Pure JVM, unit-testable.
 */
internal object QuestionnaireSignature {

    sealed class Result {
        data class Verified(val questionnaire: Questionnaire) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param pinnedPublicKeyB64 `MbPulseConfig.studySigningPublicKeyB64`; null =
     *   development build without a pinned key: the text is parsed and checked
     *   but its signature is NOT verified (as for the study definition).
     */
    fun verify(
        definition: String?,
        signatureB64: String?,
        pinnedPublicKeyB64: String?,
        expectedStudyId: String
    ): Result {
        if (definition.isNullOrEmpty()) return Result.Rejected("the questionnaire is empty")
        if (pinnedPublicKeyB64 != null) {
            if (signatureB64.isNullOrEmpty()) return Result.Rejected("the questionnaire is not signed")
            val valid = try {
                val key = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pinnedPublicKeyB64.trim())))
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(key)
                    update(definition.toByteArray(Charsets.UTF_8))
                    verify(Base64.getDecoder().decode(signatureB64))
                }
            } catch (e: Exception) {
                false
            }
            if (!valid) return Result.Rejected("the signature does not match the research team's key")
        }

        val questionnaire = try {
            json.decodeFromString(Questionnaire.serializer(), definition)
        } catch (e: Exception) {
            return Result.Rejected("the questionnaire cannot be read (${e::class.simpleName})")
        }
        if (!questionnaire.studyId.equals(expectedStudyId, ignoreCase = true)) {
            return Result.Rejected("the questionnaire belongs to a different study")
        }
        val problems = questionnaire.problems()
        if (problems.isNotEmpty()) return Result.Rejected(problems.joinToString("; "))
        return Result.Verified(questionnaire)
    }

    /** Rollback: a version issued before the one already accepted is a replay. */
    fun isRollback(candidate: Questionnaire, accepted: Questionnaire?): Boolean =
        accepted != null && (candidate.version < accepted.version ||
            candidate.issuedAtInstant.isBefore(accepted.issuedAtInstant))
}
