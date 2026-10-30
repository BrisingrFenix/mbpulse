package pt.ua.mbpulse.collection

import android.content.Context
import pt.ua.mbpulse.logging.Log
import androidx.health.connect.client.HealthConnectClient
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.crypto.KeystoreManager
import java.time.Instant

private const val TAG = "HealthConnectImporter"

/** Outcome of a Health Connect import attempt. */
internal sealed interface HealthConnectImportResult {
    /** Health Connect is not installed / available on this device. */
    object Unavailable : HealthConnectImportResult
    /** No [pt.ua.mbpulse.MbPulseConfig.healthConnectSources] registered. */
    object NotConfigured : HealthConnectImportResult
    /** A required read permission has not been granted — request it first. */
    object PermissionRequired : HealthConnectImportResult
    /** Imported successfully. [records] is the number of encrypted rows queued. */
    data class Success(val epochs: Int, val records: Int) : HealthConnectImportResult
    data class Failed(val message: String) : HealthConnectImportResult
}

/**
 * Imports historical data from Google Health Connect and routes it through the
 * same encrypt → store → sync pipeline as live collection ([EpochSink]).
 *
 * This class is data-type agnostic: it iterates the [HealthConnectSource]s the
 * host registered in [pt.ua.mbpulse.MbPulseConfig.healthConnectSources],
 * letting each read its own records and map them to [ProcessedEpoch]s. The host
 * owns the Health Connect ↔ openMHealth mapping and provenance tagging; this
 * class only orchestrates permissions, reading and the pipeline.
 */
internal class HealthConnectImporter internal constructor(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val epochSink: EpochSink
) {

    private val sources: List<HealthConnectSource>
        get() = MbPulseLibrary.config.healthConnectSources

    /** True if Health Connect is installed and usable on this device. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Union of the read permissions across every registered source. */
    val permissions: Set<String>
        get() = sources.flatMapTo(HashSet()) { it.permissions }

    /** True once every registered source's permissions have been granted. */
    suspend fun hasPermission(): Boolean = runCatching {
        client().permissionController.getGrantedPermissions().containsAll(permissions)
    }.getOrDefault(false)

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /**
     * Reads, maps, encrypts and queues data from the registered sources over
     * [since]..[until].
     *
     * @param sourceIds restrict the import to these source [HealthConnectSource.id]s,
     *   or `null` (default) to import every registered source.
     */
    suspend fun import(
        participantPseudoId: String,
        studyId: String,
        wrappedDekBytes: ByteArray,
        piPublicKeyBytes: ByteArray?,
        consentedTypeIds: Set<String>,
        since: Instant,
        until: Instant = Instant.now(),
        sourceIds: Set<String>? = null
    ): HealthConnectImportResult {
        val selected = sources.filter { sourceIds == null || it.id in sourceIds }
        if (selected.isEmpty()) return HealthConnectImportResult.NotConfigured
        if (!isAvailable()) return HealthConnectImportResult.Unavailable

        val client = client()
        val needed = selected.flatMapTo(HashSet()) { it.permissions }
        if (!client.permissionController.getGrantedPermissions().containsAll(needed)) {
            return HealthConnectImportResult.PermissionRequired
        }

        var dekBytes: ByteArray? = null
        return try {
            val epochs = selected.flatMap { source ->
                source.read(client, since, until).also {
                    Log.d(TAG, "Source '${source.id}' produced ${it.size} epochs")
                }
            }
            if (epochs.isEmpty()) return HealthConnectImportResult.Success(epochs = 0, records = 0)

            dekBytes = keystoreManager.unwrapDek(wrappedDekBytes)
            val encryptor = EpochEncryptor(
                keystoreManager     = keystoreManager,
                participantPseudoId = participantPseudoId,
                studyId             = studyId,
                dekBytes            = dekBytes,
                piPublicKeyBytes    = piPublicKeyBytes,
                consentedTypeIds    = consentedTypeIds,
                schemaToConsentType = MbPulseLibrary::consentTypeFor
            )

            var recordCount = 0
            epochs.forEach { epoch ->
                recordCount += epochSink.store(epoch, encryptor, wrappedDekBytes)
            }

            SyncWorker.schedule(context)
            SyncWorker.syncNow(context)

            Log.d(TAG, "Imported ${epochs.size} epochs / $recordCount records from ${selected.size} source(s)")
            HealthConnectImportResult.Success(epochs = epochs.size, records = recordCount)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e::class.simpleName} — ${e.message}", e)
            HealthConnectImportResult.Failed("${e::class.simpleName}: ${e.message}")
        } finally {
            dekBytes?.fill(0)
        }
    }
}
