package pt.ua.mbpulse.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `StudyConfig."endedAt"` as PostgREST returns a timestamptz. */
class StudyEndTimestampTest {

    private val expected = 1_790_000_000_123L   // 2026-09-21T14:13:20.123Z

    @Test fun offsetWithMicroseconds() =
        assertEquals(expected, parseTimestampMs("2026-09-21T14:13:20.123456+00:00"))

    @Test fun otherOffset() =
        assertEquals(expected, parseTimestampMs("2026-09-21T16:13:20.123+02:00"))

    @Test fun zulu() =
        assertEquals(expected, parseTimestampMs("2026-09-21T14:13:20.123Z"))

    @Test fun absentOrGarbage() {
        assertNull(parseTimestampMs(null))
        assertNull(parseTimestampMs("not a time"))
    }
}
