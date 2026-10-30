package pt.ua.mbpulse.ui.navigation

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import pt.ua.mbpulse.logging.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import pt.ua.mbpulse.ui.screens.consent.ReconsentScreen
import pt.ua.mbpulse.ui.screens.device.DeviceSelectionScreen
import kotlinx.coroutines.flow.map
import pt.ua.mbpulse.ui.screens.ended.DeviceReplacedScreen
import pt.ua.mbpulse.ui.screens.ended.StudyEndedScreen
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import pt.ua.mbpulse.ui.screens.collection.CollectionScreen
import pt.ua.mbpulse.ui.screens.collection.CollectionViewModel
import pt.ua.mbpulse.ui.screens.history.HistoryScreen
import pt.ua.mbpulse.ui.screens.history.HistoryViewModel
import pt.ua.mbpulse.ui.screens.loading.LoadingScreen
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.measurements.MeasurementsScreen
import pt.ua.mbpulse.ui.screens.onboarding.OnboardingOrchestrator
import pt.ua.mbpulse.ui.screens.questionnaire.QuestionnaireScreen
import pt.ua.mbpulse.ui.screens.recovery.RecoveryScreen
import pt.ua.mbpulse.ui.screens.studySelection.StudySelectionScreen
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Route keys — Navigation 3 replaces string routes with typed, serializable
// NavKeys. Serializable so rememberNavBackStack can persist the stack across
// process death. HistoryKey carries its arguments directly (no NavType /
// navArgument plumbing).
// ---------------------------------------------------------------------------

@Serializable internal data object LoadingKey : NavKey
@Serializable internal data object StudySelectionKey : NavKey
@Serializable internal data object OnboardingKey : NavKey
@Serializable internal data object RecoveryKey : NavKey
@Serializable internal data object HomeKey : NavKey
@Serializable internal data object CollectionKey : NavKey
@Serializable internal data class HistoryKey(val schemaName: String, val title: String) : NavKey
@Serializable internal data class HostScreenKey(val id: String) : NavKey
@Serializable internal data class QuestionnaireKey(val questionnaireId: String) : NavKey
@Serializable internal data object DeviceSelectionKey : NavKey

/** Screens of an enrolled participant — where the library-owned notices and re-consent apply. */
private fun NavKey?.isEnrolledScreen(): Boolean =
    this is HomeKey || this is CollectionKey || this is HistoryKey || this is HostScreenKey ||
        this is QuestionnaireKey || this is DeviceSelectionKey


/**
 * Internal nav graph used by both [pt.ua.mbpulse.ui.MbPulseApp] and
 * [pt.ua.mbpulse.ui.MbPulseNavHost].
 *
 * The back stack is an explicit, app-owned list of [NavKey]s. Navigation is
 * plain list mutation:
 *  - push:    `backStack.add(key)`
 *  - pop:     `backStack.removeLastOrNull()`
 *  - replace (the old `popUpTo(...) { inclusive = true }`): [resetTo].
 *
 * Replaceable screens are injected as parameters with library defaults; the
 * security-critical screens (Loading, Onboarding, Recovery) are not replaceable.
 *
 * ViewModel scoping:
 *  - [LoadingViewModel] and [CollectionViewModel] are activity-scoped (shared
 *    across the flow) via `hiltViewModel(activity)`.
 *  - [HistoryViewModel] is scoped to its back-stack entry via the
 *    [rememberViewModelStoreNavEntryDecorator] decorator.
 */
