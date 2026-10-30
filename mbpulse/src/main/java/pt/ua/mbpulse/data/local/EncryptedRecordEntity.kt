package pt.ua.mbpulse.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One encrypted data point queued for (or already) uploaded.
 *
 * Unique on (participant, epoch, schema) — the same key as the server's
 * `encrypted_records_unique_epoch` — so a re-delivered sensor batch is ignored
 * on insert instead of being queued and uploaded twice. Added in DB v5
 * ([AppDatabase.MIGRATION_4_5]).
 */
@Entity(
    tableName = "encrypted_records",
    indices = [Index(value = ["participantPseudoId", "epochStartMs", "schemaName"], unique = true)]
)
internal data class EncryptedRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Participant pseudonymous identifier — no real identity. */
    val participantPseudoId: String,

    val studyId: String,

    /** UTC epoch start time in milliseconds — used for ordering and deduplication. */
    val epochStartMs: Long,

    /** openMHealth schema identifier*/
    val schemaName: String,

    /** IV (12 bytes) prepended to AES-GCM ciphertext — stored as hex string. */
    val ciphertextHex: String,

    /** DEK wrapped under the participant's hardware-backed RSA key. */
    val participantWrappedDekHex: String,
    /** DEK wrapped under the PI's RSA public key — allows PI to decrypt without participant. */
    val piWrappedDekHex: String,

    val syncState: SyncState = SyncState.PENDING,

    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
internal interface EncryptedRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: EncryptedRecordEntity): Long

    /** Row ids in input order; `-1` for a duplicate that was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<EncryptedRecordEntity>): List<Long>

    /**
     * Not yet on the server: never attempted, or the last attempt failed
     * (FAILED is retried on every sync — it only records that one try failed).
     */
    @Query("SELECT * FROM encrypted_records WHERE syncState IN ('PENDING', 'FAILED') ORDER BY epochStartMs ASC")
    suspend fun getPending(): List<EncryptedRecordEntity>

    @Query("UPDATE encrypted_records SET syncState = 'SYNCED' WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE encrypted_records SET syncState = 'FAILED' WHERE id = :id")
    suspend fun markFailed(id: Long)

    @Query("SELECT COUNT(*) FROM encrypted_records WHERE syncState IN ('PENDING', 'FAILED')")
    suspend fun pendingCount(): Int

    /** Study ended: records not yet uploaded and measured after [endedAtMs] — the backend refuses them. */
    @Query("DELETE FROM encrypted_records WHERE syncState IN ('PENDING', 'FAILED') AND epochStartMs > :endedAtMs")
    suspend fun deleteUnsyncedAfter(endedAtMs: Long): Int

    @Query("SELECT COUNT(*) FROM encrypted_records WHERE syncState = 'SYNCED'")
    suspend fun syncedCount(): Int

    // Reactive queries — emit whenever SyncWorker marks records SYNCED/FAILED
    @Query("SELECT COUNT(*) FROM encrypted_records WHERE syncState IN ('PENDING', 'FAILED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM encrypted_records WHERE syncState = 'SYNCED'")
    fun observeSyncedCount(): Flow<Int>

    /** Epochs (not data points) with at least one record not yet on the server. */
    @Query("SELECT COUNT(DISTINCT epochStartMs) FROM encrypted_records WHERE syncState IN ('PENDING', 'FAILED')")
    fun observePendingEpochCount(): Flow<Int>

    @Query("UPDATE encrypted_records SET syncState = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** For development reset only — remove before release. */
    @Query("DELETE FROM encrypted_records")
    suspend fun deleteAll()
}