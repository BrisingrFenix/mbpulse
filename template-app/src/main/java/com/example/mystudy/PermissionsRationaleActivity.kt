package com.example.mystudy

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
 * Opened when a participant taps the privacy-policy link on the Health Connect
 * permission screen. Required by Health Connect for any app requesting health
 * permissions (the manifest wires both the pre-14 and 14+ entry points).
 *
 * TODO(researcher): replace the placeholder copy with this study's actual,
 * ethics-approved privacy policy wording (an agent cannot supply it — it
 * leaves this marker and lists it in its report).
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
            "With your permission, this app reads the health data you authorise " +
                "from Health Connect solely to import it into your study record.\n\n" +
                "Imported data is encrypted on your device before upload and tagged " +
                "as externally sourced. Your data is never used for advertising and " +
                "never shared for purposes unrelated to the study. You can revoke " +
                "access at any time from Health Connect settings.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
