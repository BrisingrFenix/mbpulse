package pt.ua.mbpulse.collection

import pt.ua.mbpulse.MbPulseConfig
import pt.ua.mbpulse.MbPulseConfigValidator
import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame
import pt.ua.mbpulse.data.model.OmhHeader
import pt.ua.mbpulse.data.model.OmhProvenance
import pt.ua.mbpulse.data.model.OmhSchemaId
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhTimeInterval
import pt.ua.mbpulse.data.model.OmhUnitValue
import pt.ua.mbpulse.data.model.SchemaDefinition
import pt.ua.mbpulse.data.model.SchemaField
import pt.ua.mbpulse.data.model.SchemaTerminology
import pt.ua.mbpulse.fhir.FhirMapper
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Host-defined schemas ([SchemaDefinition]) — serialization, display, FHIR and validation. */
class CustomSchemaTest {

    // Same configuration EpochEncryptor / OmhImporter use.
    private val writeJson = Json { encodeDefaults = true; explicitNulls = false }
    private val readJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val emg = SchemaDefinition(
        id = OmhSchemaId("mbpulse", "emg", "1.0"),
        terminology = SchemaTerminology(null, "Surface electromyography", "exam", "mV", "mV"),
        fields = listOf(
            SchemaField("emg_rms", FieldKind.QUANTITY, unit = "mV", label = "RMS"),
            SchemaField("emg_peak", FieldKind.QUANTITY, unit = "mV", label = "peak")
        )
    )
    private val painRating = SchemaDefinition(
        id = OmhSchemaId("mbpulse", "pain-rating", "1.0"),
        terminology = SchemaTerminology(null, "Pain intensity", "survey", null, null),
        fields = listOf(
            SchemaField("pain_intensity", FieldKind.NUMBER),
            SchemaField("pain_intensity_max", FieldKind.NUMBER, label = "max")
        )
    )
    private val painThreshold = SchemaDefinition(
        id = OmhSchemaId("mbpulse", "pain-threshold", "1.0"),
        terminology = SchemaTerminology(null, "Pain threshold reached", "survey", null, null),
        fields = listOf(
            SchemaField("threshold_kind", FieldKind.ENUM, values = listOf("pain", "tolerance")),
            SchemaField("threshold_note", FieldKind.TEXT, label = "note")
        ),
        isEvent = true
    )

    private val windowStart = 1_768_516_200_000L          // 2026-01-15T22:30:00Z
    private val windowEnd   = 1_768_516_210_000L

    @Before fun register() = OmhSchemas.register(listOf(emg, painRating, painThreshold))
    @After fun unregister() = OmhSchemas.register(emptyList())

    private fun emgPoint() = emg.dataPoint("PMED replay", windowStart, windowEnd,
        mapOf("emg_rms" to 0.012f, "emg_peak" to 0.085f))

    // ---- serialization ---------------------------------------------------------------

