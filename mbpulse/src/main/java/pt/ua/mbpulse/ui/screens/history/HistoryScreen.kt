package pt.ua.mbpulse.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.ua.mbpulse.ui.components.CategoryTimeline
import pt.ua.mbpulse.ui.components.ChartSeries
import pt.ua.mbpulse.ui.components.EventBars
import pt.ua.mbpulse.ui.components.SeriesColors
import pt.ua.mbpulse.ui.components.TimeSeriesChart
import pt.ua.mbpulse.ui.components.fmtValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import pt.ua.mbpulse.ui.theme.mbPulseTopAppBarColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun HistoryScreen(
    schemaName: String,
    title: String,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    LaunchedEffect(schemaName) { viewModel.init(schemaName) }

    val uiState   by viewModel.uiState.collectAsStateWithLifecycle()
    val timeRange by viewModel.timeRange.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                colors = mbPulseTopAppBarColors(),

                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mbpulse_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeRange.entries.forEach { range ->
                        FilterChip(
                            selected = timeRange == range,
                            onClick  = { viewModel.setTimeRange(range) },
                            label    = { Text(stringResource(range.labelRes)) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor     = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            when {
                uiState.isLoading -> item { LoadingCard() }
                uiState.isEmpty -> item { EmptyCard() }
                else -> {
                    item { ChartCard(uiState) }
                    item { StatsCard(uiState) }
                    item { HistoryHeader(uiState.count) }
                    historySection(uiState.entries)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun EmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().height(220.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.QueryStats, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.mbpulse_history_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(stringResource(R.string.mbpulse_history_empty_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChartCard(state: HistoryUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state.kind) {
                HistoryKind.NUMBER -> {
                    val series = state.series.mapIndexed { i, s ->
                        ChartSeries(s.label, SeriesColors[i % SeriesColors.size], s.points)
                    }
                    TimeSeriesChart(series, state.startMs, state.endMs, unit = state.unit)
                    state.resolutionMs?.let {
                        Text(stringResource(R.string.mbpulse_history_averaged, averagesLabel(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                HistoryKind.CATEGORY -> CategoryTimeline(state.categories, state.segments, state.startMs, state.endMs)
                HistoryKind.EVENT -> {
                    Text(stringResource(if (state.barsHourly) R.string.mbpulse_history_events_per_hour else R.string.mbpulse_history_events_per_day), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    EventBars(state.bars, state.startMs, state.endMs)
                }
            }
        }
    }
}

@Composable
private fun StatsCard(state: HistoryUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        when (state.kind) {
            HistoryKind.NUMBER -> Column(Modifier.padding(16.dp)) {
                if (state.series.size > 1) {
                    Text(state.primaryLabel, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatCell(stringResource(R.string.mbpulse_history_stat_latest), fmtValue(state.latest), state.unit, trend = state.trend)
                    StatCell(stringResource(R.string.mbpulse_history_stat_min), fmtValue(state.min), state.unit)
                    StatCell(stringResource(R.string.mbpulse_history_stat_mean), fmtValue(state.mean), state.unit)
                    StatCell(stringResource(R.string.mbpulse_history_stat_max), fmtValue(state.max), state.unit)
                }
            }
            HistoryKind.CATEGORY -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mbpulse_history_share), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.shares.forEach { (name, share) ->
                    val color = SeriesColors[state.categories.indexOf(name).coerceAtLeast(0) % SeriesColors.size]
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                            Text("${(share * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(progress = { share }, color = color,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            HistoryKind.EVENT -> {
                val days = ((state.endMs - state.startMs) / 86_400_000.0).coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatCell(stringResource(R.string.mbpulse_history_stat_events), state.count.toString(), "")
                    StatCell(stringResource(R.string.mbpulse_history_stat_per_day), fmtValue((state.count / days).toFloat()), "")
                    StatCell(stringResource(R.string.mbpulse_history_stat_last), DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(state.entries.last().epochStartMs)), "")
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(count: Int) {
    Text(
        text  = pluralStringResource(R.plurals.mbpulse_history_count, count, count),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Per-reading rows, newest first, with a date header whenever the day changes. */
private fun androidx.compose.foundation.lazy.LazyListScope.historySection(entries: List<HistoryEntry>) {
    val zone  = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = entries.asReversed().groupBy { Instant.ofEpochMilli(it.epochStartMs).atZone(zone).toLocalDate() }
    byDay.forEach { (day, rows) ->
        item(key = "header-$day") { DayHeader(dayLabel(day, today)) }
        items(rows, key = { it.id }) { entry ->
            HistoryRow(entry = entry, zone = zone)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

@Composable
private fun StatCell(label: String, value: String, unit: String, trend: Int = 0) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (trend != 0) {
                Icon(
                    imageVector        = if (trend > 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = stringResource(if (trend > 0) R.string.mbpulse_history_above_average else R.string.mbpulse_history_below_average),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(16.dp)
                )
            }
        }
        if (unit.isNotEmpty()) {
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun HistoryRow(entry: HistoryEntry, zone: ZoneId) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(TIME_FMT.withZone(zone).format(Instant.ofEpochMilli(entry.epochStartMs)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.displayValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End)
            if (entry.unit.isNotEmpty()) {
                Text(entry.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today               -> stringResource(R.string.mbpulse_today)
    today.minusDays(1)  -> stringResource(R.string.mbpulse_yesterday)
    else                -> day.format(DAY_HEADER_FMT)
}

/** "hourly averages", "15-minute averages", … */
@Composable
private fun averagesLabel(bucketMs: Long): String {
    val minutes = (bucketMs / 60_000L).toInt()
    return when {
        minutes < 60 -> pluralStringResource(R.plurals.mbpulse_history_minute_averages, minutes, minutes)
        minutes == 60 -> stringResource(R.string.mbpulse_history_hourly_averages)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.mbpulse_history_hour_averages, minutes / 60, minutes / 60)
        minutes == 24 * 60 -> stringResource(R.string.mbpulse_history_daily_averages)
        else -> stringResource(R.string.mbpulse_history_weekly_averages)
    }
}

private val DAY_HEADER_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
