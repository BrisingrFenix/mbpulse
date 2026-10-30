package pt.ua.mbpulse.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The verified questionnaires of the enrolled study, in memory — set by
 * [pt.ua.mbpulse.data.repository.QuestionnaireRepository] (from
 * the server or its verified cache). Registers their answer schemas
 * ([OmhSchemas.registerQuestionnaires]) and resolves an answer schema to its
 * consent data type ([pt.ua.mbpulse.MbPulseLibrary.consentTypeFor]).
 */
internal object QuestionnaireRegistry {

    private val _questionnaires = MutableStateFlow<List<Questionnaire>>(emptyList())
    val questionnaires: StateFlow<List<Questionnaire>> = _questionnaires

    /** The study [questionnaires] belong to; null until loaded. */
    @Volatile
    var studyId: String? = null
        private set

    internal fun set(studyId: String, questionnaires: List<Questionnaire>) {
        OmhSchemas.registerQuestionnaires(questionnaires.map { it.schemaDefinition() })
        this.studyId = studyId
        _questionnaires.value = questionnaires
    }

    internal fun clear() {
        OmhSchemas.registerQuestionnaires(emptyList())
        studyId = null
        _questionnaires.value = emptyList()
    }

    fun find(questionnaireId: String): Questionnaire? =
        _questionnaires.value.firstOrNull { it.questionnaireId == questionnaireId }

    /** The data type of a `questionnaire:<id>` schema, or null if no such questionnaire is loaded. */
    fun dataTypeFor(schemaName: String): String? =
        _questionnaires.value.firstOrNull { it.schemaName == schemaName }?.dataType

    fun isQuestionnaireSchema(schemaName: String): Boolean =
        schemaName.startsWith("${Questionnaire.NAMESPACE}:")
}
