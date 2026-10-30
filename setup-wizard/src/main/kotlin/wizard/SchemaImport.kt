package wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a JSON Schema — an Open mHealth schema fetched from GitHub, or a file
 * the researcher uploads — into the wizard's custom-schema shape:
 * `{id, version, description, fields:[{name, kind, unit, units, required}]}`.
 *
 * Custom schemas support the library's four `FieldKind`s, so every property
 * is classified into one of them:
 *  - `quantity` — a unit-value (`{value, unit}`), with the allowed units;
 *  - `number`   — a bare number/integer;
 *  - `text`     — a string or boolean;
 *  - `enum`     — a string restricted to listed values (inline or a referenced
 *    enum type such as `body-posture`), with the values.
 * `effective_time_frame` is skipped (every body carries it); arrays and nested
 * objects the library cannot represent are reported as skipped, not guessed.
 */
object SchemaImport {

    private const val REPO = "openmhealth/schemas"
    private const val BRANCH = "main"
    private const val RAW = "https://raw.githubusercontent.com/$REPO/$BRANCH/schema/omh/"
    private const val LISTING = "https://api.github.com/repos/$REPO/contents/schema/omh?ref=$BRANCH"

    /**
     * Building blocks in the OMH repo that are not measurements on their own
     * (types referenced by measurement schemas) — hidden from search.
     */
    private val BUILDING_BLOCKS = setOf(
        "activity-name", "blood-specimen-type", "body-location", "body-posture", "data-point",
        "date-time", "day-of-week", "descriptive-statistic", "descriptive-statistic-denominator",
        "full-date", "header", "intervention-administration-route", "local-time",
        "medication-dose-unit", "part-of-day", "position-during-measurement",
        "reason-single-medication-dose-not-taken", "schema-id", "specimen-source",
        "temporal-relationship-to-meal", "temporal-relationship-to-physical-activity",
        "temporal-relationship-to-sleep", "time-frame", "time-interval", "unit-value",
        "unit-value-range"
    )

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    /** A fetched document plus the URL it finally came from (after redirects) — its ref base. */
    private data class Doc(val json: JsonObject, val url: String)

    private val cache = ConcurrentHashMap<String, Pair<String, String>>()

