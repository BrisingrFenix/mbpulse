package com.clinicalTests

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown when a participant taps the privacy-policy link on the Health Connect
 * permission screen. Health Connect requires every app that requests health
 * permissions to surface this rationale; the manifest wires it to both the
 * pre-Android-14 (`ACTION_SHOW_PERMISSIONS_RATIONALE`) and Android-14+
 * (`VIEW_PERMISSION_USAGE`) entry points.
 *
 * Replace the placeholder copy below with this study's real privacy policy.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RationaleContent()
                }
            }
        }
    }
}

@Composable
private fun RationaleContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("How this app uses your health data", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "With your permission, this app reads the health data you authorise from " +
                "Health Connect (for example, heart rate) solely to import it into your " +
                "study record.\n\n" +
                "Imported data is encrypted on your device before it is uploaded and is " +
                "tagged as externally sourced so researchers can distinguish it from data " +
                "measured live by the study device.\n\n" +
                "Your data is never used for advertising and is never shared with third " +
                "parties for purposes unrelated to the study. You can revoke this app's " +
                "access at any time from Health Connect settings.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
