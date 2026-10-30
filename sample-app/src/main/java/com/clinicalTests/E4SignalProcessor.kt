package com.clinicalTests

import android.util.Log
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame
import pt.ua.mbpulse.data.model.OmhHeader
import pt.ua.mbpulse.data.model.OmhProvenance
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.OmhTimeInterval
import pt.ua.mbpulse.data.model.OmhUnitValue
import pt.ua.mbpulse.collection.ProcessedEpoch
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.collection.SignalProcessor
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "E4SignalProcessor"

/**
 * Reference [SignalProcessor] for the Empatica E4 wristband.
 *
 * Consumes the following signal names from [SensorSample.signals]:
 *   bvp, acc_x, acc_y, acc_z, temp, eda, hr, ibi
 *
 * Produces one [ProcessedEpoch] every [epochDurationMs] milliseconds (default 30 s)
 * containing up to five openMHealth data points:
 *   - omh:heart-rate v1.1          (mean HR in bpm)
 *   - mbpulse:hrv v1.0       (SDNN and RMSSD in ms)
 *   - omh:sleep-episode v1.0       (awake/sleeping + movement index)
 *   - omh:body-temperature v2.0    (mean skin temperature in °C, if available)
 *   - mbpulse:electrodermal-activity v1.0 (mean/sd/min/max EDA in μS, if available)
 *
 * This class is provided as a reference implementation. Researchers using
 * other devices or requiring different derived statistics should write their
 * own [SignalProcessor] implementation.
 */
class E4SignalProcessor(
    private val epochDurationMs: Long = 30_000L,
    private val sourceName: String    = "Empatica E4",
    private val wakeThreshold: Float  = 0.05f
) : SignalProcessor {

    private var epochStartMs: Long? = null
    private val hrValues  = mutableListOf<Float>()
    private val ibiValues = mutableListOf<Float>()
    private val accMag    = mutableListOf<Float>()
    private val tempValues = mutableListOf<Float>()
    private val edaValues  = mutableListOf<Float>()
    private var samplesInWindow = 0

    private val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override fun feed(sample: SensorSample): ProcessedEpoch? {
        if (epochStartMs == null) {
            epochStartMs = sample.timestampMs
            Log.d(TAG, "Epoch started at ${sample.timestampMs}")
        }

        samplesInWindow++

        sample.get("hr")?.let  { hrValues.add(it) }
        sample.get("ibi")?.let { ibiValues.add(it) }
        sample.get("temp")?.let { tempValues.add(it) }
        sample.get("eda")?.let  { edaValues.add(it) }

        val ax = sample.get("acc_x") ?: 0f
        val ay = sample.get("acc_y") ?: 0f
        val az = sample.get("acc_z") ?: 0f
        accMag.add(sqrt(ax * ax + ay * ay + az * az))

        val elapsed = sample.timestampMs - epochStartMs!!
        if (elapsed < epochDurationMs) return null

        return buildEpoch(sample.timestampMs).also { reset() }
    }

    private fun buildEpoch(epochEndMs: Long): ProcessedEpoch {
        val start = epochStartMs!!
        val tf    = timeFrame(start, epochEndMs)
        val points = mutableListOf<OmhDataPoint>()

        // Heart rate
        val meanHr = if (hrValues.isEmpty()) 0f else hrValues.average().toFloat()
        points.add(OmhDataPoint(
            header = header(OmhSchemas.HEART_RATE),
            body   = OmhBody(
                heartRate          = OmhUnitValue(meanHr, "beats/min"),
                effectiveTimeFrame = tf
            )
        ))

        // HRV
        val sdnn  = sdnn(ibiValues)
        val rmssd = rmssd(ibiValues)
        points.add(OmhDataPoint(
            header = header(OmhSchemas.HRV),
            body   = OmhBody(
                sdnn               = OmhUnitValue(sdnn,  "ms"),
                rmssd              = OmhUnitValue(rmssd, "ms"),
                effectiveTimeFrame = tf
            )
        ))

        // Sleep staging from actigraphy
        val meanMag   = if (accMag.isEmpty()) 0f else accMag.average().toFloat()
        val movement  = if (accMag.isEmpty()) 0f
        else accMag.map { abs(it - meanMag) }.average().toFloat()
        val stage     = if (movement > wakeThreshold) "awake" else "sleeping"
        points.add(OmhDataPoint(
            header = header(OmhSchemas.SLEEP_EPISODE),
            body   = OmhBody(
                sleepStage         = stage,
                movementIndex      = movement,
                effectiveTimeFrame = tf
            )
        ))

        // Temperature (optional)
        if (tempValues.isNotEmpty()) {
            points.add(OmhDataPoint(
                header = header(OmhSchemas.BODY_TEMPERATURE),
                body   = OmhBody(
                    bodyTemperature     = OmhUnitValue(tempValues.average().toFloat(), "C"),
                    measurementLocation = "wrist",
                    effectiveTimeFrame  = tf
                )
            ))
        }

        // EDA (optional)
        if (edaValues.isNotEmpty()) {
            val meanEda = edaValues.average().toFloat()
            val sdEda   = if (edaValues.size > 1) {
                val m = edaValues.average()
                sqrt(edaValues.map { (it - m) * (it - m) }.average()).toFloat()
            } else 0f
            points.add(OmhDataPoint(
                header = header(OmhSchemas.EDA),
                body   = OmhBody(
                    meanEda            = OmhUnitValue(meanEda,              "μS"),
                    sdEda              = OmhUnitValue(sdEda,                "μS"),
                    minEda             = OmhUnitValue(edaValues.min(),      "μS"),
                    maxEda             = OmhUnitValue(edaValues.max(),      "μS"),
                    effectiveTimeFrame = tf
                )
            ))
        }

        Log.d(TAG, "Epoch built — ${points.size} data points, stage=$stage, HR=$meanHr")
        return ProcessedEpoch(start, points)
    }

    // -------------------------------------------------------------------------

    private fun header(schemaId: pt.ua.mbpulse.data.model.OmhSchemaId) = OmhHeader(
        id                    = UUID.randomUUID().toString(),
        creationDateTime      = Instant.now().atOffset(ZoneOffset.UTC).format(isoFmt),
        schemaId              = schemaId,
        acquisitionProvenance = OmhProvenance(sourceName = sourceName)
    )

    private fun timeFrame(startMs: Long, endMs: Long) = OmhEffectiveTimeFrame(
        OmhTimeInterval(
            startDateTime = Instant.ofEpochMilli(startMs).atOffset(ZoneOffset.UTC).format(isoFmt),
            endDateTime   = Instant.ofEpochMilli(endMs).atOffset(ZoneOffset.UTC).format(isoFmt)
        )
    )

    private fun sdnn(ibi: List<Float>): Float {
        if (ibi.size < 2) return 0f
        val mean = ibi.average()
        return sqrt(ibi.map { (it - mean) * (it - mean) }.average()).toFloat()
    }

    private fun rmssd(ibi: List<Float>): Float {
        if (ibi.size < 2) return 0f
        val diffs = ibi.zipWithNext { a, b -> (b - a) * (b - a) }
        return sqrt(diffs.average()).toFloat()
    }

    private fun reset() {
        epochStartMs    = null
        samplesInWindow = 0
        hrValues.clear()
        ibiValues.clear()
        accMag.clear()
        tempValues.clear()
        edaValues.clear()
    }
}