    @Test
    fun `custom fields are written flat into the body and round-trip`() {
        val point = emgPoint()
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), point)
        val body = Json.parseToJsonElement(text).jsonObject["body"]!!.jsonObject
        assertEquals(setOf("emg_rms", "emg_peak", "effective_time_frame"), body.keys)
        assertEquals("mV", body["emg_rms"]!!.jsonObject["unit"]!!.jsonPrimitive.content)
        assertEquals(point, readJson.decodeFromString(OmhDataPoint.serializer(), text))
    }

    @Test
    fun `a custom schema may reuse a built-in field name`() {
        val hr2 = SchemaDefinition(
            id = OmhSchemaId("omh", "heart-rate-v2-test", "2.0"),
            terminology = SchemaTerminology("8867-4", "Heart rate", "vital-signs", "beats/minute", "/min"),
            fields = listOf(SchemaField("heart_rate", FieldKind.QUANTITY, unit = "beats/min"))
        )
        OmhSchemas.register(listOf(hr2))
        val point = hr2.dataPoint("watch", windowStart, windowEnd, mapOf("heart_rate" to 61f))
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), point)
        val decoded = readJson.decodeFromString(OmhDataPoint.serializer(), text)
        assertEquals(point, decoded)
        assertNull(decoded.body.heartRate)                     // stays in the definition's fields
        assertEquals("61", EpochDisplay.summarize(decoded)!!.displayValue)
    }

    @Test
    fun `unregistered schemas keep unknown fields instead of dropping them`() {
        val point = emgPoint()
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), point)
        OmhSchemas.register(emptyList())
        val decoded = readJson.decodeFromString(OmhDataPoint.serializer(), text)
        assertEquals(setOf("emg_rms", "emg_peak"), decoded.body.extra.keys)
        assertNull(EpochDisplay.summarize(decoded))            // unknown → no card, but not lost
    }

    @Test
    fun `built-in bodies serialize exactly as before`() {
        val heartRate = OmhDataPoint(
            OmhHeader("id", "2026-01-15T22:30:30Z", OmhSchemas.HEART_RATE, OmhProvenance("t")),
            OmhBody(
                heartRate = OmhUnitValue(64f, "beats/min"),
                effectiveTimeFrame = OmhEffectiveTimeFrame(OmhTimeInterval("a", "b"))
            )
        )
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), heartRate)
        val body = Json.parseToJsonElement(text).jsonObject["body"]!!.jsonObject
        assertEquals(setOf("heart_rate", "effective_time_frame"), body.keys)
        assertEquals(heartRate, readJson.decodeFromString(OmhDataPoint.serializer(), text))
    }

    @Test
    fun `events get an instant time frame`() {
        val point = painThreshold.dataPoint("app", windowStart, values = mapOf("threshold_kind" to "pain"))
        assertTrue(point.body.effectiveTimeFrame.isInstant)
    }

    @Test
    fun `values are checked against the definition`() {
        val window = OmhEffectiveTimeFrame(OmhTimeInterval("a", "b"))
        assertThrows(IllegalArgumentException::class.java) { emg.body(window, "emg_mean" to 1f) }
        assertThrows(IllegalArgumentException::class.java) { emg.body(window, "emg_rms" to "high") }
        assertThrows(IllegalArgumentException::class.java) {
            painThreshold.body(OmhEffectiveTimeFrame.instant(0), "threshold_kind" to "itchy")
        }
        assertThrows(IllegalArgumentException::class.java) {   // measurement given an instant
            emg.body(OmhEffectiveTimeFrame.instant(0), "emg_rms" to 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {   // measurement without endMs
            emg.dataPoint("x", windowStart, values = mapOf("emg_rms" to 1f))
        }
    }

    // ---- display ----------------------------------------------------------------------

    @Test
    fun `quantity primary shows value, unit and the other fields, and is charted`() {
        val summary = EpochDisplay.summarize(emgPoint())!!
        assertEquals("0.012 · peak 0.085 mV", summary.displayValue)
        assertEquals("mV", summary.unit)
        assertEquals(0.012f, summary.numericValue!!, 1e-6f)
    }

    @Test
    fun `number primary is unitless`() {
        val point = painRating.dataPoint("app", windowStart, windowEnd,
            mapOf("pain_intensity" to 42, "pain_intensity_max" to 61.5))
        val summary = EpochDisplay.summarize(point)!!
        assertEquals("42 · max 61.5", summary.displayValue)
        assertEquals("", summary.unit)
        assertEquals(42f, summary.numericValue!!, 0f)
    }

    @Test
    fun `events are shown but never charted`() {
        val point = painThreshold.dataPoint("app", windowStart,
            values = mapOf("threshold_kind" to "tolerance", "threshold_note" to "T6"))
        val summary = EpochDisplay.summarize(point)!!
        assertEquals("TOLERANCE · note T6", summary.displayValue)
        assertNull(summary.numericValue)
    }

    // ---- FHIR -------------------------------------------------------------------------

    @Test
    fun `fhir uses the definition's terminology, primary value and components`() {
        val obs = FhirMapper.toObservation(emgPoint(), "p-1")
        assertEquals("exam", obs["category"]!!.jsonArray[0].jsonObject["coding"]!!
            .jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        val value = obs["valueQuantity"]!!.jsonObject
        assertEquals("mV", value["code"]!!.jsonPrimitive.content)
        assertEquals("http://unitsofmeasure.org", value["system"]!!.jsonPrimitive.content)
        val component = obs["component"]!!.jsonArray.single().jsonObject
        assertEquals("emg-peak", component["code"]!!.jsonObject["coding"]!!
            .jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("mV", component["valueQuantity"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fhir codes enums and keeps text`() {
        val point = painThreshold.dataPoint("app", windowStart,
            values = mapOf("threshold_kind" to "pain", "threshold_note" to "T6"))
        val obs = FhirMapper.toObservation(point, "p-1")
        assertTrue("effectiveDateTime" in obs)
        assertEquals("pain", obs["valueCodeableConcept"]!!.jsonObject["coding"]!!
            .jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("T6", obs["component"]!!.jsonArray.single().jsonObject["valueString"]!!
            .jsonPrimitive.content)
    }

    // ---- registry + validation --------------------------------------------------------

    @Test
    fun `registered schemas join the registry`() {
        assertTrue(emg.id in OmhSchemas.ALL)
        assertEquals("Surface electromyography", OmhSchemas.terminologyFor("mbpulse:emg")?.display)
        assertEquals(emg, OmhSchemas.definitionFor(OmhSchemaId("mbpulse", "emg", "2.0")))
    }

    private fun config(schemas: List<SchemaDefinition>, mapper: (String) -> String? = { null }) =
        MbPulseConfig(
            supabaseUrl = "https://example.supabase.co",
            supabaseKey = "key",
            sensorSource = { object : SensorSource { override fun samples() = emptyFlow<SensorSample>() } },
            signalProcessor = { object : SignalProcessor { override fun feed(sample: SensorSample) = null } },
            schemaToConsentType = mapper,
            customSchemas = schemas
        )

    @Test
    fun `valid custom schemas pass validation and are probed by the mapper`() {
        val probed = mutableListOf<String>()
        val result = MbPulseConfigValidator.validate(
            config(listOf(emg, painRating, painThreshold)) { probed += it; null })
        assertEquals(emptyList<String>(), result.errors)
        assertTrue("mbpulse:pain-threshold" in probed)
    }

    @Test
    fun `unmapped custom schema is a warning - its points would be dropped`() {
        val result = MbPulseConfigValidator.validate(
            config(listOf(emg, painRating)) { if (it == "mbpulse:emg") "emg" else null })
        assertEquals(emptyList<String>(), result.errors)
        val ratingName = "${painRating.id.namespace}:${painRating.id.name}"
        assertTrue(result.warnings.any { ratingName in it && "dropped" in it })
        assertTrue(result.warnings.none { "'mbpulse:emg'" in it })
    }

    @Test
    fun `invalid custom schemas are reported together`() {
        val bad = listOf(
            emg.copy(fields = listOf(SchemaField("effective_time_frame", FieldKind.TEXT))),
            emg,                                                                  // duplicate id
            painRating.copy(id = OmhSchemas.HRV),                                 // built-in id
            painRating.copy(id = OmhSchemaId("x", "y", "1"), fields = listOf(
                SchemaField("Level", FieldKind.QUANTITY),                         // case + no unit
                SchemaField("kind", FieldKind.ENUM, values = listOf("only"))))    // one value
        )
        val errors = MbPulseConfigValidator.validate(config(bad)).errors
        assertTrue(errors.any { "effective_time_frame" in it && "reserved" in it })
        assertTrue(errors.any { "more than once" in it })
        assertTrue(errors.any { "mbpulse:hrv" in it && "built into the library" in it })
        assertTrue(errors.any { "'Level'" in it && "snake_case" in it })
        assertTrue(errors.any { "'Level'" in it && "without a unit" in it })
        assertTrue(errors.any { "'kind'" in it && "fewer than two" in it })
    }
}
