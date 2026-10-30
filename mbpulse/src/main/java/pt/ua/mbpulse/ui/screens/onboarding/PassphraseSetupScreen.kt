package pt.ua.mbpulse.ui.screens.onboarding

import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.ua.mbpulse.crypto.KeyRecoveryManager
import pt.ua.mbpulse.data.repository.MeasurementRepository
import kotlinx.coroutines.launch

@Composable
internal fun PassphraseSetupScreen(
    recoveryManager: KeyRecoveryManager,
    uploadState: UploadState,
    onboardingRepository: MeasurementRepository,
    onPassphraseConfirmed: (String, CharArray) -> Unit,
    onRetry: () -> Unit,
    onWithdraw: () -> Unit
) {
    var username         by remember { mutableStateOf("") }
    var passphrase       by remember { mutableStateOf("") }
    var confirmation     by remember { mutableStateOf("") }
    var usernameError    by remember { mutableStateOf<String?>(null) }
    var checkingUsername by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val usernameTakenText = stringResource(R.string.mbpulse_passphrase_username_taken)

    val strength = remember(passphrase) {
        recoveryManager.evaluatePassphraseStrength(passphrase.toCharArray())
    }

    val passphraseMatch = passphrase == confirmation && passphrase.isNotEmpty()
    val canProceed = username.isNotBlank() &&
            usernameError == null &&
            passphraseMatch &&
            strength != KeyRecoveryManager.Strength.WEAK &&
            uploadState is UploadState.Idle &&
            !checkingUsername

    val submit = {
        if (canProceed) {
            focusManager.clearFocus()
            scope.launch {
                checkingUsername = true
                val available = try {
                    onboardingRepository.isUsernameAvailable(username.trim())
                } catch (e: Exception) { true }
                checkingUsername = false
                if (!available) {
                    usernameError = usernameTakenText
                } else {
                    onPassphraseConfirmed(username.trim(), passphrase.toCharArray())
                }
            }
            Unit
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {

            // ------------------------------------------------------------------
            // Uploading overlay
            // ------------------------------------------------------------------
            if (uploadState is UploadState.Uploading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.mbpulse_passphrase_saving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Scaffold
            }

            // ------------------------------------------------------------------
            // Error state
            // ------------------------------------------------------------------
            if (uploadState is UploadState.Error) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(R.string.mbpulse_something_went_wrong),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = (uploadState as UploadState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(stringResource(R.string.mbpulse_try_again))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onWithdraw,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(stringResource(R.string.mbpulse_withdraw_menu))
                    }
                }
                return@Scaffold
            }

            // ------------------------------------------------------------------
            // Idle — passphrase form
            // ------------------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.mbpulse_passphrase_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mbpulse_passphrase_intro),

                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value         = username,
                    onValueChange = {
                        username = it
                        usernameError = null
                    },
                    label         = { Text(stringResource(R.string.mbpulse_username)) },
                    isError       = usernameError != null,
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    supportingText = {
                        if (usernameError != null) {
                            Text(usernameError!!, color = MaterialTheme.colorScheme.error)
                        } else if (username.isNotBlank()) {
                            Text(stringResource(R.string.mbpulse_passphrase_username_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.mbpulse_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction    = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    supportingText = {
                        val label = when (strength) {
                            KeyRecoveryManager.Strength.WEAK   -> stringResource(R.string.mbpulse_passphrase_weak)
                            KeyRecoveryManager.Strength.FAIR   -> stringResource(R.string.mbpulse_passphrase_fair)
                            KeyRecoveryManager.Strength.STRONG -> stringResource(R.string.mbpulse_passphrase_strong)
                        }
                        val colour = when (strength) {
                            KeyRecoveryManager.Strength.WEAK   -> MaterialTheme.colorScheme.error
                            KeyRecoveryManager.Strength.FAIR   -> MaterialTheme.colorScheme.tertiary
                            KeyRecoveryManager.Strength.STRONG -> MaterialTheme.colorScheme.primary
                        }
                        Text(label, color = colour)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.mbpulse_passphrase_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmation.isNotEmpty() && !passphraseMatch,
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction    = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    supportingText = {
                        if (confirmation.isNotEmpty() && !passphraseMatch) {
                            Text(
                                stringResource(R.string.mbpulse_passphrase_mismatch),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = submit,
                    enabled = canProceed,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (checkingUsername) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.mbpulse_continue))
                    }
                }
            }
        }
    }
}