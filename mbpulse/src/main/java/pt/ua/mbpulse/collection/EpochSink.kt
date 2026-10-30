package pt.ua.mbpulse.collection

import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.DisplayEpochEntity
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.local.EpochHistoryEntity
import pt.ua.mbpulse.data.model.OmhDataPoint

/**
 * Terminal stage of the epoch pipeline, shared by live collection
 * ([CollectionService]) and history import ([HealthConnectImporter]) so the two
 * paths can never diverge: apply the study's per-data-type sampling interval,
 * encrypt the consented data points of a [ProcessedEpoch] for backend sync,
 * then write plaintext summaries for the local UI.
 *
 * @param samplingSecondsByTypeId the study's `samplingSeconds` per consent
 *   data-type id (from `StudyConfig.dataTypes`); empty = record at the
 *   processor's natural cadence.
 */
internal class EpochSink(
    private val encryptedRecordDao: EncryptedRecordDao,
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao,
    samplingSecondsByTypeId: Map<String, Int> = emptyMap(),
    schemaToConsentType: (schemaName: String) -> String? =
        { MbPulseLibrary.consentTypeFor(it) }
) {

    private val samplingGate = SamplingGate(samplingSecondsByTypeId, schemaToConsentType)

    /**
     * Applies the sampling gate, encrypts what is due and queues the ciphertext
     * for upload, then records plaintext display/history rows.
     *
     * A point already stored (same participant, epoch and schema — e.g. a
     * re-delivered sensor batch or a repeated Health Connect import) is
     * ignored: not queued for upload again, no extra history row.
     *
     * @return the number of new encrypted records inserted (0 if every data
     *   point was filtered out by sampling or consent, or was a duplicate).
     */
    suspend fun store(
        epoch: ProcessedEpoch,
        encryptor: EpochEncryptor,
        wrappedDekBytes: ByteArray
    ): Int {
        // Events (instant time frame, e.g. stimulus triggers) always pass the gate.
        val duePoints = epoch.dataPoints.filter { point -> samplingGate.allow(point, epoch.epochStartMs) }
        if (duePoints.isEmpty()) return 0

        val dueEpoch = epoch.copy(dataPoints = duePoints)
        val records = encryptor.encrypt(dueEpoch, wrappedDekBytes)
        val rowIds = if (records.isEmpty()) emptyList() else encryptedRecordDao.insertAll(records)
        // Local display/history only for points that were newly stored: the
        // encryptor drops points of declined data types, and a record that
        // already existed (insert ignored, row id -1) is not shown twice.
        // Declined data must not be kept on the device in any form.
        val newSchemas = records.zip(rowIds)
            .filter { (_, rowId) -> rowId != -1L }
            .mapTo(HashSet()) { (record, _) -> record.schemaName }
        writeDisplay(dueEpoch.epochStartMs, dueEpoch.dataPoints.filter { point ->
            "${point.header.schemaId.namespace}:${point.header.schemaId.name}" in newSchemas
        })
        return rowIds.count { it != -1L }
    }

    private suspend fun writeDisplay(epochStartMs: Long, dataPoints: List<OmhDataPoint>) {
        dataPoints.forEach { point ->
            val schemaName = "${point.header.schemaId.namespace}:${point.header.schemaId.name}"
            val summary = EpochDisplay.summarize(point)
                ?: EpochDisplay.Summary("—", "", null)

            displayEpochDao.upsert(
                DisplayEpochEntity(
                    schemaName   = schemaName,
                    epochStartMs = epochStartMs,
                    displayValue = summary.displayValue,
                    unit         = summary.unit
                )
            )

            EpochDisplay.historyRow(schemaName, epochStartMs, summary)?.let { epochHistoryDao.insert(it) }
        }
    }
}
