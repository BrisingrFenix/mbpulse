package pt.ua.mbpulse.ui.screens.history

import pt.ua.mbpulse.ui.components.CategorySegment
import pt.ua.mbpulse.ui.components.CountBar
import kotlin.math.max

/** A timestamped history value: [series] = every numeric field by label, [category] for categorical kinds. */
internal data class HistoryPoint(val timeMs: Long, val series: Map<String, Float>, val category: String?)

/** One chart line before colouring; null values break the line. */
public data class SeriesData(val label: String, val points: List<Pair<Long, Float?>>)

/**
 * Turns history rows into what is readable on a phone: real time spacing,
 * gaps where nothing was recorded, and time-bucket averages when there are
 * more points than pixels.
 */
internal object HistoryAggregation {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private val BUCKETS = listOf(
        MINUTE, 5 * MINUTE, 15 * MINUTE, 30 * MINUTE, HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 7 * DAY
    )

    /** Raw points up to this many per series; above it, bucket averages. */
    const val MAX_POINTS = 240

    /** Smallest bucket giving at most [maxBuckets] over [spanMs]. */
    fun bucketFor(spanMs: Long, maxBuckets: Int = MAX_POINTS): Long =
        BUCKETS.firstOrNull { spanMs / it <= maxBuckets } ?: BUCKETS.last()


    /**
     * A gap is any pause longer than four typical intervals (median of the
     * spacing), and never shorter than [minGapMs] — so a missing night shows as
     * a gap instead of a straight line across it.
     */
    fun gapLimit(times: List<Long>, minGapMs: Long = 10 * MINUTE): Long {
        if (times.size < 3) return Long.MAX_VALUE
        val deltas = times.zipWithNext { a, b -> b - a }.sorted()
        return max(deltas[deltas.size / 2] * 4, minGapMs)
    }

    /** @return the series to draw and, when averaged, the bucket size in ms. */
    fun numericSeries(points: List<HistoryPoint>, startMs: Long, endMs: Long): Pair<List<SeriesData>, Long?> {
        val labels = points.flatMap { it.series.keys }.distinct()
        if (labels.isEmpty()) return emptyList<SeriesData>() to null
        val perSeries = points.size
        if (perSeries <= MAX_POINTS) {
            val limit = gapLimit(points.map { it.timeMs })
            return labels.map { label ->
                SeriesData(label, withGaps(points.mapNotNull { p -> p.series[label]?.let { p.timeMs to it } }, limit))
            } to null
        }
        val bucket = bucketFor(endMs - startMs)
        val grouped = points.groupBy { (it.timeMs - startMs).floorDiv(bucket) }
        val keys = grouped.keys.sorted()
        return labels.map { label ->
            val out = mutableListOf<Pair<Long, Float?>>()
            var prev: Long? = null
            keys.forEach { k ->
                val vs = grouped.getValue(k).mapNotNull { it.series[label] }
                if (vs.isEmpty()) return@forEach
                if (prev != null && k - prev!! > 1) out += (startMs + (prev!! + 1) * bucket) to null   // empty buckets = gap
                out += (startMs + k * bucket + bucket / 2) to vs.average().toFloat()
                prev = k
            }
            SeriesData(label, out)
        } to bucket
    }

    private fun withGaps(points: List<Pair<Long, Float>>, limit: Long): List<Pair<Long, Float?>> {
        val out = mutableListOf<Pair<Long, Float?>>()
        points.forEachIndexed { i, p ->
            if (i > 0 && p.first - points[i - 1].first > limit) out += (points[i - 1].first + 1) to null
            out += p
        }
        return out
    }

    /**
     * Each reading's category holds until the next reading — but never longer
     * than the gap limit, so an unrecorded night is not painted as "sleeping".
     */
    fun categorySegments(points: List<HistoryPoint>, categories: List<String>): List<CategorySegment> {
        val withCat = points.filter { it.category != null }
        if (withCat.isEmpty()) return emptyList()
        val limit = gapLimit(withCat.map { it.timeMs })
        val typical = if (limit == Long.MAX_VALUE) MINUTE else limit / 4
        val segments = mutableListOf<CategorySegment>()
        withCat.forEachIndexed { i, p ->
            val next = withCat.getOrNull(i + 1)?.timeMs
            val end = if (next != null && next - p.timeMs <= limit) next else p.timeMs + typical
            val idx = categories.indexOf(p.category)
            val last = segments.lastOrNull()
            if (last != null && last.category == idx && last.endMs >= p.timeMs) segments[segments.size - 1] = last.copy(endMs = end)
            else segments += CategorySegment(idx, p.timeMs, end)
        }
        return segments
    }

    /** Share of recorded time per category (by segment length), largest first. */
    fun categoryShares(segments: List<CategorySegment>, categories: List<String>): List<Pair<String, Float>> {
        val total = segments.sumOf { it.endMs - it.startMs }.toFloat()
        if (total <= 0f) return emptyList()
        return categories.mapIndexed { i, name ->
            name to segments.filter { it.category == i }.sumOf { it.endMs - it.startMs } / total
        }.filter { it.second > 0f }.sortedByDescending { it.second }
    }

    /** Events counted per bucket over [startMs, endMs]; second = true when hourly (else daily). */
    fun eventBars(points: List<HistoryPoint>, startMs: Long, endMs: Long): Pair<List<CountBar>, Boolean> {
        val bucket = if (endMs - startMs <= 2 * DAY) HOUR else DAY
        val counts = points.groupingBy { (it.timeMs - startMs).floorDiv(bucket) }.eachCount()
        val n = ((endMs - startMs) / bucket + 1).toInt().coerceAtMost(400)
        return (0 until n).map { k -> CountBar(startMs + k * bucket, startMs + (k + 1) * bucket, counts[k.toLong()] ?: 0) } to
            (bucket == HOUR)
    }
}
