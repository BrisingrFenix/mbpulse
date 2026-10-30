package pt.ua.mbpulse.collection

import pt.ua.mbpulse.collection.HealthConnectBackgroundImport.Companion.alignDown
import pt.ua.mbpulse.collection.HealthConnectBackgroundImport.Companion.alignUp
import pt.ua.mbpulse.collection.HealthConnectBackgroundImport.Companion.changedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HealthConnectBackgroundImportTest {

    private fun t(s: String) = Instant.parse(s)
    private val minute = Duration.ofMinutes(1)
    private val hour = Duration.ofHours(1)
    private val now = t("2026-10-08T12:00:00Z")

    @Test
    fun `nothing changed - no window`() {
        assertNull(changedWindow(emptyList(), hour, now))
    }

    @Test
    fun `window covers every changed record, widened to whole buckets`() {
        // A watch synced two heart-rate series late: 03:10–03:40 and 05:05–05:06.
        val w = changedWindow(
            listOf(t("2026-10-08T03:10:30Z") to t("2026-10-08T03:40:10Z"),
                   t("2026-10-08T05:05:00Z") to t("2026-10-08T05:06:00Z")),
            hour, now)
        assertEquals(t("2026-10-08T03:00:00Z") to t("2026-10-08T06:00:00Z"), w)
    }

    @Test
    fun `minute alignment for minute buckets`() {
        val w = changedWindow(listOf(t("2026-10-08T03:10:30Z") to t("2026-10-08T03:12:10Z")), minute, now)
        assertEquals(t("2026-10-08T03:10:00Z") to t("2026-10-08T03:13:00Z"), w)
    }

    @Test
    fun `an instant on a bucket boundary still gets its bucket`() {
        val at = t("2026-10-08T03:00:00Z")
        assertEquals(at to t("2026-10-08T04:00:00Z"), changedWindow(listOf(at to at), hour, now))
    }

    @Test
    fun `window never extends past now`() {
        val w = changedWindow(listOf(t("2026-10-08T11:30:00Z") to t("2026-10-08T11:45:00Z")), hour, now)
        assertEquals(t("2026-10-08T11:00:00Z") to now, w)
    }

    @Test
    fun `alignment helpers`() {
        assertEquals(t("2026-10-08T03:00:00Z"), alignDown(t("2026-10-08T03:59:59Z"), hour))
        assertEquals(t("2026-10-08T04:00:00Z"), alignUp(t("2026-10-08T03:00:01Z"), hour))
        assertEquals(t("2026-10-08T04:00:00Z"), alignUp(t("2026-10-08T03:00:00Z"), hour))
    }
}
