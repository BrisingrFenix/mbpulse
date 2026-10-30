package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.R
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EncryptedRecordEntity
import pt.ua.mbpulse.data.repository.DeviceReplacedException
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.data.repository.NotSignedInException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordUploader"

/** What the UI shows about uploading. Epochs, not data points — that is what participants record. */
internal data class SyncProgress(
    val running: Boolean = false,
    /** Epochs in the current run (0 when idle). */
    val totalEpochs: Int = 0,
    /** Epochs of the current run fully uploaded. */
    val uploadedEpochs: Int = 0,
    /** Last run that ended with everything uploaded (persisted), or null. */
    val lastSuccessAtMs: Long? = null,
    /** Why the last run stopped early, or null if it succeeded. */
    val lastError: String? = null
) {
    /** 0..1 for a progress bar while [running]. */
    val fraction: Float get() = if (totalEpochs == 0) 0f else uploadedEpochs.toFloat() / totalEpochs
    val percent: Int get() = (fraction * 100).toInt()
}

internal enum class SyncOutcome { NOTHING_TO_DO, UPLOADED, NOT_SIGNED_IN, FAILED, ALREADY_RUNNING, DEVICE_REPLACED }

/**
 * The one place records are uploaded: [SyncWorker] (WorkManager), the
 * collection service (its own cadence — not subject to job deferral) and the
 * home screen (immediate, with progress) all call [syncPending]. Runs are
 * serialised; a call while one is running returns [SyncOutcome.ALREADY_RUNNING]
 * — the running one picks up what is pending when it started, the next run
 * the rest.
 */
@Singleton
internal class RecordUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: EncryptedRecordDao,
    private val repository: MeasurementRepository
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    init {
        _progress.update { it.copy(lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { t -> t > 0 }) }
    }

    suspend fun syncPending(): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        try {
            if (DeviceReplaced.isReplaced(context)) return SyncOutcome.DEVICE_REPLACED
            // An ended study (also learned here while the app is not opened)
            // drops records measured after the end before they block a batch.
            StudyEnd.check(context, repository, dao)?.let { dao.deleteUnsyncedAfter(it) }
            val pending = dao.getPending()
            if (pending.isEmpty()) {
                markSuccess()
                return SyncOutcome.NOTHING_TO_DO
            }
            val counter = EpochProgress(pending.map { it.epochStartMs })
            _progress.update { it.copy(running = true, totalEpochs = counter.totalEpochs, uploadedEpochs = 0, lastError = null) }
            Log.d(TAG, "Uploading ${pending.size} record(s) in ${counter.totalEpochs} epoch(s)")

            var done = 0
            var rejected = 0
            for (batch in pending.chunked(BATCH_SIZE)) {
                try {
                    upload(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NotSignedInException) {
                    Log.w(TAG, "Not signed in — deferring ${pending.size - done} record(s)")
                    fail(context.getString(R.string.mbpulse_sync_error_not_signed_in))
                    return SyncOutcome.NOT_SIGNED_IN
                } catch (e: DeviceReplacedException) {
                    return deviceReplaced()
                } catch (e: Exception) {
                    Log.w(TAG, "Batch upload failed (${e::class.simpleName} — ${e.message}); retrying one by one")
                    // One bad record must not block everything behind it: retry
                    // singly. If the first single upload fails too, the problem is
                    // systemic (offline, server down) — stop and retry later.
                    val (failure, rejectedHere) = uploadSingly(batch)
                    rejected += rejectedHere
                    if (failure is DeviceReplacedException) return deviceReplaced()
                    if (failure != null) {
                        Log.e(TAG, "Upload failed after $done/${pending.size}: ${failure::class.simpleName} — ${failure.message}")
                        fail(failure.message?.take(120) ?: context.getString(R.string.mbpulse_sync_error_generic))
                        return if (failure is NotSignedInException) SyncOutcome.NOT_SIGNED_IN else SyncOutcome.FAILED
                    }
                }
                done += batch.size
                _progress.update { it.copy(uploadedEpochs = counter.completedAfter(done)) }
            }
            if (rejected > 0) {
                fail(context.resources.getQuantityString(R.plurals.mbpulse_sync_error_rejected, rejected, rejected))
                return SyncOutcome.FAILED
            }
            Log.d(TAG, "Sync complete — ${pending.size} record(s)")
            markSuccess()
            return SyncOutcome.UPLOADED
        } finally {
            _progress.update { it.copy(running = false, totalEpochs = 0, uploadedEpochs = 0) }
            mutex.unlock()
        }
    }

    private suspend fun upload(batch: List<EncryptedRecordEntity>) {
        repository.uploadRecords(batch)
        dao.markSynced(batch.map { it.id })
    }

    /**
     * Uploads [batch] record by record, marking each. Returns the error that
     * stopped it when the very first record fails (systemic) — else null — and
     * how many records were rejected (marked FAILED, retried next run).
     */
    private suspend fun uploadSingly(batch: List<EncryptedRecordEntity>): Pair<Exception?, Int> {
        var rejected = 0
        batch.forEachIndexed { i, record ->
            try {
                repository.uploadRecord(record)
                dao.markSynced(record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is DeviceReplacedException) return e to rejected   // nothing was wrong with the record
                dao.markFailed(record.id)
                if (i == 0 || e is NotSignedInException) return e to rejected
                rejected++
                Log.e(TAG, "Record id=${record.id} rejected: ${e::class.simpleName} — ${e.message}")
            }
        }
        return null to rejected
    }

    private fun markSuccess() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SUCCESS, now).apply()
        _progress.update { it.copy(lastSuccessAtMs = now, lastError = null) }
    }

    private fun fail(reason: String) = _progress.update { it.copy(lastError = reason) }

    /** Another phone recovered the account: stop everything here (the app shows why). */
    private suspend fun deviceReplaced(): SyncOutcome {
        DeviceReplaced.apply(context)
        fail(context.getString(R.string.mbpulse_sync_error_device_replaced))
        return SyncOutcome.DEVICE_REPLACED
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val PREFS = "mbpulse_sync_status"     // a timestamp only — nothing sensitive
        const val KEY_LAST_SUCCESS = "last_success_at_ms"
    }
}

/**
 * Epoch-level progress over records uploaded in order. [epochStartsInOrder] is
 * the pending list's epoch per record, sorted ascending (as `getPending` returns
 * it); an epoch counts once all its records are uploaded.
 */
internal class EpochProgress(epochStartsInOrder: List<Long>) {
    /** Exclusive end index of each epoch's run of records, ascending. */
    private val epochEnds: IntArray = buildList {
        for (i in epochStartsInOrder.indices) {
            if (i == epochStartsInOrder.lastIndex || epochStartsInOrder[i + 1] != epochStartsInOrder[i]) add(i + 1)
        }
    }.toIntArray()

    val totalEpochs: Int get() = epochEnds.size

    /** Epochs complete once the first [uploadedRecords] records are uploaded. */
    fun completedAfter(uploadedRecords: Int): Int {
        val i = epochEnds.binarySearch(uploadedRecords)
        return if (i >= 0) i + 1 else -(i + 1)
    }
}
