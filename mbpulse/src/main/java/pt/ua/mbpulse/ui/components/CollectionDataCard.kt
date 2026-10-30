package pt.ua.mbpulse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.screens.measurements.CollectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One measurement on the home screen: latest value, when it was recorded
 * (greyed out with a clock when it is late), and a 24-hour sparkline.
 *
 * @param nowMs the screen's clock (ticks every minute) so "3 min ago" stays current.
 */
@Composable
internal fun CollectionDataCard(
    card: CollectionCard,
    onClick: () -> Unit,
    nowMs: Long = System.currentTimeMillis()
) {
    val age = card.lastUpdatedMs?.let { nowMs - it }
    val stale = age != null && age > card.staleAfterMs
    val updated = card.lastUpdatedMs?.let { relativeTimeText(it, nowMs) }
    val description = when {
        updated == null -> stringResource(R.string.mbpulse_card_description_empty, card.title)
        stale -> stringResource(R.string.mbpulse_card_description_stale, card.title, card.value, card.unit, updated)
        else -> stringResource(R.string.mbpulse_card_description_data, card.title, card.value, card.unit, updated)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = if (card.hasData && !stale) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surface
        ),
        border = if (card.hasData) null else CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text  = card.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text       = card.value,
                    style      = if (card.wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (card.hasData && !stale) MaterialTheme.colorScheme.onSurface
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                if (card.unit.isNotEmpty()) {
                    Text(
                        text  = card.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            if (card.sparkline.size >= 2) {
                Sparkline(
                    card.sparkline,
                    color = if (stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (stale) {
                    Icon(Icons.Filled.Schedule, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(12.dp))
                }
                Text(
                    text  = updated?.let { if (stale) stringResource(R.string.mbpulse_card_last_reading, it) else it }
                        ?: stringResource(R.string.mbpulse_card_no_data),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** How long ago something happened, before wording ([relativeTimeText]). */
internal sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class MinutesAgo(val minutes: Int) : RelativeTime
    data class HoursAgo(val hours: Int) : RelativeTime
    data class Yesterday(val time: String) : RelativeTime
    data class On(val dateTime: String) : RelativeTime
}

internal fun relativeTime(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): RelativeTime {
    val diff = nowMs - ms
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))
    return when {
        diff < 60_000L -> RelativeTime.JustNow
        diff < 60 * 60_000L -> RelativeTime.MinutesAgo((diff / 60_000L).toInt())
        day == today -> RelativeTime.HoursAgo((diff / 3_600_000L).toInt())
        day == today.minusDays(1) -> RelativeTime.Yesterday(time)
        else -> RelativeTime.On(
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms)))
    }
}

/** "just now", "5 min ago", "2 h ago", "yesterday 14:03", "12 Mar 09:41" — from string resources. */
@Composable
internal fun relativeTimeText(ms: Long, nowMs: Long): String = when (val r = relativeTime(ms, nowMs)) {
    RelativeTime.JustNow -> stringResource(R.string.mbpulse_time_just_now)
    is RelativeTime.MinutesAgo -> pluralStringResource(R.plurals.mbpulse_time_minutes_ago, r.minutes, r.minutes)
    is RelativeTime.HoursAgo -> pluralStringResource(R.plurals.mbpulse_time_hours_ago, r.hours, r.hours)
    is RelativeTime.Yesterday -> stringResource(R.string.mbpulse_time_yesterday, r.time)
    is RelativeTime.On -> r.dateTime
}
