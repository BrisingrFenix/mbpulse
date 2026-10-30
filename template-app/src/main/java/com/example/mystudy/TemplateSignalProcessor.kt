package com.example.mystudy

import pt.ua.mbpulse.collection.ProcessedEpoch
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SignalProcessor
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame
import pt.ua.mbpulse.data.model.OmhHeader
import pt.ua.mbpulse.data.model.OmhProvenance
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhTimeInterval
import pt.ua.mbpulse.data.model.OmhUnitValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Minimal [SignalProcessor]: accumulates the `hr` signal and emits one
 * openMHealth heart-rate data point per 30-second epoch.
 *
 * This is deliberately the smallest useful processor — the shape to copy when
 * adding this study's real derived measures. For a full multi-signal example
 * (HRV, sleep staging from actigraphy, temperature, EDA) see
 * `E4SignalProcessor` in the sample app.
 *
 * TODO(agent): extend for the study —
 *  1. Accumulate the additional signals your SensorSource emits
 *     (`sample.get("<signal>")`).
 *  2. Emit one OmhDataPoint per measure in [buildEpoch]. Reuse schemas from
 *     [OmhSchemas]; for measures it lacks, declare a SchemaDefinition in the
 *     app (MbPulseConfig.customSchemas) and emit with
 *     `DEFINITION.dataPoint(sourceName, start, end, mapOf(...))` — never edit
 *     the library.
 *  3. Map every emitted schema in schemaToConsentType (StudyConfig.kt) — unmapped
 *     or undeclared schemas are silently dropped before encryption.
 *  4. Set [sourceName] to the real device name (it becomes the OMH
 *     acquisition provenance and the FHIR `device`).
 */
class TemplateSignalProcessor(
    private val epochDurationMs: Long = 30_000L,
    private val sourceName: String = "Simulated sensor (CSV replay)"
) : SignalProcessor {

    private var epochStartMs: Long? = null
    private val hrValues = mutableListOf<Float>()

    private val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override fun feed(sample: SensorSample): ProcessedEpoch? {
        if (epochStartMs == null) epochStartMs = sample.timestampMs

        sample.get("hr")?.let { hrValues.add(it) }

        val elapsed = sample.timestampMs - epochStartMs!!
        if (elapsed < epochDurationMs) return null

        return buildEpoch(sample.timestampMs).also { reset() }
    }

    private fun buildEpoch(epochEndMs: Long): ProcessedEpoch {
        val start  = epochStartMs!!
        val meanHr = if (hrValues.isEmpty()) 0f else hrValues.average().toFloat()

        val heartRatePoint = OmhDataPoint(
            header = OmhHeader(
                id                    = UUID.randomUUID().toString(),
                creationDateTime      = Instant.now().atOffset(ZoneOffset.UTC).format(isoFmt),
                schemaId              = OmhSchemas.HEART_RATE,
                acquisitionProvenance = OmhProvenance(sourceName = sourceName)
            ),
            body = OmhBody(
                heartRate          = OmhUnitValue(meanHr, "beats/min"),
                effectiveTimeFrame = OmhEffectiveTimeFrame(
                    OmhTimeInterval(
                        startDateTime = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).format(isoFmt),
                        endDateTime   = Instant.ofEpochMilli(epochEndMs).atOffset(ZoneOffset.UTC).format(isoFmt)
                    )
                )
            )
        )

        // TODO(agent): add further OmhDataPoints for this study's measures here.
        return ProcessedEpoch(start, listOf(heartRatePoint))
    }

    private fun reset() {
        epochStartMs = null
        hrValues.clear()
    }
}
