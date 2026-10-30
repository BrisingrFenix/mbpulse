package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.repository.MeasurementRepository
import kotlinx.coroutines.CancellationException

private const val TAG = "StudyEnd"

/**
 * The research team ended the study (`end_study()`, seed_study.sql): nothing
 * more is collected, what was measured before the end is still uploaded, and
 * the library shows the participant that the study has ended.
 *
 * The end time is kept with the enrollment ([EnrollmentStore]) so background
 * work honours it without any UI: [PassiveCollection] does not start,
 * [HealthConnectBackgroundImport] and [ParticipantDataRecorder] record
 * nothing, and [RecordUploader] drops records measured after the end (the
 * backend refuses them). Learned from the study load ([LoadingViewModel]) or,
 * while the app is not opened, by [RecordUploader] asking the server ([check]).
 * Withdrawal clears it with the enrollment.
 */
internal object StudyEnd {

    private const val KEY_ENDED_AT = "study_ended_at_ms"
    private const val KEY_CHECKED_AT = "study_end_checked_at_ms"
    /** How often background uploads ask the server whether the study ended. */
    private const val CHECK_INTERVAL_MS = 30 * 60_000L

    /** When the enrolled participant's study ended, or null while it runs (or not enrolled). */
    suspend fun endedAtMs(context: Context): Long? = EnrollmentStore.getLong(context, KEY_ENDED_AT)

    /**
     * Applies the end: remembers it, stops collection and the Health Connect
     * import, and drops local records measured after the end. Uploads keep
     * their schedule so what was measured before still reaches the server.
     */
    suspend fun apply(context: Context, endedAtMs: Long, dao: EncryptedRecordDao) {
        if (endedAtMs(context) == null) Log.i(TAG, "Study ended at $endedAtMs — stopping collection")
        EnrollmentStore.put(context, longs = mapOf(KEY_ENDED_AT to endedAtMs))
        CollectionService.stop(context)
        HealthConnectBackgroundImport.cancel(context)
        val dropped = dao.deleteUnsyncedAfter(endedAtMs)
        if (dropped > 0) Log.i(TAG, "Dropped $dropped record(s) measured after the study ended")
    }

    /**
     * For background uploads: asks the server, at most every [CHECK_INTERVAL_MS],
     * whether the enrolled participant's study ended, and applies it. Returns
     * the end time or null. Network errors count as "not ended" (retried next time).
     */
    suspend fun check(context: Context, repository: MeasurementRepository, dao: EncryptedRecordDao): Long? {
        endedAtMs(context)?.let { return it }
        val enrollment = EnrollmentStore.load(context) ?: return null
        val now = System.currentTimeMillis()
        val lastCheck = EnrollmentStore.getLong(context, KEY_CHECKED_AT) ?: 0L
        if (now - lastCheck < CHECK_INTERVAL_MS) return null
        val endedAt = try {
            repository.getStudyEndedAt(enrollment.studyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check whether the study ended: ${e.message}")
            return null
        }
        EnrollmentStore.put(context, longs = mapOf(KEY_CHECKED_AT to now))
        endedAt?.let { apply(context, it, dao) }
        return endedAt
    }
}
