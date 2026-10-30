package pt.ua.mbpulse.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import pt.ua.mbpulse.crypto.KeyRecoveryManager
import pt.ua.mbpulse.crypto.KeystoreManager
import pt.ua.mbpulse.data.local.AppDatabase
import pt.ua.mbpulse.data.local.DatabaseKeyProvider
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.EncryptedRecordDao
import pt.ua.mbpulse.data.local.EpochHistoryDao
import dagger.Module
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        context.deleteDatabase("mbpulse_db")

        System.loadLibrary("sqlcipher")

        val passphrase = DatabaseKeyProvider(context).getOrCreatePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, AppDatabase::class.java, "mbpulse_db_enc")
            .openHelperFactory(factory)
            // Explicit migrations keep unsynced encrypted records across schema
            // versions; the destructive fallback below is only a last resort.
            .addMigrations(*AppDatabase.MIGRATIONS)
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    fun provideEncryptedRecordDao(db: AppDatabase): EncryptedRecordDao =
        db.encryptedRecordDao()

    @Provides
    fun provideDisplayEpochDao(db: AppDatabase): DisplayEpochDao =
        db.displayEpochDao()

    @Provides
    fun provideEpochHistoryDao(db: AppDatabase): EpochHistoryDao =
        db.epochHistoryDao()

    @Provides
    @Singleton
    fun provideKeystoreManager(): KeystoreManager = KeystoreManager()

    @Provides @Singleton
    fun provideKeyRecoveryManager(): KeyRecoveryManager = KeyRecoveryManager()
}