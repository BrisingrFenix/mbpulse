package pt.ua.mbpulse.collection

import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame
import pt.ua.mbpulse.data.model.OmhHeader
import pt.ua.mbpulse.data.model.OmhProvenance
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhTimeInterval
import pt.ua.mbpulse.data.model.OmhUnitValue
import pt.ua.mbpulse.data.model.StimulusType
import pt.ua.mbpulse.fhir.FhirMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event schemas (instant time frame) — the stimulus-trigger path end to end, minus crypto/Room. */
class StimulusEventTest {

    // Same configuration EpochEncryptor / OmhImporter use.
    private val writeJson = Json { encodeDefaults = true; explicitNulls = false }
    private val readJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val triggerMs = 1_768_516_230_000L          // 2026-01-15T22:30:30Z

    private fun header(schema: pt.ua.mbpulse.data.model.OmhSchemaId) = OmhHeader(
        id = "id-1", creationDateTime = "2026-01-15T22:30:30Z", schemaId = schema,
        acquisitionProvenance = OmhProvenance("test")
    )

    private val stimulus = OmhDataPoint(
        header = header(OmhSchemas.STIMULUS_EVENT),
        body = OmhBody(
            stimulusType = StimulusType.AUDITORY,
            stimulusLabel = "trial-3",
            stimulusIntensity = 2f,
            effectiveTimeFrame = OmhEffectiveTimeFrame.instant(triggerMs)
        )
    )

    private val heartRate = OmhDataPoint(
        header = header(OmhSchemas.HEART_RATE),
        body = OmhBody(
            heartRate = OmhUnitValue(64f, "beats/min"),
            effectiveTimeFrame = OmhEffectiveTimeFrame(
                OmhTimeInterval("2026-01-15T22:30:00Z", "2026-01-15T22:30:30Z")
            )
        )
    )

    @Test
    fun `instant serializes as date_time only and round-trips`() {
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), stimulus)
        val timeFrame = Json.parseToJsonElement(text).jsonObject["body"]!!
            .jsonObject["effective_time_frame"]!!.jsonObject
        assertEquals(setOf("date_time"), timeFrame.keys)
        assertEquals("2026-01-15T22:30:30Z", timeFrame["date_time"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"stimulus_type\": \"auditory\"") || text.contains("\"stimulus_type\":\"auditory\""))

        assertEquals(stimulus, readJson.decodeFromString(OmhDataPoint.serializer(), text))
    }

    @Test
    fun `interval records serialize exactly as before`() {
        val text = writeJson.encodeToString(OmhDataPoint.serializer(), heartRate)
        val timeFrame = Json.parseToJsonElement(text).jsonObject["body"]!!
            .jsonObject["effective_time_frame"]!!.jsonObject
        assertEquals(setOf("time_interval"), timeFrame.keys)
        assertFalse(timeFrame["time_interval"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `time frame needs exactly one of interval or instant`() {
        assertThrows(IllegalArgumentException::class.java) { OmhEffectiveTimeFrame() }
        assertThrows(IllegalArgumentException::class.java) {
            OmhEffectiveTimeFrame(OmhTimeInterval("a", "b"), "c")
        }
    }

    @Test
    fun `fhir maps an instant to effectiveDateTime with coded stimulus`() {
        val obs = FhirMapper.toObservation(stimulus, "p-1")
        assertEquals("2026-01-15T22:30:30Z", obs["effectiveDateTime"]!!.jsonPrimitive.content)
        assertNull(obs["effectivePeriod"])
        val coding = obs["valueCodeableConcept"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject
        assertEquals("auditory", coding["code"]!!.jsonPrimitive.content)
        assertEquals(2, obs["component"]!!.jsonArray.size)      // label + intensity
    }

    @Test
    fun `fhir keeps effectivePeriod for interval records`() {
        val obs = FhirMapper.toObservation(heartRate, "p-1")
        assertNull(obs["effectiveDateTime"])
        assertEquals("2026-01-15T22:30:00Z",
            obs["effectivePeriod"]!!.jsonObject["start"]!!.jsonPrimitive.content)
    }

    @Test
    fun `events bypass the sampling interval, measurements do not`() {
        val mapper: (String) -> String? = { "stimuli" }        // everything → one throttled type
        val gate = SamplingGate(mapOf("stimuli" to 60), mapper)
        assertTrue(gate.allow(stimulus, 0))
        assertTrue(gate.allow(stimulus, 1_000))                // 1 s later — still kept
        assertTrue(gate.allow(stimulus, 1_001))
        assertTrue(gate.allow(heartRate, 0))
        assertFalse(gate.allow(heartRate, 1_000))              // measurement throttled
    }

    @Test
    fun `stimulus displays its type and is never charted`() {
        val summary = EpochDisplay.summarize(stimulus)!!
        // Display formatting follows the device locale (2.0 / 2,0), like the other cards.
        assertEquals("AUDITORY · trial-3 · intensity ${"%.1f".format(2f)}", summary.displayValue)
        assertNull(summary.numericValue)
    }

    @Test
    fun `feedAll defaults to feed`() {
        val epoch = ProcessedEpoch(0, listOf(heartRate))
        val processor = object : SignalProcessor {
            override fun feed(sample: SensorSample) = if (sample.timestampMs > 0) epoch else null
        }
        assertEquals(emptyList<ProcessedEpoch>(), processor.feedAll(SensorSample(0, emptyMap())))
        assertEquals(listOf(epoch), processor.feedAll(SensorSample(1, emptyMap())))
    }
}
