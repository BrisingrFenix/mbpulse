package pt.ua.mbpulse.ui.screens.onboarding

import android.util.Base64
import pt.ua.mbpulse.logging.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.R
import pt.ua.mbpulse.crypto.ConsentSignature
import pt.ua.mbpulse.crypto.DeviceBindingAuth
import pt.ua.mbpulse.ui.errorText
import pt.ua.mbpulse.crypto.KeyRecoveryManager
import pt.ua.mbpulse.crypto.KeyRotationAuth
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.data.repository.JoinTicketInvalidException
import pt.ua.mbpulse.data.repository.ParticipantEnrollment
import pt.ua.mbpulse.data.repository.StudyClosedException
import pt.ua.mbpulse.data.repository.UsernameTakenException
import pt.ua.mbpulse.ui.screens.studySelection.JoinTicketStore
import pt.ua.mbpulse.auth.ParticipantCredentialStore
import pt.ua.mbpulse.crypto.ParticipantCredentials
import pt.ua.mbpulse.crypto.UsernameToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.ua.mbpulse.ui.screens.consent.ConsentRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject


internal sealed class UploadState {
    object Idle : UploadState()
    object Uploading : UploadState()
    object Success : UploadState()
    data class Error(val message: String) : UploadState()
}

@HiltViewModel
internal class OnboardingViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    val repository: MeasurementRepository,
    private val keystoreManager: KeystoreManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {
    val recoveryManager = KeyRecoveryManager()

    companion object {
        val WRAPPED_DEK = stringPreferencesKey("wrapped_dek_b64")
        val CONSENTED_TYPE_IDS = stringPreferencesKey("consented_type_ids")
        /** [pt.ua.mbpulse.ui.screens.consent.ConsentTerms.encode] of the terms agreed to. */
        val CONSENTED_TERMS = stringPreferencesKey("consented_terms")
    }

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState

    /**
     * The enrolment of the last attempt and what it was built from. A retry
     * with the same inputs resends it unchanged: if the first attempt reached
     * the server but its response was lost, the backend recognises the
     * identical enrolment and succeeds instead of failing on "already
     * enrolled" — and the device keeps the DEK the server's recovery blob holds.
     */
    private var prepared: Pair<String, Prepared>? = null

    /** An enrolment plus the account it is created under (docs/design/participant-accounts.md §5.1). */
    private class Prepared(
        val enrollment: ParticipantEnrollment,
        val email: String,
        val authSecret: String,
        /** This phone's device key (device binding) — kept once the enrolment succeeded. */
        val deviceKey: ByteArray
    )

    fun uploadOnboardingData(
        passphrase: CharArray,
        username: String,
        consentRecord: ConsentRecord,
        studyId: String,
        participantPseudoId: String,
        /** The data types as shown on the consent screen — stored to detect later study changes. */
        consentedTerms: List<pt.ua.mbpulse.ui.screens.consent.ConsentDataType>
    ) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Uploading
            try {
                // RSA key pair in the hardware Keystore — kept across retries
                // (the prepared enrolment is bound to it).
                keystoreManager.generateKeyPairIfAbsent()

                val consent = consentRecord.copy(participantPseudoId = participantPseudoId, studyId = studyId)
                val fingerprint = fingerprint(passphrase, username, consent)
                val p = prepared?.takeIf { it.first == fingerprint }?.second
                    ?: prepare(passphrase, username, consent, JoinTicketStore.load(context, studyId))
                        .also { prepared = fingerprint to it }
                passphrase.fill(Char(0))

                // 1. The participant's account (a retry with the same inputs
                //    signs in to the account the first attempt created).
                repository.signUpParticipant(p.email, p.authSecret)
                // 2. One transaction, as that account: participant (bound to
                //    it) + rotation key + signed consent.
                repository.enrollParticipant(p.enrollment)
                // 3. Silent re-login for background sync, and the device key a
                //    new login re-attaches with (both sealed on the device).
                repository.rememberDeviceKey(p.deviceKey)
                repository.rememberCredentials(ParticipantCredentialStore.Credentials(p.email, p.authSecret))

                // Local state only once the server holds the whole enrolment.
                dataStore.edit { prefs ->
                    prefs[CONSENTED_TYPE_IDS] = consent.decisions
                        .filter { it.accepted }
                        .joinToString(",") { it.dataTypeId }
                    prefs[CONSENTED_TERMS] = pt.ua.mbpulse.ui.screens.consent.ConsentTerms.encode(consentedTerms)
                    prefs[WRAPPED_DEK] = p.enrollment.wrappedDekB64
                }
                prepared = null
                JoinTicketStore.clear(context)   // used up by the enrolment
                Log.d("OnboardingViewModel", "Enrolled; wrapped DEK persisted")

                _uploadState.value = UploadState.Success

            } catch (e: UsernameTakenException) {
                passphrase.fill(Char(0))
                prepared = null
                _uploadState.value = UploadState.Error(context.getString(R.string.mbpulse_passphrase_username_taken_back))
            } catch (e: StudyClosedException) {
                passphrase.fill(Char(0))
                prepared = null
                _uploadState.value = UploadState.Error(context.getString(R.string.mbpulse_enrolment_study_closed))
            } catch (e: JoinTicketInvalidException) {
                passphrase.fill(Char(0))
                prepared = null
                JoinTicketStore.clear(context)
                _uploadState.value = UploadState.Error(context.getString(R.string.mbpulse_enrolment_join_code_again))
            } catch (e: Exception) {
                passphrase.fill(Char(0))
                Log.e("OnboardingViewModel", "Enrolment failed", e)
                _uploadState.value = UploadState.Error(context.errorText(e, R.string.mbpulse_enrolment_failed))

            }
        }
    }

    /**
     * Builds the enrolment: a fresh DEK wrapped under the device key, the
     * passphrase recovery blob, the key-rotation key derived from the DEK, and
     * the consent record signed with the device key (attributable to this
     * participant, tamper-evident). The raw DEK never leaves this function.
     */
    private suspend fun prepare(
        passphrase: CharArray,
        username: String,
        consent: ConsentRecord,
        joinTicket: String?
    ): Prepared {
        // Account credentials — derived, never the username or passphrase
        // themselves (PBKDF2: off the main thread). Before the recovery blob,
        // which consumes the passphrase.
        val usernameToken = withContext(Dispatchers.Default) { UsernameToken.of(username) }
        val authSecret = withContext(Dispatchers.Default) {
            ParticipantCredentials.authSecret(passphrase, usernameToken)
        }
        // The passphrase proof for later recoveries: registered, never stored here.
        val passphraseKey = withContext(Dispatchers.Default) {
            ParticipantCredentials.passphraseKey(passphrase, usernameToken)
        }
        val deviceKey = DeviceBindingAuth.newDeviceKey()
        val email = repository.participantEmail(usernameToken)
        val enrollment = withContext(Dispatchers.Default) { buildEnrollment(passphrase, usernameToken, consent) }
            .copy(
                joinTicket       = joinTicket,
                deviceKeyB64     = b64(deviceKey),
                passphraseKeyB64 = b64(passphraseKey).also { passphraseKey.fill(0) }
            )
        return Prepared(enrollment, email, authSecret, deviceKey)
    }

    private fun buildEnrollment(
        passphrase: CharArray,
        usernameToken: String,
        consent: ConsentRecord
    ): ParticipantEnrollment {
        val wrappedDek = keystoreManager.generateAndWrapDek()
        val recoveryBlob = recoveryManager.createRecoveryBlob(
            dekBytes   = wrappedDek.rawDekBytes,
            passphrase = passphrase
        )
        val rotationKey = KeyRotationAuth.rotationKey(wrappedDek.rawDekBytes)
        wrappedDek.clearRaw()

        val signature = keystoreManager.sign(
            ConsentSignature.signingString(
                participantPseudoId = consent.participantPseudoId,
                studyId             = consent.studyId,
                studyVersion        = consent.studyVersion,
                definitionSha256    = consent.definitionSha256,
                completedAtMs       = consent.completedAtMs,
                decisions           = consent.decisions
            ).toByteArray(Charsets.UTF_8)
        )
        return ParticipantEnrollment(
            pseudoId            = consent.participantPseudoId,
            studyId             = consent.studyId,
            usernameToken       = usernameToken,
            publicKeyB64        = b64(keystoreManager.getPublicKeyBytes()),
            wrappedDekB64       = b64(wrappedDek.encryptedDekBytes),
            recoveryBlob        = recoveryBlob,
            rotationKeyB64      = b64(rotationKey).also { rotationKey.fill(0) },
            consent             = consent,
            consentSignatureB64 = b64(signature)
        )
    }

    /** Identifies the inputs of an attempt (passphrase only as a hash, in memory). */
    private fun fingerprint(passphrase: CharArray, username: String, consent: ConsentRecord): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        // Encode without an immutable String copy of the passphrase; wipe the bytes.
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(passphrase))
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        digest.update(bytes)
        bytes.fill(0)
        if (buffer.hasArray()) buffer.array().fill(0)
        return b64(digest.digest()) + "|" + username + "|" + consent.hashCode()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun resetUploadState() {
        _uploadState.value = UploadState.Idle
    }
}