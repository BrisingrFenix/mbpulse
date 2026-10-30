package pt.ua.mbpulse.collection

import pt.ua.mbpulse.data.model.OmhDataPoint

/**
 * Enforces the study's per-data-type sampling interval on the epoch stream.
 *
 * The study definition may set `samplingSeconds` on a data type ("record heart
 * rate at most every 60 s"); this gate keeps the first data point of each
 * schema per interval and drops the rest, keyed on the epoch's own start
 * timestamp. Schemas whose consent type has no configured interval (or that
 * map to no consent type) pass unthrottled — the processor's natural cadence
 * applies.
 *
 * Because the study configures *data types* while the pipeline carries *OMH
 * schemas*, the same `schemaToConsentType` mapping used for consent filtering
 * links the two. Pure JVM and stateful per session — create one per
 * collection/import session, like [EpochEncryptor].
 *
 * Note: this throttles how often a point is **recorded**; the point's content
 * is still whatever window statistic the processor computed (e.g. a 30-s mean
 * kept once per 5 min). Processors that should aggregate over the longer
 * window natively must be configured with that window by the host.
 */
internal class SamplingGate(
    samplingSecondsByTypeId: Map<String, Int>,
    private val schemaToConsentType: (schemaName: String) -> String?
) {
    private val intervalMsByTypeId = samplingSecondsByTypeId.mapValues { it.value * 1_000L }

    /** Resolved interval per schema; [NO_LIMIT] = pass everything. */
    private val intervalMsBySchema = HashMap<String, Long>()
    private val lastKeptMsBySchema = HashMap<String, Long>()

    /**
     * True if [point] at [epochStartMs] should be recorded. Events (an instant
     * time frame, e.g. stimulus triggers) always pass — each one is a distinct
     * marker, so throttling would silently lose it.
     */
    fun allow(point: OmhDataPoint, epochStartMs: Long): Boolean {
        if (point.body.effectiveTimeFrame.isInstant) return true
        val schemaId = point.header.schemaId
        return allow("${schemaId.namespace}:${schemaId.name}", epochStartMs)
    }

    /** True if a point of [schemaName] at [epochStartMs] should be recorded. */
    fun allow(schemaName: String, epochStartMs: Long): Boolean {
        val intervalMs = intervalMsBySchema.getOrPut(schemaName) {
            schemaToConsentType(schemaName)
                ?.let { typeId -> intervalMsByTypeId[typeId] }
                ?: NO_LIMIT
        }
        if (intervalMs == NO_LIMIT) return true

        val lastKeptMs = lastKeptMsBySchema[schemaName]
        if (lastKeptMs != null && epochStartMs - lastKeptMs < intervalMs) return false

        lastKeptMsBySchema[schemaName] = epochStartMs
        return true
    }

    private companion object {
        const val NO_LIMIT = -1L
    }
}
