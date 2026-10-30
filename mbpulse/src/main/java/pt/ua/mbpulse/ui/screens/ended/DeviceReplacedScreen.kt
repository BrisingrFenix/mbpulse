package pt.ua.mbpulse.ui.screens.ended

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

/**
 * Library-owned (not replaceable — like "study ended"): the participant's
 * account was recovered on another phone, and the backend accepts only that
 * phone now (device binding). Shown in place of every enrolled screen. Nothing
 * is collected or sent from here; the only action removes the study from this
 * phone. Withdrawal is not offered — it belongs to the participant's device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceReplacedScreen(
    ready: LoadingState.Ready,
    loadingViewModel: LoadingViewModel,
    onRemoved: () -> Unit
) {
    var removing by rememberSaveable { mutableStateOf(false) }

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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.mbpulse_device_replaced_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.mbpulse_device_replaced_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.mbpulse_device_replaced_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.mbpulse_device_replaced_not_you),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    removing = true
                    loadingViewModel.removeReplacedDevice(onRemoved)
                },
                enabled = !removing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (removing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.mbpulse_device_replaced_remove))
            }
        }
    }
}
