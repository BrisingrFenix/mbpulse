package pt.ua.mbpulse.fhir

import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhSchemaId
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhUnitValue
import pt.ua.mbpulse.data.model.SchemaDefinition
import pt.ua.mbpulse.data.model.SchemaField
import pt.ua.mbpulse.data.model.SchemaTerminology
import pt.ua.mbpulse.data.model.Ucum
import pt.ua.mbpulse.data.model.UcumUnit
import pt.ua.mbpulse.data.model.valuesIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure projection of an [OmhDataPoint] to a FHIR R4 `Observation` (as JSON).
 *
 * The OMH records remain the canonical on-device/at-rest format; this is a
 * read-only view for interoperability. Coded vocabulary and units come from
 * [OmhSchemas.terminologyFor] (authoritative), falling back to [Ucum.normalize]
 * for unit strings not covered there. `subject` is built from the participant
 * pseudo-id and `device` from the OMH `acquisition_provenance.source_name`.
 *
 * The FHIR Vital Signs profile is claimed (`meta.profile`) only for the true
 * vital signs — heart rate and body temperature — i.e. schemas that carry a
 * LOINC code and the `vital-signs` category.
 */
public object FhirMapper {

    private const val LOINC_SYSTEM     = "http://loinc.org"
    private const val UCUM_SYSTEM      = "http://unitsofmeasure.org"
    private const val SNOMED_SYSTEM    = "http://snomed.info/sct"
    private const val CATEGORY_SYSTEM  = "http://terminology.hl7.org/CodeSystem/observation-category"
    private const val VITALSIGNS_PROFILE = "http://hl7.org/fhir/StructureDefinition/vitalsigns"

    /** Local namespaces for codes that have no standard (LOINC/SNOMED) coding. */
    private const val IDENTIFIER_SYSTEM = "urn:mbpulse:omh-datapoint"
    private const val LOCAL_CODE_SYSTEM = "https://mbpulse/fhir/CodeSystem/observation-codes"

    private val prettyJson = Json { prettyPrint = true }

    /** Maps [point] to a FHIR `Observation` resource. */
    public fun toObservation(point: OmhDataPoint, participantPseudoId: String): JsonObject {
        val header     = point.header
        val body       = point.body
        val schemaId   = header.schemaId
        val term       = OmhSchemas.terminologyFor(schemaId)
        val display    = term?.display ?: schemaId.name

        return buildJsonObject {
            put("resourceType", "Observation")

            // Claim the Vital Signs profile only for the formal vital signs.
            if (isVitalSign(term)) {
                putJsonObject("meta") {
                    putJsonArray("profile") { add(VITALSIGNS_PROFILE) }
                }
            }

            putJsonArray("identifier") {
                add(buildJsonObject {
                    put("system", IDENTIFIER_SYSTEM)
                    put("value", header.id)
                })
            }

            put("status", "final")

            term?.category?.let { category ->
                putJsonArray("category") {
                    add(codeableConcept(CATEGORY_SYSTEM, category, categoryDisplay(category)))
                }
            }

            put("code", codeFor(term, schemaId, display))

            putJsonObject("subject") { put("reference", "Patient/$participantPseudoId") }
            putJsonObject("device")  { put("display", header.acquisitionProvenance.sourceName) }
            putJsonObject("method")  { put("text", header.acquisitionProvenance.modality) }

            // effective[x]: an instant (event) → effectiveDateTime, a window → effectivePeriod.
            val timeFrame = body.effectiveTimeFrame
            timeFrame.dateTime?.let { put("effectiveDateTime", it) }
            timeFrame.timeInterval?.let { interval ->
                putJsonObject("effectivePeriod") {
                    put("start", interval.startDateTime)
                    put("end", interval.endDateTime)
                }
            }
            put("issued", header.creationDateTime)

            val definition = OmhSchemas.definitionFor(schemaId)
            if (definition != null) attachDefinedValue(definition, body, term)
            else attachValue(body, term)

            term?.bodySite?.let { bodySite ->
                put("bodySite", codeableConcept(SNOMED_SYSTEM, bodySite, display = null))
            }
        }
    }