@SuppressLint("ContextCastToActivity")
@Composable
internal fun NavGraph(
    onExit: (() -> Unit)? = null,

    studySelectionScreen: @Composable (
        (onStudySelected: (studyId: String) -> Unit,
         onNavigateToRecovery: () -> Unit) -> Unit
    ) = { onStudySelected, onNavigateToRecovery ->
        StudySelectionScreen(
            onStudySelected      = onStudySelected,
            onNavigateToRecovery = onNavigateToRecovery
        )
    },

    measurementsScreen: @Composable (
        (onCardClick: (schemaName: String, title: String) -> Unit,
         onNavigateToCollection: () -> Unit,
         onReset: () -> Unit,
         collectionViewModel: CollectionViewModel) -> Unit
    ) = { onCardClick, onNavigateToCollection, onReset, vm ->
        MeasurementsScreen(
            onCardClick            = onCardClick,
            onNavigateToCollection = onNavigateToCollection,
            onReset                = onReset,
            collectionViewModel    = vm
        )
    },

    collectionScreen: @Composable (
        (viewModel: CollectionViewModel,
         onBack: () -> Unit) -> Unit
    ) = { vm, onBack ->
        CollectionScreen(viewModel = vm, onBack = onBack)
    },

    historyScreen: @Composable (
        (schemaName: String,
         title: String,
         onBack: () -> Unit,
         viewModel: HistoryViewModel) -> Unit
    ) = { schemaName, title, onBack, vm ->
        HistoryScreen(schemaName = schemaName, title = title, onBack = onBack, viewModel = vm)
    },

    hostScreens: List<HostScreen> = emptyList()
) {
    require(hostScreens.map { it.id }.toSet().size == hostScreens.size) {
        "HostScreen ids must be unique: ${hostScreens.map { it.id }}"
    }
    val activity = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel       = hiltViewModel(activity)
    val collectionViewModel: CollectionViewModel = hiltViewModel(activity)
    val collectionScreenEnabled = remember { MbPulseLibrary.isCollectionScreenEnabled(activity) }

    val backStack = rememberNavBackStack(LoadingKey)

    // -- Data-export transparency (library-owned, like consent) ---------------
    // Checked on every resume of an enrolled participant and shown as a dialog
    // over any post-onboarding screen, so replacing the home screen can never
    // hide "your data was accessed" from participants.
    val loadingState by loadingViewModel.state.collectAsStateWithLifecycle()
    val enrolled = (loadingState as? LoadingState.Ready)?.takeIf { it.onboardingComplete }
    LifecycleResumeEffect(enrolled?.studyId) {
        enrolled?.studyId?.let { loadingViewModel.checkForNewExports(it) }
        loadingViewModel.refreshIfOffline()
        onPauseOrDispose { }
    }
    val exportNotice = enrolled?.exportNotification
    val top = backStack.lastOrNull()
    if (exportNotice != null && top.isEnrolledScreen()) {
        AlertDialog(
            onDismissRequest = { },   // must be acknowledged explicitly
            title = { Text(stringResource(R.string.mbpulse_export_notice_title)) },
            text = { Text(exportNotice) },
            confirmButton = {
                TextButton(onClick = { loadingViewModel.dismissExportNotification() }) { Text(stringResource(R.string.mbpulse_ok)) }
            }
        )
    }

    // Replace the whole stack with a single key — the Navigation 3 equivalent of
    // navigate(dest) { popUpTo(start) { inclusive = true } } and popUpTo(0).
    fun resetTo(key: NavKey) {
        backStack.clear()
        backStack.add(key)
    }

    // -- Account recovered on another phone (library-owned, like consent) -----
    // The backend accepts only the other phone now (device binding): nothing is
    // collected or sent from here; the only way on removes the study locally.
    if (enrolled != null && enrolled.deviceReplaced && top.isEnrolledScreen()) {
        DeviceReplacedScreen(
            ready            = enrolled,
            loadingViewModel = loadingViewModel,
            onRemoved        = { resetTo(LoadingKey) }
        )
        return
    }

    // -- Study ended (library-owned, like consent) -----------------------------
    // In place of every post-onboarding screen: nothing is collected any more,
    // remaining uploads finish, withdrawal stays available. No re-consent.
    val endedAt = enrolled?.endedAtMs
    if (enrolled != null && endedAt != null && top.isEnrolledScreen()) {
        StudyEndedScreen(
            ready            = enrolled,
            endedAtMs        = endedAt,
            loadingViewModel = loadingViewModel,
            onWithdrawn      = { resetTo(LoadingKey) }
        )
        return
    }

    // -- Consent review after a study update (library-owned, like consent) ---
    // Shown in place of any post-onboarding screen, so a custom home screen
    // cannot skip it. "Not now" postpones it until the next app start (or a
    // further change); meanwhile new/changed types are not collected.
    val review = enrolled?.consentReview
    var postponedReview by rememberSaveable { mutableStateOf<Int?>(null) }
    if (enrolled != null && review != null && postponedReview != review.hashCode() &&
        top.isEnrolledScreen()
    ) {
        ReconsentScreen(
            ready            = enrolled,
            review           = review,
            loadingViewModel = loadingViewModel,
            onPostpone       = { postponedReview = review.hashCode() },
            onWithdrawn      = { resetTo(LoadingKey) }
        )
        return
    }

    // -- Device selection (MbPulseConfig.deviceSelection) ---------------------
    // Until a device is chosen, shown in place of any post-onboarding screen on
    // every app start; "Not now" postpones it until the next one (debug builds
    // replay CSV meanwhile). Later changes go through the home screen's card.
    val deviceSelectionOn = remember { MbPulseLibrary.config.deviceSelection != null }
    val hasDevice by remember(activity) {
        pt.ua.mbpulse.collection.SelectedDevice.observe(activity).map<_, Boolean?> { it != null }
    }.collectAsStateWithLifecycle(initialValue = null)
    var postponedDevice by rememberSaveable { mutableStateOf(false) }
    if (deviceSelectionOn && enrolled != null && hasDevice == false && !postponedDevice &&
        top.isEnrolledScreen() && top !is DeviceSelectionKey
    ) {
        DeviceSelectionScreen(onClose = { postponedDevice = true })
        return
    }

    // Host screens open only from an enrolled participant's screens — never
    // during loading, onboarding or recovery.
    val navigator = remember(backStack, hostScreens) {
        object : MbPulseNavigator {
            override val hostScreens: List<HostScreen> = hostScreens
            override fun openHostScreen(id: String) {
                when {
                    hostScreens.none { it.id == id } ->
                        Log.w("NavGraph", "No host screen '$id' (registered: ${hostScreens.map { it.id }})")
                    !backStack.lastOrNull().isEnrolledScreen() ->
                        Log.w("NavGraph", "Host screen '$id' is only reachable once the participant is enrolled")
                    else -> backStack.add(HostScreenKey(id))
                }
            }
            override fun openQuestionnaire(questionnaireId: String) {
                if (backStack.lastOrNull().isEnrolledScreen()) backStack.add(QuestionnaireKey(questionnaireId))
                else Log.w("NavGraph", "Questionnaire '$questionnaireId' is only reachable once the participant is enrolled")
            }
            override fun openDeviceSelection() {
                when {
                    !deviceSelectionOn -> Log.w("NavGraph", "Device selection is not configured (MbPulseConfig.deviceSelection)")
                    !backStack.lastOrNull().isEnrolledScreen() ->
                        Log.w("NavGraph", "Device selection is only reachable once the participant is enrolled")
                    backStack.lastOrNull() !is DeviceSelectionKey -> backStack.add(DeviceSelectionKey)
                }
            }
        }
    }

    CompositionLocalProvider(LocalMbPulseNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (backStack.size <= 1) onExit?.invoke()
                else backStack.removeLastOrNull()
            },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {

                // -- Non-replaceable --------------------------------------------

                entry<LoadingKey> {
                    LoadingScreen(
                        viewModel = loadingViewModel,
                        onNavigateToStudySelection = { resetTo(StudySelectionKey) },
                        onNavigateToOnboarding     = { resetTo(OnboardingKey) },
                        onNavigateToHome           = { resetTo(HomeKey) }
                    )
                }

                entry<OnboardingKey> {
                    val state by loadingViewModel.state.collectAsStateWithLifecycle()
                    val config = state as? LoadingState.Ready
                    if (config?.endedAtMs != null) {
                        // Ended before this participant finished joining: back to the list.
                        LaunchedEffect(Unit) {
                            loadingViewModel.unSelectStudy()
                            resetTo(StudySelectionKey)
                        }
                    } else if (config != null) {
                        OnboardingOrchestrator(
                            studyId             = config.studyId,
                            studyName           = config.studyName,
                            studyVersion        = config.studyVersion,
                            definitionSha256    = config.definitionSha256,
                            participantPseudoId = config.participantPseudoId,
                            dataTypes           = config.dataTypes,
                            onOnboardingComplete = {
                                loadingViewModel.markOnboardingComplete()
                                resetTo(HomeKey)
                            },
                            onWithdraw = {
                                loadingViewModel.unSelectStudy()
                                resetTo(StudySelectionKey)
                            }
                        )
                    }
                }

                entry<RecoveryKey> {
                    RecoveryScreen(
                        onRecoverySuccess = { resetTo(LoadingKey) },
                        onBack            = { backStack.removeLastOrNull() }
                    )
                }

                // -- Replaceable ------------------------------------------------

                entry<StudySelectionKey> {
                    studySelectionScreen(
                        { studyId ->
                            loadingViewModel.selectStudy(studyId)
                            resetTo(LoadingKey)
                        },
                        { backStack.add(RecoveryKey) }
                    )
                }

                entry<HomeKey> {
                    measurementsScreen(
                        { schemaName, title -> backStack.add(HistoryKey(schemaName, title)) },
                        // Enforced here, not only by hiding the button, so a custom
                        // home screen cannot reach a disabled collection screen.
                        {
                            if (collectionScreenEnabled) backStack.add(CollectionKey)
                            else Log.w("NavGraph", "Collection screen is disabled for this build " +
                                "(MbPulseConfig.collectionScreen = ${MbPulseLibrary.config.collectionScreenAccess})")
                        },
                        { resetTo(LoadingKey) },
                        collectionViewModel
                    )
                }

                entry<CollectionKey> {
                    // Also covers a CollectionKey restored from saved state.
                    if (collectionScreenEnabled) {
                        collectionScreen(collectionViewModel) { backStack.removeLastOrNull() }
                    } else {
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    }
                }

                entry<HistoryKey> { key ->
                    historyScreen(
                        key.schemaName,
                        key.title,
                        { backStack.removeLastOrNull() },
                        hiltViewModel()
                    )
                }

                // -- Backend questionnaires (library-owned form) -----------------

                entry<QuestionnaireKey> { key ->
                    QuestionnaireScreen(
                        questionnaireId = key.questionnaireId,
                        onBack          = { backStack.removeLastOrNull() },
                        viewModel       = hiltViewModel()
                    )
                }

                // -- Device selection (library-owned) --------------------------------

                entry<DeviceSelectionKey> {
                    if (deviceSelectionOn) {
                        DeviceSelectionScreen(onClose = { backStack.removeLastOrNull() })
                    } else {
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    }
                }

                // -- Host-app screens (questionnaires, diaries, …) ----------------

                entry<HostScreenKey> { key ->
                    val screen = hostScreens.firstOrNull { it.id == key.id }
                    if (screen != null) {
                        screen.content { backStack.removeLastOrNull() }
                    } else {
                        // Restored from saved state after the host removed the screen.
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    }
                }
            }
        )
    }
}
