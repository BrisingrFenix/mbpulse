package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val TAG = "HCBackgroundImport"

/**
 * Periodic background import from Health Connect — data from watches that
 * write to Health Connect flows in without the participant opening the import
 * screen. Runs every [pt.ua.mbpulse.MbPulseConfig.healthConnectBackgroundImport]
 * while the participant is enrolled ([EnrollmentStore]).
 *
 * Per source, with change tracking ([HealthConnectSource.recordTypes]):
 *  1. First run: take a Health Connect changes token and import the last
 *     [INITIAL_LOOKBACK] (older history is the import screen's job).
 *  2. Later runs: ask Health Connect what was inserted/updated since the token
 *     and re-read the time window those records cover (widened to
 *     [HealthConnectSource.readAlignment]) — so data a watch syncs hours late,
 *     with old timestamps, is still picked up.
 *  3. Expired token (unused for ~30 days): new token, re-read since the last
 *     successful run.
 * Sources without record types are polled: the last [POLL_LOOKBACK] each run.
 *
 * Everything goes through [HealthConnectImporter] → [EpochSink], i.e. consent
 * filter, sampling gate, encryption and upload as for any other data. Re-reads
 * overlap freely: the local unique key (participant, epoch, schema) drops
 * epochs already stored. The token is saved only after its window was stored,
 * so a failed run retries the same changes.
 *
 * Deleted Health Connect records are not propagated — data already uploaded
 * stays (the study keeps what was consented and collected).
 */
