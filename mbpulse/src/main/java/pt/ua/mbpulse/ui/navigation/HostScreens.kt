package pt.ua.mbpulse.ui.navigation

import pt.ua.mbpulse.logging.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A screen the host app adds to the library's navigation — a questionnaire,
 * a diary, device pairing, study information. Passed to
 * `MbPulseApp(hostScreens = …)` / `MbPulseNavHost(hostScreens = …)`.
 *
 * Host screens are reachable only for an enrolled participant (from the home
 * screen or another enrolled screen), and the library-owned re-consent and
 * data-export notices still appear over them. To store what the participant
 * enters, use [pt.ua.mbpulse.collection.ParticipantDataRecorder].
 *
 * @param id unique among the host screens; used to open it ([MbPulseNavigator]).
 * @param titleRes the title on its home-screen card (and usually its top bar).
 * @param icon shown on the home-screen card.
 * @param showOnHome list it as a card on the library's home screen. false =
 *   opened only through [MbPulseNavigator.openHostScreen] (a custom home screen,
 *   another host screen).
 * @param subtitle second line of the home card, e.g. "Done today" — composable
 *   so it can read the host's own state and string resources; null = none.
 * @param content the screen; `onBack` returns to the previous screen. ViewModels
 *   from `hiltViewModel()` are scoped to this screen's back-stack entry.
 */
public data class HostScreen(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector? = null,
    val showOnHome: Boolean = true,
    val subtitle: @Composable () -> String? = { null },
    val content: @Composable (onBack: () -> Unit) -> Unit,
)

/**
 * Navigation the library exposes to host code inside `MbPulseNavHost`:
 * custom home screens and host screens read it from [LocalMbPulseNavigator].
 */
@Stable
public interface MbPulseNavigator {
    /** The host screens passed to `MbPulseNavHost`. */
    public val hostScreens: List<HostScreen>

    /**
     * Opens the host screen [id]. Ignored (logged) for an unknown id or when
     * no participant is enrolled — e.g. during onboarding.
     */
    public fun openHostScreen(id: String)

    /**
     * Opens the library's form for backend questionnaire [questionnaireId]
     * (`QuestionnaireRegistry`). Same rules as [openHostScreen].
     */
    public fun openQuestionnaire(questionnaireId: String)

    /**
     * Opens the library's device-selection step (choose / change / forget the
     * wearable) — `MbPulseConfig.deviceSelection`. Ignored (logged) when it is
     * not configured; same rules as [openHostScreen] otherwise.
     */
    public fun openDeviceSelection()
}

/** The [MbPulseNavigator] of the enclosing `MbPulseNavHost`. */
public val LocalMbPulseNavigator: androidx.compose.runtime.ProvidableCompositionLocal<MbPulseNavigator> =
    staticCompositionLocalOf<MbPulseNavigator> { NoHostNavigator }

/** Outside an `MbPulseNavHost` (previews, tests): no host screens. */
private object NoHostNavigator : MbPulseNavigator {
    override val hostScreens: List<HostScreen> = emptyList()
    override fun openHostScreen(id: String) {
        Log.w("MbPulseNavigator", "openHostScreen('$id') outside MbPulseNavHost — ignored")
    }
    override fun openQuestionnaire(questionnaireId: String) {
        Log.w("MbPulseNavigator", "openQuestionnaire('$questionnaireId') outside MbPulseNavHost — ignored")
    }
    override fun openDeviceSelection() {
        Log.w("MbPulseNavigator", "openDeviceSelection() outside MbPulseNavHost — ignored")
    }
}
