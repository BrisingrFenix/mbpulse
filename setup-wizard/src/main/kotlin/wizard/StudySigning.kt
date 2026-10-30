package wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Signs a study definition before seeding: the exact JSON text below is stored
 * in `StudyConfig."signedDefinition"` with its signature in
 * `"definitionSignature"`, and the app verifies it against its pinned key and
 * then uses ONLY that text (`StudyDefinitionSignature.kt` in the library —
 * keep the format in sync).
 *
 * Covers everything that must not be changeable by whoever controls the
 * backend: the PI key the app wraps data keys for, and the consent wording,
 * retention and sampling participants agree to. Not the join code (a secret,
 * never in a readable column) and not `isActive` (availability, not integrity).
 * `issuedAt` lets the app refuse an older definition replayed later.
 */
object StudySigning {

    const val FORMAT = "mbpulse-study-v1"

    /** The study with a `studyId` (assigned if new), plus the signed fields — ready for seed_study(). */
    fun sign(study: JsonObject, piPublicKeyPem: String, keysDir: Path, now: Instant = Instant.now()): JsonObject {
        val studyId = study["studyId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val definition = buildJsonObject {
            put("format", FORMAT)
            put("studyId", studyId)
            put("issuedAt", now.toString())
            put("studyName", study["studyName"] ?: error("study has no studyName"))
            put("studyVersion", study["studyVersion"] ?: error("study has no studyVersion"))
            put("piPublicKey", piPublicKeyPem)
            put("dataTypes", study["dataTypes"] ?: error("study has no dataTypes"))
            study["schemaDataTypes"]?.let { put("schemaDataTypes", it) }
        }
        val text = Json.encodeToString(JsonObject.serializer(), definition)
        return JsonObject(study + mapOf(
            "studyId" to JsonPrimitive(studyId),
            "signedDefinition" to JsonPrimitive(text),
            "definitionSignature" to JsonPrimitive(Keys.signStudyDefinition(keysDir, text))
        ))
    }
}
