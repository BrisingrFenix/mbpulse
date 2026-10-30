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
 * Append-only history of every epoch value per signal type.
 * Unlike [DisplayEpochEntity] which keeps only the latest value,
 * this table keeps all epochs for charting and history views.
 */
@Entity(
    tableName = "epoch_history",
    indices   = [Index(value = ["schemaName", "epochStartMs"])]
)
internal data class EpochHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val schemaName: String,
    val epochStartMs: Long,

    /** Numeric value for charting — NaN if the signal has no numeric form. */
    val numericValue: Float,

    /** Formatted string for display in the list below the chart. */
    val displayValue: String,

    val unit: String,

    /** [pt.ua.mbpulse.collection.EpochDisplay.Kind] name; null = NUMBER (rows before v6). */
    val kind: String? = null,

    /** Category value for CATEGORY / EVENT rows (e.g. "awake", "thermal"). */
    val category: String? = null,

    /** Every numeric field as a JSON object {label: value}, primary first; null = only [numericValue]. */
    val seriesJson: String? = null
)

@Dao
internal interface EpochHistoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EpochHistoryEntity)

    @Query("""
        SELECT * FROM epoch_history
        WHERE schemaName = :schemaName
        AND epochStartMs >= :sinceMs
        ORDER BY epochStartMs ASC
    """)
    fun observe(schemaName: String, sinceMs: Long): Flow<List<EpochHistoryEntity>>

    @Query("""
        SELECT * FROM epoch_history
        WHERE schemaName = :schemaName
        ORDER BY epochStartMs ASC
    """)
    fun observeAll(schemaName: String): Flow<List<EpochHistoryEntity>>

    /** Every schema's rows since [sinceMs] — the home cards' sparklines. */
    @Query("SELECT * FROM epoch_history WHERE epochStartMs >= :sinceMs ORDER BY epochStartMs ASC")
    fun observeSince(sinceMs: Long): Flow<List<EpochHistoryEntity>>

    @Query("SELECT MIN(numericValue) FROM epoch_history WHERE schemaName = :schemaName AND epochStartMs >= :sinceMs")
    suspend fun min(schemaName: String, sinceMs: Long): Float?

    @Query("SELECT MAX(numericValue) FROM epoch_history WHERE schemaName = :schemaName AND epochStartMs >= :sinceMs")
    suspend fun max(schemaName: String, sinceMs: Long): Float?

    @Query("SELECT AVG(numericValue) FROM epoch_history WHERE schemaName = :schemaName AND epochStartMs >= :sinceMs")
    suspend fun avg(schemaName: String, sinceMs: Long): Float?

    @Query("DELETE FROM epoch_history")
    suspend fun deleteAll()
}