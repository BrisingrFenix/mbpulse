package pt.ua.mbpulse.crypto

import pt.ua.mbpulse.ui.screens.consent.ConsentDecision
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A consent record as stored on the backend: the participant's decisions plus
 * their RSA signature (SHA256withRSA, made with the hardware device key at
 * enrolment) and the public key that signed it (DER X.509 SPKI, base64).
 *
 * The signature makes the record tamper-evident: changing any decision, the
 * study, the version or the participant breaks it, and only the participant's
 * device could have produced it. The backend additionally binds the signer key
 * to the participant's registered key at insert and blocks updates.
 */
internal data class SignedConsent(
    val participantPseudoId: String,
    val studyId: String,
    val studyVersion: String,
    val completedAtMs: Long,
    val decisions: List<ConsentDecision>,
    val signatureB64: String?,
    val signerPublicKeyB64: String?,
    /**
     * The study definition the consent was given for ([StudyDefinitionHash]) —
     * signed (format v2). Null only for records made before definitions were
     * tracked (format v1, linked by [studyVersion] alone).
     */
    val definitionSha256: String? = null
)

/**
 * Canonical signing format for consent records, and its verification.
 *
 * Pure JVM (`java.util.Base64`, `java.security`) so it is unit-testable and can
 * be mirrored by PI tooling. **The format is a contract** — anything that
 * verifies consent signatures (e.g. a PI audit script) must build exactly this
 * string; bump the version line for any change:
 *
 * ```
 * mbpulse-consent-v2
 * participant:<pseudo id, lowercase>
 * study:<study id, lowercase>
 * studyVersion:<study version, as stored>
 * definition:<SHA-256 of the study definition consented to, lowercase hex>
 * completedAtMs:<epoch ms>
 * decision:<data type id>:<accepted|declined>:<timestampMs>:<data type name>
 * …one decision line per data type, in the order shown to the participant
 * ```
 * Lines are joined with `\n` (no trailing newline) and UTF-8 encoded. Every
 * free-text value is percent-encoded: each UTF-8 byte outside
 * `A-Z a-z 0-9 - _ . ~` becomes `%XX` (uppercase hex), so values can never
 * contain the separators.
 *
 * The `definition` line ([StudyDefinitionHash]) names the exact terms: the
 * backend keeps every study definition ever applied (`study_definitions`,
 * append-only), so the record shows what was agreed even after the study is
 * updated. Version 1 (`mbpulse-consent-v1`, no `definition` line, else
 * identical) is still verified for records made before definitions were
 * tracked; new records are always v2.
 */
internal object ConsentSignature {

    const val VERSION_LINE = "mbpulse-consent-v2"
    /** Records made before definitions were tracked — verified, never produced. */
    const val VERSION_LINE_V1 = "mbpulse-consent-v1"
    private const val ALGORITHM = "SHA256withRSA"

    /**
     * The string to sign. [definitionSha256] = the study definition consented
     * to (format v2); null builds the legacy v1 string, for verifying old records only.
     */
    fun signingString(
        participantPseudoId: String,
        studyId: String,
        studyVersion: String,
        definitionSha256: String?,
        completedAtMs: Long,
        decisions: List<ConsentDecision>
    ): String = buildList {
        add(if (definitionSha256 != null) VERSION_LINE else VERSION_LINE_V1)
        // UUIDs: lowercased, as Postgres returns them.
        add("participant:${encode(participantPseudoId.lowercase())}")
        add("study:${encode(studyId.lowercase())}")
        add("studyVersion:${encode(studyVersion)}")
        definitionSha256?.let { add("definition:${encode(it.lowercase())}") }
        add("completedAtMs:$completedAtMs")
        decisions.forEach { d ->
            add("decision:${encode(d.dataTypeId)}:${if (d.accepted) "accepted" else "declined"}" +
                ":${d.timestampMs}:${encode(d.dataTypeName)}")
        }
    }.joinToString("\n")

    fun signingString(c: SignedConsent): String =
        signingString(c.participantPseudoId, c.studyId, c.studyVersion, c.definitionSha256, c.completedAtMs, c.decisions)

    /** True when [c] carries a valid signature by [SignedConsent.signerPublicKeyB64] over its content. */
    fun verify(c: SignedConsent): Boolean {
        val signature = c.signatureB64 ?: return false
        val publicKey = c.signerPublicKeyB64 ?: return false
        return try {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(signingString(c).toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signature))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The participant's current consent: the last of [records] (in server
     * order, oldest first) that belongs to [participantPseudoId] / [studyId]
     * and carries a valid signature. Unsigned, forged or altered records are
     * ignored. Null when none qualifies — callers must then fail closed
     * (essential data types only), never treat it as "everything allowed".
     */
    fun latestValid(
        records: List<SignedConsent>,
        participantPseudoId: String,
        studyId: String
    ): SignedConsent? = records.lastOrNull { c ->
        c.participantPseudoId.equals(participantPseudoId, ignoreCase = true) &&
            c.studyId.equals(studyId, ignoreCase = true) &&
            verify(c)
    }

    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) append(c.toChar()) else append('%').append("%02X".format(c))
        }
    }
}
