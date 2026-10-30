package wizard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.LocalDate

/**
 * `handoff.json` — the machine-readable half of AGENT_BRIEF.md, written into
 * the study app's folder. The app's `HandoffConformanceTest` (template-app,
 * src/test) checks the app's MbPulseConfig, processor and manifest against it.
 *
 * It is the study app's specification, so it is meant to be committed with
 * the app: it holds no secret. The backend address and key appear only as
 * SHA-256 hashes, enough to check that BuildConfig carries the right ones.
 */
object Handoff {

    const val FORMAT = "mbpulse-handoff-v1"

    fun build(
        study: JsonObject,
        app: JsonObject,
        receiptPublicKeyB64: String?,
        studySigningPublicKeyB64: String?,
        libraryVersion: String,
        supabaseUrl: String?,
        supabaseKey: String?
    ): JsonObject {
        fun s(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val mapping = Brief.mapping(app)
        val customSchemas = app["customSchemas"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val customIds = customSchemas.mapNotNull { s(it, "id") }.toSet()
        val builtInEvents = SCHEMA_REGISTRY.filter { it.role == "event" }.map { it.id }.toSet()
        val collectionMode = s(app, "collectionMode")
            ?.takeIf { it in setOf("PASSIVE_CONNECTED_DEVICE", "PASSIVE_HEALTH", "SESSION") }
            ?: "PASSIVE_CONNECTED_DEVICE"

        return buildJsonObject {
            put("format", FORMAT)
            put("generated", LocalDate.now().toString())
            put("studyName", s(study, "studyName"))
            put("libraryVersion", libraryVersion)
            put("packageId", s(app, "packageId") ?: "com.example.mystudy")
            put("supabaseUrlSha256", supabaseUrl?.let(::sha256Hex))
            put("supabaseKeySha256", supabaseKey?.let(::sha256Hex))
            put("studySigningPublicKeyB64", studySigningPublicKeyB64)
            put("deletionReceiptPublicKeyB64", receiptPublicKeyB64)
            put("collectionMode", collectionMode)
            put("collectionScreenInRelease", collectionMode != "SESSION" && s(app, "collectionScreenInRelease") == "true")
            putJsonArray("dataTypes") {
                study["dataTypes"]?.jsonArray.orEmpty().forEach { el ->
                    val t = el.jsonObject
                    addJsonObject {
                        put("id", s(t, "id"))
                        put("isEssential", s(t, "isEssential") == "true")
                    }
                }
            }
            putJsonObject("schemaToConsentType") { mapping.forEach { (schema, type) -> put(schema, type) } }
            putJsonArray("supportedDataTypeIds") { Brief.supportedDataTypeIds(app).forEach { add(it) } }
            // Instant (event) schemas: the processor emits them on a trigger,
            // which a replayed CSV need not contain.
            putJsonArray("eventSchemas") {
                customSchemas.filter { s(it, "role") == "event" }.mapNotNull { s(it, "id") }.forEach { add(it) }
                mapping.keys.filter { it in builtInEvents }.forEach { add(it) }
            }
            // Declared by the template (participant-entered, not the processor).
            putJsonArray("templateSchemas") {
                mapping.keys.filter { it in Brief.TEMPLATE_SCHEMAS && it !in customIds }.forEach { add(it) }
            }
            putJsonArray("customSchemas") {
                customSchemas.forEach { c ->
                    addJsonObject {
                        put("id", s(c, "id"))
                        put("version", s(c, "version") ?: "1.0")
                        put("isEvent", s(c, "role") == "event")
                        putJsonArray("fields") {
                            c["fields"]?.jsonArray.orEmpty().forEach { fe ->
                                val f = fe.jsonObject
                                addJsonObject {
                                    put("name", s(f, "name"))
                                    put("kind", (s(f, "kind") ?: "number").uppercase())
                                    if (s(f, "kind") == "quantity") put("unit", s(f, "unit"))
                                    if (s(f, "kind") == "enum") putJsonArray("values") {
                                        f["values"]?.jsonArray.orEmpty().forEach { add(it.jsonPrimitive.content) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
