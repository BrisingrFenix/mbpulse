package pt.ua.mbpulse.ui.screens.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTermsTest {

    private fun dt(id: String, retention: Int = 30, essential: Boolean = false, sampling: Int? = null) =
        ConsentDataType(id, "Name $id", "What $id", "Why $id", retention, essential, sampling)

    private val hr = dt("hr", essential = true)
    private val eda = dt("eda")
    private val temp = dt("temp")

    @Test
    fun `encode and decode round-trip, order-independent`() {
        val encoded = ConsentTerms.encode(listOf(eda, hr))
        assertEquals(encoded, ConsentTerms.encode(listOf(hr, eda)))
        assertEquals(setOf(hr, eda), ConsentTerms.decode(encoded)!!.toSet())
        assertEquals(emptyList<ConsentDataType>(), ConsentTerms.decode(ConsentTerms.encode(emptyList())))
        assertNull(ConsentTerms.decode(null))
        assertNull(ConsentTerms.decode("not json"))
    }

    @Test
    fun `unchanged study needs no review and keeps consent`() {
        val changes = ConsentTerms.diff(listOf(hr, eda), listOf(hr, eda))
        assertFalse(changes.needsReview)
        assertEquals(setOf("hr", "eda"), ConsentTerms.effectiveConsent(setOf("hr", "eda"), changes, listOf(hr, eda)))
    }

    @Test
    fun `added type needs review and is not collected`() {
        val changes = ConsentTerms.diff(listOf(hr), listOf(hr, temp))
        assertTrue(changes.needsReview)
        assertEquals(listOf(temp), changes.added)
        assertEquals(setOf("hr"), ConsentTerms.effectiveConsent(setOf("hr"), changes, listOf(hr, temp)))
    }

    @Test
    fun `changed terms stop collection of that type until reviewed`() {
        val longer = eda.copy(retentionDays = 90)
        val changes = ConsentTerms.diff(listOf(hr, eda), listOf(hr, longer))
        assertTrue(changes.needsReview)
        assertEquals(listOf(TermChange.Retention(30, 90)), changes.changed.single().whatChanged)
        assertEquals(setOf("hr"), ConsentTerms.effectiveConsent(setOf("hr", "eda"), changes, listOf(hr, longer)))
        assertEquals(listOf(longer), changes.toReview)
    }

    @Test
    fun `removed type stops at once without a review`() {
        val changes = ConsentTerms.diff(listOf(hr, eda), listOf(hr))
        assertFalse(changes.needsReview)
        assertEquals(listOf(eda), changes.removed)
        assertEquals(setOf("hr"), ConsentTerms.effectiveConsent(setOf("hr", "eda"), changes, listOf(hr)))
    }

    @Test
    fun `nothing agreed means everything is reviewed and nothing collected`() {
        val changes = ConsentTerms.diff(emptyList(), listOf(hr, eda))
        assertEquals(listOf(hr, eda), changes.toReview)
        assertEquals(emptySet<String>(), ConsentTerms.effectiveConsent(setOf("hr", "eda"), changes, listOf(hr, eda)))
    }

    @Test
    fun `merged decisions cover every current type in study order`() {
        val reviewed = listOf(ConsentDecision("temp", "Name temp", accepted = true, timestampMs = 5))
        val merged = ConsentTerms.mergeDecisions(listOf(hr, eda, temp), setOf("hr"), reviewed, nowMs = 9)
        assertEquals(listOf("hr", "eda", "temp"), merged.map { it.dataTypeId })
        assertEquals(listOf(true, false, true), merged.map { it.accepted })
        assertEquals(5, merged.last().timestampMs)
        assertEquals(9, merged.first().timestampMs)
    }
}
