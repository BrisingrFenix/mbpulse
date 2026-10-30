package pt.ua.mbpulse.ui.screens.healthconnect

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.collection.HealthConnectImportResult
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

/**
 * Lets a participant import their existing history from Health Connect into the
 * study. The categories offered are whatever the host registered in
 * [pt.ua.mbpulse.MbPulseConfig.healthConnectSources]. Reachable
 * composable — wire it to a route/button in your host UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthConnectImportScreen(
    onBack: () -> Unit,
    viewModel: HealthConnectImportViewModel = hiltViewModel()
) {
    val activity = LocalActivity.current as ComponentActivity
    val loadingViewModel: LoadingViewModel = hiltViewModel(activity)
    val loadingState by loadingViewModel.state.collectAsStateWithLifecycle()
    val ready = loadingState as? LoadingState.Ready

    val importState by viewModel.state.collectAsStateWithLifecycle()

    val available  = remember { viewModel.isAvailable() }
    val configured = remember { viewModel.isConfigured() }
    var granted    by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { grantedPerms ->
        granted = grantedPerms.containsAll(viewModel.permissions)
    }

    LaunchedEffect(available, configured) {
        if (available && configured) granted = viewModel.hasPermission()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.mbpulse_hc_title)) },
                colors = mbPulseTopAppBarColors(),

                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mbpulse_back))
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.mbpulse_hc_intro),

                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (configured && viewModel.sourceNames.isNotEmpty()) {
                Notice(stringResource(R.string.mbpulse_hc_categories, viewModel.sourceNames.joinToString()))
            }

            when {
                !configured -> Notice(stringResource(R.string.mbpulse_hc_not_configured))
                !available  -> Notice(stringResource(R.string.mbpulse_hc_unavailable))
                ready == null -> Notice(stringResource(R.string.mbpulse_hc_onboarding_first))
                !granted -> Button(
                    onClick = { permissionLauncher.launch(viewModel.requestPermissions) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.mbpulse_hc_allow)) }

                else -> Button(
                    onClick = { viewModel.importLastDays(ready) },
                    enabled = importState !is ImportUiState.Importing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(pluralStringResource(R.plurals.mbpulse_hc_import_days, viewModel.importDays, viewModel.importDays)) }
            }

            when (val s = importState) {
                is ImportUiState.Importing -> Row2(stringResource(R.string.mbpulse_hc_importing))
                is ImportUiState.Done -> Notice(s.result.message())
                else -> Unit
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Row2(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HealthConnectImportResult.message(): String = when (this) {
    is HealthConnectImportResult.Success ->
        if (records == 0) stringResource(R.string.mbpulse_hc_result_empty)
        else pluralStringResource(R.plurals.mbpulse_hc_result_imported, records, records, epochs)
    HealthConnectImportResult.NotConfigured  -> stringResource(R.string.mbpulse_hc_not_configured)
    HealthConnectImportResult.Unavailable    -> stringResource(R.string.mbpulse_hc_unavailable)
    HealthConnectImportResult.PermissionRequired -> stringResource(R.string.mbpulse_hc_permission_required)
    is HealthConnectImportResult.Failed      -> stringResource(R.string.mbpulse_hc_result_failed, message)
}
