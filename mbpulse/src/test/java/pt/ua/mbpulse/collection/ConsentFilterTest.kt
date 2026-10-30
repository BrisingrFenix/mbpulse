package pt.ua.mbpulse.collection

import pt.ua.mbpulse.collection.ConsentFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentFilterTest {

    private val mapping: (String) -> String? = { schema ->
        if (schema == "omh:heart-rate") "heart_rate" else null
    }

    @Test fun `mapped and consented is kept`() {
        assertEquals(Decision.CONSENTED, ConsentFilter.decide("omh:heart-rate", setOf("heart_rate"), mapping))
        assertTrue(ConsentFilter.allows("omh:heart-rate", setOf("heart_rate"), mapping))
    }

    @Test fun `mapped but not consented is dropped`() {
        assertEquals(Decision.NOT_CONSENTED, ConsentFilter.decide("omh:heart-rate", setOf("other"), mapping))
        assertFalse(ConsentFilter.allows("omh:heart-rate", setOf("other"), mapping))
    }

    @Test fun `unmapped is dropped even when every type is consented`() {
        assertEquals(Decision.UNMAPPED, ConsentFilter.decide("omh:step-count", setOf("heart_rate"), mapping))
        assertFalse(ConsentFilter.allows("omh:step-count", setOf("heart_rate"), mapping))
    }

    @Test fun `empty consent set keeps nothing`() {
        assertFalse(ConsentFilter.allows("omh:heart-rate", emptySet(), mapping))
        assertFalse(ConsentFilter.allows("omh:step-count", emptySet(), mapping))
    }

    @Test fun `default mapper keeps nothing`() {
        assertFalse(ConsentFilter.allows("omh:heart-rate", setOf("heart_rate")) { null })
    }
}
