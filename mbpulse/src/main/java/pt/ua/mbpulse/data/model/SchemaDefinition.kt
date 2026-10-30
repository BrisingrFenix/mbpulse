package pt.ua.mbpulse.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A study-specific OMH schema declared by the **host app**
 * ([pt.ua.mbpulse.MbPulseConfig.customSchemas]) — so a study adds
 * new measures without modifying the library.
 *
 * Its fields travel in [OmhBody.extra] and are written **flat** into the body
 * JSON, exactly like the built-in typed fields (`{"emg_rms": {...}, ...}`).
 * The library displays, charts and maps them to FHIR from this definition:
 *  - the **first** field is the primary value — the card value, the charted
 *    number (if numeric and not an event) and the FHIR `value[x]`;
 *  - the other fields are shown alongside it and become FHIR `component`s.
 *
 * Build data points with [dataPoint] (or [body]), which checks every value
 * against the definition.
 */
public data class SchemaDefinition(
    val id: OmhSchemaId,
    /** FHIR / terminology binding (LOINC, category, UCUM) — as for built-in schemas. */
    val terminology: SchemaTerminology,
    val fields: List<SchemaField>,
    /**
     * An event (e.g. a stimulus trigger): instant time frame, never throttled
     * by sampling, never charted as a numeric series.
     */
    val isEvent: Boolean = false
) {
    /** The `"namespace:name"` key used across the pipeline. */
    val schemaName: String get() = "${id.namespace}:${id.name}"

    /** The primary field (the first one). */
    val primary: SchemaField get() = fields.first()

    public fun field(name: String): SchemaField? = fields.firstOrNull { it.name == name }

    /**
     * Body for this schema. [values] are field name → value: a number for
     * QUANTITY (unit added from the definition) and NUMBER fields, a String for
     * TEXT and ENUM fields (an ENUM value must be one of its values). `null`
     * values are omitted.
     *
     * @throws IllegalArgumentException on an unknown field or a wrong value.
     */
    public fun body(timeFrame: OmhEffectiveTimeFrame, values: Map<String, Any?>): OmhBody {
        require(timeFrame.isInstant == isEvent) {
            "$schemaName is ${if (isEvent) "an event — use OmhEffectiveTimeFrame.instant(…)" else
                "a measurement — use an interval time frame"}"
        }
        val extra = buildJsonObject {
            values.forEach { (name, value) ->
                val field = requireNotNull(field(name)) {
                    "$schemaName has no field '$name' (fields: ${fields.joinToString { it.name }})"
                }
                if (value != null) put(name, field.encode(value, schemaName))
            }
        }
        return OmhBody(effectiveTimeFrame = timeFrame, extra = extra)
    }

    public fun body(timeFrame: OmhEffectiveTimeFrame, vararg values: Pair<String, Any?>): OmhBody =
        body(timeFrame, values.toMap())

    /**
     * A complete data point: fresh header (UUID, creation time, provenance) +
     * [body]. For a measurement pass the window as [startMs]..[endMs]; for an
     * event pass the instant as [startMs] and leave [endMs] null.
     */
    public fun dataPoint(
        sourceName: String,
        startMs: Long,
        endMs: Long? = null,
        values: Map<String, Any?>,
        modality: String = "sensed"
    ): OmhDataPoint {
        val timeFrame = if (isEvent) {
            OmhEffectiveTimeFrame.instant(startMs)
        } else {
            OmhEffectiveTimeFrame(OmhTimeInterval(iso(startMs), iso(requireNotNull(endMs) {
                "$schemaName is a measurement — pass endMs (the end of the window)"
            })))
        }
        return OmhDataPoint(
            header = OmhHeader(
                id                    = UUID.randomUUID().toString(),
                creationDateTime      = iso(System.currentTimeMillis()),
                schemaId              = id,
                acquisitionProvenance = OmhProvenance(sourceName = sourceName, modality = modality)
            ),
            body = body(timeFrame, values)
        )
    }

    private fun iso(ms: Long): String =
        Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/**
 * One body field of a [SchemaDefinition]. [name] is the JSON key (snake_case).
 *
 * @param unit  OMH unit string, required for [FieldKind.QUANTITY]
 *   (e.g. "mV", "breaths/min"); normalized to UCUM only in the FHIR projection.
 * @param values allowed values of an [FieldKind.ENUM] field (at least two).
 * @param label  human label for display and FHIR components; defaults to the name.
 */
public data class SchemaField(
    val name: String,
    val kind: FieldKind,
    val unit: String? = null,
    val values: List<String> = emptyList(),
    val label: String = name.replace('_', ' ')
) {
    internal fun encode(value: Any, schemaName: String): JsonElement = when (kind) {
        FieldKind.QUANTITY -> buildJsonObject {
            put("value", number(value, schemaName))
            put("unit", unit)
        }
        FieldKind.NUMBER -> JsonPrimitive(number(value, schemaName))
        FieldKind.TEXT -> JsonPrimitive(value.toString())
        FieldKind.ENUM -> {
            val code = value.toString()
            require(code in values) { "$schemaName.$name must be one of $values, got '$code'" }
            JsonPrimitive(code)
        }
    }

    /** The numeric value of this field in [element], or null for TEXT/ENUM or a missing value. */
    internal fun numberIn(element: JsonElement?): Float? = when (kind) {
        FieldKind.QUANTITY -> (element as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull
        FieldKind.NUMBER -> (element as? JsonPrimitive)?.floatOrNull
        else -> null
    }

    /** The text value of this field in [element], or null for numeric kinds. */
    internal fun textIn(element: JsonElement?): String? = when (kind) {
        FieldKind.TEXT, FieldKind.ENUM -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }

    /** The unit stored in [element] (QUANTITY), falling back to the declared [unit]. */
    internal fun unitIn(element: JsonElement?): String? =
        (element as? JsonObject)?.get("unit")?.jsonPrimitive?.content ?: unit

    private fun number(value: Any, schemaName: String): Float {
        require(value is Number) { "$schemaName.$name is numeric — got ${value::class.simpleName}" }
        return value.toFloat()
    }
}

/** Shape of a [SchemaField] — the same four kinds the setup wizard offers. */
public enum class FieldKind {
    /** `{"value": 1.2, "unit": "mV"}` — an [OmhUnitValue]. */
    QUANTITY,
    /** A bare number (unitless scores, indices, ratings). */
    NUMBER,
    /** Free text (labels). */
    TEXT,
    /** One of a fixed set of string values. */
    ENUM
}

/** The values of a body of this schema, read back from [OmhBody.extra]. */
internal fun SchemaDefinition.valuesIn(body: OmhBody): List<Pair<SchemaField, JsonElement>> =
    fields.mapNotNull { f -> body.extra[f.name]?.let { f to it } }
