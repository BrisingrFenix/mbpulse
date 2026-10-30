package pt.ua.mbpulse.ui.screens.studySelection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.data.repository.StudySummary
import pt.ua.mbpulse.ui.theme.LocalMbPulseBranding
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun StudySelectionScreen(
    onStudySelected: (studyId: String) -> Unit,
    onNavigateToRecovery: () -> Unit,
    viewModel: StudySelectionViewModel = hiltViewModel()
) {
    val selectionState by viewModel.selectionState.collectAsStateWithLifecycle()
    val joinState      by viewModel.joinState.collectAsStateWithLifecycle()

    var selectedStudy  by remember { mutableStateOf<StudySummary?>(null) }
    var joinCode       by remember { mutableStateOf("") }

    // Join code dialog
    selectedStudy?.let { study ->
        if (study.hasJoinCode) {
            AlertDialog(
                onDismissRequest = {
                    selectedStudy = null
                    joinCode = ""
                    viewModel.resetJoinState()
                },
                title = { Text(study.studyName) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.mbpulse_join_code_prompt),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value              = joinCode,
                            onValueChange      = { joinCode = it; viewModel.resetJoinState() },
                            label              = { Text(stringResource(R.string.mbpulse_join_code_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            isError            = joinState is JoinState.Error,
                            supportingText     = {
                                if (joinState is JoinState.Error) {
                                    Text(
                                        (joinState as JoinState.Error).message,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.joinStudy(study, joinCode) { studyId ->
                                selectedStudy = null
                                joinCode = ""
                                onStudySelected(studyId)
                            }
                        },
                        enabled = joinCode.isNotEmpty() && joinState !is JoinState.Verifying
                    ) {
                        if (joinState is JoinState.Verifying) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.mbpulse_join))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        selectedStudy = null
                        joinCode = ""
                        viewModel.resetJoinState()
                    }) {
                        Text(stringResource(R.string.mbpulse_cancel))
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mbpulse_studies_title), fontWeight = FontWeight.SemiBold) },
                colors = mbPulseTopAppBarColors(),
                actions = {
                    IconButton(onClick = { viewModel.loadStudies() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mbpulse_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier         = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = selectionState) {

                is StudySelectionState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is StudySelectionState.Error -> {
                    Column(
                        modifier              = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment   = Alignment.CenterHorizontally,
                        verticalArrangement   = Arrangement.Center
                    ) {
                        Text(
                            stringResource(R.string.mbpulse_studies_failed),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { viewModel.loadStudies() }) { Text(stringResource(R.string.mbpulse_retry)) }
                    }
                }

                is StudySelectionState.Loaded -> {
                    if (state.studies.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.mbpulse_studies_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier            = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding      = PaddingValues(vertical = 16.dp)
                        ) {
                            item {
                                LocalMbPulseBranding.current.logoRes?.let { logo ->
                                    Image(
                                        painterResource(logo), contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().height(72.dp).padding(bottom = 12.dp)
                                    )
                                }
                                Text(
                                    stringResource(R.string.mbpulse_studies_intro),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            items(state.studies) { item ->
                                StudyCard(
                                    study   = item.study,
                                    enabled = item.isSupported,
                                    onClick = {
                                        if (item.study.hasJoinCode) {
                                            selectedStudy = item.study
                                        } else {
                                            onStudySelected(item.study.studyId)
                                        }
                                    }
                                )
                            }
                            item {
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick  = onNavigateToRecovery,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.mbpulse_studies_recover),
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

@Composable
private fun StudyCard(study: StudySummary, enabled: Boolean, onClick: () -> Unit) {
    val disabledAlpha = 0.38f
    Card(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor         = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = study.studyName,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (enabled) MaterialTheme.colorScheme.onSurface
                                 else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = if (enabled) stringResource(R.string.mbpulse_studies_version, study.studyVersion)
                            else stringResource(R.string.mbpulse_studies_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else disabledAlpha)
                )
            }
            if (study.hasJoinCode) {
                Icon(
                    imageVector        = Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.mbpulse_join_code_required),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else disabledAlpha)
                )
            }
        }
    }
}