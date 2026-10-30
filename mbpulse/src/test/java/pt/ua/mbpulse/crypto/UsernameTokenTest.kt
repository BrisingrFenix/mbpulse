package pt.ua.mbpulse.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class UsernameTokenTest {

    @Test
    fun `token is deterministic and normalised`() {
        val token = UsernameToken.of("alice")
        assertEquals(token, UsernameToken.of("  Alice "))
        assertEquals(token, UsernameToken.of("ＡＬＩＣＥ"))            // full-width → NFKC → alice
        assertNotEquals(token, UsernameToken.of("alice2"))
    }

    @Test
    fun `token is the documented PBKDF2 and never contains the username`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("alice".toCharArray(),
                "mbpulse-username-token-v1".toByteArray(), 100_000, 256))
            .encoded
        val token = UsernameToken.of("alice")
        assertEquals(Base64.getEncoder().encodeToString(expected), token)
        assertEquals(32, Base64.getDecoder().decode(token).size)
        assertFalse(token.contains("alice", ignoreCase = true))
    }
}
