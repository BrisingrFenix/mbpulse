package pt.ua.mbpulse.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaMappingCheckTest {

    private val app = mapOf("omh:heart-rate" to "heart_rate", "ns:pain" to "pain")
    private val schemas = listOf("omh:heart-rate", "ns:pain", "omh:body-temperature")

    @Test fun `equal copies give no differences`() {
        assertTrue(SchemaMappingCheck.differences(app, schemas) { app[it] }.isEmpty())
    }

    @Test fun `a schema both map to nothing is not a difference`() {
        // omh:body-temperature: known to the app, unmapped on both sides.
        assertTrue(SchemaMappingCheck.differences(app, schemas) { app[it] }.none { "body-temperature" in it })
    }

    @Test fun `no server copy is reported once`() {
        val diffs = SchemaMappingCheck.differences(null, schemas) { app[it] }
        assertEquals(1, diffs.size)
        assertTrue("no schemaDataTypes" in diffs.single())
    }

    @Test fun `each kind of disagreement is reported for its schema`() {
        val server = mapOf("omh:heart-rate" to "hr_other", "omh:body-temperature" to "temperature")
        val diffs = SchemaMappingCheck.differences(server, schemas) { app[it] }
        assertEquals(3, diffs.size)
        assertTrue(diffs.any { it.startsWith("omh:heart-rate:") && "different types" in it })
        assertTrue(diffs.any { it.startsWith("omh:body-temperature:") && "never collected" in it })
        assertTrue(diffs.any { it.startsWith("ns:pain:") && "longest period" in it })
    }

    @Test fun `a server-only schema the app does not know is still compared`() {
        val diffs = SchemaMappingCheck.differences(mapOf("ns:gone" to "x"), emptyList()) { null }
        assertTrue(diffs.single().startsWith("ns:gone:"))
    }

    @Test fun `a throwing mapper is reported, not propagated`() {
        val diffs = SchemaMappingCheck.differences(mapOf("ns:a" to "a"), emptyList()) { error("boom") }
        assertTrue("throws" in diffs.single())
    }
}
