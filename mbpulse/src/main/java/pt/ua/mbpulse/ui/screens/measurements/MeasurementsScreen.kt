package pt.ua.mbpulse.ui.screens.measurements

import pt.ua.mbpulse.logging.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.CollectionRequirements
import pt.ua.mbpulse.ui.components.CollectionDataCard
import pt.ua.mbpulse.ui.components.HostScreenCard
import pt.ua.mbpulse.ui.screens.device.DeviceStatusCard
import pt.ua.mbpulse.ui.navigation.LocalMbPulseNavigator
import pt.ua.mbpulse.ui.screens.questionnaire.ObserveQuestionnaires
import pt.ua.mbpulse.ui.screens.questionnaire.QuestionnaireCard
import pt.ua.mbpulse.ui.screens.questionnaire.QuestionnairesViewModel
import pt.ua.mbpulse.ui.components.SyncStatusCard
import pt.ua.mbpulse.ui.screens.healthconnect.HealthConnectImportScreen
import pt.ua.mbpulse.ui.screens.collection.CollectionViewModel
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.loading.WithdrawalState
import pt.ua.mbpulse.ui.theme.LocalMbPulseBranding
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues

// private const val TAG = "MeasurementsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MeasurementsScreen(
    onCardClick: (schemaName: String, title: String) -> Unit,
    onNavigateToCollection: () -> Unit,
    onReset: () -> Unit,
    collectionViewModel: CollectionViewModel,        // activity-scoped, passed from NavGraph
    cardsViewModel: CollectionMeasurementsViewModel = hiltViewModel()
) {
    val activity       = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel = hiltViewModel(activity)
    val loadingState   by loadingViewModel.state.collectAsState()
    val ready          = loadingState as? LoadingState.Ready

    // Health Connect import — only offered when the host registered at least one
    // import source. Shown as a full screen in place of the measurements grid.
    val importEnabled = remember { MbPulseLibrary.config.healthConnectSources.isNotEmpty() }
    val collectionScreenEnabled = remember { MbPulseLibrary.isCollectionScreenEnabled(activity) }
    val debugBuild = remember { CollectionRequirements.isDebuggableBuild(activity) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    if (showImport) {
        BackHandler { showImport = false }
        HealthConnectImportScreen(onBack = { showImport = false })
        return
    }

    // Withdrawal — permanently leave the study and erase all data.
    var menuOpen            by rememberSaveable { mutableStateOf(false) }
    var showWithdrawConfirm by rememberSaveable { mutableStateOf(false) }

    val navigator   = LocalMbPulseNavigator.current
    val hostScreens = remember(navigator) { navigator.hostScreens.filter { it.showOnHome } }
    val questionnairesViewModel: QuestionnairesViewModel = hiltViewModel()
    ObserveQuestionnaires(questionnairesViewModel)
    val questionnaires by questionnairesViewModel.entries.collectAsState()

    val collectionStatus by collectionViewModel.status.collectAsState()
    val cards            by cardsViewModel.cards.collectAsState()

    // Cards only for what the participant accepted — declined types are not collected.
    LaunchedEffect(ready?.consentedDataTypes) {
        ready?.consentedDataTypes?.let { cardsViewModel.observeForConsentedTypes(it) }
    }

    // The "your data was accessed" notice is library-owned (NavGraph), not here.

    val scope = rememberCoroutineScope()

    // Pull-to-refresh / menu "Refresh": study (→ re-consent review), questionnaires,
    // upload and export notices — without closing the app.
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    val refreshDone = stringResource(R.string.mbpulse_refresh_done)
    val refreshOffline = stringResource(R.string.mbpulse_refresh_offline)
    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            val result = loadingViewModel.refresh()
            (loadingViewModel.state.value as? LoadingState.Ready)?.takeIf { it.onboardingComplete }?.let {
                questionnairesViewModel.refresh(it.studyId, it.consentedTypeIds)
            }
            refreshing = false
            snackbar.showSnackbar(when (result) {
                LoadingViewModel.RefreshResult.Updated -> refreshDone
                LoadingViewModel.RefreshResult.Offline -> refreshOffline
                is LoadingViewModel.RefreshResult.Failed -> result.message
            })
        }
    }

    // One clock for "3 min ago" and staleness, ticking every minute.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(LocalMbPulseBranding.current.homeTitle ?: stringResource(R.string.mbpulse_home_title),
                        fontWeight = FontWeight.Bold)
                },
                colors = mbPulseTopAppBarColors(),
                actions = {
                    // Session mode: the collection screen is the main action.
                    // Passive modes collect without it; it is reachable from the
                    // menu where enabled (MbPulseConfig.collectionScreen).
                    val passive = MbPulseLibrary.config.collectionMode.isPassive
                    if (collectionScreenEnabled && !passive) {
                        IconButton(onClick = onNavigateToCollection) {
                            Icon(Icons.Filled.Sensors, contentDescription = stringResource(R.string.mbpulse_home_action_collection))
                        }
                    }
                    if (importEnabled) {
                        IconButton(onClick = { showImport = true }) {
                            Icon(Icons.Filled.MonitorHeart, contentDescription = stringResource(R.string.mbpulse_home_action_import))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mbpulse_more_options))
                        }
                        DropdownMenu(
                            expanded         = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text    = { Text(stringResource(R.string.mbpulse_home_menu_refresh)) },
                                onClick = { menuOpen = false; refresh() }
                            )
                            if (collectionScreenEnabled && passive) {
                                DropdownMenuItem(
                                    text    = { Text(stringResource(R.string.mbpulse_home_menu_collection)) },
                                    onClick = { menuOpen = false; onNavigateToCollection() }
                                )
                            }
                            DropdownMenuItem(
                                text    = { Text(stringResource(R.string.mbpulse_withdraw_menu)) },
                                onClick = {
                                    menuOpen = false
                                    showWithdrawConfirm = true
                                }
                            )
                            // Development reset wipes enrolment, keys and local data —
                            // debuggable builds only, never in the release app.
                            if (debugBuild) {
                                DropdownMenuItem(
                                    text    = { Text(stringResource(R.string.mbpulse_home_menu_reset), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            loadingViewModel.resetForDevelopment()
                                            onReset()
                                        }
                                        Log.d("Reset", "Reset")
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->

        if (ready != null && !ready.onboardingComplete) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.mbpulse_home_onboarding_incomplete),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh    = ::refresh,
            modifier     = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Recording, offline and upload state in one place (scrolls with the cards).
                if (ready != null) {
                    item(span = { GridItemSpan(2) }) {
                        StatusPanel(
                            collectionStatus = collectionStatus,
                            offlineSinceMs = ready.offlineSinceMs,
                            nowMs = nowMs
                        )
                    }
                }

                // The wearable chosen in the device-selection step (MbPulseConfig.deviceSelection).
                if (ready != null && MbPulseLibrary.config.deviceSelection != null) {
                    item(key = "device-card", span = { GridItemSpan(2) }) {
                        DeviceStatusCard(onClick = { navigator.openDeviceSelection() }, modifier = Modifier)
                    }
                }

                // Backend questionnaires (consented data types only). Keys must not
                // collide with the measurement cards below, which are keyed by schema
                // name — an answered questionnaire has one ("questionnaire:<id>").
                questionnaires.forEach { entry ->
                    item(key = "questionnaire-card/${entry.questionnaire.questionnaireId}", span = { GridItemSpan(2) }) {
                        QuestionnaireCard(entry, onClick = { navigator.openQuestionnaire(entry.questionnaire.questionnaireId) })
                    }
                }

                // Host-app screens (questionnaires, diaries, …) — MbPulseNavHost(hostScreens).
                hostScreens.forEach { screen ->
                    item(key = "host-screen/${screen.id}", span = { GridItemSpan(2) }) {
                        HostScreenCard(screen = screen, onClick = { navigator.openHostScreen(screen.id) })
                    }
                }

                if (cards.isEmpty() && ready != null) {
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            stringResource(
                                if (MbPulseLibrary.config.collectionMode.isPassive) R.string.mbpulse_home_empty_passive
                                else R.string.mbpulse_home_empty_session
                            ),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                        )
                    }
                }

                cards.forEach { card ->
                    item(key = card.schemaName, span = { GridItemSpan(if (card.wide) 2 else 1) }) {
                        CollectionDataCard(
                            card = card,
                            onClick = { onCardClick(card.schemaName, card.title) },
                            nowMs = nowMs
                        )
                    }
                }
            }
        }
    }

    // ---- Withdrawal: confirm dialog, progress overlay, error/receipt dialogs ----

    WithdrawalDialogs(
        loadingViewModel = loadingViewModel,
        showConfirm      = showWithdrawConfirm,
        onDismissConfirm = { showWithdrawConfirm = false },
        onFinished       = onReset
    )
}
