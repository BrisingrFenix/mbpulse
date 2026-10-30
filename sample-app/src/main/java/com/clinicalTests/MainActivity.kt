package com.clinicalTests

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pt.ua.mbpulse.ui.MbPulseApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Turnkey sample — the library owns the full UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MbPulseApp() }
    }
}