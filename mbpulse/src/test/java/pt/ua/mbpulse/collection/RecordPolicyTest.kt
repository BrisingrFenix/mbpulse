package pt.ua.mbpulse.collection

import pt.ua.mbpulse.collection.RecordPolicy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordPolicyTest {

    private val mapping: (String) -> String? = { schema ->
        when (schema) {
            "mystudy:check-in" -> "daily_check_in"
            "mystudy:diary"    -> "diary"
            else               -> null
        }
    }
    private val day = 86_400

    private fun check(
        candidates: List<Candidate>,
        consented: Set<String> = setOf("daily_check_in", "diary"),
        sampling: Map<String, Int> = emptyMap(),
        last: Map<String, Long?> = emptyMap()
    ) = RecordPolicy.check(candidates, consented, mapping, sampling, last)

    private fun checkIn(atMs: Long) = Candidate("mystudy:check-in", atMs, isEvent = false)

    @Test fun `consented and no interval may record`() {
        assertNull(check(listOf(checkIn(1_000))))
    }

    @Test fun `type not consented is refused`() {
        assertEquals(
            RecordResult.NotConsented(setOf("mystudy:check-in")),
            check(listOf(checkIn(1_000)), consented = setOf("diary"))
        )
    }

    @Test fun `empty consent set refuses every mapped schema`() {
        assertEquals(
            RecordResult.NotConsented(setOf("mystudy:check-in")),
            check(listOf(checkIn(1_000)), consented = emptySet())
        )
    }

    @Test fun `unmapped schema is refused like in the encryptor`() {
        assertEquals(
            RecordResult.NotConsented(setOf("other:thing")),
            check(listOf(Candidate("other:thing", 1_000, false)))
        )
    }

    @Test fun `all or nothing - one unconsented schema refuses the batch`() {
        val result = check(
            listOf(checkIn(1_000), Candidate("mystudy:diary", 1_000, false)),
            consented = setOf("daily_check_in")
        )
        assertEquals(RecordResult.NotConsented(setOf("mystudy:diary")), result)
    }

    @Test fun `sampling interval is measured from the last stored reading`() {
        val last = 10_000_000L
        val result = check(
            listOf(checkIn(last + 3_600_000)),
            sampling = mapOf("daily_check_in" to day),
            last = mapOf("mystudy:check-in" to last)
        )
        assertEquals(RecordResult.TooSoon("mystudy:check-in", last + day * 1_000L), result)
    }

    @Test fun `after the interval it may record again`() {
        val last = 10_000_000L
        assertNull(check(
            listOf(checkIn(last + day * 1_000L)),
            sampling = mapOf("daily_check_in" to day),
            last = mapOf("mystudy:check-in" to last)
        ))
    }

    @Test fun `back-dated entry next to an existing one is too soon`() {
        val last = 100_000_000L
        val result = check(
            listOf(checkIn(last - 3_600_000)),
            sampling = mapOf("daily_check_in" to day),
            last = mapOf("mystudy:check-in" to last)
        )
        assertEquals(RecordResult.TooSoon("mystudy:check-in", last + day * 1_000L), result)
    }

    @Test fun `first reading is never too soon`() {
        assertNull(check(listOf(checkIn(1_000)), sampling = mapOf("daily_check_in" to day)))
    }

    @Test fun `events are never limited by sampling`() {
        assertNull(check(
            listOf(Candidate("mystudy:check-in", 2_000, isEvent = true)),
            sampling = mapOf("daily_check_in" to day),
            last = mapOf("mystudy:check-in" to 1_000L)
        ))
    }
}
