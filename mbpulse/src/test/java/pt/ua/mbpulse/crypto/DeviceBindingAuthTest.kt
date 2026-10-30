package pt.ua.mbpulse.crypto

import pt.ua.mbpulse.crypto.DeviceBindingAuth.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class DeviceBindingAuthTest {

    private val pseudoId = "6F1C2A3B-0000-4000-8000-000000000001"

    @Test
    fun `device keys are 32 random bytes`() {
        val a = DeviceBindingAuth.newDeviceKey()
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), DeviceBindingAuth.newDeviceKey().toList())
    }

    @Test
    fun `proof message is the documented format with a lowercase pseudo id`() {
        assertEquals("bind|6f1c2a3b-0000-4000-8000-000000000001|n0nce", DeviceBindingAuth.proofMessage(pseudoId, "n0nce"))
    }

    @Test
    fun `proof depends on the device key and the nonce`() {
        val key = ByteArray(32) { 1 }
        val proof = DeviceBindingAuth.proof(key, pseudoId, "n1")
        assertEquals(proof, DeviceBindingAuth.proof(key.copyOf(), pseudoId.lowercase(), "n1"))
        assertNotEquals(proof, DeviceBindingAuth.proof(key, pseudoId, "n2"))
        assertNotEquals(proof, DeviceBindingAuth.proof(ByteArray(32) { 2 }, pseudoId, "n1"))
    }

    @Test
    fun `session id is read from the access token payload`() {
        fun jwt(payload: String) = "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"
        assertEquals("1b2c", DeviceBindingAuth.sessionIdOf(jwt("""{"sub":"u","session_id":"1b2c"}""")))
        assertNull(DeviceBindingAuth.sessionIdOf(jwt("""{"sub":"u"}""")))
        assertNull(DeviceBindingAuth.sessionIdOf(jwt("""{"session_id":""}""")))
        assertNull(DeviceBindingAuth.sessionIdOf("not a jwt"))
    }

    @Test
    fun `binding decision`() {
        // This session is the bound one.
        assertEquals(Step.DONE, DeviceBindingAuth.nextStep("this", serverHasDeviceKey = true, hasLocalKey = true))
        // Enrolled before binding existed: first updated app claims it.
        assertEquals(Step.REGISTER, DeviceBindingAuth.nextStep("none", serverHasDeviceKey = false, hasLocalKey = false))
        assertEquals(Step.REGISTER, DeviceBindingAuth.nextStep("none", serverHasDeviceKey = false, hasLocalKey = true))
        // A new login (silent re-login) of the bound phone — or a replaced phone: the proof decides.
        assertEquals(Step.BIND, DeviceBindingAuth.nextStep("other", serverHasDeviceKey = true, hasLocalKey = true))
        // Bound elsewhere and this phone has no device key: replaced.
        assertEquals(Step.REPLACED, DeviceBindingAuth.nextStep("other", serverHasDeviceKey = true, hasLocalKey = false))
        // Recovered elsewhere by an app without binding (no device key on the server, but bound).
        assertEquals(Step.REPLACED, DeviceBindingAuth.nextStep("other", serverHasDeviceKey = false, hasLocalKey = true))
    }
}