    /** Maps [point] to a FHIR `Observation` and serializes it to pretty JSON. */
    public fun toObservationJson(point: OmhDataPoint, participantPseudoId: String): String =
        prettyJson.encodeToString(JsonObject.serializer(), toObservation(point, participantPseudoId))

    // -----------------------------------------------------------------------
    // value[x] — depends on the measurement shape
    // -----------------------------------------------------------------------

    private fun kotlinx.serialization.json.JsonObjectBuilder.attachValue(
        body: OmhBody,
        term: SchemaTerminology?
    ) {
        when {
            // Single-quantity vital signs.
            body.heartRate != null       -> put("valueQuantity", quantity(body.heartRate, term))
            body.bodyTemperature != null -> put("valueQuantity", quantity(body.bodyTemperature, term))

            // HRV — two derived statistics → FHIR components.
            body.sdnn != null || body.rmssd != null -> putJsonArray("component") {
                body.sdnn?.let  { add(component("sdnn",  "SDNN",  it, term)) }
                body.rmssd?.let { add(component("rmssd", "RMSSD", it, term)) }
            }

            // EDA — mean is the primary value; spread/min/max as components.
            body.meanEda != null -> {
                put("valueQuantity", quantity(body.meanEda, term))
                if (body.sdEda != null || body.minEda != null || body.maxEda != null) {
                    putJsonArray("component") {
                        body.sdEda?.let  { add(component("sd-eda",  "SD EDA",  it, term)) }
                        body.minEda?.let { add(component("min-eda", "Min EDA", it, term)) }
                        body.maxEda?.let { add(component("max-eda", "Max EDA", it, term)) }
                    }
                }
            }

            // Sleep stage — coded value, with the movement index as a component.
            body.sleepStage != null -> {
                put("valueCodeableConcept",
                    codeableConcept(LOCAL_CODE_SYSTEM, body.sleepStage, body.sleepStage))
                body.movementIndex?.let { mi ->
                    putJsonArray("component") {
                        add(buildJsonObject {
                            put("code", codeableConcept(LOCAL_CODE_SYSTEM, "movement-index", "Movement index"))
                            putJsonObject("valueQuantity") { put("value", mi) }
                        })
                    }
                }
            }

            // Stimulus event — coded stimulus type; label and intensity as components.
            body.stimulusType != null -> {
                val type = body.stimulusType.code
                put("valueCodeableConcept", codeableConcept(LOCAL_CODE_SYSTEM, type, type))
                if (body.stimulusLabel != null || body.stimulusIntensity != null) {
                    putJsonArray("component") {
                        body.stimulusLabel?.let { label ->
                            add(buildJsonObject {
                                put("code", codeableConcept(LOCAL_CODE_SYSTEM, "stimulus-label", "Stimulus label"))
                                put("valueString", label)
                            })
                        }
                        body.stimulusIntensity?.let { intensity ->
                            add(buildJsonObject {
                                put("code", codeableConcept(LOCAL_CODE_SYSTEM, "stimulus-intensity", "Stimulus intensity"))
                                putJsonObject("valueQuantity") { put("value", intensity) }
                            })
                        }
                    }
                }
            }
        }
    }

