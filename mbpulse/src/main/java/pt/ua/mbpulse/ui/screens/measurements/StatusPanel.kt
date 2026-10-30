package pt.ua.mbpulse.ui.screens.measurements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.ui.components.SyncStatusContent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.components.relativeTimeText
import pt.ua.mbpulse.collection.CollectionStage
import pt.ua.mbpulse.ui.screens.collection.CollectionStatus
import pt.ua.mbpulse.ui.screens.collection.stageText

/**
 * One place for "is my study working?": offline state, whether data is being
 * recorded (and why not), and upload progress. Usable in a replaced home screen.
 */
@Composable
internal fun StatusPanel(
    collectionStatus: CollectionStatus,
    offlineSinceMs: Long?,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            offlineSinceMs?.let {
                StatusRow(
                    icon = Icons.Filled.CloudOff, tint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.mbpulse_status_offline_title),
                    detail = stringResource(R.string.mbpulse_status_offline_detail, relativeTimeText(it, nowMs))
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            CollectionRow(collectionStatus)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SyncStatusContent()
        }
    }
}

@Composable
private fun CollectionRow(status: CollectionStatus) {
    val passive = MbPulseLibrary.config.collectionMode.isPassive
    val stage = status.currentStage
    when {
        status.error != null -> StatusRow(
            icon = Icons.Filled.ErrorOutline, tint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.mbpulse_status_not_recording),
            detail = status.error
        )
        status.isRunning && stage is CollectionStage.Reconnecting -> StatusRow(
            icon = Icons.Filled.SensorsOff, tint = MaterialTheme.colorScheme.tertiary,
            title = stringResource(R.string.mbpulse_status_disconnected_title),
            detail = stringResource(R.string.mbpulse_status_disconnected_detail, stageText(stage))
        )
        status.isRunning -> StatusRow(
            dot = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.mbpulse_status_recording),
            detail = if (status.epochsProcessed > 0)
                pluralStringResource(R.plurals.mbpulse_status_recording_epochs, status.epochsProcessed, status.epochsProcessed)
            else stringResource(R.string.mbpulse_status_waiting_first)
        )
        passive -> StatusRow(
            dot = MaterialTheme.colorScheme.outline,
            title = stringResource(R.string.mbpulse_status_not_recording),
            detail = stringResource(R.string.mbpulse_status_passive_idle)
        )
        else -> StatusRow(
            dot = MaterialTheme.colorScheme.outline,
            title = stringResource(R.string.mbpulse_status_no_session),
            detail = stringResource(R.string.mbpulse_status_no_session_detail)
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    dot: Color? = null
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            else if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
