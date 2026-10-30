package pt.ua.mbpulse.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ParticipantCredentialsTest {

    private val token = UsernameToken.of("alice")

    @Test
    fun `auth secret is the documented PBKDF2 and is deterministic`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
            PBEKeySpec("correct horse".toCharArray(),
                ("mbpulse-auth-v1|$token").toByteArray(), 600_000, 256)
        ).encoded
        val secret = ParticipantCredentials.authSecret("correct horse".toCharArray(), token)
        assertEquals(Base64.getEncoder().encodeToString(expected), secret)
        assertEquals(secret, ParticipantCredentials.authSecret("correct horse".toCharArray(), token))
    }

    @Test
    fun `auth secret depends on passphrase and username, and never contains the passphrase`() {
        val secret = ParticipantCredentials.authSecret("correct horse".toCharArray(), token)
        assertNotEquals(secret, ParticipantCredentials.authSecret("correct horsf".toCharArray(), token))
        assertNotEquals(secret, ParticipantCredentials.authSecret("correct horse".toCharArray(), UsernameToken.of("bob")))
        assertFalse(secret.contains("horse"))
    }

    @Test
    fun `the caller's passphrase array is left intact (the recovery blob still needs it)`() {
        val passphrase = "correct horse".toCharArray()
        ParticipantCredentials.authSecret(passphrase, token)
        assertEquals("correct horse", String(passphrase))
    }

    @Test
    fun `passphrase key is the documented PBKDF2 and differs from the stored auth secret`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
            PBEKeySpec("correct horse".toCharArray(),
                ("mbpulse-rotate-v1|$token").toByteArray(), 600_000, 256)
        ).encoded
        val passphrase = "correct horse".toCharArray()
        val key = ParticipantCredentials.passphraseKey(passphrase, token)
        assertEquals(expected.toList(), key.toList())
        assertEquals("correct horse", String(passphrase))   // still needed afterwards
        // The phone stores authSecret; the passphrase key must not be derivable from it.
        assertNotEquals(
            ParticipantCredentials.authSecret("correct horse".toCharArray(), token),
            Base64.getEncoder().encodeToString(key)
        )
    }

    @Test
    fun `email uses the login id and the reserved domain`() {
        val id = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("$id@participants.invalid", ParticipantCredentials.email(id))
        assertThrows(IllegalArgumentException::class.java) { ParticipantCredentials.email("alice") }
        assertThrows(IllegalArgumentException::class.java) { ParticipantCredentials.email(id.uppercase()) }
    }
}
