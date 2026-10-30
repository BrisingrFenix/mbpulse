package pt.ua.mbpulse.ui.screens.collection

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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.collection.SyncWorker
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.CollectionStage
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CollectionScreen(
    viewModel: CollectionViewModel,
    onBack: () -> Unit
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    // Get participant info from the activity-scoped LoadingViewModel
    val activity = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel = hiltViewModel(activity)
    val loadingState by loadingViewModel.state.collectAsStateWithLifecycle()
    val ready = loadingState as? LoadingState.Ready

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.mbpulse_collection_title)) },
                colors = mbPulseTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.mbpulse_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            Text(
                stringResource(
                    if (MbPulseLibrary.config.collectionMode.isPassive) R.string.mbpulse_collection_intro_passive
                    else R.string.mbpulse_collection_intro_session
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow(stringResource(R.string.mbpulse_collection_status), stageText(status.currentStage))
                    StatusRow(stringResource(R.string.mbpulse_collection_epochs), status.epochsProcessed.toString())
                    StatusRow(stringResource(R.string.mbpulse_collection_pending), status.recordsPending.toString())
                    StatusRow(stringResource(R.string.mbpulse_collection_synced), status.recordsSynced.toString())

                    status.error?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Manual sync trigger — useful during development
            if (!status.isRunning  && status.recordsPending > 0) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { SyncWorker.syncNow(activity) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.mbpulse_collection_sync_now, status.recordsPending))
                }
            }


            // Start / Stop button
            if (status.isRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Button(
                        onClick = { viewModel.stopCollection() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("  " + stringResource(R.string.mbpulse_collection_stop))
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (ready != null) {
                            viewModel.startCollection(
                                participantPseudoId = ready.participantPseudoId,
                                wrappedDekBytes     = ready.wrappedDekBytes,
                                piPublicKeyBytes    = ready.piPublicKeyBytes,
                                studyId             = ready.studyId,
                                consentedTypeIds    = ready.consentedTypeIds,
                                samplingSecondsByTypeId = ready.samplingSecondsByTypeId
                            )
                        }
                    },
                    enabled = ready != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("  " + stringResource(R.string.mbpulse_collection_start))
                }
            }

            if (ready?.wrappedDekBytes?.isEmpty() == true) {
                Text(
                    stringResource(R.string.mbpulse_collection_onboarding_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** The collection stage in the participant's language. */
@Composable
public fun stageText(stage: CollectionStage): String = when (stage) {
    CollectionStage.Idle -> stringResource(R.string.mbpulse_stage_idle)
    CollectionStage.Starting -> stringResource(R.string.mbpulse_stage_starting)
    CollectionStage.Recording -> stringResource(R.string.mbpulse_stage_recording)
    is CollectionStage.Reconnecting ->
        pluralStringResource(R.plurals.mbpulse_stage_reconnecting, stage.seconds.toInt(), stage.seconds.toInt())
    is CollectionStage.Complete ->
        pluralStringResource(R.plurals.mbpulse_stage_complete, stage.epochs, stage.epochs)
    CollectionStage.Stopped -> stringResource(R.string.mbpulse_stage_stopped)
    CollectionStage.Error -> stringResource(R.string.mbpulse_stage_error)
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}