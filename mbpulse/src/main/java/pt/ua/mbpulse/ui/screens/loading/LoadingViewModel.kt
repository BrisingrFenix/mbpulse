package pt.ua.mbpulse.ui.screens.loading

import android.content.Context
import android.util.Base64
import pt.ua.mbpulse.logging.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.errorText
import pt.ua.mbpulse.collection.BackgroundWork
import pt.ua.mbpulse.collection.Enrollment
import pt.ua.mbpulse.collection.DeviceReplaced
import pt.ua.mbpulse.collection.StudyEnd
import pt.ua.mbpulse.crypto.DeviceBindingAuth
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.repository.BackendCompatibility
import pt.ua.mbpulse.data.repository.BackendMismatchException
import pt.ua.mbpulse.data.repository.DeletionReceipt
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.repository.MeasurementRepository
import pt.ua.mbpulse.data.repository.StudyConfig
import pt.ua.mbpulse.data.repository.StudyDefinitionException
import androidx.datastore.preferences.core.longPreferencesKey
import pt.ua.mbpulse.ui.screens.consent.ConsentChanges
import pt.ua.mbpulse.ui.screens.consent.ConsentDataType
import pt.ua.mbpulse.ui.screens.consent.ConsentTerms
import pt.ua.mbpulse.ui.screens.onboarding.OnboardingViewModel
import pt.ua.mbpulse.ui.screens.onboarding.OnboardingViewModel.Companion.CONSENTED_TYPE_IDS
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.ConnectivityManager
import android.net.Network
import pt.ua.mbpulse.collection.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val TAG = "LoadingViewModel"

public sealed class LoadingState {
    public object InProgress : LoadingState()
    public object NoStudySelected : LoadingState()
    public data class Ready(
        val onboardingComplete: Boolean,
        val studyId: String,
        val studyName: String,
        val studyVersion: String,
        /** The current study definition ([pt.ua.mbpulse.crypto.StudyDefinitionHash]); consent records name it. */
        val definitionSha256: String,
        val participantPseudoId: String,
        val dataTypes: List<ConsentDataType>,
        val wrappedDekBytes: ByteArray,
        val piPublicKeyBytes: ByteArray?,
        val exportNotification: String? = null,
        /** `exported_at_ms` of the export [exportNotification] is about. */
        val exportNotificationAtMs: Long? = null,
        /**
         * What may be collected now: the participant's accepted types, minus
         * any the study removed or changed since they agreed (see [consentReview]).
         */
        val consentedTypeIds: Set<String> = emptySet(),
        /** Non-null when the study changed since the participant consented — library-owned review. */
        val consentReview: ConsentReview? = null,
        /** Server unreachable: the study definition is this device's verified copy from this time. */
        val offlineSinceMs: Long? = null,
        /**
         * The research team ended the study at this time (epoch ms): nothing is
         * collected, remaining uploads finish, and `NavGraph` shows the
         * library-owned "study ended" screen in place of the enrolled screens.
         */
        val endedAtMs: Long? = null,
        /**
         * The account was recovered on another phone, which the backend now
         * accepts instead of this one (device binding): nothing is collected or
         * sent, and `NavGraph` shows the library-owned "now used on another
         * device" screen in place of the enrolled screens.
         */
        val deviceReplaced: Boolean = false
    ) : LoadingState() {
        /** The study's `samplingSeconds` per data-type id, for the sampling gate. */
        val samplingSecondsByTypeId: Map<String, Int>
            get() = dataTypes.mapNotNull { dt -> dt.samplingSeconds?.let { dt.id to it } }.toMap()

        /**
         * The data types being collected for this participant — what the home
         * screen shows cards for. [dataTypes] is the whole study, declined and
         * not-yet-reviewed types included; never use it for what is collected.
         * (Essential types are in [consentedTypeIds] because enrolment requires
         * accepting them — a newly added essential type is not, until reviewed.)
         */
        val consentedDataTypes: List<ConsentDataType>
            get() = dataTypes.filter { it.id in consentedTypeIds }
    }
    public data class Error(val message: String) : LoadingState()
}


/**
 * The study changed since the participant last consented: [changes] lists what,
 * [previouslyConsented] is what they had accepted (before filtering), so the
 * re-consent record can carry their earlier choice for everything not reviewed.
 */
