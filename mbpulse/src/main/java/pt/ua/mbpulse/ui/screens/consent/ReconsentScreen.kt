package pt.ua.mbpulse.ui.screens.consent

import pt.ua.mbpulse.logging.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.crypto.ConsentSignature
import pt.ua.mbpulse.ui.errorText
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.ui.screens.loading.ConsentReview
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import pt.ua.mbpulse.ui.screens.loading.LoadingViewModel
import pt.ua.mbpulse.ui.screens.measurements.WithdrawalDialogs
import pt.ua.mbpulse.ui.screens.onboarding.OnboardingViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "Reconsent"

internal sealed interface ReconsentState {
    object Idle : ReconsentState
    object Submitting : ReconsentState
    data class Error(val message: String) : ReconsentState
}

/**
 * Records a re-consent after a study update: a new consent record over EVERY
 * current data type (the reviewed decisions, the previous choice for the
 * rest), for the current study version, signed with the device key and
 * appended through `submit_consent_record` — earlier records stay (immutable),
 * so the server holds the participant's full consent history. Only once the
 * server has it are the local choices and agreed terms replaced; the
 * resulting reload restarts collection with the new consent.
 */
@HiltViewModel
internal class ReconsentViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: MeasurementRepository,
    private val keystoreManager: KeystoreManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _state = MutableStateFlow<ReconsentState>(ReconsentState.Idle)
    val state: StateFlow<ReconsentState> = _state

    private var last: Triple<LoadingState.Ready, ConsentReview, List<ConsentDecision>>? = null

    fun submit(ready: LoadingState.Ready, review: ConsentReview, reviewed: List<ConsentDecision>) {
        if (_state.value == ReconsentState.Submitting) return
        last = Triple(ready, review, reviewed)
        _state.value = ReconsentState.Submitting
        viewModelScope.launch {
            try {
                val record = ConsentRecord(
                    studyId             = ready.studyId,
                    participantPseudoId = ready.participantPseudoId,
                    studyVersion        = ready.studyVersion,
                    definitionSha256    = ready.definitionSha256,
                    decisions           = ConsentTerms.mergeDecisions(ready.dataTypes, review.previouslyConsented, reviewed)
                )
                val signature = withContext(Dispatchers.Default) {
                    keystoreManager.sign(ConsentSignature.signingString(
                        participantPseudoId = record.participantPseudoId,
                        studyId             = record.studyId,
                        studyVersion        = record.studyVersion,
                        definitionSha256    = record.definitionSha256,
                        completedAtMs       = record.completedAtMs,
                        decisions           = record.decisions
                    ).toByteArray(Charsets.UTF_8))
                }
                repository.insertConsentRecord(record, signature, keystoreManager.getPublicKeyBytes())
                dataStore.edit { prefs ->
                    prefs[OnboardingViewModel.CONSENTED_TYPE_IDS] =
                        record.decisions.filter { it.accepted }.joinToString(",") { it.dataTypeId }
                    prefs[OnboardingViewModel.CONSENTED_TERMS] = ConsentTerms.encode(ready.dataTypes)
                }
                Log.i(TAG, "Re-consent recorded for study version ${ready.studyVersion} (definition ${ready.definitionSha256})")
                _state.value = ReconsentState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Re-consent submission failed", e)
                _state.value = ReconsentState.Error(context.errorText(e, R.string.mbpulse_reconsent_failed))

            }
        }
    }

    fun retry() {
        val (ready, review, reviewed) = last ?: return
        _state.value = ReconsentState.Idle
        submit(ready, review, reviewed)
    }
}

/** The participant-facing summary of what changed. */
@Composable
internal fun describeChanges(changes: ConsentChanges): String {
    val lines = mutableListOf(stringResource(R.string.mbpulse_reconsent_intro), "")
    changes.added.forEach {
        lines += stringResource(if (it.isEssential) R.string.mbpulse_reconsent_added_required else R.string.mbpulse_reconsent_added, it.name)
    }
    changes.changed.forEach { c ->
        val what = c.whatChanged.map { termChangeText(it) }.joinToString(", ")
        lines += stringResource(R.string.mbpulse_reconsent_changed, c.after.name, what)
    }
    if (changes.removed.isNotEmpty()) {
        lines += ""
        lines += stringResource(R.string.mbpulse_reconsent_removed, changes.removed.joinToString { it.name })
    }
    lines += ""
    lines += stringResource(R.string.mbpulse_reconsent_meanwhile)
    return lines.joinToString("\n")
}

@Composable
private fun termChangeText(change: TermChange): String = when (change) {
    TermChange.Name -> stringResource(R.string.mbpulse_reconsent_change_name)
    TermChange.Description -> stringResource(R.string.mbpulse_reconsent_change_description)
    TermChange.Purpose -> stringResource(R.string.mbpulse_reconsent_change_purpose)
    is TermChange.Retention -> stringResource(R.string.mbpulse_reconsent_change_retention, change.fromDays, change.toDays)
    TermChange.Sampling -> stringResource(R.string.mbpulse_reconsent_change_sampling)
    TermChange.NowRequired -> stringResource(R.string.mbpulse_reconsent_change_required)
    TermChange.NowOptional -> stringResource(R.string.mbpulse_reconsent_change_optional)
}

/**
 * Library-owned consent review after a study update (not replaceable — like
 * onboarding it is ethics-critical). Shows only the new / changed data types;
 * declining an essential one leads to withdrawal. "Not now" postpones it —
 * the changed types simply stay uncollected.
 */
@Composable
internal fun ReconsentScreen(
    ready: LoadingState.Ready,
    review: ConsentReview,
    loadingViewModel: LoadingViewModel,
    onPostpone: () -> Unit,
    onWithdrawn: () -> Unit,
    viewModel: ReconsentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showWithdrawConfirm by rememberSaveable { mutableStateOf(false) }

    when (val s = state) {
        ReconsentState.Submitting -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mbpulse_reconsent_saving))
        }

        is ReconsentState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(s.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mbpulse_try_again)) }
            TextButton(onClick = onPostpone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mbpulse_not_now)) }
        }

        ReconsentState.Idle -> ConsentFlowScreen(
            dataTypes           = review.changes.toReview,
            studyId             = ready.studyId,
            studyName           = ready.studyName,
            studyVersion        = ready.studyVersion,
            definitionSha256    = ready.definitionSha256,
            participantPseudoId = ready.participantPseudoId,
            onComplete          = { record -> viewModel.submit(ready, review, record.decisions) },
            onWithdraw          = { showWithdrawConfirm = true },
            viewModel           = viewModel(key = "reconsent-${ready.studyVersion}"),
            introTitle          = stringResource(R.string.mbpulse_reconsent_title),
            introNote           = describeChanges(review.changes),
            declineLabel        = stringResource(R.string.mbpulse_withdraw_menu),
            onPostpone          = onPostpone
        )
    }

    WithdrawalDialogs(
        loadingViewModel = loadingViewModel,
        showConfirm      = showWithdrawConfirm,
        onDismissConfirm = { showWithdrawConfirm = false },
        onFinished       = onWithdrawn
    )
}
