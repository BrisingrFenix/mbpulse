package pt.ua.mbpulse.ui.screens.consent

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
internal class ConsentViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow<ConsentUiState>(ConsentUiState.Intro)
    val uiState: StateFlow<ConsentUiState> = _uiState.asStateFlow()

    private var dataTypes: List<ConsentDataType> = emptyList()
    private val decisions = mutableListOf<ConsentDecision>()
    private var currentIndex = 0

    fun initialise(types: List<ConsentDataType>) {
        dataTypes = types
        decisions.clear()
        currentIndex = 0
    }

    fun startFlow() {
        if (dataTypes.isEmpty()) return
        currentIndex = 0
        showCurrent()
    }

    /** User accepted the current data type. */
    fun onAccept() {
        recordDecision(accepted = true)
        currentIndex++
        showCurrent()
    }

    /**
     * User declined the current data type.
     * If the type is essential the caller should observe the state change to
     * [ConsentUiState.EssentialDeclineWarning] and render the warning screen.
     * If non-essential the decision is recorded and flow advances.
     */
    fun onDecline() {
        val type = dataTypes[currentIndex]
        if (type.isEssential) {
            _uiState.value = ConsentUiState.EssentialDeclineWarning(
                dataType = type,
                index = currentIndex,
                total = dataTypes.size
            )
        } else {
            recordDecision(accepted = false)
            currentIndex++
            showCurrent()
        }
    }

    /** User chose "Go back" from the essential-decline warning — re-show the type. */
    fun onGoBackFromWarning() {
        showCurrent()
    }

    /** Produces the final [ConsentRecord] once the summary is confirmed. */
    fun buildRecord(
        studyId: String,
        participantPseudoId: String,
        studyVersion: String,
        definitionSha256: String
    ): ConsentRecord = ConsentRecord(
        studyId = studyId,
        participantPseudoId = participantPseudoId,
        studyVersion = studyVersion,
        definitionSha256 = definitionSha256,
        decisions = decisions.toList()
    )

    // -------------------------------------------------------------------------

    private fun recordDecision(accepted: Boolean) {
        val type = dataTypes[currentIndex]
        decisions.add(
            ConsentDecision(
                dataTypeId = type.id,
                dataTypeName = type.name,
                accepted = accepted
            )
        )
    }

    private fun showCurrent() {
        if (currentIndex >= dataTypes.size) {
            _uiState.value = ConsentUiState.Summary(decisions.toList())
            return
        }
        _uiState.value = ConsentUiState.DataTypeConsent(
            current = dataTypes[currentIndex],
            index = currentIndex,
            total = dataTypes.size,
            decisions = decisions.toList()
        )
    }
}