package pt.ua.mbpulse.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// Envelope — every omh data point shares this structure
// ---------------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = OmhDataPointSerializer::class)
public data class OmhDataPoint(
    val header: OmhHeader,
    val body: OmhBody
)

@Serializable
public data class OmhHeader(
    val id: String,                             // UUID per data point
    @SerialName("creation_date_time")
    val creationDateTime: String,               // ISO-8601
    @SerialName("schema_id")
    val schemaId: OmhSchemaId,
    @SerialName("acquisition_provenance")
    val acquisitionProvenance: OmhProvenance
)

@Serializable
public data class OmhSchemaId(
    val namespace: String,
    val name: String,
    val version: String
)

@Serializable
public data class OmhProvenance(
    @SerialName("source_name")
    val sourceName: String,
    val modality: String = "sensed"
)

// ---------------------------------------------------------------------------
// Time frame — shared by all body types
// ---------------------------------------------------------------------------

@Serializable
public data class OmhTimeInterval(
    @SerialName("start_date_time") val startDateTime: String,
    @SerialName("end_date_time")   val endDateTime: String
)

/**
 * OMH time frame: exactly one of a [timeInterval] (measurements summarising a
 * window) or a single instant [dateTime] (events, e.g. a stimulus delivered).
 * The unset one is omitted from the JSON, so interval records serialize as before.
 */
@Serializable
public data class OmhEffectiveTimeFrame(
    @SerialName("time_interval") val timeInterval: OmhTimeInterval? = null,
    @SerialName("date_time")     val dateTime: String? = null
) {
    init {
        require((timeInterval == null) != (dateTime == null)) {
            "effective_time_frame needs exactly one of time_interval or date_time"
        }
    }

    /** True for an instant (event) rather than an interval. */
    val isInstant: Boolean get() = dateTime != null

    public companion object {
        /** An instant time frame at [epochMs] (ISO-8601 UTC). */
        public fun instant(epochMs: Long): OmhEffectiveTimeFrame =
            OmhEffectiveTimeFrame(dateTime = java.time.Instant.ofEpochMilli(epochMs).toString())
    }
}

// ---------------------------------------------------------------------------
// Body types — one per schema
// ---------------------------------------------------------------------------

/**
 * omh:heart-rate v1.1
 * https://www.openmhealth.org/documentation/#/schema-docs/schema-library/schemas/omh_heart-rate
 */
@Serializable
public data class OmhBody(
    // heart-rate fields
    @SerialName("heart_rate")
    val heartRate: OmhUnitValue? = null,

    // hrv fields (custom namespace: mbpulse:hrv v1.0)
    @SerialName("sdnn")
    val sdnn: OmhUnitValue? = null,
    @SerialName("rmssd")
    val rmssd: OmhUnitValue? = null,

    // sleep-episode fields (omh:sleep-episode v1.0)
    @SerialName("sleep_stage")
    val sleepStage: String? = null,
    @SerialName("movement_index")
    val movementIndex: Float? = null,

    // body-temperature fields (omh:body-temperature v2.0)
    @SerialName("body_temperature")
    val bodyTemperature: OmhUnitValue? = null,
    @SerialName("measurement_location")
    val measurementLocation: String? = null,

    @SerialName("mean_eda")
    val meanEda: OmhUnitValue? = null,
    @SerialName("sd_eda")
    val sdEda: OmhUnitValue? = null,
    @SerialName("min_eda")
    val minEda: OmhUnitValue? = null,
    @SerialName("max_eda")
    val maxEda: OmhUnitValue? = null,

    // stimulus-event fields (custom namespace: mbpulse:stimulus-event v1.0)
    // — an event schema: its time frame is an instant (date_time).
    @SerialName("stimulus_type")
    val stimulusType: StimulusType? = null,
    @SerialName("stimulus_label")
    val stimulusLabel: String? = null,
    @SerialName("stimulus_intensity")
    val stimulusIntensity: Float? = null,

    // shared by all
    @SerialName("effective_time_frame")
    val effectiveTimeFrame: OmhEffectiveTimeFrame,

    /**
     * Fields of host-defined schemas ([SchemaDefinition]), keyed by field name.
     * Written flat into the body JSON (no `extra` key) by [OmhDataPointSerializer];
     * build it with [SchemaDefinition.body] rather than by hand.
     */
    val extra: JsonObject = JsonObject(emptyMap())
)

/**
 * Serializes [OmhDataPoint] with [OmhBody.extra] flattened into the body, so
 * custom-schema fields sit at the top level of the body as OMH expects. A body
 * without extra fields serializes exactly as before.
 *
 * On read, the schema id decides where body keys go: for a registered
 * [SchemaDefinition] every key except the time frame goes into `extra` (so a
 * custom schema may use any field name, even one a built-in schema uses);
 * otherwise only keys that are not built-in fields do.
 */
