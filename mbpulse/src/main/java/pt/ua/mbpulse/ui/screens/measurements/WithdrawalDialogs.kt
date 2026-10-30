package pt.ua.mbpulse.ui.screens.measurements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.loading.WithdrawalState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R

/**
 * Withdrawal UI — confirm dialog, progress overlay, error and deletion-receipt
 * dialogs — shared by the home screen and the library-owned consent review.
 * [onFinished] runs after the participant closes the receipt.
 */
@Composable
internal fun WithdrawalDialogs(
    loadingViewModel: LoadingViewModel,
    showConfirm: Boolean,
    onDismissConfirm: () -> Unit,
    onFinished: () -> Unit
) {
    val withdrawalState by loadingViewModel.withdrawal.collectAsState()
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { onDismissConfirm() },
            title = { Text(stringResource(R.string.mbpulse_withdraw_confirm_title)) },
            text  = {
                Text(stringResource(R.string.mbpulse_withdraw_confirm_text))

            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissConfirm()
                    loadingViewModel.withdrawFromStudy()
                }) {
                    Text(stringResource(R.string.mbpulse_withdraw_confirm_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissConfirm() }) { Text(stringResource(R.string.mbpulse_cancel)) }
            }
        )
    }

    (withdrawalState as? WithdrawalState.Error)?.let { error ->
        AlertDialog(
            onDismissRequest = { loadingViewModel.resetWithdrawalState() },
            title = { Text(stringResource(R.string.mbpulse_withdraw_failed_title)) },
            text  = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = {
                    loadingViewModel.resetWithdrawalState()
                    loadingViewModel.withdrawFromStudy()
                }) { Text(stringResource(R.string.mbpulse_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { loadingViewModel.resetWithdrawalState() }) { Text(stringResource(R.string.mbpulse_close)) }
            }
        )
    }

    (withdrawalState as? WithdrawalState.Done)?.let { done ->
        val finish = {
            loadingViewModel.resetWithdrawalState()
            onFinished()   // back to Loading → routes to study selection (study now unselected)
        }
        AlertDialog(
            onDismissRequest = finish,
            title = { Text(stringResource(R.string.mbpulse_withdraw_done_title)) },
            text  = {
                val r = done.receipt
                if (r == null) {
                    Text(stringResource(R.string.mbpulse_withdraw_done_local))
                } else {
                    Column {
                        Text(stringResource(R.string.mbpulse_withdraw_done_server))
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.mbpulse_withdraw_records, r.recordsDeleted, r.recordsDeleted))
                        Text(pluralStringResource(R.plurals.mbpulse_withdraw_consents, r.consentDeleted, r.consentDeleted))
                        Text(pluralStringResource(R.plurals.mbpulse_withdraw_enrolments, r.participantDeleted, r.participantDeleted))
                        Text(pluralStringResource(R.plurals.mbpulse_withdraw_accounts, r.accountDeleted, r.accountDeleted))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when {
                                r.remainingOnServer == 0 ->
                                    stringResource(R.string.mbpulse_withdraw_recheck_ok)
                                r.remainingOnServer > 0 ->
                                    pluralStringResource(R.plurals.mbpulse_withdraw_recheck_remaining,
                                        r.remainingOnServer, r.remainingOnServer)
                                else ->
                                    stringResource(R.string.mbpulse_withdraw_recheck_failed)
                            },
                            color = if (r.remainingOnServer > 0)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        r.signatureVerified?.let { verified ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(if (verified) R.string.mbpulse_withdraw_signature_ok
                                               else R.string.mbpulse_withdraw_signature_failed),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.mbpulse_withdraw_receipt_id, r.receiptId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            r.deletedAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = finish) { Text(stringResource(R.string.mbpulse_done)) }
            }
        )
    }

    if (withdrawalState is WithdrawalState.InProgress) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.mbpulse_withdraw_in_progress), color = Color.White)
            }
        }
    }
}
