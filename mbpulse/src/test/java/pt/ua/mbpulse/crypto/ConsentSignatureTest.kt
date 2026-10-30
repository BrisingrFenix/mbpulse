package pt.ua.mbpulse.crypto

import pt.ua.mbpulse.ui.screens.consent.ConsentDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ConsentSignatureTest {

    private val pseudoId = "6f1c2a3b-0000-4000-8000-000000000001"
    private val studyId  = "0b8e9d7c-0000-4000-8000-0000000000aa"
    private val definition = StudyDefinitionHash.of("""{"format":"mbpulse-study-v1","studyId":"x"}""")

    private val decisions = listOf(
        ConsentDecision("heart_activity", "Heart activity", accepted = true, timestampMs = 1_000),
        ConsentDecision("skin_conductance", "Skin conductance: sweat", accepted = false, timestampMs = 2_000)
    )

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Signs like the device does (KeystoreManager.sign = SHA256withRSA). */
    private fun signed(
        keys: KeyPair,
        decisions: List<ConsentDecision> = this.decisions,
        completedAtMs: Long = 3_000,
        definitionSha256: String? = definition
    ): SignedConsent {
        val unsigned = SignedConsent(pseudoId, studyId, "1.0", completedAtMs, decisions, null, null, definitionSha256)
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(ConsentSignature.signingString(unsigned).toByteArray(Charsets.UTF_8))
            sign()
        }
        return unsigned.copy(
            signatureB64 = Base64.getEncoder().encodeToString(sig),
            signerPublicKeyB64 = Base64.getEncoder().encodeToString(keys.public.encoded)
        )
    }

    @Test
    fun `signing string is the documented canonical format`() {
        assertEquals(
            """
            mbpulse-consent-v2
            participant:$pseudoId
            study:$studyId
            studyVersion:1.0
            definition:$definition
            completedAtMs:3000
            decision:heart_activity:accepted:1000:Heart%20activity
            decision:skin_conductance:declined:2000:Skin%20conductance%3A%20sweat
            """.trimIndent(),
            ConsentSignature.signingString(pseudoId.uppercase(), studyId, "1.0", definition.uppercase(), 3_000, decisions)
        )
    }

    @Test
    fun `legacy v1 string has no definition line`() {
        assertEquals(
            """
            mbpulse-consent-v1
            participant:$pseudoId
            study:$studyId
            studyVersion:1.0
            completedAtMs:3000
            decision:heart_activity:accepted:1000:Heart%20activity
            decision:skin_conductance:declined:2000:Skin%20conductance%3A%20sweat
            """.trimIndent(),
            ConsentSignature.signingString(pseudoId, studyId, "1.0", null, 3_000, decisions)
        )
    }

    @Test
    fun `legacy v1 records still verify`() {
        assertTrue(ConsentSignature.verify(signed(keyPair(), definitionSha256 = null)))
    }

    @Test
    fun `the definition consented to is covered by the signature`() {
        val record = signed(keyPair())
        assertFalse(ConsentSignature.verify(record.copy(definitionSha256 = StudyDefinitionHash.of("other terms"))))
        // Dropping the definition (downgrade to v1) breaks it too.
        assertFalse(ConsentSignature.verify(record.copy(definitionSha256 = null)))
    }

    @Test
    fun `definition hash is hex SHA-256 of the UTF-8 text`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", StudyDefinitionHash.of("abc"))
        assertTrue(StudyDefinitionHash.isValid(definition))
        assertFalse(StudyDefinitionHash.isValid(definition.uppercase()))
        assertFalse(StudyDefinitionHash.isValid("abc"))
    }

    @Test
    fun `a valid signature verifies`() {
        assertTrue(ConsentSignature.verify(signed(keyPair())))
    }

    @Test
    fun `any change to the content breaks the signature`() {
        val record = signed(keyPair())
        val flipped = record.decisions.map { if (it.dataTypeId == "skin_conductance") it.copy(accepted = true) else it }
        assertFalse(ConsentSignature.verify(record.copy(decisions = flipped)))
        assertFalse(ConsentSignature.verify(record.copy(studyVersion = "2.0")))
        assertFalse(ConsentSignature.verify(record.copy(completedAtMs = 3_001)))
        assertFalse(ConsentSignature.verify(record.copy(decisions = record.decisions.reversed())))
    }

    @Test
    fun `unsigned, garbage or wrong-key records do not verify`() {
        val record = signed(keyPair())
        assertFalse(ConsentSignature.verify(record.copy(signatureB64 = null)))
        assertFalse(ConsentSignature.verify(record.copy(signatureB64 = "bm90IGEgc2lnbmF0dXJl")))
        val otherKey = Base64.getEncoder().encodeToString(keyPair().public.encoded)
        assertFalse(ConsentSignature.verify(record.copy(signerPublicKeyB64 = otherKey)))
    }

    @Test
    fun `latest valid record wins and forgeries are skipped`() {
        val keys = keyPair()
        val first = signed(keys, completedAtMs = 3_000)
        val later = signed(keys, decisions.map { it.copy(accepted = true) }, completedAtMs = 4_000)
        val forged = later.copy(decisions = decisions.map { it.copy(accepted = true) }, completedAtMs = 5_000)
        val unsigned = later.copy(signatureB64 = null, completedAtMs = 6_000)

        assertEquals(later, ConsentSignature.latestValid(listOf(first, later, forged, unsigned), pseudoId, studyId))
    }

    @Test
    fun `records of another participant or study are ignored`() {
        val record = signed(keyPair())
        assertNull(ConsentSignature.latestValid(listOf(record), "someone-else", studyId))
        assertNull(ConsentSignature.latestValid(listOf(record), pseudoId, "other-study"))
        assertEquals(record, ConsentSignature.latestValid(listOf(record), pseudoId.uppercase(), studyId))
    }
}
