package pt.ua.mbpulse.collection

import android.content.Context
import android.util.Base64
import pt.ua.mbpulse.logging.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "EnrollmentStore"

/**
 * Own preferences file, so writes here do not re-trigger observers of the main
 * app preferences (LoadingViewModel reloads the study on every change).
 */
private val Context.enrollmentStore: DataStore<Preferences> by preferencesDataStore(name = "mbpulse_enrollment")

/**
 * What background work needs to encrypt data for the enrolled participant
 * without any UI: passive collection after a reboot ([PassiveCollection]) and
 * the periodic Health Connect import ([HealthConnectBackgroundImport]).
 *
 * The values are those the collection screen passes: the RSA-wrapped DEK
 * (onboarding already stores it in the app preferences — useless without the
 * hardware key), the PI *public* key, consent choices and sampling intervals.
 * Saved when the participant is onboarded, cleared on withdrawal and reset.
 */
@Serializable
internal data class Enrollment(
    val participantPseudoId: String,
    val studyId: String,
    val wrappedDekB64: String,
    val piPublicKeyB64: String? = null,
    val consentedTypeIds: Set<String>,
    val samplingSecondsByTypeId: Map<String, Int> = emptyMap(),
) {
    val wrappedDekBytes: ByteArray get() = Base64.decode(wrappedDekB64, Base64.NO_WRAP)
    val piPublicKeyBytes: ByteArray? get() = piPublicKeyB64?.let { Base64.decode(it, Base64.NO_WRAP) }

    companion object {
        fun of(
            participantPseudoId: String,
            studyId: String,
            wrappedDekBytes: ByteArray,
            piPublicKeyBytes: ByteArray?,
            consentedTypeIds: Set<String>,
            samplingSecondsByTypeId: Map<String, Int>,
        ) = Enrollment(
            participantPseudoId     = participantPseudoId,
            studyId                 = studyId,
            wrappedDekB64           = Base64.encodeToString(wrappedDekBytes, Base64.NO_WRAP),
            piPublicKeyB64          = piPublicKeyBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            consentedTypeIds        = consentedTypeIds,
            samplingSecondsByTypeId = samplingSecondsByTypeId,
        )
    }
}

internal object EnrollmentStore {

    private val ENROLLMENT = stringPreferencesKey("enrollment")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(context: Context, enrollment: Enrollment) {
        context.enrollmentStore.edit { it[ENROLLMENT] = json.encodeToString(Enrollment.serializer(), enrollment) }
    }

    suspend fun load(context: Context): Enrollment? {
        val raw = context.enrollmentStore.data.first()[ENROLLMENT] ?: return null
        return runCatching { json.decodeFromString(Enrollment.serializer(), raw) }
            .getOrElse { Log.w(TAG, "Unreadable enrollment — ignoring: ${it.message}"); null }
    }

    /** Clears the enrollment and every piece of background-work state. */
    suspend fun clear(context: Context) {
        context.enrollmentStore.edit { it.clear() }
    }

    // -- small key/value state for background work (change tokens, watermarks) --

    suspend fun getString(context: Context, key: String): String? =
        context.enrollmentStore.data.first()[stringPreferencesKey(key)]

    fun observeString(context: Context, key: String): Flow<String?> =
        context.enrollmentStore.data.map { it[stringPreferencesKey(key)] }

    suspend fun getLong(context: Context, key: String): Long? =
        context.enrollmentStore.data.first()[longPreferencesKey(key)]

    suspend fun put(context: Context, strings: Map<String, String?> = emptyMap(), longs: Map<String, Long> = emptyMap()) {
        context.enrollmentStore.edit { prefs ->
            strings.forEach { (k, v) ->
                if (v == null) prefs.remove(stringPreferencesKey(k)) else prefs[stringPreferencesKey(k)] = v
            }
            longs.forEach { (k, v) -> prefs[longPreferencesKey(k)] = v }
        }
    }
}