    /** GET → (body, final URL after redirects). */
    private fun get(url: String): Pair<String, String> = cache.getOrPut(url) {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "mbpulse-setup-wizard").build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET $url → HTTP ${res.statusCode()}" }
        res.body() to res.uri().toString()
    }

    /**
     * Fetches a schema document. The OMH repo's `*-1.x.json` aliases are git
     * symlinks, so raw GitHub serves just the target file name — follow it.
     */
    private fun fetchDoc(url: String, hops: Int = 0): Doc {
        val (body, finalUrl) = get(url)
        val text = body.trim()
        if (!text.startsWith("{") && text.endsWith(".json") && hops < 3)
            return fetchDoc(finalUrl.substringBeforeLast('/') + "/" + text, hops + 1)
        return Doc(Json.parseToJsonElement(text).jsonObject, finalUrl)
    }

    // ------------------------------------------------------------ search

    /** name → versions (newest first), measurement schemas only. */
    private val catalogue: Map<String, List<String>> by lazy {
        val versionRe = Regex("""^(.+)-(\d+\.\d+)\.json$""")   // skips the "-1.x" aliases
        Json.parseToJsonElement(get(LISTING).first).jsonArray
            .mapNotNull { versionRe.find(it.jsonObject["name"]!!.jsonPrimitive.content) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (name, _) -> name !in BUILDING_BLOCKS && !name.endsWith("unit-value") && !name.endsWith("-range") }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.sortedWith(compareByDescending<String> { it.substringBefore('.').toInt() }
                .thenByDescending { it.substringAfter('.').toInt() }) }
            .toSortedMap()
    }

    fun search(query: String): JsonObject {
        val q = query.trim().lowercase().replace(' ', '-')
        return buildJsonObject {
            put("ok", true)
            put("results", buildJsonArray {
                catalogue.filterKeys { q.isEmpty() || q in it }.forEach { (name, versions) ->
                    add(buildJsonObject {
                        put("name", name)
                        put("versions", buildJsonArray { versions.forEach { add(it) } })
                    })
                }
            })
        }
    }

    // ------------------------------------------------------------ import

    /** Fetches `omh:<name>` at [version] from the Open mHealth repo and converts it. */
    fun fromOmh(name: String, version: String): JsonObject {
        require(Regex("""[a-z0-9-]+""").matches(name) && Regex("""\d+\.\d+""").matches(version)) {
            "bad schema name/version"
        }
        val doc = fetchDoc("$RAW$name-$version.json")
        return convert(doc.json, "omh:$name", version, source = doc.url, base = doc.url)
    }

    /**
     * Converts an uploaded JSON document. Accepts either a JSON Schema (id
     * taken from `$id` when it follows the `.../<namespace>/<name>-<ver>.json`
     * convention, else from [fallbackId]) or a wizard export that already has
     * `fields`.
     */
    fun fromJson(text: String, fallbackId: String?): JsonObject {
        val doc = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { error("Not a JSON object: ${it.message}") }

        // Already in the wizard's own shape — pass through.
        if (doc["fields"] is JsonArray) return buildJsonObject {
            put("ok", true); doc.forEach { (k, v) -> put(k, v) }
        }

        val idMatch = doc.str("\$id")?.let {
            Regex("""/([a-z0-9-]+)/([a-z0-9-]+?)-(\d+\.\d+)\.json$""").find(it)
        }
        val id = idMatch?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
            ?: fallbackId?.takeIf { it.isNotBlank() }
            ?: error("The schema has no usable \$id — enter the schema id (namespace:name) first.")
        val version = idMatch?.groupValues?.get(3) ?: "1.0"
        // Relative refs of an omh: schema resolve against the OMH repo; any
        // other schema's against its own $id when that is a URL.
        val base = if (id.startsWith("omh:")) RAW
            else doc.str("\$id")?.takeIf { it.startsWith("http") }
        return convert(doc, id, version, source = doc.str("\$id"), base = base)
    }

    // ------------------------------------------------------------ conversion

    private data class Field(
        val kind: String, val units: List<String> = emptyList(), val values: List<String> = emptyList()
    )

    /**
     * [base] is the URL of the document being read; relative `$ref`s resolve
     * against its directory (null = cannot resolve, fall back to name heuristics).
     */
    private fun convert(
        schema: JsonObject, id: String, version: String, source: String?, base: String?
    ): JsonObject {
        val props = schema["properties"]?.jsonObject
            ?: error("The schema has no top-level \"properties\" — is it a measurement schema?")
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
        val skipped = mutableListOf<String>()

        val fields = buildJsonArray {
            props.forEach { (name, prop) ->
                if (name == "effective_time_frame") return@forEach
                val f = classify(prop.jsonObject, schema, base, depth = 0)
                if (f == null) { skipped += name; return@forEach }
                add(buildJsonObject {
                    put("name", name)
                    put("kind", f.kind)
                    put("unit", f.units.firstOrNull() ?: "")
                    put("units", buildJsonArray { f.units.forEach { add(it) } })
                    if (f.kind == "enum") put("values", buildJsonArray { f.values.forEach { add(it) } })
                    put("required", name in required)
                    prop.jsonObject.str("description")?.let { put("description", it) }
                })
            }
        }
        require(fields.isNotEmpty()) { "No importable fields found (skipped: ${skipped.joinToString()})." }

        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("version", version)
            put("description", schema.str("description") ?: id)
            source?.let { put("source", it) }
            schema["deprecation"]?.jsonObject?.str("reason")?.let { put("deprecated", it) }
            put("fields", fields)
            put("skipped", buildJsonArray { skipped.forEach { add(it) } })
        }
    }

    /**
     * Classifies one property schema; null when the library cannot represent it.
     * [root] is the document [p] lives in (for `#/` pointers), [base] its URL.
     */
    private fun classify(p: JsonObject, root: JsonObject, base: String?, depth: Int): Field? {
        if (depth > 8) return Field("text")

        p["allOf"]?.jsonArray?.let { parts ->
            val classified = parts.mapNotNull { classify(it.jsonObject, root, base, depth + 1) }
            val units = parts.flatMap { unitEnum(it.jsonObject) } + classified.flatMap { it.units }
            return when {
                classified.any { it.kind == "quantity" } || units.isNotEmpty() -> Field("quantity", units.distinct())
                else -> classified.firstOrNull()
            }
        }
        (p["oneOf"] ?: p["anyOf"])?.jsonArray?.let { alts ->
            return alts.firstNotNullOfOrNull { classify(it.jsonObject, root, base, depth + 1) }
        }

        p.str("\$ref")?.let { ref -> return classifyRef(ref, root, base, depth) }

        p["enum"]?.jsonArray?.let { e ->
            val values = e.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content }
            // A single-value or non-string enum is not a useful choice list.
            return if (values.size >= 2) Field("enum", values = values) else Field("text")
        }
        return when (p.str("type")) {
            "number", "integer" -> Field("number")
            "string", "boolean" -> Field("text")
            "object" -> {
                val keys = p["properties"]?.jsonObject?.keys.orEmpty()
                if ("value" in keys && "unit" in keys) Field("quantity", unitEnum(p)) else null
            }
            else -> null   // arrays, untyped — not representable as a SchemaField
        }
    }

    private fun classifyRef(ref: String, root: JsonObject, base: String?, depth: Int): Field? {
        // Local definition: #/definitions/x
        if (ref.startsWith("#/")) {
            val target = ref.removePrefix("#/").split('/')
                .fold(root as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }
            return (target as? JsonObject)?.let { classify(it, root, base, depth + 1) }
        }
        val typeName = ref.substringAfterLast('/').substringBefore('#')
            .removeSuffix(".json").replace(Regex("""-\d+\.(\d+|x)$"""), "")
        if (typeName == "time-frame" || typeName == "time-interval") return null

        val doc = fetchRef(ref, base)
        if (doc != null) {
            val f = classify(doc.json, doc.json, doc.url, depth + 1)
            // A unit-value type is a quantity even when its units stay unknown.
            return if (typeName.endsWith("unit-value") && f?.kind != "quantity") Field("quantity") else f
        }
        // Unresolvable (the IEEE 1752 GitLab serves a bot-check page to
        // non-browser clients): fall back to the type's name. Units that stay
        // unknown are left blank for the researcher to fill in.
        return when {
            typeName == "percent-unit-value" -> Field("quantity", listOf("%"))
            typeName.endsWith("unit-value") -> Field("quantity")
            else -> Field("text")
        }
    }

    /** Resolves a (possibly relative) file reference against the referring document's URL. */
    private fun fetchRef(ref: String, base: String?): Doc? = runCatching {
        val file = ref.substringBefore('#')
        val url = when {
            file.startsWith("http") -> file
            base != null -> base.substringBeforeLast('/') + "/" + file
            else -> return null
        }
        fetchDoc(url)
    }.getOrNull()

    private fun unitEnum(o: JsonObject): List<String> =
        o["properties"]?.jsonObject?.get("unit")?.jsonObject?.get("enum")?.jsonArray
            ?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
