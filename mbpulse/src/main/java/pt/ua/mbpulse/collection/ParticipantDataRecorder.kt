package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.QuestionnaireRegistry
import pt.ua.mbpulse.data.repository.QuestionnaireRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlin.math.abs

private const val TAG = "ParticipantDataRecorder"

/** Outcome of [ParticipantDataRecorder.record]. Nothing is stored unless [Recorded]. */
public sealed interface RecordResult {
    /** Encrypted, stored and queued for upload — [records] new encrypted records. */
    public data class Recorded(val records: Int) : RecordResult

    /** No enrolled participant (onboarding not finished, withdrawn, or reset), or the study has ended. */
    public data object NotEnrolled : RecordResult

    /**
     * The participant has not consented to the data type of these schemas — or
     * the type changed in a study update and awaits their review (re-consent).
     */
    public data class NotConsented(val schemaNames: Set<String>) : RecordResult

    /**
     * The study's `samplingSeconds` for this data type allows one reading per
     * interval, and one was already recorded; the next is allowed from
     * [nextAllowedAtMs]. E.g. `samplingSeconds: 86400` = at most once a day.
     */
    public data class TooSoon(val schemaName: String, val nextAllowedAtMs: Long) : RecordResult

    /** Every data point was already stored (same epoch and schema) — nothing new. */
    public data object Duplicate : RecordResult

    /** Encryption or storage failed; [message] is technical detail for logs. */
    public data class Failed(val message: String) : RecordResult
}

/** Whether a schema can be recorded right now — to show a form, or why not. */
public sealed interface RecordAvailability {
    public data object Available : RecordAvailability
    public data object NotEnrolled : RecordAvailability
    public data object NotConsented : RecordAvailability
    public data class TooSoon(val nextAllowedAtMs: Long) : RecordAvailability
}

/**
 * Records data that does not come from a sensor — questionnaire answers,
 * diary entries, manual readings — through exactly the same pipeline as live
 * collection and Health Connect import ([EpochSink]): consent filter,
 * per-point AES-256-GCM encryption, local display/history rows, upload.
 *
 * The host app builds the data points (usually with a custom
 * [pt.ua.mbpulse.data.model.SchemaDefinition.dataPoint], modality
 * `"self-reported"`) and calls [record]; it never touches keys or consent.
 * Works in every collection mode, without a running collection session, for
 * the enrolled participant ([EnrollmentStore] — refreshed with the effective
 * consent set on every app start, so a pending re-consent blocks changed types).
 *
 * Inject it (`@Inject constructor(private val recorder: ParticipantDataRecorder)`)
 * or, outside Hilt, use [MbPulseLibrary.recorder].
 */
