package pt.ua.mbpulse

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography

/**
 * How the library's screens look in the host app ([MbPulseConfig.branding]).
 * Every screen — including the non-replaceable onboarding, consent and
 * recovery — follows it, so a study can carry its own identity without
 * replacing screens.
 *
 * The usual case is just a brand colour (and maybe a logo):
 * ```kotlin
 * branding = MbPulseBranding(primaryColor = 0xFF1565C0.toInt(), logoRes = R.drawable.study_logo)
 * ```
 * Wording comes from string resources (`mbpulse_*` in the library's
 * `res/values/strings_*.xml`): define a string with the same name in the host
 * app to change it, or add `values-xx/` translations.
 */
public data class MbPulseBranding(
    /** Brand colour (ARGB). Light and dark colour schemes are derived from it. */
    @ColorInt val primaryColor: Int = DEFAULT_PRIMARY,
    /** Optional second accent (ARGB); a muted shade of [primaryColor] when null. */
    @ColorInt val secondaryColor: Int? = null,
    /** Home screen title; null = the `mbpulse_home_title` string ("Measurements"). */
    val homeTitle: String? = null,
    /** Logo (host drawable), shown on study selection and the consent introduction. */
    @DrawableRes val logoRes: Int? = null,
    /** Top app bars filled with the brand colour (true) or plain surface colour (false). */
    val coloredTopBars: Boolean = true,
    /** Use the wallpaper colours (Material You, Android 12+) instead of [primaryColor]. */
    val dynamicColor: Boolean = false,
    /** null = follow the system setting; true / false = always dark / light. */
    val darkTheme: Boolean? = null,
    /**
     * Full control over colours: replaces the derived scheme (argument: dark
     * mode). Keep `tertiary` an attention colour — the library uses it for
     * "out of date" and "offline" states, and `error` for failures.
     */
    val colorScheme: ((dark: Boolean) -> ColorScheme)? = null,
    val typography: Typography? = null,
    val shapes: Shapes? = null
) {
    public companion object {
        /** The library's default green. */
        public const val DEFAULT_PRIMARY: Int = 0xFF078966.toInt()
    }
}
