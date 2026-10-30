package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"

// ---------------------------------------------------------------------------
// Insert DTO — matches the encrypted_records Supabase table
// ---------------------------------------------------------------------------

@Serializable
internal data class EncryptedRecordDto(
    @SerialName("participant_pseudo_id") val participantPseudoId: String,
    @SerialName("study_id")                 val studyId: String,
    @SerialName("epoch_start_ms")        val epochStartMs: Long,
    @SerialName("schema_name")           val schemaName: String,
    @SerialName("ciphertext_hex")        val ciphertextHex: String,
    @SerialName("participant_wrapped_dek_hex") val participantWrappedDekHex: String,
    @SerialName("pi_wrapped_dek_hex")       val piWrappedDekHex: String
)

// ---------------------------------------------------------------------------
// Worker
// ---------------------------------------------------------------------------

@HiltWorker
internal class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: RecordUploader
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME_PERIODIC     = "sync_encrypted_records_periodic"
        private const val NOTIFICATION_CHANNEL_ID = "mbpulse_sync_channel"
        private const val NOTIFICATION_ID         = 1001
        private const val WORK_NAME_IMMEDIATE = "sync_encrypted_records_now"
        /** A failing run retries this often before leaving it to the next scheduled run. */
        private const val MAX_RETRIES = 3

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Periodic background sync every [pt.ua.mbpulse.MbPulseConfig.syncInterval].
         * Idempotent — called on enrolment and every app start ([BackgroundWork.enable]);
         * UPDATE applies a changed interval without resetting the schedule.
         */
        fun schedule(context: Context) {
            val interval = MbPulseLibrary.config.syncInterval
            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Periodic sync scheduled every ${interval.toMinutes()} min")
        }

        /** Withdrawal / reset: stop background uploads. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME_PERIODIC)
                cancelUniqueWork(WORK_NAME_IMMEDIATE)
            }
        }

        /** Triggers an immediate one-time sync — e.g. after a session or a Health Connect import. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(networkConstraint)
                // Short, bounded retries: KEEP below ignores new requests while
                // one waits in backoff, so a long backoff would swallow them.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Immediate sync enqueued")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = android.app.NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.mbpulse_notification_sync_channel),
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        applicationContext
            .getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification = androidx.core.app.NotificationCompat
            .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(applicationContext.getString(R.string.mbpulse_notification_sync_title))
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val outcome = uploader.syncPending()
        Log.d(TAG, "Sync run (attempt ${runAttemptCount + 1}): $outcome")
        return when (outcome) {
            SyncOutcome.FAILED, SyncOutcome.NOT_SIGNED_IN ->
                // Records stay pending either way; after a few tries the next
                // periodic run (or the collection service) takes over.
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}