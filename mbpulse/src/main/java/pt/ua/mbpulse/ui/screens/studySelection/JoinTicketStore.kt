package pt.ua.mbpulse.ui.screens.studySelection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Own file: writes to the main preferences make LoadingViewModel reload the study. */
private val Context.joinTicketStore: DataStore<Preferences> by preferencesDataStore(name = "mbpulse_join")

/**
 * The join ticket the backend issued for the entered join code
 * (`issue_join_ticket`, join_code.sql), kept from study selection until
 * enrolment presents it. Single use and short-lived on the server; not the
 * code itself, which is never stored.
 */
internal object JoinTicketStore {

    private val STUDY_ID = stringPreferencesKey("study_id")
    private val TICKET = stringPreferencesKey("ticket")

    suspend fun save(context: Context, studyId: String, ticket: String) {
        context.joinTicketStore.edit {
            it[STUDY_ID] = studyId
            it[TICKET] = ticket
        }
    }

    /** The ticket for [studyId], or null (none, or one for another study). */
    suspend fun load(context: Context, studyId: String): String? {
        val prefs = context.joinTicketStore.data.first()
        return prefs[TICKET]?.takeIf { prefs[STUDY_ID] == studyId }
    }

    suspend fun clear(context: Context) {
        context.joinTicketStore.edit { it.clear() }
    }
}
