package pt.ua.mbpulse.ui.screens.questionnaire

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.collection.ParticipantDataRecorder
import pt.ua.mbpulse.collection.RecordAvailability
import pt.ua.mbpulse.collection.RecordResult
import pt.ua.mbpulse.data.model.Questionnaire
import pt.ua.mbpulse.data.model.QuestionnaireAnswers
import pt.ua.mbpulse.data.model.QuestionnaireRegistry
import pt.ua.mbpulse.data.repository.QuestionnaireRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A questionnaire offered on the home screen, with whether it can be filled in now. */
internal data class QuestionnaireEntry(val questionnaire: Questionnaire, val availability: RecordAvailability)

/**
 * The home screen's questionnaire cards: the study's verified questionnaires
 * whose data type the participant consented to (effective consent — a type
 * awaiting re-consent is not offered), refreshed from the server on every
 * resume so a newly published version appears without an app update.
 */
@HiltViewModel
internal class QuestionnairesViewModel @Inject constructor(
    private val repository: QuestionnaireRepository,
    private val recorder: ParticipantDataRecorder
) : ViewModel() {

    private val _entries = MutableStateFlow<List<QuestionnaireEntry>>(emptyList())
    val entries: StateFlow<List<QuestionnaireEntry>> = _entries

    fun refresh(studyId: String, consentedTypeIds: Set<String>) {
        viewModelScope.launch {
            // Cached ones first (instant, offline), then the server's.
            repository.ensureLoaded(studyId)
            publish(consentedTypeIds)
            repository.refresh(studyId)
            publish(consentedTypeIds)
        }
    }

    private suspend fun publish(consentedTypeIds: Set<String>) {
        _entries.value = QuestionnaireRegistry.questionnaires.value
            .filter { it.dataType in consentedTypeIds }
            .map { QuestionnaireEntry(it, recorder.availability(it.schemaName)) }
    }
}

internal sealed interface QuestionnaireFormState {
    data object Loading : QuestionnaireFormState
    data class Form(val questionnaire: Questionnaire) : QuestionnaireFormState
    data object Saving : QuestionnaireFormState
    /** Cannot be filled in now (not consented, already done, not enrolled) — or no longer published. */
    data class Unavailable(val availability: RecordAvailability?) : QuestionnaireFormState
    data class Finished(val result: RecordResult) : QuestionnaireFormState
}

/** One questionnaire being filled in. Answers survive configuration changes; the data point covers the time the form was open. */
@HiltViewModel
internal class QuestionnaireViewModel @Inject constructor(
    private val recorder: ParticipantDataRecorder
) : ViewModel() {

    private val _state = MutableStateFlow<QuestionnaireFormState>(QuestionnaireFormState.Loading)
    val state: StateFlow<QuestionnaireFormState> = _state

    /** Item id → answer (see [QuestionnaireAnswers] for the value types). */
    val answers = mutableStateMapOf<String, Any?>()

    var showErrors by mutableStateOf(false)
        private set

    private val openedAtMs = System.currentTimeMillis()
    private var questionnaire: Questionnaire? = null

    fun load(questionnaireId: String) {
        if (questionnaire != null) return
        viewModelScope.launch {
            val q = QuestionnaireRegistry.find(questionnaireId)
            if (q == null) {
                _state.value = QuestionnaireFormState.Unavailable(null)
                return@launch
            }
            questionnaire = q
            val availability = recorder.availability(q.schemaName)
            _state.value = if (availability == RecordAvailability.Available) QuestionnaireFormState.Form(q)
                           else QuestionnaireFormState.Unavailable(availability)
        }
    }

    fun answer(itemId: String, value: Any?) { answers[itemId] = value }

    fun invalidItems(): List<String> =
        questionnaire?.let { QuestionnaireAnswers.invalidItems(it, answers.toMap()) }.orEmpty()

    fun submit() {
        val q = questionnaire ?: return
        if (invalidItems().isNotEmpty()) { showErrors = true; return }
        _state.value = QuestionnaireFormState.Saving
        viewModelScope.launch {
            val point = QuestionnaireAnswers.dataPoint(q, answers.toMap(), openedAtMs, System.currentTimeMillis())
            _state.value = QuestionnaireFormState.Finished(recorder.record(point))
        }
    }

    /** After a failure: back to the form, answers kept. */
    fun retry() {
        questionnaire?.let { _state.value = QuestionnaireFormState.Form(it) }
    }
}