public data class ConsentReview(val changes: ConsentChanges, val previouslyConsented: Set<String>)

/** Proof-of-deletion shown to the participant after a successful withdrawal. */
public data class WithdrawalReceipt(
    val receiptId: String,
    val deletedAt: String,
    val recordsDeleted: Int,
    val consentDeleted: Int,
    val participantDeleted: Int,
    /** 1 when the participant's account was deleted too. */
    val accountDeleted: Int = 0,
    /** Records the app re-found on the server after deletion: 0 = verified gone, -1 = re-check failed. */
    val remainingOnServer: Int,
    /** Backend signature check: true = verified, false = failed/unsigned, null = no key configured. */
    val signatureVerified: Boolean?
)

public sealed interface WithdrawalState {
    public object Idle : WithdrawalState
    public object InProgress : WithdrawalState
    /** [receipt] is null when the wipe was local-only (participant never enrolled). */
    public data class Done(val receipt: WithdrawalReceipt?) : WithdrawalState
    public data class Error(val message: String) : WithdrawalState
}

@HiltViewModel
public class LoadingViewModel @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val repository: MeasurementRepository,
    private val encryptedRecordDao: EncryptedRecordDao,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao,
    private val keystoreManager: KeystoreManager,
    private val studyConfigCache: pt.ua.mbpulse.data.repository.StudyConfigCache
) : ViewModel() {

    public companion object {
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val PARTICIPANT_PSEUDO_ID = stringPreferencesKey("participant_pseudo_id")
        private val SELECTED_STUDY_ID     = stringPreferencesKey("selected_study_id")
        private val LAST_SEEN_EXPORT_MS   = androidx.datastore.preferences.core.longPreferencesKey("last_seen_export_ms")
        private const val RECONNECT_ATTEMPTS = 4
        private const val RECONNECT_BACKOFF_MS = 3_000L
    }

    private val _state = MutableStateFlow<LoadingState>(LoadingState.InProgress)
    public val state: StateFlow<LoadingState> = _state

    private val _withdrawal = MutableStateFlow<WithdrawalState>(WithdrawalState.Idle)
    public val withdrawal: StateFlow<WithdrawalState> = _withdrawal

    init {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                _state.value = LoadingState.InProgress
                try {
                    checkBackend()
                    val selectedStudyId = prefs[SELECTED_STUDY_ID]
                    if (selectedStudyId == null) {
                        _state.value = LoadingState.NoStudySelected
                        return@collect
                    }

                    val pseudoId = prefs[PARTICIPANT_PSEUDO_ID]
                        ?: UUID.randomUUID().toString().also { newId ->
                            dataStore.edit { it[PARTICIPANT_PSEUDO_ID] = newId }
                        }

                    _state.value = buildReady(prefs, selectedStudyId, pseudoId)
                    Log.d(TAG, "_state.value: ${_state.value}")
                    startBackgroundWorkIfEnrolled()
                } catch (e: Exception) {
                    _state.value = LoadingState.Error(context.errorText(e, R.string.mbpulse_loading_failed_detail))

                }
            }
        }
    }

    /** Set once the backend matched this library build — checked once per view model. */
    private var backendCompatible = false

    /**
     * App ↔ backend API check ([BackendCompatibility]) before anything else
     * talks to the server: a mismatch throws [BackendMismatchException] →
     * the loading screen explains it (with Retry) instead of a later, obscure
     * failure such as an enrolment the backend refuses. When the server cannot
     * be reached the check is skipped — the offline start works as before —
     * and runs again on the next load.
     */
    private suspend fun checkBackend() {
        if (backendCompatible) return
        val version = try {
            repository.getBackendVersion()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backend version not checked (${e::class.simpleName}: ${e.message})")
            return
        }
        val message = when (val r = BackendCompatibility.check(version)) {
            BackendCompatibility.Result.Compatible -> { backendCompatible = true; return }
            is BackendCompatibility.Result.Incomplete ->
                context.getString(R.string.mbpulse_backend_incomplete, r.missing.joinToString())
            is BackendCompatibility.Result.BackendTooOld ->
                context.getString(R.string.mbpulse_backend_too_old, r.backendApi, BackendCompatibility.CLIENT_API)
            is BackendCompatibility.Result.AppTooOld ->
                context.getString(R.string.mbpulse_app_too_old, BackendCompatibility.CLIENT_API, r.compatibleSince)
        }
        Log.e(TAG, "Backend mismatch: $version vs client API ${BackendCompatibility.CLIENT_API}")
        throw BackendMismatchException(message)
    }

    /**
     * The study config, verified by the repository against the pinned
     * study-signing key, plus a rollback check: a signed definition older than
     * one this device already accepted is a replay (e.g. an earlier version
     * with different consent wording or retention) and is refused. Throws
     * [StudyDefinitionException] → the loading screen shows the error and no
     * collection starts.
     */
    private suspend fun loadStudyConfig(studyId: String): StudyConfig {
        val config = repository.getStudyConfig(studyId)
        val issuedAt = config.definitionIssuedAt ?: return config
        val key = longPreferencesKey("study_definition_issued_at_$studyId")
        val lastAccepted = dataStore.data.first()[key]
        if (lastAccepted != null && issuedAt.toEpochMilli() < lastAccepted) {
            Log.e(TAG, "Study $studyId: signed definition issued $issuedAt is older than the one " +
                "already accepted (${java.time.Instant.ofEpochMilli(lastAccepted)}) — rollback refused")
            throw StudyDefinitionException(context.getString(R.string.mbpulse_study_rollback))

        }
        if (lastAccepted == null || issuedAt.toEpochMilli() > lastAccepted) {
            dataStore.edit { it[key] = issuedAt.toEpochMilli() }
        }
        return config
    }

    /**
     * The Ready state for the stored enrolment and the current (verified) study.
     *
     * Consent vs study updates: the terms the participant agreed to are stored
     * on the device ([OnboardingViewModel.CONSENTED_TERMS]); when the current
     * definition adds data types or changes the terms of one, [LoadingState.Ready.consentReview]
     * is set (NavGraph shows the library-owned review) and only types with
     * unchanged terms keep being collected — fail closed until the participant
     * decides. Removed types stop being collected at once.
     */
    private suspend fun buildReady(prefs: Preferences, studyId: String, pseudoId: String): LoadingState.Ready {
        val onboardingDone = prefs[ONBOARDING_COMPLETE] ?: false
        val accepted = prefs[CONSENTED_TYPE_IDS]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
        val wrappedDekBytes = prefs[OnboardingViewModel.WRAPPED_DEK]
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0)
        val config = loadStudyConfig(studyId)
        // The server's, or — opened offline from an older copy — what background
        // uploads already learned (StudyEnd).
        val endedAtMs = config.endedAtMs ?: if (onboardingDone) StudyEnd.endedAtMs(context) else null
        val deviceReplaced = onboardingDone && isDeviceReplaced(online = config.offlineSinceMs == null)

        var consented = accepted
        var review: ConsentReview? = null
        if (onboardingDone && endedAtMs == null && !deviceReplaced) {
            val agreed = ConsentTerms.decode(prefs[OnboardingViewModel.CONSENTED_TERMS])
            if (agreed == null) {
                // Enrolled before terms were stored: take the current study as
                // what was agreed (the best available baseline) — later changes
                // are then detected against it.
                Log.i(TAG, "No stored consent terms — recording the current study as the baseline")
                dataStore.edit { it[OnboardingViewModel.CONSENTED_TERMS] = ConsentTerms.encode(config.dataTypes) }
            } else {
                val changes = ConsentTerms.diff(agreed, config.dataTypes)
                consented = ConsentTerms.effectiveConsent(accepted, changes, config.dataTypes)
                if (changes.needsReview) {
                    review = ConsentReview(changes, accepted)
                    Log.w(TAG, "Study changed since consent — added=${changes.added.map { it.id }} " +
                        "changed=${changes.changed.map { it.after.id }}; collecting only $consented until reviewed")
                } else if (changes.removed.isNotEmpty()) {
                    Log.i(TAG, "Data types removed from the study, no longer collected: ${changes.removed.map { it.id }}")
                }
            }
        }

        return LoadingState.Ready(
            onboardingComplete  = onboardingDone,
            participantPseudoId = pseudoId,
            wrappedDekBytes     = wrappedDekBytes,
            studyId             = config.studyId,
            studyName           = config.studyName,
            studyVersion        = config.studyVersion,
            definitionSha256    = config.definitionSha256,
            dataTypes           = config.dataTypes,
            piPublicKeyBytes    = config.piPublicKeyBytes,
            consentedTypeIds    = consented,
            consentReview       = review,
            offlineSinceMs      = config.offlineSinceMs,
            endedAtMs           = endedAtMs,
            deviceReplaced      = deviceReplaced
        )
    }

    /**
     * Whether another phone took the account over (device binding): remembered
     * once learned ([DeviceReplaced]); otherwise asked of the backend when
     * online — which also re-attaches a new login of this phone. Unreachable
     * or an older backend = not replaced (checked again on the next load).
     */
    private suspend fun isDeviceReplaced(online: Boolean): Boolean {
        if (DeviceReplaced.isReplaced(context)) return true
        if (!online) return false
        val binding = try {
            repository.checkDeviceBinding()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device binding not checked (${e::class.simpleName}: ${e.message})")
            return false
        }
        if (binding != DeviceBindingAuth.Binding.REPLACED) return false
        DeviceReplaced.apply(context)
        return true
    }

    /**
     * Opened offline (cached study definition): try the server again — called
     * on every resume by the library's navigation and whenever a network
     * connection appears ([networkCallback]). No-op when online.
     */
    public fun refreshIfOffline() {
        val ready = _state.value as? LoadingState.Ready ?: return
        if (ready.offlineSinceMs == null) return
        viewModelScope.launch { if (tryGoOnline(ready)) onBackOnline() }
    }

    /** One attempt to replace the cached study with the server's; true when online again. */
    private suspend fun tryGoOnline(ready: LoadingState.Ready): Boolean = runCatching {
        val prefs = dataStore.data.first()
        val fresh = buildReady(prefs, ready.studyId, ready.participantPseudoId)
        if (fresh.offlineSinceMs != null) return@runCatching false
        // Keep the transient export notice; everything else is fresh.
        _state.value = fresh.copy(
            exportNotification = ready.exportNotification,
            exportNotificationAtMs = ready.exportNotificationAtMs
        )
        startBackgroundWorkIfEnrolled()
        true
    }.getOrElse { Log.w(TAG, "Still offline: ${it.message}"); false }

    /** Back online after an offline start: upload what waited and look for export notices. */
    private fun onBackOnline() {
        Log.i(TAG, "Back online — study refreshed, syncing")
        SyncWorker.syncNow(context)
        (_state.value as? LoadingState.Ready)?.takeIf { it.onboardingComplete }?.let { checkForNewExports(it.studyId) }
    }

    // -- Reconnect when a network appears ------------------------------------
    // Offline start (cached study) or a load error: when Android reports a
    // network, retry a few times with backoff — "available" often comes a
    // moment before DNS / the server is reachable.

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var reconnectJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { onNetworkAvailable() }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "Could not watch connectivity: ${it.message}") }
    }

    private fun onNetworkAvailable() {
        val needsReconnect = when (val s = _state.value) {
            is LoadingState.Ready -> s.offlineSinceMs != null
            is LoadingState.Error -> true
            else -> false
        }
        if (!needsReconnect || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            for (attempt in 0 until RECONNECT_ATTEMPTS) {
                val online = when (val s = _state.value) {
                    is LoadingState.Ready -> s.offlineSinceMs == null || tryGoOnline(s)
                    is LoadingState.Error -> reload()
                    else -> return@launch              // loading / no study: nothing to do
                }
                if (online) { onBackOnline(); return@launch }
                delay(RECONNECT_BACKOFF_MS * (attempt + 1))
            }
        }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    /**
     * Background work for an enrolled participant: passive collection (passive
     * modes) and the periodic Health Connect import (when configured). Called
     * on every load (app start, onboarding finished), so stopped work restarts
     * and config changes apply; each part is a no-op when not configured.
     */
    private fun startBackgroundWorkIfEnrolled() {
        val ready = _state.value as? LoadingState.Ready ?: return
        if (!ready.onboardingComplete || ready.wrappedDekBytes.isEmpty() || ready.deviceReplaced) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val enrollment = Enrollment.of(
                    participantPseudoId     = ready.participantPseudoId,
                    studyId                 = ready.studyId,
                    wrappedDekBytes         = ready.wrappedDekBytes,
                    piPublicKeyBytes        = ready.piPublicKeyBytes,
                    consentedTypeIds        = ready.consentedTypeIds,
                    samplingSecondsByTypeId = ready.samplingSecondsByTypeId
                )
                val endedAt = ready.endedAtMs
                if (endedAt == null) {
                    BackgroundWork.enable(context, enrollment)
                } else {
                    // Collection stops for good; what was measured before uploads now.
                    BackgroundWork.end(context, enrollment, endedAt, encryptedRecordDao)
                    SyncWorker.syncNow(context)
                }
            }.onFailure { Log.e(TAG, "Could not start background work: ${it.message}", it) }
        }
    }

    public fun selectStudy(studyId: String) {
        _state.value = LoadingState.InProgress
        viewModelScope.launch {
            dataStore.edit { it[SELECTED_STUDY_ID] = studyId }
        }
    }

    public fun unSelectStudy() {
        viewModelScope.launch {
            dataStore.edit { it.remove(SELECTED_STUDY_ID) }
        }
    }

    public fun markOnboardingComplete() {
        viewModelScope.launch {
            dataStore.edit { it[ONBOARDING_COMPLETE] = true }
        }
        Log.d(TAG, "Marked Onboarding Complete")
    }

    public suspend fun resetForDevelopment() {
        viewModelScope.launch(Dispatchers.IO) { wipeThisDevice() }
    }

    /**
     * The "now used on another device" screen's only action: removes the
     * study from this phone — local data, keys, enrolment and the local login.
     * Nothing on the server changes (it belongs to the other phone now).
     * Readings never uploaded from here are lost; they could not be sent.
     */
    internal fun removeReplacedDevice(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            wipeThisDevice()
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Local wipe shared by the development reset and [removeReplacedDevice]; the account stays on the server. */
    private suspend fun wipeThisDevice() {
        run {
            BackgroundWork.disable(context)
            dataStore.edit {
                it[ONBOARDING_COMPLETE] = false
                it.remove(PARTICIPANT_PSEUDO_ID)  // also regenerates the pseudo ID
                it.remove(OnboardingViewModel.WRAPPED_DEK)
                it.remove(SELECTED_STUDY_ID)
                it.remove(OnboardingViewModel.CONSENTED_TYPE_IDS)
                it.remove(OnboardingViewModel.CONSENTED_TERMS)
            }
            encryptedRecordDao.deleteAll()
            displayEpochDao.deleteAll()
            epochHistoryDao.deleteAll()
            studyConfigCache.clear()
            keystoreManager.deleteKeyPair()
            repository.signOutParticipant()   // the account stays on the server (dev reset, not withdrawal)
        }
    }

    /**
     * Withdraws the participant from the current study and erases their data.
     *
     * Order matters for safety: the remote erasure goes first and, if it fails,
     * nothing local is touched — the pseudo id (needed to retry) is preserved and
     * the call surfaces as [WithdrawalState.Error]. Only once the backend has
     * deleted the participant's rows do we wipe local data, delete the device key
     * pair (rendering any residual ciphertext permanently unreadable), and clear
     * the enrolment/identity preferences.
     */
    public fun withdrawFromStudy() {
        if (_withdrawal.value == WithdrawalState.InProgress) return
        val pseudoId = (_state.value as? LoadingState.Ready)?.participantPseudoId
        _withdrawal.value = WithdrawalState.InProgress
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Stop any active recording so it can't re-insert rows mid-wipe,
                // and stop all background work (passive collection, Health
                // Connect import) so a reboot or a scheduled run cannot restart it.
                // (If the erasure below fails the participant is still enrolled,
                // and the next app start re-enables collection as usual.)
                BackgroundWork.disable(context)

                // 1) Prove key ownership: fetch a challenge, sign it with the
                //    device private key, and let the backend verify + erase.
                //    Abort the whole flow if any step fails.
                var receipt: WithdrawalReceipt? = null
                if (!pseudoId.isNullOrBlank()) {
                    val challenge = repository.requestWithdrawalChallenge(pseudoId)
                    val signature = keystoreManager.sign(challenge.toByteArray(Charsets.UTF_8))
                    val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
                    val serverReceipt = repository.confirmWithdrawal(pseudoId, signatureB64)

                    // Independent proof: re-query our own records; expect none left.
                    // -1 signals the re-check itself failed (network), not that data remains.
                    val remaining = runCatching { repository.fetchMyEncryptedRecords().size }
                        .getOrDefault(-1)

                    receipt = WithdrawalReceipt(
                        receiptId          = serverReceipt.receiptId,
                        deletedAt          = serverReceipt.deletedAt,
                        recordsDeleted     = serverReceipt.recordsDeleted,
                        consentDeleted     = serverReceipt.consentDeleted,
                        participantDeleted = serverReceipt.participantDeleted,
                        accountDeleted     = serverReceipt.accountDeleted,
                        remainingOnServer  = remaining,
                        signatureVerified  = verifyReceiptSignature(serverReceipt)
                    )
                }

                // 2) Local data.
                encryptedRecordDao.deleteAll()
                displayEpochDao.deleteAll()
                epochHistoryDao.deleteAll()
                studyConfigCache.clear()

                // 3) Device key pair — makes any leftover ciphertext unreadable.
                runCatching { keystoreManager.deleteKeyPair() }
                    .onFailure { Log.w(TAG, "deleteKeyPair failed: ${it.message}") }

                // 3b) Session + stored credentials (the account itself was
                //     deleted server-side by confirm-withdrawal).
                repository.signOutParticipant()

                // 4) Enrolment / identity preferences (also regenerates the pseudo id).
                dataStore.edit {
                    it[ONBOARDING_COMPLETE] = false
                    it.remove(PARTICIPANT_PSEUDO_ID)
                    it.remove(OnboardingViewModel.WRAPPED_DEK)
                    it.remove(SELECTED_STUDY_ID)
                    it.remove(OnboardingViewModel.CONSENTED_TYPE_IDS)
                    it.remove(OnboardingViewModel.CONSENTED_TERMS)
                    it.remove(LAST_SEEN_EXPORT_MS)
                }

                _withdrawal.value = WithdrawalState.Done(receipt)
            } catch (e: Exception) {
                Log.e(TAG, "Withdrawal failed", e)
                _withdrawal.value = WithdrawalState.Error(context.errorText(e, R.string.mbpulse_withdraw_failed_default))

            }
        }
    }

    /**
     * Verifies the backend's RSA signature on a deletion receipt, reconstructing
     * the exact signed string the Edge Function used (keep this in sync with
     * `confirm-withdrawal/index.ts`). Returns null when no verification key is
     * configured, false if configured but the check fails or the receipt is
     * unsigned, true when the signature is valid.
     */
    private fun verifyReceiptSignature(r: DeletionReceipt): Boolean? {
        val publicKeyB64 = MbPulseLibrary.config.deletionReceiptPublicKeyB64 ?: return null
        val signatureB64 = r.signatureB64 ?: return false
        return try {
            // v2 (accounts) appends accountDeleted; v1 receipts from older backends still verify.
            val v1Fields = "${r.receiptId}|${r.pseudoIdHash ?: ""}|${r.studyId ?: ""}" +
                "|${r.deletedAt}|${r.recordsDeleted}|${r.consentDeleted}|${r.participantDeleted}"
            val signingString = when (r.version) {
                "v2" -> "v2|$v1Fields|${r.accountDeleted}"
                else -> "v1|$v1Fields"
            }
            keystoreManager.verifyWithExternalKey(
                message        = signingString.toByteArray(Charsets.UTF_8),
                signature      = Base64.decode(signatureB64, Base64.NO_WRAP),
                publicKeyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Receipt signature verification error: ${e.message}")
            false
        }
    }

    /** Resets the withdrawal state after the UI has handled a terminal result. */
    public fun resetWithdrawalState() {
        _withdrawal.value = WithdrawalState.Idle
    }

    /**
     * Marks the export the notice is about as seen — its own timestamp, so an
     * export logged after the notice appeared is still announced next time.
     */
    public fun dismissExportNotification() {
        viewModelScope.launch {
            val current = _state.value as? LoadingState.Ready ?: return@launch
            current.exportNotificationAtMs?.let { seen ->
                dataStore.edit { prefs ->
                    if (seen > (prefs[LAST_SEEN_EXPORT_MS] ?: 0L)) prefs[LAST_SEEN_EXPORT_MS] = seen
                }
            }
            (_state.value as? LoadingState.Ready)?.let {
                _state.value = it.copy(exportNotification = null, exportNotificationAtMs = null)
            }
        }
    }

    /**
     * Transparency notice: if the research team exported this study's data
     * since the participant last acknowledged an export, sets
     * [LoadingState.Ready.exportNotification]. Called by the library's
     * navigation on every resume of an enrolled participant — not by a
     * replaceable screen — so a custom home screen cannot drop it.
     */
    public fun checkForNewExports(studyId: String) {
        viewModelScope.launch {
            try {
                val lastSeenMs = dataStore.data.first()[LAST_SEEN_EXPORT_MS] ?: 0L
                val latestExport = repository.getLatestExport(studyId) ?: return@launch
                if (latestExport.exportedAtMs > lastSeenMs) {
                    val current = _state.value as? LoadingState.Ready ?: return@launch
                    if (current.studyId != studyId) return@launch
                    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(latestExport.exportedAtMs))
                    _state.value = current.copy(
                        exportNotification = context.getString(R.string.mbpulse_export_notice_text, date),
                        exportNotificationAtMs = latestExport.exportedAtMs
                    )
                }
            } catch (e: Exception) {
                // Non-critical — retried on the next resume.
                Log.w(TAG, "Export check failed: ${e.message}")
            }
        }
    }

    /** Outcome of [refresh], for the message shown after it. */
    public sealed interface RefreshResult {
        /** The server answered: study (and consent review), sync and export notices are current. */
        public data object Updated : RefreshResult
        /** Still offline — the cached study stays in use. */
        public data object Offline : RefreshResult
        /** The study could not be loaded or verified; the current one stays in use. */
        public data class Failed(val message: String) : RefreshResult
    }

    /**
     * Pull-to-refresh / "Refresh": loads the study again without leaving the
     * screen — a study update shows the re-consent review at once — restarts
     * background work with the new parameters, uploads and checks export
     * notices. Unlike [retryLoad] the state never goes back to InProgress, and
     * a definition that fails to load or verify leaves the current (verified)
     * one in place.
     */
    public suspend fun refresh(): RefreshResult {
        val ready = _state.value as? LoadingState.Ready
            ?: return RefreshResult.Failed(context.getString(R.string.mbpulse_refresh_failed))
        return try {
            val fresh = buildReady(dataStore.data.first(), ready.studyId, ready.participantPseudoId)
            // Keep the transient export notice; everything else is fresh.
            _state.value = fresh.copy(
                exportNotification = ready.exportNotification,
                exportNotificationAtMs = ready.exportNotificationAtMs
            )
            startBackgroundWorkIfEnrolled()
            if (fresh.offlineSinceMs != null) RefreshResult.Offline
            else { onBackOnline(); RefreshResult.Updated }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            RefreshResult.Failed(context.errorText(e, R.string.mbpulse_refresh_failed))
        }
    }

    /** The Retry button on the loading error screen (also retried automatically when a network appears). */
    public fun retryLoad() {
        viewModelScope.launch { reload() }
    }

    /** Loads the study again; true when the server answered (not offline from the cache). */
    private suspend fun reload(): Boolean {
        _state.value = LoadingState.InProgress
        return try {
            checkBackend()
            val prefs = dataStore.data.first()
            val ready = buildReady(prefs, prefs[SELECTED_STUDY_ID] ?: "", prefs[PARTICIPANT_PSEUDO_ID] ?: "")
            _state.value = ready
            startBackgroundWorkIfEnrolled()
            ready.offlineSinceMs == null
        } catch (e: Exception) {
            _state.value = LoadingState.Error(context.errorText(e, R.string.mbpulse_loading_failed_detail))
            false
        }
    }
}