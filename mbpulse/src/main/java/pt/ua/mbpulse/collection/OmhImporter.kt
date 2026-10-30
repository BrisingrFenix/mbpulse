package pt.ua.mbpulse.collection

import pt.ua.mbpulse.logging.Log
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.DisplayEpochEntity
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.local.EpochHistoryEntity
import pt.ua.mbpulse.data.model.OmhDataPoint
import kotlinx.serialization.json.Json

private const val TAG = "OmhImporter"

/**
 * Decrypts a raw ciphertext hex string using the provided DEK bytes,
 * parses the resulting omh JSON, and writes the signal values into the
 * local Room tables so the UI shows recovered data immediately.
 *
 * Display/history rows come from [EpochDisplay] — the same extraction live
 * collection uses — so every schema the library knows is recovered, and
 * recovered rows look exactly like freshly collected ones.
 */
internal object OmhImporter {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal suspend fun importRecord(
        ciphertextHex: String,
        schemaName: String,
        epochStartMs: Long,
        dekBytes: ByteArray,
        keystoreManager: KeystoreManager,
        displayEpochDao: DisplayEpochDao,
        epochHistoryDao: EpochHistoryDao
    ) {
        try {
            val ciphertext = hexToBytes(ciphertextHex)
            val plaintext  = keystoreManager.decryptRecord(ciphertext, dekBytes)
            val omh        = json.decodeFromString<OmhDataPoint>(
                plaintext.toString(Charsets.UTF_8)
            )

            val summary = EpochDisplay.summarize(omh)
            if (summary == null) {
                Log.w(TAG, "Unknown schema: $schemaName — skipping")
                return
            }

            displayEpochDao.upsert(DisplayEpochEntity(
                schemaName   = schemaName,
                epochStartMs = epochStartMs,
                displayValue = summary.displayValue,
                unit         = summary.unit
            ))
            EpochDisplay.historyRow(schemaName, epochStartMs, summary)?.let { epochHistoryDao.insert(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import record schema=$schemaName epoch=$epochStartMs: ${e.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
