package pt.ua.mbpulse.data.repository

/**
 * What `backend_version()` (backend/00_schema.sql) reports: the lowest API
 * version among the SQL files ([api]), the oldest client API the backend still
 * serves ([compatibleSince]), and SQL files never applied ([missing]).
 * [api] is 0 for a backend from before versioning (no `backend_version()`).
 */
internal data class BackendVersion(
    val api: Int,
    val compatibleSince: Int,
    val missing: List<String> = emptyList()
)

/** App and backend do not match ([BackendCompatibility]); [message] is participant-facing. */
internal class BackendMismatchException(message: String) : Exception(message)

/**
 * Whether this library build can talk to a backend — checked on every study
 * load ([pt.ua.mbpulse.ui.screens.loading.LoadingViewModel]) so a
 * mismatch shows a clear message instead of failing later (e.g. at enrolment).
 * Pure, unit-tested.
 */
internal object BackendCompatibility {

    /**
     * The backend API version this library speaks (backend/00_schema.sql
     * lists them). Bump it together with the SQL stamps when the library starts
     * relying on a backend change; a backend change that breaks older clients
     * also raises `compatible_since` there.
     */
    const val CLIENT_API = 4

    sealed interface Result {
        data object Compatible : Result
        /** Some SQL files were never applied — the backend set-up is incomplete. */
        data class Incomplete(val missing: List<String>) : Result
        /** The backend is older than this app needs — re-run the backend SQL. */
        data class BackendTooOld(val backendApi: Int) : Result
        /** The backend no longer serves this app version — the app needs updating. */
        data class AppTooOld(val compatibleSince: Int) : Result
    }

    fun check(backend: BackendVersion, clientApi: Int = CLIENT_API): Result = when {
        backend.missing.isNotEmpty() -> Result.Incomplete(backend.missing)
        backend.api < clientApi -> Result.BackendTooOld(backend.api)
        backend.compatibleSince > clientApi -> Result.AppTooOld(backend.compatibleSince)
        else -> Result.Compatible
    }
}
