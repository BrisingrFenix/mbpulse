package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log

/**
 * This phone is no longer the participant's device — the account was
 * recovered on another phone (device binding, backend `bind_session`;
 * learned as [pt.ua.mbpulse.data.repository.DeviceReplacedException]
 * by an upload, or by `LoadingViewModel` on load).
 *
 * Remembered with the enrolment, so it holds without network: collection,
 * Health Connect import, uploads and [ParticipantDataRecorder] stop, and
 * `NavGraph` shows the library-owned "now used on another device" screen,
 * whose only action removes the study from this phone (cleared then).
 */
internal object DeviceReplaced {

    private const val KEY = "device_replaced_at_ms"

    suspend fun isReplaced(context: Context): Boolean = EnrollmentStore.getLong(context, KEY) != null

    /** Records it and stops every piece of background work. Idempotent. */
    suspend fun apply(context: Context) {
        if (isReplaced(context)) return
        Log.w("DeviceReplaced", "Account recovered on another device — stopping all work on this one")
        EnrollmentStore.put(context, longs = mapOf(KEY to System.currentTimeMillis()))
        SyncWorker.cancel(context)
        HealthConnectBackgroundImport.cancel(context)
        CollectionService.stop(context)
    }
}
