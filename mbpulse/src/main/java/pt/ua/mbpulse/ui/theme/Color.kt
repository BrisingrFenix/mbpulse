package pt.ua.mbpulse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** The library's default brand colour. */
internal val Green = Color(0xFF078966)

/** Black or white, whichever reads better on [background]. */
internal fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.4f) Color(0xFF111111) else Color.White

/**
 * A full Material 3 colour scheme from one brand colour (plus an optional
 * second accent): containers are tints/shades of it, surfaces carry a hint of
 * it, and dark mode uses a lighter primary for contrast. `tertiary` is a fixed
 * amber "attention" colour (out of date, offline); `error` stays red.
 */
internal fun brandColorScheme(primary: Color, secondary: Color?, dark: Boolean): ColorScheme {
    val second = secondary ?: lerp(primary, Color(0xFF6F7975), 0.45f)
    return if (!dark) {
        lightColorScheme(
            primary = primary,
            onPrimary = contentColorOn(primary),
            primaryContainer = lerp(primary, Color.White, 0.82f),
            onPrimaryContainer = lerp(primary, Color.Black, 0.62f),
            inversePrimary = lerp(primary, Color.White, 0.45f),
            secondary = second,
            onSecondary = contentColorOn(second),
            secondaryContainer = lerp(second, Color.White, 0.84f),
            onSecondaryContainer = lerp(second, Color.Black, 0.65f),
            tertiary = Color(0xFF8A5A00),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFDDB3),
            onTertiaryContainer = Color(0xFF2B1700),
            background = lerp(Color(0xFFFCFCFC), primary, 0.02f),
            surface = lerp(Color(0xFFFCFCFC), primary, 0.02f),
            surfaceVariant = lerp(Color(0xFFE4E6E5), primary, 0.08f),
            onSurfaceVariant = Color(0xFF424846),
            outline = Color(0xFF727876),
            outlineVariant = lerp(Color(0xFFC6CAC8), primary, 0.06f),
            surfaceTint = primary
        )
    } else {
        val p = lerp(primary, Color.White, 0.35f)
        val s = lerp(second, Color.White, 0.35f)
        darkColorScheme(
            primary = p,
            onPrimary = lerp(primary, Color.Black, 0.7f),
            primaryContainer = lerp(primary, Color.Black, 0.55f),
            onPrimaryContainer = lerp(primary, Color.White, 0.8f),
            inversePrimary = primary,
            secondary = s,
            onSecondary = lerp(second, Color.Black, 0.7f),
            secondaryContainer = lerp(second, Color.Black, 0.6f),
            onSecondaryContainer = lerp(second, Color.White, 0.82f),
            tertiary = Color(0xFFFFB951),
            onTertiary = Color(0xFF462A00),
            tertiaryContainer = Color(0xFF643F00),
            onTertiaryContainer = Color(0xFFFFDDB3),
            background = lerp(Color(0xFF121413), primary, 0.03f),
            surface = lerp(Color(0xFF121413), primary, 0.03f),
            surfaceVariant = lerp(Color(0xFF3F4946), primary, 0.08f),
            onSurfaceVariant = Color(0xFFC0C8C4),
            outline = Color(0xFF8A928E),
            outlineVariant = lerp(Color(0xFF3F4946), primary, 0.05f),
            surfaceTint = p
        )
    }
}