@HiltWorker
internal class HealthConnectBackgroundImport @AssistedInject internal constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val keystoreManager: KeystoreManager,
    private val encryptedRecordDao: EncryptedRecordDao,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = MbPulseLibrary.config
        val sources = config.healthConnectSources
        if (config.healthConnectBackgroundImport == null || sources.isEmpty()) return Result.success()
        val enrollment = EnrollmentStore.load(applicationContext) ?: return Result.success()
        if (StudyEnd.endedAtMs(applicationContext) != null || DeviceReplaced.isReplaced(applicationContext)) return Result.success()

        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            Log.i(TAG, "Health Connect not available — skipping")
            return Result.success()
        }
        val client = HealthConnectClient.getOrCreate(applicationContext)
        if (!backgroundReadAvailable(client)) {
            Log.i(TAG, "This Health Connect version cannot read in the background — skipping")
            return Result.success()
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            Log.i(TAG, "Background-read permission not granted (import screen requests it) — skipping")
            return Result.success()
        }

        val importer = HealthConnectImporter(
            context         = applicationContext,
            keystoreManager = keystoreManager,
            epochSink       = EpochSink(
                encryptedRecordDao, displayEpochDao, epochHistoryDao,
                samplingSecondsByTypeId = enrollment.samplingSecondsByTypeId
            )
        )

        var failed = false
        for (source in sources) {
            if (!granted.containsAll(source.permissions)) {
                Log.i(TAG, "Source '${source.id}': read permission not granted — skipping")
                continue
            }
            try {
                if (source.recordTypes.isEmpty()) poll(source, importer, enrollment)
                else importChanges(client, source, importer, enrollment)
            } catch (e: Exception) {
                Log.e(TAG, "Source '${source.id}' failed: ${e::class.simpleName} — ${e.message}", e)
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    private suspend fun importChanges(
        client: HealthConnectClient,
        source: HealthConnectSource,
        importer: HealthConnectImporter,
        enrollment: Enrollment,
    ) {
        val now = Instant.now()
        val ctx = applicationContext
        val typesSignature = source.recordTypes.map { it.qualifiedName ?: it.toString() }.sorted().joinToString(",")
        // A changed recordTypes set invalidates the token (it tracks the old set).
        val token = EnrollmentStore.getString(ctx, tokenKey(source))
            ?.takeIf { EnrollmentStore.getString(ctx, typesKey(source)) == typesSignature }

        if (token == null) {
            val fresh = client.getChangesToken(ChangesTokenRequest(source.recordTypes))
            importWindow(importer, enrollment, source, now - INITIAL_LOOKBACK, now)
            saveProgress(source, fresh, typesSignature, now)
            Log.d(TAG, "Source '${source.id}': change tracking started")
            return
        }

        val spans = mutableListOf<Pair<Instant, Instant>>()
        var undated = 0
        var next: String = token
        var expired = false
        do {
            val response = client.getChanges(next)
            if (response.changesTokenExpired) { expired = true; break }
            response.changes.filterIsInstance<UpsertionChange>().forEach { change ->
                span(change.record)?.let { spans += it } ?: undated++
            }
            next = response.nextChangesToken
        } while (response.hasMore)

        if (expired) {
            val fresh = client.getChangesToken(ChangesTokenRequest(source.recordTypes))
            val since = EnrollmentStore.getLong(ctx, lastRunKey(source))
                ?.let { Instant.ofEpochMilli(it) } ?: (now - TOKEN_LIFETIME)
            Log.w(TAG, "Source '${source.id}': changes token expired — re-reading since $since")
            importWindow(importer, enrollment, source, since, now)
            saveProgress(source, fresh, typesSignature, now)
            return
        }

        // A changed record without a readable time: cover everything since the
        // last run (overlap is harmless — duplicates are dropped).
        if (undated > 0) {
            val lastRun = EnrollmentStore.getLong(ctx, lastRunKey(source))
                ?.let { Instant.ofEpochMilli(it) } ?: (now - INITIAL_LOOKBACK)
            spans += lastRun to now
        }

        changedWindow(spans, source.readAlignment, now)?.let { (since, until) ->
            Log.d(TAG, "Source '${source.id}': ${spans.size} changed record(s) → re-reading $since..$until")
            importWindow(importer, enrollment, source, since, until)
        }
        saveProgress(source, next, typesSignature, now)
    }

    private suspend fun poll(source: HealthConnectSource, importer: HealthConnectImporter, enrollment: Enrollment) {
        val now = Instant.now()
        val since = alignDown(now - POLL_LOOKBACK, source.readAlignment)
        importWindow(importer, enrollment, source, since, now)
        EnrollmentStore.put(applicationContext, longs = mapOf(lastRunKey(source) to now.toEpochMilli()))
    }

    private suspend fun importWindow(
        importer: HealthConnectImporter,
        enrollment: Enrollment,
        source: HealthConnectSource,
        since: Instant,
        until: Instant,
    ) {
        val result = importer.import(
            participantPseudoId = enrollment.participantPseudoId,
            studyId             = enrollment.studyId,
            wrappedDekBytes     = enrollment.wrappedDekBytes,
            piPublicKeyBytes    = enrollment.piPublicKeyBytes,
            consentedTypeIds    = enrollment.consentedTypeIds,
            since               = since,
            until               = until,
            sourceIds           = setOf(source.id),
        )
        when (result) {
            is HealthConnectImportResult.Success ->
                Log.d(TAG, "Source '${source.id}': ${result.epochs} epochs, ${result.records} new records")
            is HealthConnectImportResult.Failed -> error(result.message)   // → retry, token not advanced
            else -> Log.i(TAG, "Source '${source.id}': $result — skipped")
        }
    }

    private suspend fun saveProgress(source: HealthConnectSource, token: String, types: String, now: Instant) {
        EnrollmentStore.put(
            applicationContext,
            strings = mapOf(tokenKey(source) to token, typesKey(source) to types),
            longs   = mapOf(lastRunKey(source) to now.toEpochMilli()),
        )
    }

    companion object {
        private const val WORK_NAME = "health_connect_background_import"
        private val INITIAL_LOOKBACK = Duration.ofDays(1)
        private val POLL_LOOKBACK    = Duration.ofHours(6)
        private val TOKEN_LIFETIME   = Duration.ofDays(30)

        private fun tokenKey(s: HealthConnectSource)   = "hc_token_${s.id}"
        private fun typesKey(s: HealthConnectSource)   = "hc_types_${s.id}"
        private fun lastRunKey(s: HealthConnectSource) = "hc_last_run_${s.id}"

        /** Schedules the periodic import (or cancels it when not configured). */
        fun schedule(context: Context) {
            val config = MbPulseLibrary.config
            val interval = config.healthConnectBackgroundImport
            if (interval == null || config.healthConnectSources.isEmpty()) {
                cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<HealthConnectBackgroundImport>(
                interval.toMinutes(), TimeUnit.MINUTES
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            // UPDATE: a changed interval in a new app version takes effect.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** True if Health Connect on this device supports reads from the background. */
        fun backgroundReadAvailable(client: HealthConnectClient): Boolean = runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)

        /**
         * The time span a record covers, or null if it exposes none.
         *
         * Health Connect's `IntervalRecord` / `InstantaneousRecord` interfaces
         * are Kotlin-internal, but every concrete record class has public
         * `getStartTime`/`getEndTime` (interval and series records) or
         * `getTime` (instantaneous records) — read those.
         */
        private fun span(record: Record): Pair<Instant, Instant>? {
            fun instant(getter: String): Instant? = runCatching {
                record.javaClass.getMethod(getter).invoke(record) as? Instant
            }.getOrNull()
            instant("getStartTime")?.let { start -> return start to (instant("getEndTime") ?: start) }
            instant("getTime")?.let { return it to it }
            return null
        }

        /**
         * The window to re-read for records spanning [spans]: from the earliest
         * start rounded down to [alignment] to the latest end rounded up (at
         * most [now]). Null when nothing changed.
         */
        internal fun changedWindow(
            spans: List<Pair<Instant, Instant>>,
            alignment: Duration,
            now: Instant,
        ): Pair<Instant, Instant>? {
            if (spans.isEmpty()) return null
            val since = alignDown(spans.minOf { it.first }, alignment)
            val until = minOf(alignUp(spans.maxOf { it.second }, alignment), now)
            return since to maxOf(until, since + Duration.ofMillis(1))
        }

        internal fun alignDown(t: Instant, alignment: Duration): Instant {
            val a = alignment.toMillis().coerceAtLeast(1)
            return Instant.ofEpochMilli(Math.floorDiv(t.toEpochMilli(), a) * a)
        }

        /** Smallest aligned instant strictly after [t] (an instant on a boundary still gets its bucket). */
        internal fun alignUp(t: Instant, alignment: Duration): Instant {
            val a = alignment.toMillis().coerceAtLeast(1)
            return Instant.ofEpochMilli((Math.floorDiv(t.toEpochMilli(), a) + 1) * a)
        }
    }
}
