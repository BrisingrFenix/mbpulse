package pt.ua.mbpulse.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stores the latest plaintext derived value per signal type for UI display.
 * One row per schema — replaced on each new epoch via REPLACE conflict strategy.
 *
 * This is the only plaintext health data on disk. It contains only the
 * derived summary values (mean HR, sleep stage, etc.) — no raw sensor data.
 * Raw signals and full epoch data only exist in memory during processing,
 * or encrypted in [EncryptedRecordEntity].
 */
@Entity(tableName = "display_epochs")
internal data class DisplayEpochEntity(
    /** omh schema name, e.g. "omh:heart-rate" — primary key, one row per signal type. */
    @PrimaryKey
    val schemaName: String,

    val epochStartMs: Long,

    /** Human-readable value formatted for display, e.g. "62.3" or "WAKE". */
    val displayValue: String,

    val unit: String
)

@Dao
internal interface DisplayEpochDao {

    /** Replaces the existing row for this schema — always keeps the latest epoch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(epoch: DisplayEpochEntity)

    /** Observed by the UI — emits a new list whenever any epoch is updated. */
    @Query("SELECT * FROM display_epochs ORDER BY schemaName ASC")
    fun observeAll(): Flow<List<DisplayEpochEntity>>

    @Query("SELECT * FROM display_epochs WHERE schemaName = :schemaName LIMIT 1")
    suspend fun getBySchema(schemaName: String): DisplayEpochEntity?

    @Query("DELETE FROM display_epochs")
    suspend fun deleteAll()
}