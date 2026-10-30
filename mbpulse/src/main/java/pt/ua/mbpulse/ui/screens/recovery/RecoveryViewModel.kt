package pt.ua.mbpulse.ui.screens.recovery

import android.util.Base64
import pt.ua.mbpulse.logging.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.R
import pt.ua.mbpulse.collection.OmhImporter
import pt.ua.mbpulse.ui.errorText
import pt.ua.mbpulse.crypto.ConsentSignature
import pt.ua.mbpulse.crypto.DeviceBindingAuth
import pt.ua.mbpulse.crypto.KeyRecoveryManager
import pt.ua.mbpulse.crypto.KeyRotationAuth
import pt.ua.mbpulse.auth.ParticipantCredentialStore
import pt.ua.mbpulse.crypto.ParticipantCredentials
import pt.ua.mbpulse.crypto.UsernameToken
import pt.ua.mbpulse.data.repository.InvalidCredentialsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.ui.screens.consent.ConsentTerms
import pt.ua.mbpulse.ui.screens.onboarding.OnboardingViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed class RecoveryState {
    object Idle : RecoveryState()
    data class Loading(@androidx.annotation.StringRes val messageRes: Int = R.string.mbpulse_recovery_recovering) : RecoveryState()
    object Success : RecoveryState()
    data class Error(val message: String) : RecoveryState()
}