internal object OmhDataPointSerializer :
    JsonTransformingSerializer<OmhDataPoint>(OmhDataPoint.generatedSerializer()) {

    private const val BODY = "body"
    private const val EXTRA = "extra"
    private const val TIME_FRAME = "effective_time_frame"

    /** JSON keys of the built-in typed [OmhBody] fields. */
    private val builtInFields: Set<String> =
        OmhBody.serializer().descriptor.let { d ->
            (0 until d.elementsCount).mapTo(HashSet()) { d.getElementName(it) } - EXTRA
        }

    override fun transformSerialize(element: JsonElement): JsonElement {
        val point = element.jsonObject
        val body = point[BODY]?.jsonObject ?: return point
        val extra = body[EXTRA] as? JsonObject ?: return point
        val flat = JsonObject(body.filterKeys { it != EXTRA } + extra.filterKeys { it !in body })
        return JsonObject(point + (BODY to flat))
    }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val point = element.jsonObject
        val body = point[BODY]?.jsonObject ?: return point
        val schemaId = point["header"]?.jsonObject?.get("schema_id")?.jsonObject
        val schemaName = "${schemaId?.get("namespace")?.jsonPrimitive?.content}:" +
            "${schemaId?.get("name")?.jsonPrimitive?.content}"
        val isCustom = OmhSchemas.definitionFor(schemaName) != null
        val (typed, custom) = body.entries.partition { (key, _) ->
            if (isCustom) key == TIME_FRAME else key in builtInFields
        }
        if (custom.isEmpty()) return point
        val split = JsonObject(typed.associate { it.toPair() } +
            (EXTRA to JsonObject(custom.associate { it.toPair() })))
        return JsonObject(point + (BODY to split))
    }
}

@Serializable
public data class OmhUnitValue(
    val value: Float,
    val unit: String
)

/**
 * Kind of stimulus delivered (`stimulus_type` in mbpulse:stimulus-event).
 * Serialized as the lowercase names; protocol specifics go in `stimulus_label`.
 */
@Serializable
public enum class StimulusType {
    @SerialName("auditory")  AUDITORY,
    @SerialName("visual")    VISUAL,
    @SerialName("tactile")   TACTILE,
    @SerialName("thermal")   THERMAL,
    @SerialName("cognitive") COGNITIVE;

    /** The serialized value, e.g. "auditory". */
    public val code: String get() = name.lowercase()
}

// ---------------------------------------------------------------------------
// Schema identifiers + FHIR terminology bindings — single source of truth
// ---------------------------------------------------------------------------

/**
 * FHIR / terminology binding for a measurement schema: the coded vocabulary and
 * units needed to render an [OmhDataPoint] as a FHIR `Observation`.
 *
 * Kept separate from [OmhSchemaId] on purpose — the OMH `schema_id` serialized
 * into each record stays spec-clean (namespace/name/version only), while this
 * binding lives in a lookup table ([OmhSchemas.terminologyFor]). These are the
 * inputs a future OMH→FHIR mapper needs:
 *  - [loincCode] → `Observation.code` (LOINC system)
 *  - [category]  → `Observation.category` (FHIR observation-category value set)
 *  - [ucumUnit] / [ucumCode] → `Observation.valueQuantity.unit` / `.code` (UCUM)
 *  - [bodySite]  → `Observation.bodySite` (SNOMED CT)
 *
 * Not `@Serializable`: it is metadata, never written into a record.
 */
public data class SchemaTerminology(
    /**
     * LOINC code, or null where no single standard code fits — HRV is expressed
     * as SDNN/RMSSD components, and EDA has no LOINC code.
     */
    val loincCode: String?,
    /** Human-readable label (LOINC long common name or local display). */
    val display: String,
    /**
     * FHIR `Observation.category` code from
     * http://terminology.hl7.org/CodeSystem/observation-category
     * (e.g. "vital-signs", "activity"). Only heart rate and body temperature are
     * part of the formal FHIR Vital Signs profile; the others reuse the closest
     * category as a project choice.
     */
    val category: String,
    /** UCUM display unit (the human `Quantity.unit`), or null if coded/unitless. */
    val ucumUnit: String?,
    /** UCUM case-sensitive code (the machine `Quantity.code`), or null if coded/unitless. */
    val ucumCode: String?,
    /** SNOMED CT body-site code, for anatomically located measurements. */
    val bodySite: String? = null
)

public object OmhSchemas {

    public val HEART_RATE: OmhSchemaId = OmhSchemaId(
        namespace = "omh",
        name      = "heart-rate",
        version   = "1.1"
    )

    /** No official omh HRV schema exists — using project namespace. */
    public val HRV: OmhSchemaId = OmhSchemaId(
        namespace = "mbpulse",
        name      = "hrv",
        version   = "1.0"
    )

    public val SLEEP_EPISODE: OmhSchemaId = OmhSchemaId(
        namespace = "omh",
        name      = "sleep-episode",
        version   = "1.0"
    )

    public val BODY_TEMPERATURE: OmhSchemaId = OmhSchemaId(
        namespace = "omh",
        name      = "body-temperature",
        version   = "2.0"
    )

    /** No official omh EDA schema — using project namespace. */
    public val EDA: OmhSchemaId = OmhSchemaId(
        namespace = "mbpulse",
        name      = "electrodermal-activity",
        version   = "1.0"
    )

