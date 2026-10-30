package pt.ua.mbpulse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import pt.ua.mbpulse.MbPulseBranding
import pt.ua.mbpulse.MbPulseLibrary

/** The branding in effect — screens read the logo, title and top-bar style from it. */
internal val LocalMbPulseBranding = staticCompositionLocalOf { MbPulseBranding() }

/**
 * The library's theme, built from the host's [MbPulseBranding]
 * (`MbPulseConfig.branding`). [MbPulseApp][pt.ua.mbpulse.ui.MbPulseApp]
 * applies it; wrap [MbPulseNavHost][pt.ua.mbpulse.ui.MbPulseNavHost]
 * in it when embedding.
 */
@Composable
public fun MbPulseTheme(
    branding: MbPulseBranding = MbPulseLibrary.branding,
    content: @Composable () -> Unit
) {
    val dark = branding.darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        branding.colorScheme != null -> branding.colorScheme.invoke(dark)
        branding.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> brandColorScheme(Color(branding.primaryColor), branding.secondaryColor?.let { Color(it) }, dark)
    }

    // Status bar icons readable on whatever the top bars are drawn in.
    val barColor = if (branding.coloredTopBars) colorScheme.primary else colorScheme.surface
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = barColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = barColor.luminance() > 0.5f
        }
    }

    CompositionLocalProvider(LocalMbPulseBranding provides branding) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = branding.typography ?: Typography,
            shapes = branding.shapes ?: MaterialTheme.shapes,
            content = content
        )
    }
}

/** Top app bar colours of every library screen: brand-filled or plain, per [MbPulseBranding.coloredTopBars]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun mbPulseTopAppBarColors(): TopAppBarColors {
    val scheme = MaterialTheme.colorScheme
    return if (LocalMbPulseBranding.current.coloredTopBars) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = scheme.primary,
            titleContentColor = scheme.onPrimary,
            navigationIconContentColor = scheme.onPrimary,
            actionIconContentColor = scheme.onPrimary
        )
    } else TopAppBarDefaults.topAppBarColors()
}