@HiltViewModel
internal class RecoveryViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: MeasurementRepository,
    private val keystoreManager: KeystoreManager,
    private val recoveryManager: KeyRecoveryManager,
    private val dataStore: DataStore<Preferences>,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao
) : ViewModel() {

    private val _state = MutableStateFlow<RecoveryState>(RecoveryState.Idle)
    val state: StateFlow<RecoveryState> = _state

    fun recover(username: String, passphrase: CharArray) {
        viewModelScope.launch {
            _state.value = RecoveryState.Loading(R.string.mbpulse_recovery_step_sign_in)
            try {
                // 1. Sign in to the participant's account (credentials derived
                //    on the device — the username and passphrase never leave
                //    it). A wrong passphrase fails here, at the server and
                //    rate-limited: the recovery blob is only served afterwards.
                val usernameToken = withContext(Dispatchers.Default) { UsernameToken.of(username) }
                val authSecret = withContext(Dispatchers.Default) {
                    ParticipantCredentials.authSecret(passphrase, usernameToken)
                }
                // The passphrase proof of the key rotation (step 5) — computed while
                // the passphrase is here, never stored.
                val passphraseKey = withContext(Dispatchers.Default) {
                    ParticipantCredentials.passphraseKey(passphrase, usernameToken)
                }
                val email = repository.participantEmail(usernameToken)
                try {
                    repository.signInParticipant(email, authSecret)
                } catch (e: InvalidCredentialsException) {
                    passphrase.fill(Char(0))
                    passphraseKey.fill(0)
                    _state.value = RecoveryState.Error(context.getString(R.string.mbpulse_recovery_wrong_credentials))
                    return@launch
                }

                val participant = repository.findMyRecoveryMaterial()
                    ?: run {
                        passphrase.fill(Char(0))
                        passphraseKey.fill(0)
                        repository.signOutParticipant()
                        _state.value = RecoveryState.Error(context.getString(R.string.mbpulse_recovery_no_enrolment))
                        return@launch
                    }
                _state.value = RecoveryState.Loading(R.string.mbpulse_recovery_step_decrypt)

                // 2. Decrypt recovery blob → raw DEK
                val rawDek = withContext(Dispatchers.Default) {
                    recoveryManager.recoverDek(
                        recoveryBlob = participant.recoveryBlob,
                        passphrase   = passphrase
                    )
                }
                passphrase.fill(Char(0))

                if (rawDek == null) {
                    passphraseKey.fill(0)
                    _state.value = RecoveryState.Error(context.getString(R.string.mbpulse_recovery_wrong_passphrase))
                    return@launch
                }

                // 3. Generate fresh RSA key pair on this device
                keystoreManager.deleteKeyPair()
                keystoreManager.generateKeyPairIfAbsent()

                // 4. Wrap recovered DEK under new RSA public key
                val newWrappedDek = keystoreManager.wrapDekWithPublicKey(rawDek)

                // 5. Register the new key pair. The backend only allows it in
                //    answer to a one-time challenge, proven with the rotation
                //    key derived from the DEK — i.e. only by whoever decrypted
                //    the recovery blob with the passphrase — and with the
                //    passphrase key, which no phone stores (so the phone being
                //    replaced, which holds the DEK, cannot rotate it back).
                //    It also makes this phone the participant's device: its
                //    new device key and this session; every other phone's
                //    requests are refused from now on.
                val newPublicKeyB64  = Base64.encodeToString(keystoreManager.getPublicKeyBytes(), Base64.NO_WRAP)
                val newWrappedDekB64 = Base64.encodeToString(newWrappedDek, Base64.NO_WRAP)
                val deviceKey = DeviceBindingAuth.newDeviceKey()
                val deviceKeyB64 = Base64.encodeToString(deviceKey, Base64.NO_WRAP)
                val nonce = repository.requestKeyRotationChallenge(participant.pseudoId)
                val rotationKey = KeyRotationAuth.rotationKey(rawDek)
                val proof = KeyRotationAuth.proof(
                    rotationKey, participant.pseudoId, nonce, newPublicKeyB64, newWrappedDekB64
                )
                rotationKey.fill(0)
                val passphraseProof = KeyRotationAuth.passphraseProof(
                    passphraseKey, participant.pseudoId, nonce, newPublicKeyB64, newWrappedDekB64, deviceKeyB64
                )
                val passphraseKeyB64 = Base64.encodeToString(passphraseKey, Base64.NO_WRAP)
                passphraseKey.fill(0)
                repository.rotateKeys(
                    pseudoId         = participant.pseudoId,
                    newPublicKeyB64  = newPublicKeyB64,
                    newWrappedDekB64 = newWrappedDekB64,
                    nonce            = nonce,
                    proofB64         = proof,
                    deviceKeyB64     = deviceKeyB64,
                    passphraseKeyB64   = passphraseKeyB64,
                    passphraseProofB64 = passphraseProof
                )
                repository.rememberDeviceKey(deviceKey)
                // The replaced phone's logins: revoked (its requests are refused
                // by the binding anyway — this also ends its refresh tokens).
                repository.signOutOtherSessions()

                _state.value = RecoveryState.Loading(R.string.mbpulse_recovery_step_import)

                // 6. Fetch records from backend, then decrypt locally
                val records = repository.fetchMyEncryptedRecords()
                records.forEach { record ->
                    OmhImporter.importRecord(
                        ciphertextHex   = record.ciphertextHex,
                        schemaName      = record.schemaName,
                        epochStartMs    = record.epochStartMs,
                        dekBytes        = rawDek,
                        keystoreManager = keystoreManager,
                        displayEpochDao = displayEpochDao,
                        epochHistoryDao = epochHistoryDao
                    )
                }

                // 7. Zero the raw DEK now that import is done
                rawDek.fill(0)

                // 8. Restore the participant's consent from their latest signed
                //    consent record. Fails closed: with no verifiable record (or
                //    one for an older study version) nothing is collected until
                //    the participant reviews the consent again.
                _state.value = RecoveryState.Loading(R.string.mbpulse_recovery_step_consent)
                val (consentedTypeIds, consentedTerms) = restoreConsent(participant.pseudoId, participant.studyId)

                // Silent re-login for background sync on this device.
                repository.rememberCredentials(ParticipantCredentialStore.Credentials(email, authSecret))

                // 9. Restore local DataStore — participant is enrolled again
                dataStore.edit { prefs ->
                    prefs[OnboardingViewModel.CONSENTED_TYPE_IDS] = consentedTypeIds.joinToString(",")
                    prefs[OnboardingViewModel.CONSENTED_TERMS] = consentedTerms
                    prefs[stringPreferencesKey("participant_pseudo_id")] = participant.pseudoId
                    prefs[stringPreferencesKey("selected_study_id")]     = participant.studyId
                    prefs[booleanPreferencesKey("onboarding_complete")]  = true
                    prefs[OnboardingViewModel.WRAPPED_DEK] = Base64.encodeToString(
                        newWrappedDek, Base64.NO_WRAP
                    )
                }

                _state.value = RecoveryState.Success

            } catch (e: Exception) {
                _state.value = RecoveryState.Error(context.errorText(e, R.string.mbpulse_recovery_failed))

            }
        }
    }
    /**
     * Accepted data-type ids from the latest consent record whose signature
     * verifies, plus the consent terms to store (ConsentTerms.encode).
     *
     * The terms count as agreed only when that record was signed for the
     * current study version; otherwise (study updated since, or no verifiable
     * record) the stored terms are "nothing agreed" — the app then collects
     * nothing and asks the participant to review every data type again.
     */
    private suspend fun restoreConsent(pseudoId: String, studyId: String): Pair<Set<String>, String> {
        val config = repository.getStudyConfig(studyId)
        val records = runCatching { repository.fetchMyConsentRecords() }
            .onFailure { Log.w(TAG, "Could not fetch consent records: ${it.message}") }
            .getOrDefault(emptyList())
        val consent = ConsentSignature.latestValid(records, pseudoId, studyId)
        if (consent == null) {
            Log.w(TAG, "No verifiable consent record among ${records.size} — full consent review required")
            return emptySet<String>() to ConsentTerms.encode(emptyList())
        }
        val accepted = consent.decisions.filter { it.accepted }.mapTo(LinkedHashSet()) { it.dataTypeId }
        // The record names the exact definition it was given for (format v2);
        // records made before definitions were tracked (v1) only the version label.
        val sameTerms = consent.definitionSha256?.equals(config.definitionSha256, ignoreCase = true)
            ?: (consent.studyVersion == config.studyVersion)
        return if (sameTerms) {
            accepted to ConsentTerms.encode(config.dataTypes)
        } else {
            Log.w(TAG, "Consent was given for definition ${consent.definitionSha256 ?: "v" + consent.studyVersion}, " +
                "current is ${config.definitionSha256} — full consent review required")
            accepted to ConsentTerms.encode(emptyList())
        }
    }

    fun resetState() { _state.value = RecoveryState.Idle }

    private companion object {
        const val TAG = "RecoveryViewModel"
    }
}