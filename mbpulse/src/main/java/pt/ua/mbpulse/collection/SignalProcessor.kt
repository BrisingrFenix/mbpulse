package pt.ua.mbpulse.collection

import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhEffectiveTimeFrame

/**
 * The output of one completed epoch window from a [SignalProcessor].
 *
 * @param epochStartMs UTC timestamp in milliseconds of the first sample in
 *   the window. Used to key records in the database.
 * @param dataPoints One [OmhDataPoint] per signal type. The library encrypts
 *   and stores each data point as a separate record in Supabase.
 */
public data class ProcessedEpoch(
    val epochStartMs: Long,
    val dataPoints: List<OmhDataPoint>
)

/**
 * Accumulates [SensorSample] values and emits a [ProcessedEpoch] whenever a
 * complete epoch window has been filled.
 *
 * The library calls [feed] for every sample emitted by the [SensorSource].
 * The processor is responsible for:
 *  1. Defining the epoch duration (e.g. 30 seconds).
 *  2. Accumulating signal values across samples within the window.
 *  3. Computing derived statistics at window close (e.g. mean HR, SDNN).
 *  4. Structuring the output as [OmhDataPoint] objects conforming to the
 *     appropriate openMHealth schemas (or custom schemas in the
 *     `mbpulse` namespace).
 *  5. Returning null for every sample that does not complete an epoch.
 *
 * The processor is stateful — it holds the accumulation buffers between
 * calls. The library creates one instance per collection session and discards
 * it when collection ends.
 *
 * ## Thread safety
 * [feed] is called sequentially from a single coroutine on [Dispatchers.IO].
 * No external synchronisation is required.
 *
 * ## Example
 * ```kotlin
 * class HeartRateProcessor : SignalProcessor {
 *     private val EPOCH_MS = 30_000L
 *     private var epochStart: Long? = null
 *     private val hrValues = mutableListOf<Float>()
 *
 *     override fun feed(sample: SensorSample): ProcessedEpoch? {
 *         if (epochStart == null) epochStart = sample.timestampMs
 *         sample.get("hr")?.let { hrValues.add(it) }
 *
 *         if (sample.timestampMs - epochStart!! < EPOCH_MS) return null
 *
 *         val mean = hrValues.average().toFloat()
 *         hrValues.clear()
 *         val start = epochStart!!
 *         epochStart = null
 *
 *         val point = OmhDataPoint(
 *             header = OmhHeader(
 *                 id = UUID.randomUUID().toString(),
 *                 creationDateTime = Instant.now().toString(),
 *                 schemaId = OmhSchemas.HEART_RATE,
 *                 acquisitionProvenance = OmhProvenance("My Device")
 *             ),
 *             body = OmhBody(
 *                 heartRate = OmhUnitValue(mean, "beats/min"),
 *                 effectiveTimeFrame = OmhEffectiveTimeFrame(
 *                     OmhTimeInterval(
 *                         Instant.ofEpochMilli(start).toString(),
 *                         Instant.ofEpochMilli(sample.timestampMs).toString()
 *                     )
 *                 )
 *             )
 *         )
 *         return ProcessedEpoch(start, listOf(point))
 *     }
 * }
 * ```
 */
public interface SignalProcessor {
    /**
     * Process one [SensorSample].
     *
     * @return A [ProcessedEpoch] if this sample completed an epoch window,
     *   or null if the window is still accumulating.
     */
    public fun feed(sample: SensorSample): ProcessedEpoch?

    /**
     * Process one [SensorSample], returning every epoch it produces. This is
     * what the library calls; the default wraps [feed].
     *
     * Override it for **event schemas** (e.g. stimulus triggers): one sample
     * can both close a window and carry a trigger. Emit each trigger as its
     * own [ProcessedEpoch] with `epochStartMs` = the trigger instant and an
     * instant time frame ([OmhEffectiveTimeFrame.instant]) — never hold it
     * until the window closes, never merge triggers:
     * ```kotlin
     * override fun feedAll(sample: SensorSample): List<ProcessedEpoch> {
     *     val trigger = sample.get("marker")?.takeIf { it > 0f }?.let { stimulusEpoch(sample) }
     *     return listOfNotNull(trigger, feed(sample))
     * }
     * ```
     * Event points are never throttled by the study's sampling interval.
     */
    public fun feedAll(sample: SensorSample): List<ProcessedEpoch> = listOfNotNull(feed(sample))
}