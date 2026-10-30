package pt.ua.mbpulse.ui.screens.ended

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.components.SyncStatusCard
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.measurements.WithdrawalDialogs
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import java.text.DateFormat
import java.util.Date

/**
 * Library-owned (not replaceable — like re-consent): the research team ended
 * the study. Shown in place of every enrolled screen, so a custom home screen
 * cannot hide it. Nothing is collected any more; the upload card sends what
 * was measured before the end; withdrawal (erasure) stays available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudyEndedScreen(
    ready: LoadingState.Ready,
    endedAtMs: Long,
    loadingViewModel: LoadingViewModel,
    onWithdrawn: () -> Unit
) {
    var showWithdrawConfirm by rememberSaveable { mutableStateOf(false) }
    val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(endedAtMs))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready.studyName, fontWeight = FontWeight.SemiBold) },
                colors = mbPulseTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mbpulse_study_ended_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.mbpulse_study_ended_body, date), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.mbpulse_study_ended_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SyncStatusCard()
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showWithdrawConfirm = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Text(stringResource(R.string.mbpulse_study_ended_withdraw))
            }
        }
    }

    WithdrawalDialogs(
        loadingViewModel = loadingViewModel,
        showConfirm      = showWithdrawConfirm,
        onDismissConfirm = { showWithdrawConfirm = false },
        onFinished       = onWithdrawn
    )
}
