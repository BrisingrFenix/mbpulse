package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.MbPulseLibrary

/**
 * Starts and keeps alive collection in the passive modes
 * ([pt.ua.mbpulse.CollectionMode.isPassive]): no collection screen,
 * collection runs whenever the participant is enrolled.
 *
 * Uses the persisted [Enrollment] so collection can resume without any UI —
 * after a reboot or app update ([CollectionBootReceiver]).
 *
 * Hosts whose prerequisite is granted after onboarding (e.g. BLUETOOTH_CONNECT
 * requested when pairing the wearable) call [resume] once it is granted.
 */
public object PassiveCollection {

    /**
     * Starts collection for [enrollment] if the app is in a passive mode.
     * Idempotent — a running service ignores the duplicate start.
     */
    internal fun start(context: Context, enrollment: Enrollment): Boolean {
        if (!MbPulseLibrary.config.collectionMode.isPassive || enrollment.wrappedDekB64.isEmpty()) return false
        return CollectionService.start(
            context                 = context,
            participantPseudoId     = enrollment.participantPseudoId,
            studyId                 = enrollment.studyId,
            wrappedDekBytes         = enrollment.wrappedDekBytes,
            piPublicKeyBytes        = enrollment.piPublicKeyBytes,
            consentedTypeIds        = enrollment.consentedTypeIds,
            samplingSecondsByTypeId = enrollment.samplingSecondsByTypeId,
        )
    }

    /**
     * Resumes collection for the stored enrollment — on boot / app update, or
     * by the host after granting a late prerequisite. No-op when not enrolled
     * or in session mode, or once the study has ended ([StudyEnd]).
     * @return true if collection was started.
     */
    public suspend fun resume(context: Context): Boolean {
        val enrollment = EnrollmentStore.load(context) ?: return false
        if (StudyEnd.endedAtMs(context) != null || DeviceReplaced.isReplaced(context)) return false
        return start(context, enrollment)
    }
}
