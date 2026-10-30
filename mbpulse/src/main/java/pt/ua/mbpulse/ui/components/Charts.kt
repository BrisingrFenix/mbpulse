package pt.ua.mbpulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.ua.mbpulse.R
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One line of a [TimeSeriesChart]; a null value breaks the line (missing data). */
internal data class ChartSeries(val label: String, val color: Color, val points: List<Pair<Long, Float?>>)

/** A category active from [startMs] to [endMs] (one row of a [CategoryTimeline]). */
public data class CategorySegment(val category: Int, val startMs: Long, val endMs: Long)

/** A count over [startMs, endMs) for [EventBars]. */
public data class CountBar(val startMs: Long, val endMs: Long, val count: Int)

/** Distinct series colours that read on light and dark surfaces. */
internal val SeriesColors = listOf(
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF00838F), Color(0xFFC62828), Color(0xFF6D4C41), Color(0xFF558B2F)
)

// ---------------------------------------------------------------------------
// Time axis helpers
// ---------------------------------------------------------------------------

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

internal fun timeLabel(ms: Long, spanMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val pattern = when {
        spanMs <= 36 * HOUR -> "HH:mm"
        spanMs <= 8 * DAY -> "EEE HH'h'"
        else -> "d MMM"
    }
    return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(Instant.ofEpochMilli(ms))
}

internal fun fullTimeLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))

/** Four significant digits, no trailing zeros. */
internal fun fmtValue(v: Float): String =
    if (v.isNaN()) "—" else BigDecimal(v.toDouble()).round(MathContext(4)).stripTrailingZeros().toPlainString()

/** "Nice" y-axis bounds and step for [lo]..[hi] with about [ticks] gridlines. */
private fun niceAxis(lo: Float, hi: Float, ticks: Int = 4): Triple<Float, Float, Float> {
    var a = lo; var b = hi
    if (a == b) { a -= 1f; b += 1f }
    val raw = (b - a) / ticks
    val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
    return Triple(floor(a / step) * step, ceil(b / step) * step, step)
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, TextStyle(fontSize = 10.sp, color = color))
    val x = when {
        alignRight -> at.x - layout.size.width
        center -> at.x - layout.size.width / 2f
        else -> at.x
    }
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}

// ---------------------------------------------------------------------------
// Line chart on a real time axis
// ---------------------------------------------------------------------------

/**
 * Lines on a time axis (x spaced by actual time, so gaps in the data are
 * visible as gaps). Tap or drag to read exact values at a point in time.
 */
