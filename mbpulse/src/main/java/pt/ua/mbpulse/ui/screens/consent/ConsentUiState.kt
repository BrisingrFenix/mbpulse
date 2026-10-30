package pt.ua.mbpulse.ui.screens.consent


internal sealed class ConsentUiState {

    /** Landing screen shown before the per-type review begins. */
    object Intro : ConsentUiState()

    /** One data type is shown for the user to accept or decline. */
    data class DataTypeConsent(
        val current: ConsentDataType,
        val index: Int,       // 0-based, used for the progress indicator
        val total: Int,
        val decisions: List<ConsentDecision>
    ) : ConsentUiState()

    /**
     * Shown when the user declines an essential data type.
     * Gives them a chance to go back rather than immediately withdrawing.
     */
    data class EssentialDeclineWarning(
        val dataType: ConsentDataType,
        val index: Int,
        val total: Int
    ) : ConsentUiState()

    /** All types reviewed; user sees a summary before final confirmation. */
    data class Summary(
        val decisions: List<ConsentDecision>
    ) : ConsentUiState()
}