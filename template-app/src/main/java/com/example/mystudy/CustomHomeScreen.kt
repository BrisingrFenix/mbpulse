package com.example.mystudy

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.ui.MbPulseNavHost
import pt.ua.mbpulse.ui.navigation.LocalMbPulseNavigator
import pt.ua.mbpulse.ui.screens.questionnaire.QuestionnaireCards
import pt.ua.mbpulse.ui.screens.collection.CollectionViewModel
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.measurements.CollectionMeasurementsViewModel
import pt.ua.mbpulse.ui.screens.collection.stageText
import pt.ua.mbpulse.ui.theme.MbPulseTheme
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

/**
 * WORKED EXAMPLE — replacing one library screen while keeping the rest.
 *
 * This is the pattern to copy for any replaceable slot (`studySelectionScreen`,
 * `measurementsScreen`, `collectionScreen`, `historyScreen`). The loading,
 * onboarding (consent/passphrase) and recovery screens are deliberately NOT
 * replaceable — they are the security- and ethics-critical path and stay
 * library-owned.
 *
 * To try it: in [MainActivity], swap `setContent { MbPulseApp() }` for
 * `setContent { AppWithCustomHome() }`.
 */
@Composable
fun AppWithCustomHome() {
    MbPulseTheme {      // colours/logo from MbPulseConfig.branding
        MbPulseNavHost(
            hostScreens = listOf(SymptomLog.screen),
            // Only the home/measurements slot is overridden; every screen not
            // named here keeps its library default.
            measurementsScreen = { onCardClick, onNavigateToCollection, _, collectionViewModel ->
                CustomHomeScreen(
                    onCardClick            = onCardClick,
                    onNavigateToCollection = onNavigateToCollection,
                    collectionViewModel    = collectionViewModel
                )
            }
        )
    }
}

/**
 * A minimal custom home screen showing the three things an override usually
 * needs:
 *  1. The slot's navigation callbacks (`onCardClick`, `onNavigateToCollection`).
 *  2. The activity-scoped [CollectionViewModel] handed in by the slot (live
 *     recording status).
 *  3. Library ViewModels obtained the normal Hilt way — here
 *     [CollectionMeasurementsViewModel] for the per-signal latest values and
 *     the activity-scoped [LoadingViewModel] for study/consent state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomHomeScreen(
    onCardClick: (schemaName: String, title: String) -> Unit,
    onNavigateToCollection: () -> Unit,
    collectionViewModel: CollectionViewModel,
    cardsViewModel: CollectionMeasurementsViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel = hiltViewModel(activity)
    val loadingState by loadingViewModel.state.collectAsStateWithLifecycle()
    val ready = loadingState as? LoadingState.Ready

    // Feed the consented data types into the cards VM (same wiring the
    // library's own MeasurementsScreen does).
    LaunchedEffect(ready?.consentedDataTypes) {
        ready?.consentedDataTypes?.let { cardsViewModel.observeForConsentedTypes(it) }
    }

    val status by collectionViewModel.status.collectAsState()
    val cards  by cardsViewModel.cards.collectAsState()
    val navigator = LocalMbPulseNavigator.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("My Study — custom home", fontWeight = FontWeight.SemiBold) },
                colors = mbPulseTopAppBarColors()      // same bar style as the library screens
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (status.isRunning)
                    "Recording — ${status.epochsProcessed} epochs · ${stageText(status.currentStage)}"
                else
                    "Not recording · ${status.recordsPending} pending / ${status.recordsSynced} synced",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Passive studies hide the collection screen in release builds
                // (MbPulseConfig.collectionScreen) — follow the same rule here.
                if (MbPulseLibrary.isCollectionScreenEnabled(activity)) {
                    Button(onClick = onNavigateToCollection) { Text("Collection") }
                }
                // Host screens (MbPulseNavHost(hostScreens)) open through the
                // library's navigator — here the example symptom log.
                OutlinedButton(onClick = { navigator.openHostScreen(SymptomLog.SCREEN_ID) }) {
                    Text("Log a symptom")
                }
            }

            // The study's backend questionnaires (published in the setup
            // wizard) — the same cards the library's home screen shows.
            QuestionnaireCards()

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cards) { card ->
                    Card(
                        onClick  = { onCardClick(card.schemaName, card.title) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(card.title, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    card.value,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (card.unit.isNotEmpty()) {
                                    Spacer(Modifier.height(0.dp))
                                    Text(
                                        " ${card.unit}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