@Composable
internal fun TimeSeriesChart(
    series: List<ChartSeries>,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    unit: String = "",
    height: Dp = 220.dp
) {
    val tm = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selectedX by remember(series) { mutableStateOf<Float?>(null) }
    val valueLabel = stringResource(R.string.mbpulse_chart_value)
    val tapHint = stringResource(R.string.mbpulse_chart_tap_hint)

    val values = series.flatMap { s -> s.points.mapNotNull { it.second } }
    if (values.isEmpty()) return
    val chartDescription = stringResource(R.string.mbpulse_chart_description,
        fullTimeLabel(startMs), fullTimeLabel(endMs), fmtValue(values.min()), fmtValue(values.max()), unit)
    val (yMin, yMax, yStep) = niceAxis(values.min(), values.max())
    val span = max(1L, endMs - startMs)

    // Value readout for the selected time (nearest point of each series).
    val readout = selectedX?.let { fraction ->
        val t = startMs + (fraction * span).toLong()
        val parts = series.mapNotNull { s ->
            s.points.filter { it.second != null }.minByOrNull { abs(it.first - t) }?.let { s to it }
        }
        parts.firstOrNull()?.let { (_, p) -> fullTimeLabel(p.first) } to
            parts.joinToString("   ") { (s, p) -> "${s.label.ifEmpty { valueLabel }} ${fmtValue(p.second!!)}${if (unit.isNotEmpty()) " $unit" else ""}" }
    }

    Column(modifier) {
        Text(
            readout?.let { "${it.first} · ${it.second}" } ?: tapHint,
            style = MaterialTheme.typography.labelSmall,
            color = axisColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = chartDescription }

                .pointerInput(series) {
                    detectTapGestures { selectedX = ((it.x - 44.dp.toPx()) / (size.width - 52.dp.toPx())).coerceIn(0f, 1f) }
                }
                .pointerInput(series) {
                    detectDragGestures { change, _ ->
                        selectedX = ((change.position.x - 44.dp.toPx()) / (size.width - 52.dp.toPx())).coerceIn(0f, 1f)
                    }
                }
        ) {
            val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
            val top = 6.dp.toPx(); val bottom = size.height - 20.dp.toPx()
            fun x(t: Long) = left + (t - startMs).toFloat() / span * (right - left)
            fun y(v: Float) = bottom - (v - yMin) / (yMax - yMin) * (bottom - top)

            // Grid + y labels
            var v = yMin
            while (v <= yMax + yStep / 2) {
                drawLine(gridColor, Offset(left, y(v)), Offset(right, y(v)), 1f)
                label(tm, fmtValue(v), Offset(left - 6.dp.toPx(), y(v)), axisColor, alignRight = true)
                v += yStep
            }
            // x labels
            val ticks = 4
            for (i in 0..ticks) {
                val t = startMs + span * i / ticks
                label(tm, timeLabel(t, span), Offset(x(t), size.height - 8.dp.toPx()), axisColor,
                    center = i in 1 until ticks, alignRight = i == ticks)
            }
            // Lines (broken at nulls)
            series.forEach { s ->
                val path = Path()
                var open = false
                var single: Offset? = null
                s.points.forEach { (t, value) ->
                    if (value == null) { open = false; return@forEach }
                    val p = Offset(x(t), y(value))
                    if (open) path.lineTo(p.x, p.y) else { path.moveTo(p.x, p.y); single = p }
                    open = true
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (s.points.count { it.second != null } <= 40) {
                    s.points.forEach { (t, value) -> if (value != null) drawCircle(s.color, 2.5.dp.toPx(), Offset(x(t), y(value))) }
                } else single?.let { drawCircle(s.color, 1.5.dp.toPx(), it) }
            }
            // Selection cursor
            selectedX?.let { f ->
                val cx = left + f * (right - left)
                drawLine(axisColor, Offset(cx, top), Offset(cx, bottom), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        if (series.size > 1 || series.firstOrNull()?.label?.isNotEmpty() == true) Legend(series.map { it.label to it.color })
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 6.dp)
    ) {
        items.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(" $name", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Category timeline (e.g. sleep stage)
// ---------------------------------------------------------------------------

/** One row per category; a bar wherever that category was recorded. */
@Composable
internal fun CategoryTimeline(
    categories: List<String>,
    segments: List<CategorySegment>,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier
) {
    val tm = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val rowH = 28.dp
    val span = max(1L, endMs - startMs)
    val timelineDescription = stringResource(R.string.mbpulse_chart_timeline_description, categories.joinToString())
    Canvas(
        modifier
            .fillMaxWidth()
            .height(rowH * categories.size + 24.dp)
            .semantics { contentDescription = timelineDescription }
    ) {
        val left = 72.dp.toPx(); val right = size.width - 8.dp.toPx()
        val rowPx = rowH.toPx()
        fun x(t: Long) = left + (t - startMs).toFloat() / span * (right - left)
        categories.forEachIndexed { i, name ->
            val cy = i * rowPx + rowPx / 2
            drawLine(gridColor, Offset(left, cy), Offset(right, cy), 1f)
            label(tm, name.replaceFirstChar { it.uppercase() }, Offset(0f, cy), axisColor)
        }
        segments.forEach { s ->
            val cy = s.category * rowPx + rowPx / 2
            val x0 = x(s.startMs); val x1 = max(x(s.endMs), x0 + 2f)
            drawRect(SeriesColors[s.category % SeriesColors.size],
                Offset(x0, cy - rowPx * 0.3f), Size(x1 - x0, rowPx * 0.6f))
        }
        for (i in 0..4) {
            val t = startMs + span * i / 4
            label(tm, timeLabel(t, span), Offset(x(t), size.height - 8.dp.toPx()), axisColor,
                center = i in 1 until 4, alignRight = i == 4)
        }
    }
}

// ---------------------------------------------------------------------------
// Event counts
// ---------------------------------------------------------------------------

@Composable
internal fun EventBars(bars: List<CountBar>, startMs: Long, endMs: Long, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val tm = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val barColor = MaterialTheme.colorScheme.primary
    val maxCount = max(1, bars.maxOfOrNull { it.count } ?: 1)
    val (_, yMax, yStep) = niceAxis(0f, maxCount.toFloat())
    val span = max(1L, endMs - startMs)
    val total = bars.sumOf { it.count }
    val barsDescription = pluralStringResource(R.plurals.mbpulse_chart_events_description, total, total, maxCount)
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = barsDescription }
    ) {
        val left = 36.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 6.dp.toPx(); val bottom = size.height - 20.dp.toPx()
        fun x(t: Long) = left + (t - startMs).toFloat() / span * (right - left)
        fun y(v: Float) = bottom - v / yMax * (bottom - top)
        var v = 0f
        while (v <= yMax + yStep / 2) {
            drawLine(gridColor, Offset(left, y(v)), Offset(right, y(v)), 1f)
            label(tm, fmtValue(v), Offset(left - 6.dp.toPx(), y(v)), axisColor, alignRight = true)
            v += yStep
        }
        bars.filter { it.count > 0 }.forEach { b ->
            val x0 = x(b.startMs) + 1f; val x1 = max(x(b.endMs) - 1f, x0 + 2f)
            drawRect(barColor, Offset(x0, y(b.count.toFloat())), Size(x1 - x0, bottom - y(b.count.toFloat())))
        }
        for (i in 0..4) {
            val t = startMs + span * i / 4
            label(tm, timeLabel(t, span), Offset(x(t), size.height - 8.dp.toPx()), axisColor,
                center = i in 1 until 4, alignRight = i == 4)
        }
    }
}

// ---------------------------------------------------------------------------
// Sparkline (home cards)
// ---------------------------------------------------------------------------

@Composable
internal fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val lo = values.min(); val hi = values.max()
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val range = if (hi - lo < 1e-6f) 1f else hi - lo
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i / (values.size - 1f) * size.width
            val py = size.height - (v - lo) / range * (size.height - 4f) - 2f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Averages [points] (time-sorted) into at most [maxPoints] values — for sparklines. */
internal fun downsample(points: List<Float>, maxPoints: Int = 48): List<Float> {
    if (points.size <= maxPoints) return points
    val per = points.size.toFloat() / maxPoints
    return (0 until maxPoints).map { i ->
        val from = (i * per).toInt(); val to = min(points.size, ((i + 1) * per).toInt().coerceAtLeast(from + 1))
        points.subList(from, to).average().toFloat()
    }
}
