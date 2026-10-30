package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.data.local.EncryptedRecordDao

/**
 * Turns all participant background work on and off together:
 * passive collection ([PassiveCollection]), the periodic Health Connect
 * import ([HealthConnectBackgroundImport]) and the periodic upload
 * ([SyncWorker]), driven by the persisted [Enrollment].
 */
internal object BackgroundWork {

    /**
     * Called when the participant is onboarded, and on every app start while
     * enrolled — so stopped work is restarted and config changes (interval,
     * mode) apply. Each part is a no-op when not configured. Once the study
     * has ended ([StudyEnd]) only uploads are scheduled.
     */
    suspend fun enable(context: Context, enrollment: Enrollment) {
        EnrollmentStore.save(context, enrollment)
        if (DeviceReplaced.isReplaced(context)) return   // another phone has the account now
        SyncWorker.schedule(context)
        if (StudyEnd.endedAtMs(context) != null) return
        PassiveCollection.start(context, enrollment)
        HealthConnectBackgroundImport.schedule(context)
    }

    /**
     * The study ended at [endedAtMs]: collection and import stop for good,
     * uploads continue so what was measured before the end reaches the server.
     */
    internal suspend fun end(context: Context, enrollment: Enrollment, endedAtMs: Long, dao: EncryptedRecordDao) {
        EnrollmentStore.save(context, enrollment)
        StudyEnd.apply(context, endedAtMs, dao)
        if (DeviceReplaced.isReplaced(context)) return
        SyncWorker.schedule(context)
    }

    /** Withdrawal / reset: stop everything and forget the enrollment, tokens and chosen device. */
    suspend fun disable(context: Context) {
        DeviceSelectionStore.current(context)?.let { DeviceAssociation.forget(context, it) }
        EnrollmentStore.clear(context)
        SyncWorker.cancel(context)
        HealthConnectBackgroundImport.cancel(context)
        CollectionService.stop(context)
    }
}
