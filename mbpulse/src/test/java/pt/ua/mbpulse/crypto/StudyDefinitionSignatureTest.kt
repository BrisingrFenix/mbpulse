package pt.ua.mbpulse.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

class StudyDefinitionSignatureTest {

    private val studyId = "0b8e9d7c-0000-4000-8000-0000000000aa"
    private val pi = keyPair()
    private val signer = keyPair()
    private val pinned = Base64.getEncoder().encodeToString(signer.public.encoded)

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** The text the wizard signs (StudySigning.kt): compact JSON, these fields. */
    private fun definition(
        issuedAt: String = "2026-10-14T10:00:00Z",
        id: String = studyId,
        retention: Int = 30,
        format: String = "mbpulse-study-v1"
    ) = """{"format":"$format","studyId":"$id","issuedAt":"$issuedAt","studyName":"PainMonit",""" +
        """"studyVersion":1,"piPublicKey":"-----BEGIN PUBLIC KEY-----\n""" +
        Base64.getEncoder().encodeToString(pi.public.encoded) + """\n-----END PUBLIC KEY-----\n",""" +
        """"dataTypes":[{"id":"heart_activity","name":"Heart","description":"d","purpose":"p",""" +
        """"retentionDays":$retention,"isEssential":true}],"schemaDataTypes":{"omh:heart-rate":"heart_activity"}}"""

    private fun sign(text: String, keys: KeyPair = signer): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        })

    private fun verify(text: String?, sig: String?, id: String = studyId) =
        StudyDefinitionSignature.verify(text, sig, pinned, id)

    @Test
    fun `a definition signed by the pinned key verifies and is parsed`() {
        val text = definition()
        val result = verify(text, sign(text)) as StudyDefinitionSignature.Result.Verified
        val d = result.definition
        assertEquals("PainMonit", d.studyName)
        assertEquals(1.0, d.studyVersion, 0.0)
        assertEquals(30, d.dataTypes.single().retentionDays)
        assertEquals("heart_activity", d.schemaDataTypes!!["omh:heart-rate"])
        assertTrue(d.piPublicKey.contains("BEGIN PUBLIC KEY"))
        assertEquals(Instant.parse("2026-10-14T10:00:00Z"), d.issuedAtInstant)
    }

    @Test
    fun `unsigned, altered or foreign-signed definitions are rejected`() {
        val text = definition()
        assertTrue(verify(text, null) is StudyDefinitionSignature.Result.Rejected)
        assertTrue(verify(null, sign(text)) is StudyDefinitionSignature.Result.Rejected)
        // Retention changed after signing (e.g. by whoever controls the backend).
        assertTrue(verify(definition(retention = 3650), sign(text)) is StudyDefinitionSignature.Result.Rejected)
        // Signed with the attacker's own key — the pinned key decides, not the server.
        assertTrue(verify(text, sign(text, keyPair())) is StudyDefinitionSignature.Result.Rejected)
        assertTrue(verify(text, "bm90IGEgc2ln") is StudyDefinitionSignature.Result.Rejected)
    }

    @Test
    fun `a valid signature for another study or format is rejected`() {
        val other = definition(id = "0b8e9d7c-0000-4000-8000-0000000000bb")
        assertTrue(verify(other, sign(other)) is StudyDefinitionSignature.Result.Rejected)
        val v2 = definition(format = "mbpulse-study-v2")
        assertTrue(verify(v2, sign(v2)) is StudyDefinitionSignature.Result.Rejected)
        val badDate = definition(issuedAt = "yesterday")
        assertTrue(verify(badDate, sign(badDate)) is StudyDefinitionSignature.Result.Rejected)
    }

    @Test
    fun `older definitions are rollbacks, equal or newer are not`() {
        val text = definition(issuedAt = "2026-10-14T10:00:00Z")
        val d = (verify(text, sign(text)) as StudyDefinitionSignature.Result.Verified).definition
        assertFalse(StudyDefinitionSignature.isRollback(d, null))
        assertFalse(StudyDefinitionSignature.isRollback(d, Instant.parse("2026-10-14T10:00:00Z")))
        assertFalse(StudyDefinitionSignature.isRollback(d, Instant.parse("2026-10-01T00:00:00Z")))
        assertTrue(StudyDefinitionSignature.isRollback(d, Instant.parse("2026-10-15T00:00:00Z")))
    }
}
