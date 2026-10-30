package pt.ua.mbpulse.ui.screens.healthconnect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import pt.ua.mbpulse.collection.EpochSink
import pt.ua.mbpulse.collection.HealthConnectBackgroundImport
import pt.ua.mbpulse.collection.HealthConnectImportResult
import pt.ua.mbpulse.collection.HealthConnectImporter
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.ui.screens.loading.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

internal sealed interface ImportUiState {
    object Idle : ImportUiState
    object Importing : ImportUiState
    data class Done(val result: HealthConnectImportResult) : ImportUiState
}

@HiltViewModel
internal class HealthConnectImportViewModel @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val encryptedRecordDao: EncryptedRecordDao,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao
) : ViewModel() {

    // Availability/permission probes only — the import itself builds a fresh
    // importer whose EpochSink carries the study's sampling intervals.
    private val importer = HealthConnectImporter(
        context         = context,
        keystoreManager = keystoreManager,
        epochSink       = EpochSink(encryptedRecordDao, displayEpochDao, epochHistoryDao)
    )

    /** Health Connect read permissions the import needs (granted = import can run). */
    val permissions: Set<String> get() = importer.permissions

    /**
     * What to pass to the permission launcher: [permissions] plus background
     * reading when the background import is configured and this device's
     * Health Connect supports it — so one prompt covers both.
     */
    val requestPermissions: Set<String>
        get() {
            if (!isAvailable()) return permissions
            val client = HealthConnectClient.getOrCreate(context)
            val background = MbPulseLibrary.config.healthConnectBackgroundImport != null &&
                HealthConnectBackgroundImport.backgroundReadAvailable(client)
            val history = importDays > 30 && featureAvailable(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)
            return permissions +
                (if (background) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else emptySet()) +
                (if (history) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY) else emptySet())
        }

    /** Days of history the import reads ([pt.ua.mbpulse.MbPulseConfig.healthConnectImportDays]). */
    val importDays: Int get() = MbPulseLibrary.config.healthConnectImportDays

    private fun featureAvailable(client: HealthConnectClient, feature: Int): Boolean = runCatching {
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** Display names of the categories this build can import, for the UI. */
    val sourceNames: List<String>
        get() = MbPulseLibrary.config.healthConnectSources.map { it.displayName }

    fun isAvailable(): Boolean = importer.isAvailable()

    /** True when this app build registered at least one Health Connect source. */
    fun isConfigured(): Boolean = MbPulseLibrary.config.healthConnectSources.isNotEmpty()

    suspend fun hasPermission(): Boolean = importer.hasPermission()

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    fun importLastDays(ready: LoadingState.Ready, days: Long = importDays.toLong()) {
        viewModelScope.launch {
            _state.value = ImportUiState.Importing
            val gatedImporter = HealthConnectImporter(
                context         = context,
                keystoreManager = keystoreManager,
                epochSink       = EpochSink(
                    encryptedRecordDao, displayEpochDao, epochHistoryDao,
                    samplingSecondsByTypeId = ready.samplingSecondsByTypeId
                )
            )
            val result = gatedImporter.import(
                participantPseudoId = ready.participantPseudoId,
                studyId             = ready.studyId,
                wrappedDekBytes     = ready.wrappedDekBytes,
                piPublicKeyBytes    = ready.piPublicKeyBytes,
                consentedTypeIds    = ready.consentedTypeIds,
                since               = Instant.now().minus(days, ChronoUnit.DAYS)
            )
            _state.value = ImportUiState.Done(result)
        }
    }
}
