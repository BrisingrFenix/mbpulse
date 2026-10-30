package pt.ua.mbpulse.ui.screens.onboarding

import pt.ua.mbpulse.logging.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.ui.screens.consent.ConsentDataType
import pt.ua.mbpulse.ui.screens.consent.ConsentFlowScreen
import pt.ua.mbpulse.ui.screens.consent.ConsentRecord

private const val TAG = "OnboardingOrchestrator"

@Composable
internal fun OnboardingOrchestrator(
    studyId: String,
    studyName: String,
    studyVersion: String,
    definitionSha256: String,
    participantPseudoId: String,
    dataTypes: List<ConsentDataType>,
    onOnboardingComplete: () -> Unit,
    onWithdraw: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()

    var step by remember { mutableStateOf(OnboardingStep.CONSENT) }
    var completedConsentRecord by remember { mutableStateOf<ConsentRecord?>(null) }

    Log.d(TAG, "selected study: $studyId")

    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Success) {
            step = OnboardingStep.COMPLETE
        }
    }

    when (step) {

        OnboardingStep.CONSENT -> {
            ConsentFlowScreen(
                dataTypes = dataTypes,
                studyId = studyId,
                studyName = studyName,
                studyVersion = studyVersion,
                definitionSha256 = definitionSha256,
                participantPseudoId = participantPseudoId,
                onComplete = { record ->
                    completedConsentRecord = record
                    step = OnboardingStep.PASSPHRASE
                },
                onWithdraw = onWithdraw
            )
        }

        OnboardingStep.PASSPHRASE -> {
            PassphraseSetupScreen(
                recoveryManager     = viewModel.recoveryManager,
                uploadState         = uploadState,
                onboardingRepository = viewModel.repository,
                onPassphraseConfirmed = { username, passphrase ->
                    viewModel.uploadOnboardingData(
                        username            = username,
                        passphrase          = passphrase,
                        consentRecord       = completedConsentRecord!!,
                        studyId             = studyId,
                        participantPseudoId = participantPseudoId,
                        consentedTerms      = dataTypes
                    )
                },
                onRetry    = { viewModel.resetUploadState() },
                onWithdraw = onWithdraw
            )
        }

        OnboardingStep.COMPLETE -> {
            onOnboardingComplete()
        }
    }
}


private enum class OnboardingStep { CONSENT, PASSPHRASE, COMPLETE }