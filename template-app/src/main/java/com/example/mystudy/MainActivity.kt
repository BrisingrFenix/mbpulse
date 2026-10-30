package com.example.mystudy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pt.ua.mbpulse.ui.MbPulseApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Turnkey mode: the library provides the complete UI.
 *
 * TODO(agent): to replace individual screens, swap `MbPulseApp()` for
 * `AppWithCustomHome()` — see CustomHomeScreen.kt for the complete, compiling
 * example of overriding one MbPulseNavHost slot while keeping the rest.
 * Replaceable slots: studySelectionScreen, measurementsScreen,
 * collectionScreen, historyScreen. Loading, onboarding and recovery are
 * deliberately not replaceable (security/ethics-critical path).
 *
 * TODO(agent): `hostScreens` adds the study's own screens (symptom logs,
 * diaries, device pairing …) as cards on the home screen — see SymptomLog.kt.
 * Questionnaires need no code: publish them in the setup wizard.
 * Pass an empty list (or omit it) if the study has none.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MbPulseApp(hostScreens = listOf(SymptomLog.screen)) }
    }
}
