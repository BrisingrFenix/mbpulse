package pt.ua.mbpulse.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal enum class SyncState { PENDING, SYNCED, FAILED }

internal class SyncStateConverter {
    @TypeConverter fun fromSyncState(state: SyncState): String = state.name
    @TypeConverter fun toSyncState(value: String): SyncState = SyncState.valueOf(value)
}

@Database(
    entities = [
        EncryptedRecordEntity::class,
        DisplayEpochEntity::class,
        EpochHistoryEntity::class
               ],
    version = 6,
    // Written to mbpulse/schemas/ (committed): the reference the
    // migration tests (AppDatabaseMigrationTest) check every migration against.
    // A schema change shows up there as a diff — with no version bump, Room
    // would overwrite the file; bump the version and add a migration instead.
    exportSchema = true
)
@TypeConverters(SyncStateConverter::class)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun encryptedRecordDao(): EncryptedRecordDao
    abstract fun displayEpochDao(): DisplayEpochDao
    abstract fun epochHistoryDao(): EpochHistoryDao

    companion object {
        /**
         * Every version bump needs a migration here: the builder falls back to
         * a DESTRUCTIVE migration otherwise, which would wipe encrypted records
         * that have not been uploaded yet.
         */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_4_5, MIGRATION_5_6)

        /**
         * v6: history rows say how to chart them (number / category / event)
         * and keep every numeric field, not only the primary one. Existing
         * sleep-stage rows (stored as 1/0) become categories.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE epoch_history ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE epoch_history ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE epoch_history ADD COLUMN seriesJson TEXT")
                db.execSQL(
                    "UPDATE epoch_history SET kind = 'CATEGORY', category = lower(displayValue) " +
                        "WHERE schemaName = 'omh:sleep-episode'"
                )
            }
        }

        /**
         * v5: encrypted_records is unique on (participant, epoch, schema), and
         * FAILED records are retried. Before v5 a FAILED record was never
         * selected for upload again — re-queue those, so data stranded by a
         * single network error finally reaches the server.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Collapse existing duplicates before the unique index can exist.
                // Keep one row per key: a SYNCED copy if any, else the oldest.
                db.execSQL(
                    """
                    DELETE FROM encrypted_records
                    WHERE EXISTS (
                        SELECT 1 FROM encrypted_records AS keep
                        WHERE keep.participantPseudoId = encrypted_records.participantPseudoId
                          AND keep.epochStartMs        = encrypted_records.epochStartMs
                          AND keep.schemaName          = encrypted_records.schemaName
                          AND keep.id                 <> encrypted_records.id
                          AND (
                                (keep.syncState = 'SYNCED' AND encrypted_records.syncState <> 'SYNCED')
                             OR ((keep.syncState = 'SYNCED') = (encrypted_records.syncState = 'SYNCED')
                                 AND keep.id < encrypted_records.id)
                          )
                    )
                    """.trimIndent()
                )
                db.execSQL("UPDATE encrypted_records SET syncState = 'PENDING' WHERE syncState = 'FAILED'")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_encrypted_records_participantPseudoId_epochStartMs_schemaName` " +
                        "ON `encrypted_records` (`participantPseudoId`, `epochStartMs`, `schemaName`)"
                )
            }
        }
    }
}