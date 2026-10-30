package pt.ua.mbpulse.ui.screens.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import pt.ua.mbpulse.R
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.collection.EpochDisplay
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.local.EpochHistoryEntity
import pt.ua.mbpulse.ui.components.CategorySegment
import pt.ua.mbpulse.ui.components.CountBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

public enum class TimeRange(@StringRes public val labelRes: Int, public val days: Int?) {
    DAY(R.string.mbpulse_history_range_day, 1),
    SEVEN(R.string.mbpulse_history_range_week, 7),
    THIRTY(R.string.mbpulse_history_range_month, 30),
    ALL(R.string.mbpulse_history_range_all, null)
}

/** How a schema is charted: values over time, a category timeline, or event counts. */
public enum class HistoryKind { NUMBER, CATEGORY, EVENT }

/** One stored reading as the history list shows it — not the database row. */
public data class HistoryEntry(
    val id: Long,
    val epochStartMs: Long,
    /** The primary numeric value; NaN when the reading has none (categories, events). */
    val value: Float,
    val displayValue: String,
    val unit: String,
    /** The category of a CATEGORY / EVENT reading (e.g. "sleeping"), else null. */
    val category: String?
)

public data class HistoryUiState(
    val isLoading: Boolean = true,
    val kind: HistoryKind = HistoryKind.NUMBER,
    /** Rows of the selected range, oldest first (the list shows them newest first). */
    val entries: List<HistoryEntry> = emptyList(),
    val unit: String = "",
    /** The chart's time axis. */
    val startMs: Long = 0,
    val endMs: Long = 0,

    // NUMBER
    val series: List<SeriesData> = emptyList(),
    /** Bucket size (ms) when the chart shows bucket means instead of every reading; null = raw. */
    val resolutionMs: Long? = null,
    /** Stats of the first (primary) series. */
    val primaryLabel: String = "",
    val min: Float = 0f,
    val max: Float = 0f,
    val mean: Float = 0f,
    val latest: Float = 0f,

    // CATEGORY
    val categories: List<String> = emptyList(),
    val segments: List<CategorySegment> = emptyList(),
    val shares: List<Pair<String, Float>> = emptyList(),

    // EVENT
    val bars: List<CountBar> = emptyList(),
    /** Bars count per hour (true) or per day (false). */
    val barsHourly: Boolean = false
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val count: Int get() = entries.size

    /** Sign of (latest − mean): >0 above average, <0 below, 0 flat/insufficient data. */
    val trend: Int get() = when {
        kind != HistoryKind.NUMBER || entries.size < 2 -> 0
        latest > mean + 1e-3f -> 1
        latest < mean - 1e-3f -> -1
        else -> 0
    }
}

@HiltViewModel
public class HistoryViewModel @Inject internal constructor(
    private val dao: EpochHistoryDao
) : ViewModel() {

    private val _schemaName = MutableStateFlow("")
    private val _timeRange = MutableStateFlow(TimeRange.SEVEN)

    public val timeRange: StateFlow<TimeRange> = _timeRange

    @OptIn(ExperimentalCoroutinesApi::class)
    public val uiState: StateFlow<HistoryUiState> =
        combine(_schemaName, _timeRange) { schema, range -> schema to range }
            .flatMapLatest { (schema, range) ->
                if (schema.isEmpty()) {
                    // Not initialised yet — stay in the loading state rather than
                    // querying for an empty schema and flashing "no data".
                    flowOf(HistoryUiState(isLoading = true))
                } else {
                    val now = System.currentTimeMillis()
                    val sinceMs = range.days?.let { now - it * 86_400_000L }
                    val source = if (sinceMs == null) dao.observeAll(schema) else dao.observe(schema, sinceMs)
                    source.map { entries -> buildState(entries, sinceMs, System.currentTimeMillis()) }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState()
            )

    public fun init(schemaName: String) {
        _schemaName.value = schemaName
    }

    public fun setTimeRange(range: TimeRange) {
        _timeRange.value = range
    }

    internal companion object {
        /** Pure: rows of one schema (time-ascending) → what the screen draws. */
        internal fun buildState(entries: List<EpochHistoryEntity>, sinceMs: Long?, nowMs: Long): HistoryUiState {
            if (entries.isEmpty()) return HistoryUiState(isLoading = false)
            val kind = when (EpochDisplay.kindOf(entries.last())) {
                EpochDisplay.Kind.CATEGORY -> HistoryKind.CATEGORY
                EpochDisplay.Kind.EVENT -> HistoryKind.EVENT
                EpochDisplay.Kind.NUMBER -> HistoryKind.NUMBER
            }
            // A range shows the whole window (so missing days are visible);
            // "All" spans the data.
            val start = sinceMs ?: entries.first().epochStartMs
            val end = if (sinceMs != null) nowMs else maxOf(entries.last().epochStartMs, start + 60_000)
            val points = entries.map {
                HistoryPoint(it.epochStartMs, EpochDisplay.seriesOf(it).mapKeys { (k, _) -> k }, it.category)
            }
            val rows = entries.map {
                HistoryEntry(it.id, it.epochStartMs, it.numericValue, it.displayValue, it.unit, it.category)
            }
            val base = HistoryUiState(isLoading = false, kind = kind, entries = rows,
                unit = entries.last().unit, startMs = start, endMs = end)

            return when (kind) {
                HistoryKind.NUMBER -> {
                    val (series, resolution) = HistoryAggregation.numericSeries(points, start, end)
                    val primary = entries.map { it.numericValue }
                    base.copy(
                        series = series, resolutionMs = resolution,
                        primaryLabel = series.firstOrNull()?.label.orEmpty(),
                        min = primary.min(), max = primary.max(),
                        mean = primary.average().toFloat(), latest = primary.last()
                    )
                }
                HistoryKind.CATEGORY -> {
                    val categories = points.mapNotNull { it.category }.distinct().sorted()
                    val segments = HistoryAggregation.categorySegments(points, categories)
                    base.copy(categories = categories, segments = segments,
                        shares = HistoryAggregation.categoryShares(segments, categories))
                }
                HistoryKind.EVENT -> {
                    val (bars, hourly) = HistoryAggregation.eventBars(points, start, end)
                    base.copy(bars = bars, barsHourly = hourly)
                }
            }
        }
    }
}
