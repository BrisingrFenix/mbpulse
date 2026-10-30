package pt.ua.mbpulse.ui.screens.recovery

import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryScreen(
    onRecoverySuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var username   by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current

    val canSubmit = username.isNotBlank() && passphrase.isNotEmpty() &&
            state !is RecoveryState.Loading
    val submit = {
        if (canSubmit) {
            focusManager.clearFocus()
            viewModel.recover(
                username   = username.trim(),
                passphrase = passphrase.toCharArray()
            )
        }
    }

    LaunchedEffect(state) {
        if (state is RecoveryState.Success) onRecoverySuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mbpulse_recovery_title)) },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.mbpulse_recovery_heading),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.mbpulse_recovery_intro),

                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value           = username,
                onValueChange   = { username = it; viewModel.resetState() },
                label           = { Text(stringResource(R.string.mbpulse_username)) },
                isError         = state is RecoveryState.Error,
                singleLine      = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier        = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value                = passphrase,
                onValueChange        = { passphrase = it; viewModel.resetState() },
                label                = { Text(stringResource(R.string.mbpulse_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                isError              = state is RecoveryState.Error,
                singleLine           = true,
                keyboardOptions      = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction    = ImeAction.Done
                ),
                keyboardActions      = KeyboardActions(onDone = { submit() }),
                supportingText       = {
                    if (state is RecoveryState.Error) {
                        Text(
                            (state as RecoveryState.Error).message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick  = submit,
                enabled  = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (state is RecoveryState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.mbpulse_recovery_action))
                }
            }

            (state as? RecoveryState.Loading)?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(it.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}