package pt.ua.mbpulse.ui

import android.content.Context
import androidx.annotation.StringRes
import pt.ua.mbpulse.data.repository.BackendMismatchException
import pt.ua.mbpulse.data.repository.StudyDefinitionException

/**
 * What a participant reads when something fails: the translated [fallback]
 * text, with the technical detail in brackets for support. Exceptions that
 * already carry participant-facing wording (a study definition that does not
 * verify) are shown as they are.
 */
internal fun Context.errorText(e: Throwable, @StringRes fallback: Int): String {
    if (e is StudyDefinitionException || e is BackendMismatchException) return e.message ?: getString(fallback)
    val detail = e.message?.takeIf { it.isNotBlank() }?.take(200)
    return if (detail == null) getString(fallback) else getString(R_DETAIL, getString(fallback), detail)
}

private val R_DETAIL = pt.ua.mbpulse.R.string.mbpulse_error_with_detail
