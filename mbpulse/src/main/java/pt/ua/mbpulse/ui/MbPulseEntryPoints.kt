package pt.ua.mbpulse.ui

import androidx.compose.runtime.Composable
import pt.ua.mbpulse.ui.navigation.HostScreen
import pt.ua.mbpulse.ui.navigation.LocalMbPulseNavigator
import pt.ua.mbpulse.ui.navigation.NavGraph
import pt.ua.mbpulse.ui.screens.collection.CollectionViewModel
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.theme.MbPulseTheme

/**
 * Turnkey entry point — a complete standalone application.
 *
 * Use this when the mbPulse library IS your app. Set it as the content of
 * your host app's `MainActivity`:
 *
 * ```kotlin
 * setContent { MbPulseApp() }
 * ```
 *
 * The library provides the full navigation graph, theme, and all screens.
 * Colours, logo and title follow `MbPulseConfig.branding`; wording follows the
 * `mbpulse_*` string resources (override them in the host app to change it).
 */
@Composable
public fun MbPulseApp(hostScreens: List<HostScreen> = emptyList()) {
    MbPulseTheme {
        MbPulseNavHost(hostScreens = hostScreens)
    }
}

/**
 * Embeddable nav graph with optional screen overrides.
 *
 * Use this to embed the mbPulse flow inside your own navigation, or to
 * replace specific screens while keeping the library defaults for the rest.
 *
 * ## Embed in an existing nav graph
 * ```kotlin
 * composable("health") {
 *     MbPulseNavHost(
 *         onExit = { hostNavController.navigateUp() }
 *     )
 * }
 * ```
 *
 * ## Replace only the measurements screen
 * ```kotlin
 * MbPulseNavHost(
 *     measurementsScreen = { onCardClick, onNavigateToCollection, onReset, vm ->
    *         MyMeasurementsScreen(
        *             onCardClick            = onCardClick,
        *             onNavigateToCollection = onNavigateToCollection,
        *             collectionViewModel    = vm
                *         )
    *     }
 * )
 * ```
 *
 * ## Replace only the collection screen
 * ```kotlin
 * MbPulseNavHost(
 *     collectionScreen = { vm, ready, onBack ->
    *         MyCollectionScreen(viewModel = vm, ready = ready, onBack = onBack)
    *     }
 * )
 * ```
 *
 * @param navController         Nav controller dedicated to the mbPulse flow.
 * @param onExit                Called when the user should return to the host
 *                              app. If null, the back stack handles exit.
 * @param studySelectionScreen  Override the study list screen.
 * @param measurementsScreen    Override the home/measurements screen.
 * @param collectionScreen      Override the data collection screen.
 * @param historyScreen         Override the signal history/chart screen.
 * @param hostScreens           Extra host-app screens (questionnaire, diary, …),
 *                              listed as cards on the home screen and opened
 *                              via [LocalMbPulseNavigator] — see [HostScreen].
 */
@Composable
public fun MbPulseNavHost(
    onExit: (() -> Unit)? = null,

    studySelectionScreen: @Composable ((
        onStudySelected: (studyId: String) -> Unit,
        onNavigateToRecovery: () -> Unit
    ) -> Unit)? = null,

    measurementsScreen: @Composable ((
        onCardClick: (schemaName: String, title: String) -> Unit,
        onNavigateToCollection: () -> Unit,
        onReset: () -> Unit,
        collectionViewModel: CollectionViewModel
    ) -> Unit)? = null,

    collectionScreen: @Composable ((
        viewModel: CollectionViewModel,
        onBack: () -> Unit
    ) -> Unit)? = null,

    historyScreen: @Composable ((
        schemaName: String,
        title: String,
        onBack: () -> Unit,
        viewModel: pt.ua.mbpulse.ui.screens.history.HistoryViewModel
    ) -> Unit)? = null,

    hostScreens: List<HostScreen> = emptyList()
) {
    // Build the argument list, falling back to null (NavGraph uses its own
    // defaults) for any screen the host app did not override.
    NavGraph(
        onExit               = onExit,
        studySelectionScreen = studySelectionScreen
            ?: { onStudySelected, onNavigateToRecovery ->
                pt.ua.mbpulse.ui.screens.studySelection.StudySelectionScreen(
                    onStudySelected      = onStudySelected,
                    onNavigateToRecovery = onNavigateToRecovery
                )
            },
        measurementsScreen   = measurementsScreen
            ?: { onCardClick, onNavigateToCollection, onReset, vm ->
                pt.ua.mbpulse.ui.screens.measurements.MeasurementsScreen(
                    onCardClick            = onCardClick,
                    onNavigateToCollection = onNavigateToCollection,
                    onReset                = onReset,
                    collectionViewModel    = vm
                )
            },
        collectionScreen     = collectionScreen
            ?: { vm, onBack ->
                pt.ua.mbpulse.ui.screens.collection.CollectionScreen(
                    viewModel = vm,
                    onBack    = onBack
                )
            },
        historyScreen        = historyScreen
            ?: { schemaName, title, onBack, vm ->
                pt.ua.mbpulse.ui.screens.history.HistoryScreen(
                    schemaName = schemaName,
                    title      = title,
                    onBack     = onBack,
                    viewModel  = vm
                )
            },
        hostScreens          = hostScreens
    )
}