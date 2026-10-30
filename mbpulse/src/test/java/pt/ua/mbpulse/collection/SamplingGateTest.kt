package pt.ua.mbpulse.collection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingGateTest {

    // Maps like the sample host config: two schemas share one consent type.
    private val mapper: (String) -> String? = { schema ->
        when (schema) {
            "omh:heart-rate", "mbpulse:hrv" -> "bvp_hr_ibi"
            "omh:body-temperature"                -> "skin_temperature"
            else                                  -> null
        }
    }

    @Test
    fun `no configured interval passes everything`() {
        val gate = SamplingGate(emptyMap(), mapper)
        assertTrue(gate.allow("omh:heart-rate", 0))
        assertTrue(gate.allow("omh:heart-rate", 1))
        assertTrue(gate.allow("omh:heart-rate", 2))
    }

    @Test
    fun `unmapped schema passes everything even with intervals configured`() {
        val gate = SamplingGate(mapOf("bvp_hr_ibi" to 60), mapper)
        assertTrue(gate.allow("custom:unmapped", 0))
        assertTrue(gate.allow("custom:unmapped", 1))
    }

    @Test
    fun `keeps first point then drops until the interval elapses`() {
        val gate = SamplingGate(mapOf("bvp_hr_ibi" to 60), mapper)   // 60 s
        assertTrue(gate.allow("omh:heart-rate", 0))          // first
        assertFalse(gate.allow("omh:heart-rate", 30_000))    // 30 s — too soon
        assertFalse(gate.allow("omh:heart-rate", 59_999))    // just under
        assertTrue(gate.allow("omh:heart-rate", 60_000))     // due
        assertFalse(gate.allow("omh:heart-rate", 90_000))    // window restarted at 60 s
        assertTrue(gate.allow("omh:heart-rate", 120_000))
    }

    @Test
    fun `schemas are throttled independently even when sharing a consent type`() {
        val gate = SamplingGate(mapOf("bvp_hr_ibi" to 60), mapper)
        assertTrue(gate.allow("omh:heart-rate", 0))
        assertTrue(gate.allow("mbpulse:hrv", 0))       // own window
        assertFalse(gate.allow("omh:heart-rate", 30_000))
        assertFalse(gate.allow("mbpulse:hrv", 30_000))
    }

    @Test
    fun `different types can have different intervals`() {
        val gate = SamplingGate(
            mapOf("bvp_hr_ibi" to 30, "skin_temperature" to 300), mapper
        )
        assertTrue(gate.allow("omh:heart-rate", 0))
        assertTrue(gate.allow("omh:body-temperature", 0))
        // 30 s later: HR due again, temperature not for another 270 s.
        assertTrue(gate.allow("omh:heart-rate", 30_000))
        assertFalse(gate.allow("omh:body-temperature", 30_000))
        assertFalse(gate.allow("omh:body-temperature", 299_000))
        assertTrue(gate.allow("omh:body-temperature", 300_000))
    }
}