public class ParticipantDataRecorder @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val encryptedRecordDao: EncryptedRecordDao,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao,
    private val questionnaireRepository: QuestionnaireRepository,
) {

    /**
     * Whether a reading of [schemaName] (`"namespace:name"`) taken at [atMs]
     * would be recorded — enrolment, consent and the study's sampling interval.
     */
    public suspend fun availability(
        schemaName: String,
        atMs: Long = System.currentTimeMillis()
    ): RecordAvailability = withContext(Dispatchers.IO) {
        val enrollment = EnrollmentStore.load(context)
            ?.takeIf { it.wrappedDekB64.isNotEmpty() && StudyEnd.endedAtMs(context) == null && !DeviceReplaced.isReplaced(context) }
            ?: return@withContext RecordAvailability.NotEnrolled
        loadQuestionnairesFor(listOf(schemaName), enrollment)
        val candidate = RecordPolicy.Candidate(
            schemaName = schemaName,
            startMs    = atMs,
            isEvent    = OmhSchemas.definitionFor(schemaName)?.isEvent ?: false
        )
        when (val blocked = check(listOf(candidate), enrollment)) {
            null -> RecordAvailability.Available
            is RecordResult.NotConsented -> RecordAvailability.NotConsented
            is RecordResult.TooSoon -> RecordAvailability.TooSoon(blocked.nextAllowedAtMs)
            else -> RecordAvailability.Available
        }
    }

    /**
     * Encrypts and stores [dataPoints] as one epoch and triggers an upload.
     * All or nothing: if any point is not consented or too soon, none is stored.
     *
     * @throws IllegalArgumentException for an empty list or a schema that is
     *   neither built in nor registered in `MbPulseConfig.customSchemas`.
     */
    public suspend fun record(dataPoints: List<OmhDataPoint>): RecordResult {
        require(dataPoints.isNotEmpty()) { "record() needs at least one data point" }
        // Questionnaire answer schemas exist once the study's questionnaires are loaded.
        EnrollmentStore.load(context)?.let { loadQuestionnairesFor(dataPoints.map { it.schemaName }, it) }
        val registered = OmhSchemas.ALL.mapTo(HashSet()) { "${it.namespace}:${it.name}" }
        val candidates = dataPoints.map { point ->
            val schemaName = point.schemaName
            require(schemaName in registered) {
                "Schema '$schemaName' is not registered — declare it in MbPulseConfig.customSchemas"
            }
            RecordPolicy.Candidate(schemaName, point.startMs(), point.body.effectiveTimeFrame.isInstant)
        }
        return withContext(Dispatchers.IO) {
            // Serialised: two quick submissions must not both pass the sampling check.
            mutex.withLock { recordLocked(dataPoints, candidates) }
        }
    }

    public suspend fun record(dataPoint: OmhDataPoint): RecordResult = record(listOf(dataPoint))

    private suspend fun loadQuestionnairesFor(schemaNames: List<String>, enrollment: Enrollment) {
        if (schemaNames.any { QuestionnaireRegistry.isQuestionnaireSchema(it) }) {
            questionnaireRepository.ensureLoaded(enrollment.studyId)
        }
    }

    private suspend fun recordLocked(
        dataPoints: List<OmhDataPoint>,
        candidates: List<RecordPolicy.Candidate>
    ): RecordResult {
        val enrollment = EnrollmentStore.load(context)
            ?.takeIf { it.wrappedDekB64.isNotEmpty() && StudyEnd.endedAtMs(context) == null && !DeviceReplaced.isReplaced(context) }
            ?: return RecordResult.NotEnrolled
        check(candidates, enrollment)?.let { blocked ->
            Log.i(TAG, "Not recorded: $blocked")
            return blocked
        }

        var dekBytes: ByteArray? = null
        return try {
            dekBytes = keystoreManager.unwrapDek(enrollment.wrappedDekBytes)
            val encryptor = EpochEncryptor(
                keystoreManager     = keystoreManager,
                participantPseudoId = enrollment.participantPseudoId,
                studyId             = enrollment.studyId,
                dekBytes            = dekBytes,
                piPublicKeyBytes    = enrollment.piPublicKeyBytes,
                consentedTypeIds    = enrollment.consentedTypeIds,
                schemaToConsentType = MbPulseLibrary::consentTypeFor
            )
            val sink = EpochSink(
                encryptedRecordDao, displayEpochDao, epochHistoryDao,
                samplingSecondsByTypeId = enrollment.samplingSecondsByTypeId
            )
            val epoch = ProcessedEpoch(candidates.minOf { it.startMs }, dataPoints)
            val stored = sink.store(epoch, encryptor, enrollment.wrappedDekBytes)
            if (stored == 0) return RecordResult.Duplicate

            SyncWorker.syncNow(context)
            Log.d(TAG, "Recorded $stored record(s): ${candidates.map { it.schemaName }.distinct()}")
            RecordResult.Recorded(stored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed: ${e::class.simpleName} — ${e.message}", e)
            RecordResult.Failed("${e::class.simpleName}: ${e.message}")
        } finally {
            dekBytes?.fill(0)
        }
    }

    private suspend fun check(candidates: List<RecordPolicy.Candidate>, enrollment: Enrollment): RecordResult? {
        // The latest stored reading per schema (display rows exist only for
        // stored, consented points) — the reference for the sampling interval.
        val lastRecordedMs = candidates.map { it.schemaName }.distinct()
            .associateWith { displayEpochDao.getBySchema(it)?.epochStartMs }
        return RecordPolicy.check(
            candidates              = candidates,
            consentedTypeIds        = enrollment.consentedTypeIds,
            schemaToConsentType     = MbPulseLibrary::consentTypeFor,
            samplingSecondsByTypeId = enrollment.samplingSecondsByTypeId,
            lastRecordedMs          = lastRecordedMs
        )
    }

    private companion object {
        val mutex = Mutex()
    }
}

/**
 * The checks [ParticipantDataRecorder] makes before storing anything — pure,
 * so they are unit-tested. Consent is [ConsentFilter], as in [EpochEncryptor]:
 * a schema that maps to no type, or to one the participant did not accept, is
 * refused. The sampling interval follows
 * [SamplingGate], measured against the latest stored reading — in either
 * direction, so a back-dated entry cannot squeeze in next to an existing one.
 * Events (instant time frame) are never limited.
 */
internal object RecordPolicy {

    data class Candidate(val schemaName: String, val startMs: Long, val isEvent: Boolean)

    /** null = may record; otherwise the reason it may not. */
    fun check(
        candidates: List<Candidate>,
        consentedTypeIds: Set<String>,
        schemaToConsentType: (schemaName: String) -> String?,
        samplingSecondsByTypeId: Map<String, Int>,
        lastRecordedMs: Map<String, Long?>
    ): RecordResult? {
        val notConsented = candidates.mapNotNullTo(LinkedHashSet()) { c ->
            c.schemaName.takeUnless { ConsentFilter.allows(c.schemaName, consentedTypeIds, schemaToConsentType) }
        }
        if (notConsented.isNotEmpty()) return RecordResult.NotConsented(notConsented)

        for (c in candidates) {
            if (c.isEvent) continue
            val typeId = schemaToConsentType(c.schemaName) ?: continue
            val intervalMs = samplingSecondsByTypeId[typeId]?.let { it * 1_000L } ?: continue
            val last = lastRecordedMs[c.schemaName] ?: continue
            if (abs(c.startMs - last) < intervalMs) return RecordResult.TooSoon(c.schemaName, last + intervalMs)
        }
        return null
    }
}

/** Gives non-Hilt code (and [MbPulseLibrary.recorder]) the recorder. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ParticipantDataRecorderEntryPoint {
    fun participantDataRecorder(): ParticipantDataRecorder
}

private val OmhDataPoint.schemaName: String
    get() = "${header.schemaId.namespace}:${header.schemaId.name}"

/** Start of the point's time frame (the instant for events) in epoch ms. */
private fun OmhDataPoint.startMs(): Long {
    val frame = body.effectiveTimeFrame
    val iso = frame.dateTime ?: frame.timeInterval!!.startDateTime
    return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}
