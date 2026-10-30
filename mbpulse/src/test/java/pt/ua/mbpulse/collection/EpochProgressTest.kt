package pt.ua.mbpulse.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class EpochProgressTest {

    // Three epochs: 1000 (2 records), 2000 (1 record), 3000 (3 records).
    private val progress = EpochProgress(listOf(1000L, 1000L, 2000L, 3000L, 3000L, 3000L))

    @Test
    fun `counts distinct epochs`() {
        assertEquals(3, progress.totalEpochs)
    }

    @Test
    fun `an epoch completes only when all its records are uploaded`() {
        assertEquals(0, progress.completedAfter(0))
        assertEquals(0, progress.completedAfter(1))
        assertEquals(1, progress.completedAfter(2))
        assertEquals(2, progress.completedAfter(3))
        assertEquals(2, progress.completedAfter(5))
        assertEquals(3, progress.completedAfter(6))
    }

    @Test
    fun `empty pending list`() {
        val empty = EpochProgress(emptyList())
        assertEquals(0, empty.totalEpochs)
        assertEquals(0, empty.completedAfter(0))
    }
}
