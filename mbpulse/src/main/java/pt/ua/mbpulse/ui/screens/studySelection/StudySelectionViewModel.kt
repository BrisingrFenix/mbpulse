package pt.ua.mbpulse.ui.screens.studySelection

import pt.ua.mbpulse.logging.Log
import androidx.lifecycle.ViewModel
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.errorText
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.data.repository.StudyClosedException
import pt.ua.mbpulse.data.repository.StudySummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A study plus whether this app build can collect its essential data types.
 * When [isSupported] is false the study is shown disabled in the picker.
 */
public data class SelectableStudy(
    val study: StudySummary,
    val isSupported: Boolean
)

public sealed class StudySelectionState {
    public object Loading : StudySelectionState()
    public data class Loaded(val studies: List<SelectableStudy>) : StudySelectionState()
    public data class Error(val message: String) : StudySelectionState()
}

public sealed class JoinState {
    public object Idle : JoinState()
    public object Verifying : JoinState()
    public data class Error(val message: String) : JoinState()
}

@HiltViewModel
public class StudySelectionViewModel @Inject internal constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: MeasurementRepository
) : ViewModel() {

    private val _selectionState = MutableStateFlow<StudySelectionState>(StudySelectionState.Loading)
    public val selectionState: StateFlow<StudySelectionState> = _selectionState

    private val _joinState = MutableStateFlow<JoinState>(JoinState.Idle)
    public val joinState: StateFlow<JoinState> = _joinState

    init {
        loadStudies()
    }

    public fun loadStudies() {
        viewModelScope.launch {
            _selectionState.value = StudySelectionState.Loading
            try {
                val studies = repository.getActiveStudies()
                val supported = MbPulseLibrary.config.supportedDataTypeIds
                val items = studies.map { summary ->
                    val isSupported = supported == null ||
                        summary.essentialDataTypeIds.all { it in supported }
                    SelectableStudy(summary, isSupported)
                }
                _selectionState.value = StudySelectionState.Loaded(items)
            } catch (e: Exception) {
                _selectionState.value = StudySelectionState.Error(
                    context.errorText(e, R.string.mbpulse_studies_failed_detail)
                )
            }
        }
    }

    /**
     * Trades the join code (any input for a study without one) for the
     * backend's join ticket, keeps it for enrolment ([JoinTicketStore] — the
     * backend refuses enrolment without it) and calls [onSuccess] with the
     * study ID. A custom study-selection screen must join through here too.
     */
    public fun joinStudy(study: StudySummary, enteredCode: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _joinState.value = JoinState.Verifying
            try {
                val ticket = repository.issueJoinTicket(study.studyId, enteredCode)
                if (ticket != null) {
                    Log.d("StudySelection", "Join ticket issued")
                    JoinTicketStore.save(context, study.studyId, ticket)
                    _joinState.value = JoinState.Idle
                    onSuccess(study.studyId)
                } else {
                    _joinState.value = JoinState.Error(context.getString(R.string.mbpulse_join_code_incorrect))
                }
            } catch (e: StudyClosedException) {
                _joinState.value = JoinState.Error(context.getString(R.string.mbpulse_join_study_closed))
                loadStudies()
            } catch (e: Exception) {
                _joinState.value = JoinState.Error(context.errorText(e, R.string.mbpulse_join_code_failed))
            }
        }
    }

    public fun resetJoinState() {
        _joinState.value = JoinState.Idle
    }
}