    /**
     * Host-defined schema ([SchemaDefinition]): the primary (first) field is
     * `value[x]`, every other present field a `component` coded by its name.
     * QUANTITY → `valueQuantity` (UCUM from the terminology for the primary
     * value, else from the field's unit), NUMBER → unitless `valueQuantity`,
     * ENUM → `valueCodeableConcept`, TEXT → `valueString`.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.attachDefinedValue(
        definition: SchemaDefinition,
        body: OmhBody,
        term: SchemaTerminology?
    ) {
        val values = definition.valuesIn(body)
        values.firstOrNull { (f, _) -> f == definition.primary }?.let { (f, element) ->
            definedValue(f, element, term)?.let { (key, value) -> put(key, value) }
        }
        val components = values.filter { (f, _) -> f != definition.primary }
        if (components.isNotEmpty()) putJsonArray("component") {
            components.forEach { (f, element) ->
                val (key, value) = definedValue(f, element, term = null) ?: return@forEach
                add(buildJsonObject {
                    put("code", codeableConcept(LOCAL_CODE_SYSTEM, f.name.replace('_', '-'), f.label))
                    put(key, value)
                })
            }
        }
    }

    /** `value[x]` key and value for one defined field, or null if the stored value has the wrong shape. */
    private fun definedValue(
        field: SchemaField,
        element: JsonElement,
        term: SchemaTerminology?
    ): Pair<String, JsonElement>? = when (field.kind) {
        FieldKind.QUANTITY -> field.numberIn(element)?.let { number ->
            "valueQuantity" to quantity(OmhUnitValue(number, field.unitIn(element).orEmpty()), term)
        }
        FieldKind.NUMBER -> field.numberIn(element)?.let { number ->
            "valueQuantity" to buildJsonObject { put("value", number) }
        }
        FieldKind.ENUM -> field.textIn(element)?.let { code ->
            "valueCodeableConcept" to codeableConcept(LOCAL_CODE_SYSTEM, code, code)
        }
        FieldKind.TEXT -> field.textIn(element)?.let { "valueString" to JsonPrimitive(it) }
    }

    private fun component(
        code: String,
        display: String,
        value: OmhUnitValue,
        term: SchemaTerminology?
    ): JsonObject = buildJsonObject {
        put("code", codeableConcept(LOCAL_CODE_SYSTEM, code, display))
        put("valueQuantity", quantity(value, term))
    }

    private fun quantity(value: OmhUnitValue, term: SchemaTerminology?): JsonObject {
        val ucum = termUcum(term) ?: Ucum.normalize(value.unit)
        return buildJsonObject {
            put("value", value.value)
            if (ucum != null) {
                put("unit", ucum.unit)
                put("system", UCUM_SYSTEM)
                put("code", ucum.code)
            } else {
                // Unmapped unit — keep the raw OMH unit string so nothing is lost.
                put("unit", value.unit)
            }
        }
    }

    // -----------------------------------------------------------------------
    // code / CodeableConcept helpers
    // -----------------------------------------------------------------------

    private fun codeFor(term: SchemaTerminology?, schemaId: OmhSchemaId, display: String): JsonObject =
        if (term?.loincCode != null) {
            codeableConcept(LOINC_SYSTEM, term.loincCode, term.display, text = term.display)
        } else {
            // No LOINC — fall back to a local code derived from the OMH schema id.
            codeableConcept("$LOCAL_CODE_SYSTEM/${schemaId.namespace}", schemaId.name, display, text = display)
        }

    private fun codeableConcept(
        system: String,
        code: String,
        display: String?,
        text: String? = null
    ): JsonObject = buildJsonObject {
        putJsonArray("coding") {
            add(buildJsonObject {
                put("system", system)
                put("code", code)
                if (display != null) put("display", display)
            })
        }
        if (text != null) put("text", text)
    }

    private fun termUcum(term: SchemaTerminology?): UcumUnit? =
        term?.ucumCode?.let { UcumUnit(term.ucumUnit ?: it, it) }

    private fun isVitalSign(term: SchemaTerminology?): Boolean =
        term != null && term.loincCode != null && term.category == "vital-signs"

    private fun categoryDisplay(category: String): String = when (category) {
        "vital-signs" -> "Vital Signs"
        "activity"    -> "Activity"
        "laboratory"  -> "Laboratory"
        "procedure"   -> "Procedure"
        else          -> category.replaceFirstChar { it.uppercase() }
    }
}
