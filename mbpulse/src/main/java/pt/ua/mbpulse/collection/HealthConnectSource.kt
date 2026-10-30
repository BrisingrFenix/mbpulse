package pt.ua.mbpulse.collection

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * A host-defined importer for one Health Connect data category.
 *
 * The library's Health Connect import is data-type agnostic: it knows how to
 * drive the encrypt → store → sync pipeline, but not which Health Connect
 * record types a given study cares about. The host **expands** the import by
 * registering one [HealthConnectSource] per category it wants to pull in (heart
 * rate, steps, sleep, …) via
 * [pt.ua.mbpulse.MbPulseConfig.healthConnectSources].
 *
 * Each source declares the Health Connect read permission(s) it needs, reads
 * its own records over the requested window, and maps them to [ProcessedEpoch]s
 * — the same structure a [SignalProcessor] emits — which then flow through the
 * normal encryption and upload path ([EpochSink]).
 *
 * Implementations **must** tag each produced data point's openMHealth
 * `acquisitionProvenance` (e.g. `modality = "self-reported"`,
 * `sourceName = "Health Connect"`) so imported data stays distinguishable from
 * data measured live by the study device.
 */
public interface HealthConnectSource {

    /** Stable identifier, e.g. `"heart-rate"`. Used for selection and dedup. */
    public val id: String

    /** Human-readable label shown in the import UI, e.g. `"Heart rate"`. */
    public val displayName: String

    /** Health Connect read-permission strings this source requires. */
    public val permissions: Set<String>

    /**
     * The Health Connect record types [read] uses, e.g. `setOf(HeartRateRecord::class)`.
     * Enables the background import's change tracking: it asks Health Connect
     * what changed since the last run and re-reads exactly the affected time
     * window — catching data a watch syncs hours late with old timestamps.
     *
     * Empty (default) = no change tracking; the background import then re-reads
     * a recent window each run ([pt.ua.mbpulse.MbPulseConfig.healthConnectBackgroundImport]),
     * which misses data synced later than that window.
     */
    public val recordTypes: Set<KClass<out Record>> get() = emptySet()

    /**
     * The longest time bucket [read] aggregates into (e.g. 1-minute mean HR →
     * 1 minute). Background re-reads are widened to whole multiples of it so a
     * re-read never starts mid-bucket and produces a truncated aggregate.
     * Default 1 hour, which covers any shorter bucket.
     */
    public val readAlignment: Duration get() = Duration.ofHours(1)

    /**
     * Reads this category's records in [since]..[until] and maps them to epochs.
     * Invoked off the main thread. Use [readAll] for the common paginated read.
     */
    public suspend fun read(
        client: HealthConnectClient,
        since: Instant,
        until: Instant
    ): List<ProcessedEpoch>
}

/**
 * Reads every record of [recordType] in [since]..[until], following Health
 * Connect pagination. A convenience for [HealthConnectSource.read] implementations.
 */
public suspend fun <T : Record> HealthConnectClient.readAll(
    recordType: KClass<T>,
    since: Instant,
    until: Instant
): List<T> {
    val out = ArrayList<T>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType      = recordType,
                timeRangeFilter = TimeRangeFilter.between(since, until),
                pageToken       = pageToken
            )
        )
        out += response.records
        pageToken = response.pageToken
    } while (pageToken != null)
    return out
}
