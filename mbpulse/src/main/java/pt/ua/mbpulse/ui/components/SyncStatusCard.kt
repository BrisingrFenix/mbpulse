package pt.ua.mbpulse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.RecordUploader
import pt.ua.mbpulse.collection.SyncProgress
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

internal data class SyncStatusUi(val progress: SyncProgress = SyncProgress(), val pendingEpochs: Int = 0)

@HiltViewModel
internal class SyncStatusViewModel @Inject constructor(
    private val uploader: RecordUploader,
    dao: EncryptedRecordDao
) : ViewModel() {

    val status: StateFlow<SyncStatusUi> =
        combine(uploader.progress, dao.observePendingEpochCount()) { p, pending -> SyncStatusUi(p, pending) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusUi())

    /** Uploads in-process (the screen is open), so progress shows live. */
    fun syncNow() {
        viewModelScope.launch { uploader.syncPending() }
    }
}

/**
 * Upload status for the home screen: a progress bar with epochs and percentage
 * while uploading, otherwise what is still waiting and when the last full sync
 * finished. Syncs whenever the screen is shown with data pending. Usable in a
 * replaced home screen too.
 */
@Composable
public fun SyncStatusCard(modifier: Modifier = Modifier) {
    SyncStatusCard(modifier, hiltViewModel())
}

@Composable
internal fun SyncStatusCard(modifier: Modifier, viewModel: SyncStatusViewModel) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        SyncStatusContent(Modifier.padding(12.dp), viewModel)
    }
}

/** The body of [SyncStatusCard], for embedding in another card (e.g. the home status panel). */
@Composable
internal fun SyncStatusContent(
    modifier: Modifier = Modifier,
    viewModel: SyncStatusViewModel = hiltViewModel()
) {
    val ui by viewModel.status.collectAsState()
    val p = ui.progress
    LifecycleResumeEffect(Unit) {
        viewModel.syncNow()          // no-op when nothing is pending or a run is active
        onPauseOrDispose { }
    }
    run {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.running) {
                Text(
                    pluralStringResource(R.plurals.mbpulse_sync_uploading, p.totalEpochs,
                        p.uploadedEpochs, p.totalEpochs, p.percent),
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ui.pendingEpochs == 0) stringResource(R.string.mbpulse_sync_all_uploaded)
                            else pluralStringResource(R.plurals.mbpulse_sync_waiting, ui.pendingEpochs, ui.pendingEpochs),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            p.lastSuccessAtMs?.let {
                                stringResource(R.string.mbpulse_sync_last_full, relativeTimeText(it, System.currentTimeMillis()))
                            } ?: stringResource(R.string.mbpulse_sync_never),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        p.lastError?.takeIf { ui.pendingEpochs > 0 }?.let {
                            Text(
                                stringResource(R.string.mbpulse_sync_last_error, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (ui.pendingEpochs > 0) {
                        TextButton(onClick = viewModel::syncNow) { Text(stringResource(R.string.mbpulse_sync_now)) }
                    }
                }
            }
        }
    }
}
