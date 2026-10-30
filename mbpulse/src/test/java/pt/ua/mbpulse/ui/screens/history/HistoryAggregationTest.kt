package pt.ua.mbpulse.ui.screens.history

import pt.ua.mbpulse.data.local.EpochHistoryEntity
import pt.ua.mbpulse.ui.components.RelativeTime
import pt.ua.mbpulse.ui.components.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HistoryAggregationTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val t0 = 1_760_000_000_000L

    private fun num(t: Long, v: Float, extra: String? = null) = EpochHistoryEntity(
        schemaName = "s", epochStartMs = t, numericValue = v, displayValue = "$v", unit = "ms",
        kind = "NUMBER", seriesJson = extra ?: """{"SDNN":$v,"RMSSD":${v / 2}}""")

    @Test
    fun `few points are drawn raw, every series, with a gap across a missing stretch`() {
        val rows = (0 until 10).map { num(t0 + it * min, it.toFloat()) } +
            (0 until 10).map { num(t0 + 3 * hour + it * min, it.toFloat()) }
        val state = HistoryViewModel.buildState(rows, null, t0 + 4 * hour)
        assertEquals(HistoryKind.NUMBER, state.kind)
        assertEquals(listOf("SDNN", "RMSSD"), state.series.map { it.label })
        assertNull(state.resolutionMs)
        val sdnn = state.series.first().points
        assertEquals(21, sdnn.size)                       // 20 readings + 1 gap marker
        assertEquals(1, sdnn.count { it.second == null })
        assertEquals("SDNN", state.primaryLabel)
        assertEquals(9f, state.max, 0f)
    }

    @Test
    fun `many points over a week are averaged into buckets and say so`() {
        val rows = (0 until 7 * 24 * 60).map { num(t0 + it * min, (it % 60).toFloat()) }
        val state = HistoryViewModel.buildState(rows, t0, t0 + 7 * 24 * hour)
        assertEquals(hour, state.resolutionMs)
        val pts = state.series.first().points
        assertTrue("≤ 240 points, got ${pts.size}", pts.size <= HistoryAggregation.MAX_POINTS)
        assertEquals(29.5f, pts.first().second!!, 1e-3f)  // mean of 0..59
    }

    @Test
    fun `categories become a timeline and time shares, not a numeric line`() {
        val rows = (0 until 60).map {
            EpochHistoryEntity(schemaName = "sleep", epochStartMs = t0 + it * min, numericValue = 0f,
                displayValue = "", unit = "", kind = "CATEGORY", category = if (it < 45) "sleeping" else "awake")
        }
        val state = HistoryViewModel.buildState(rows, null, t0 + hour)
        assertEquals(HistoryKind.CATEGORY, state.kind)
        assertEquals(listOf("awake", "sleeping"), state.categories)
        assertEquals(2, state.segments.size)              // runs are merged
        assertEquals("sleeping", state.shares.first().first)
        assertEquals(0.75f, state.shares.first().second, 0.02f)
    }

    @Test
    fun `a pause is not painted as the last category`() {
        val rows = listOf(0L, 1, 2, 3, 300, 301).map {
            EpochHistoryEntity(schemaName = "sleep", epochStartMs = t0 + it * min, numericValue = 0f,
                displayValue = "", unit = "", kind = "CATEGORY", category = "sleeping")
        }
        val segs = HistoryViewModel.buildState(rows, null, t0 + 6 * hour).segments
        assertEquals(2, segs.size)
        assertTrue(segs[0].endMs < t0 + 10 * min)
    }

    @Test
    fun `events are counted per hour within a day`() {
        val rows = listOf(0L, 5, 10, 70).map {
            EpochHistoryEntity(schemaName = "stim", epochStartMs = t0 + it * min, numericValue = 0f,
                displayValue = "", unit = "", kind = "EVENT", category = "thermal")
        }
        val state = HistoryViewModel.buildState(rows, t0, t0 + 24 * hour)
        assertEquals(HistoryKind.EVENT, state.kind)
        assertTrue(state.barsHourly)
        assertEquals(listOf(3, 1), state.bars.take(2).map { it.count })
        assertEquals(4, state.bars.sumOf { it.count })
    }

    @Test
    fun `rows from before v6 (no kind, no series) still chart their value`() {
        val rows = (0 until 5).map {
            EpochHistoryEntity(schemaName = "hr", epochStartMs = t0 + it * min, numericValue = 60f + it,
                displayValue = "", unit = "bpm")
        }
        val state = HistoryViewModel.buildState(rows, null, t0 + hour)
        assertEquals(HistoryKind.NUMBER, state.kind)
        assertEquals(5, state.series.single().points.size)
    }

    @Test
    fun `relative times read naturally`() {
        val zone = ZoneOffset.UTC
        val now = 1_760_000_000_000L - 1_760_000_000_000L % (24 * hour) + 15 * hour   // 15:00 UTC
        assertEquals(RelativeTime.JustNow, relativeTime(now - 20_000, now, zone))
        assertEquals(RelativeTime.MinutesAgo(5), relativeTime(now - 5 * min, now, zone))
        assertEquals(RelativeTime.HoursAgo(3), relativeTime(now - 3 * hour, now, zone))
        assertEquals(RelativeTime.Yesterday("23:00"), relativeTime(now - 16 * hour, now, zone))
    }
}
