package pt.ua.mbpulse.data.repository

import android.content.Context
import pt.ua.mbpulse.logging.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last study definition fetched AND verified, as the server sent it, so
 * the app opens without a network connection. Not a trust anchor: a cached
 * copy goes through exactly the same parsing and signature verification as a
 * fresh one (and the rollback check), so editing the file achieves nothing an
 * attacker could not do by replaying an old server response. Public data —
 * no encryption needed.
 */
@Singleton
internal class StudyConfigCache @Inject constructor(@ApplicationContext private val context: Context) {

    data class Entry(val raw: String, val savedAtMs: Long)

    private fun file(studyId: String) =
        File(context.filesDir, "study_config_${studyId.filter { it.isLetterOrDigit() || it == '-' }}.json")

    fun save(studyId: String, raw: String) {
        runCatching {
            val f = file(studyId)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText("${System.currentTimeMillis()}\n$raw")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w("StudyConfigCache", "Could not cache study $studyId: ${it.message}") }
    }

    fun load(studyId: String): Entry? = runCatching {
        val text = file(studyId).takeIf { it.exists() }?.readText() ?: return null
        val savedAt = text.substringBefore('\n').toLong()
        Entry(text.substringAfter('\n'), savedAt)
    }.getOrNull()

    // -- Questionnaires (QuestionnaireRepository) -------------------------------
    // The accepted (verified) questionnaire rows as the server sent them. Also
    // the rollback reference: a version older than the cached one is refused.

    private fun questionnaireFile(studyId: String) =
        File(context.filesDir, "study_config_${studyId.filter { it.isLetterOrDigit() || it == '-' }}.questionnaires.json")

    fun saveQuestionnaires(studyId: String, raw: String) {
        runCatching {
            val f = questionnaireFile(studyId)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(raw)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w("StudyConfigCache", "Could not cache questionnaires of $studyId: ${it.message}") }
    }

    fun loadQuestionnaires(studyId: String): String? =
        runCatching { questionnaireFile(studyId).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Withdrawal / reset: forget every cached study (and its questionnaires). */
    fun clear() {
        context.filesDir.listFiles { f -> f.name.startsWith("study_config_") }?.forEach { it.delete() }
        pt.ua.mbpulse.data.model.QuestionnaireRegistry.clear()
    }
}
