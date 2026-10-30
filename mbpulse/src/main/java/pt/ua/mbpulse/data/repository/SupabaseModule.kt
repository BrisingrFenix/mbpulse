package pt.ua.mbpulse.data.repository

import android.content.Context
import pt.ua.mbpulse.MbPulseLibrary.config
import pt.ua.mbpulse.auth.ParticipantCredentialStore
import pt.ua.mbpulse.auth.SealedSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal class SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(@ApplicationContext context: Context): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = config.supabaseUrl,
            supabaseKey = config.supabaseKey
        ) {
            // Participant accounts (docs/design/participant-accounts.md). The
            // session is sealed under the Keystore, loaded on start (also in
            // WorkManager workers, which share this singleton) and refreshed
            // automatically; requests then run as `authenticated`.
            install(Auth) {
                sessionManager = SealedSessionManager(context)
                autoLoadFromStorage = true
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
    }

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context) = ParticipantCredentialStore(context)

    @Provides
    @Singleton
    fun provideDeviceKeyStore(@ApplicationContext context: Context) =
        pt.ua.mbpulse.auth.DeviceKeyStore(context)
}