    /**
     * Event schema: marks the instant a stimulus was delivered (time frame is
     * `date_time`). Every trigger is recorded — sampling never applies.
     */
    public val STIMULUS_EVENT: OmhSchemaId = OmhSchemaId(
        namespace = "mbpulse",
        name      = "stimulus-event",
        version   = "1.0"
    )

    /** The schemas built into the library. */
    public val BUILT_IN: List<OmhSchemaId> =
        listOf(HEART_RATE, HRV, SLEEP_EPISODE, BODY_TEMPERATURE, EDA, STIMULUS_EVENT)

    /** Host-defined schemas, registered by `MbPulseLibrary.init` from `MbPulseConfig.customSchemas`. */
    @Volatile
    private var custom: List<SchemaDefinition> = emptyList()

    /**
     * Registers the host app's [SchemaDefinition]s (replacing any earlier set).
     * Called by `MbPulseLibrary.init` after validation; tests and tooling that
     * use [pt.ua.mbpulse.fhir.FhirMapper] outside the app call it directly.
     */
    public fun register(definitions: List<SchemaDefinition>) {
        custom = definitions.toList()
    }

    /**
     * Answer schemas of the study's backend questionnaires (`questionnaire:<id>`),
     * replaced whenever verified questionnaires are loaded ([QuestionnaireRegistry]).
     */
    @Volatile
    private var questionnaires: List<SchemaDefinition> = emptyList()

    internal fun registerQuestionnaires(definitions: List<SchemaDefinition>) {
        questionnaires = definitions.toList()
    }

    /** Every registered schema — built-in, host-defined and questionnaires. Used by validation and tooling. */
    public val ALL: List<OmhSchemaId> get() = BUILT_IN + customDefinitions.map { it.id }

    /** The definition-driven schemas currently registered: host-defined + questionnaires. */
    public val customDefinitions: List<SchemaDefinition> get() = custom + questionnaires

    /** Definition for a `"namespace:name"` key (host or questionnaire), or null for built-in/unknown schemas. */
    public fun definitionFor(schemaName: String): SchemaDefinition? =
        custom.firstOrNull { it.schemaName == schemaName }
            ?: questionnaires.firstOrNull { it.schemaName == schemaName }

    /** Host definition for a schema id (version ignored), or null. */
    public fun definitionFor(schema: OmhSchemaId): SchemaDefinition? =
        definitionFor("${schema.namespace}:${schema.name}")

    /**
     * FHIR terminology bindings per schema — drives the OMH→FHIR mapping.
     * Values flagged "verify" still need terminology sign-off before clinical use.
     */
    private val TERMINOLOGY: Map<OmhSchemaId, SchemaTerminology> = mapOf(
        HEART_RATE to SchemaTerminology(
            loincCode = "8867-4",                 // LOINC "Heart rate"
            display   = "Heart rate",
            category  = "vital-signs",
            ucumUnit  = "beats/minute",
            ucumCode  = "/min"
        ),
        BODY_TEMPERATURE to SchemaTerminology(
            loincCode = "8310-5",                 // LOINC "Body temperature"
            display   = "Body temperature",
            category  = "vital-signs",
            ucumUnit  = "Cel",
            ucumCode  = "Cel",
            bodySite  = "8205005"                 // SNOMED CT "Wrist region structure" — verify
        ),
        HRV to SchemaTerminology(
            loincCode = null,                     // SDNN/RMSSD → FHIR components, not one code
            display   = "Heart rate variability",
            category  = "vital-signs",
            ucumUnit  = "ms",
            ucumCode  = "ms"
        ),
        SLEEP_EPISODE to SchemaTerminology(
            loincCode = null,                     // coded sleep stage — terminology TBD
            display   = "Sleep stage",
            category  = "activity",
            ucumUnit  = null,
            ucumCode  = null
        ),
        EDA to SchemaTerminology(
            loincCode = null,                     // no LOINC for electrodermal activity
            display   = "Electrodermal activity",
            category  = "vital-signs",
            ucumUnit  = "uS",                     // microsiemens
            ucumCode  = "uS"
        ),
        STIMULUS_EVENT to SchemaTerminology(
            loincCode = null,                     // no LOINC for a protocol stimulus marker
            display   = "Stimulus event",
            category  = "procedure",              // closest observation-category — verify
            ucumUnit  = null,
            ucumCode  = null
        )
    )

    /** Terminology binding for a schema id, or null if none is registered. */
    public fun terminologyFor(schema: OmhSchemaId): SchemaTerminology? =
        TERMINOLOGY[schema] ?: definitionFor(schema)?.terminology

    /**
     * Terminology binding by `"namespace:name"` key — the [schemaName] form used
     * across the pipeline (consent filtering, storage) — ignoring version.
     */
    public fun terminologyFor(schemaName: String): SchemaTerminology? =
        TERMINOLOGY.entries.firstOrNull { (id, _) ->
            "${id.namespace}:${id.name}" == schemaName
        }?.value ?: definitionFor(schemaName)?.terminology
}