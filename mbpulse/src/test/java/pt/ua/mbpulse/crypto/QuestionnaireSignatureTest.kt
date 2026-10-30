package pt.ua.mbpulse.crypto

import pt.ua.mbpulse.data.repository.QuestionnaireMerge
import pt.ua.mbpulse.data.repository.QuestionnaireRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class QuestionnaireSignatureTest {

    private val studyId = "11111111-1111-1111-1111-111111111111"
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val other: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val pinned = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun definition(version: Int = 1, issuedAt: String = "2026-10-20T10:00:00Z", study: String = studyId,
                           format: String = "mbpulse-questionnaire-v1", id: String = "daily-check-in") =
        """{"format":"$format","studyId":"$study","questionnaireId":"$id","version":$version,""" +
            """"issuedAt":"$issuedAt","dataType":"daily_check_in","title":"Daily check-in",""" +
            """"items":[{"id":"wellbeing","type":"scale","text":"How do you feel?","required":true,"min":0,"max":10}]}"""

    private fun sign(text: String, pair: KeyPair = keys): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update(text.toByteArray(Charsets.UTF_8)); sign()
        })

    private fun verify(text: String, sig: String?) = QuestionnaireSignature.verify(text, sig, pinned, studyId)

    @Test fun `signed questionnaire verifies`() {
        val text = definition()
        val r = verify(text, sign(text))
        assertTrue(r is QuestionnaireSignature.Result.Verified)
        assertEquals("daily-check-in", (r as QuestionnaireSignature.Result.Verified).questionnaire.questionnaireId)
    }

    @Test fun `unsigned, other key and altered text are rejected`() {
        val text = definition()
        assertTrue(verify(text, null) is QuestionnaireSignature.Result.Rejected)
        assertTrue(verify(text, sign(text, other)) is QuestionnaireSignature.Result.Rejected)
        assertTrue(verify(text.replace("How do you feel?", "Enter your bank PIN"), sign(text))
            is QuestionnaireSignature.Result.Rejected)
    }

    @Test fun `a signed study definition is not accepted as a questionnaire`() {
        val text = definition(format = StudyDefinitionSignature.FORMAT)
        assertTrue(verify(text, sign(text)) is QuestionnaireSignature.Result.Rejected)
    }

    @Test fun `questionnaire of another study is rejected`() {
        val text = definition(study = "22222222-2222-2222-2222-222222222222")
        assertTrue(verify(text, sign(text)) is QuestionnaireSignature.Result.Rejected)
    }

    @Test fun `without a pinned key the text is checked but not the signature`() {
        val text = definition()
        assertTrue(QuestionnaireSignature.verify(text, null, null, studyId) is QuestionnaireSignature.Result.Verified)
        assertTrue(QuestionnaireSignature.verify(definition(format = "x"), null, null, studyId)
            is QuestionnaireSignature.Result.Rejected)
    }

    // -- QuestionnaireMerge -----------------------------------------------------

    private fun row(text: String, sig: String? = sign(text), id: String = "daily-check-in") = QuestionnaireRow(id, text, sig)
    private val verifier: (QuestionnaireRow, String) -> QuestionnaireSignature.Result =
        { r, s -> QuestionnaireSignature.verify(r.definition, r.signature, pinned, s) }

    @Test fun `newer version replaces the accepted one`() {
        val v1 = row(definition(1)); val v2 = row(definition(2, "2026-10-21T10:00:00Z"))
        assertEquals(listOf(v2), QuestionnaireMerge.merge(listOf(v1), listOf(v2), verifier, studyId))
    }

    @Test fun `older version (rollback) keeps the accepted one`() {
        val v1 = row(definition(1)); val v2 = row(definition(2, "2026-10-21T10:00:00Z"))
        assertEquals(listOf(v2), QuestionnaireMerge.merge(listOf(v2), listOf(v1), verifier, studyId))
    }

    @Test fun `tampered row keeps the accepted one, and is dropped when there is none`() {
        val v1 = row(definition(1))
        val bad = row(definition(2, "2026-10-21T10:00:00Z"), sig = sign(definition(1)))
        assertEquals(listOf(v1), QuestionnaireMerge.merge(listOf(v1), listOf(bad), verifier, studyId))
        assertEquals(emptyList<QuestionnaireRow>(), QuestionnaireMerge.merge(emptyList(), listOf(bad), verifier, studyId))
    }

    @Test fun `questionnaire no longer listed is retired`() {
        val v1 = row(definition(1))
        assertEquals(emptyList<QuestionnaireRow>(), QuestionnaireMerge.merge(listOf(v1), emptyList(), verifier, studyId))
    }

    @Test fun `row whose definition names another questionnaire is not accepted`() {
        val text = definition(1, id = "other-one")
        assertEquals(emptyList<QuestionnaireRow>(),
            QuestionnaireMerge.merge(emptyList(), listOf(row(text, id = "daily-check-in")), verifier, studyId))
    }
}
