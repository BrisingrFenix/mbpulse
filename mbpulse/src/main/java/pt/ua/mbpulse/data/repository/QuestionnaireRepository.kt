package pt.ua.mbpulse.data.repository

import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.crypto.QuestionnaireSignature
import pt.ua.mbpulse.data.model.Questionnaire
import pt.ua.mbpulse.data.model.QuestionnaireRegistry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QuestionnaireRepository"

/**
 * The study's backend questionnaires: fetched with `get_study_questionnaires`,
 * verified ([QuestionnaireSignature], pinned study-signing key), cached for
 * offline use ([StudyConfigCache], re-verified on every load) and published to
 * [QuestionnaireRegistry].
 *
 * Per questionnaire, [QuestionnaireMerge] keeps the accepted copy when the
 * server sends a rejected or older (rollback) one; one the server no longer
 * lists (retired) is dropped.
 */
@Singleton
internal class QuestionnaireRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val cache: StudyConfigCache
) {
    private val lock = Mutex()

    /** Fetches from the server; on failure (offline) falls back to the cache. Never throws. */
    suspend fun refresh(studyId: String): List<Questionnaire> = withContext(Dispatchers.IO) {
        lock.withLock {
            val accepted = cachedRows(studyId)
            val fetched = try {
                supabase.postgrest.rpc("get_study_questionnaires", StudyIdParam(studyId)).data
                    .let { json.decodeFromString<List<QuestionnaireRow>>(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Questionnaires unreachable (${e::class.simpleName}) — using the cached ones")
                null
            }
            val rows = if (fetched == null) accepted else {
                QuestionnaireMerge.merge(accepted, fetched, ::verify, studyId).also { merged ->
                    cache.saveQuestionnaires(studyId, json.encodeToString(merged))
                }
            }
            publish(studyId, rows)
        }
    }

    /** The cached questionnaires, if [QuestionnaireRegistry] does not hold [studyId]'s yet (e.g. a background start). */
    suspend fun ensureLoaded(studyId: String) {
        if (QuestionnaireRegistry.studyId == studyId) return
        withContext(Dispatchers.IO) { lock.withLock { publish(studyId, cachedRows(studyId)) } }
    }

    private fun publish(studyId: String, rows: List<QuestionnaireRow>): List<Questionnaire> {
        val questionnaires = rows.mapNotNull { (verify(it, studyId) as? QuestionnaireSignature.Result.Verified)?.questionnaire }
        QuestionnaireRegistry.set(studyId, questionnaires)
        return questionnaires
    }

    private fun cachedRows(studyId: String): List<QuestionnaireRow> =
        cache.loadQuestionnaires(studyId)?.let { raw ->
            runCatching { json.decodeFromString<List<QuestionnaireRow>>(raw) }.getOrNull()
        }.orEmpty()

    private fun verify(row: QuestionnaireRow, studyId: String): QuestionnaireSignature.Result {
        val pinned = MbPulseLibrary.config.studySigningPublicKeyB64
        if (pinned == null) Log.w(TAG, "No studySigningPublicKeyB64 configured — questionnaire '${row.questionnaireId}' is NOT verified")
        return QuestionnaireSignature.verify(row.definition, row.signature, pinned, studyId).also {
            if (it is QuestionnaireSignature.Result.Rejected) {
                Log.e(TAG, "Questionnaire '${row.questionnaireId}' rejected: ${it.reason}")
            }
        }
    }

    @Serializable
    private data class StudyIdParam(@SerialName("p_study_id") val studyId: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** One row of `get_study_questionnaires`, as stored in the cache. */
@Serializable
internal data class QuestionnaireRow(
    val questionnaireId: String,
    val definition: String,
    val signature: String? = null
)

/**
 * Which questionnaire rows to keep after a fetch — pure, unit-tested. For each
 * questionnaire the server lists: its row if it verifies and is not older than
 * the accepted one; otherwise the accepted row (an attacker or a stale replica
 * cannot replace good questions with bad or older ones). Questionnaires the
 * server no longer lists are dropped (retired).
 */
internal object QuestionnaireMerge {

    fun merge(
        accepted: List<QuestionnaireRow>,
        fetched: List<QuestionnaireRow>,
        verify: (QuestionnaireRow, String) -> QuestionnaireSignature.Result,
        studyId: String
    ): List<QuestionnaireRow> {
        val acceptedById = accepted.associateBy { it.questionnaireId }
        return fetched.mapNotNull { row ->
            val previousRow = acceptedById[row.questionnaireId]
            val previous = previousRow?.let { (verify(it, studyId) as? QuestionnaireSignature.Result.Verified)?.questionnaire }
            when (val v = verify(row, studyId)) {
                is QuestionnaireSignature.Result.Verified ->
                    if (v.questionnaire.questionnaireId != row.questionnaireId) previousRow
                    else if (QuestionnaireSignature.isRollback(v.questionnaire, previous)) previousRow
                    else row
                is QuestionnaireSignature.Result.Rejected -> previousRow?.takeIf { previous != null }
            }
        }
    }
}
