package pt.ua.mbpulse.collection

import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.Questionnaire
import pt.ua.mbpulse.data.model.QuestionnaireAnswers
import pt.ua.mbpulse.data.model.QuestionnaireItem
import pt.ua.mbpulse.data.model.QuestionnaireItemType
import pt.ua.mbpulse.data.model.QuestionnaireOption
import pt.ua.mbpulse.data.model.QuestionnaireRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireTest {

    private val moods = listOf("bad", "neutral", "good").map { QuestionnaireOption(it, it.uppercase()) }

    private fun questionnaire(items: List<QuestionnaireItem> = defaultItems) = Questionnaire(
        format = Questionnaire.FORMAT,
        studyId = "11111111-1111-1111-1111-111111111111",
        questionnaireId = "daily-check-in",
        version = 3,
        issuedAt = "2026-10-20T10:00:00Z",
        dataType = "daily_check_in",
        title = "Daily check-in",
        items = items
    )

    private val defaultItems = listOf(
        QuestionnaireItem("wellbeing", QuestionnaireItemType.SCALE, "How do you feel?", required = true, min = 0.0, max = 10.0),
        QuestionnaireItem("mood", QuestionnaireItemType.CHOICE, "Mood?", required = true, options = moods),
        QuestionnaireItem("sleep_hours", QuestionnaireItemType.NUMBER, "Hours slept?", min = 0.0, max = 24.0, unit = "h"),
        QuestionnaireItem("symptoms", QuestionnaireItemType.MULTICHOICE, "Symptoms?", options = listOf(
            QuestionnaireOption("headache", "Headache"), QuestionnaireOption("nausea", "Nausea"))),
        QuestionnaireItem("notes", QuestionnaireItemType.TEXT, "Notes?", maxLength = 10)
    )

    @After fun tearDown() = QuestionnaireRegistry.clear()

    @Test fun `valid definition has no problems`() {
        assertEquals(emptyList<String>(), questionnaire().problems())
    }

    @Test fun `definition problems are reported`() {
        val bad = questionnaire(listOf(
            QuestionnaireItem("Bad Id", QuestionnaireItemType.TEXT, "x"),
            QuestionnaireItem("scale", QuestionnaireItemType.SCALE, "x", min = 5.0, max = 1.0),
            QuestionnaireItem("pick", QuestionnaireItemType.CHOICE, "x", options = moods.take(1)),
            QuestionnaireItem("pick", QuestionnaireItemType.TEXT, ""),
            QuestionnaireItem(Questionnaire.VERSION_FIELD, QuestionnaireItemType.TEXT, "x")
        )).problems()
        assertTrue(bad.any { "'Bad Id'" in it })
        assertTrue(bad.any { "min < max" in it })
        assertTrue(bad.any { "two options" in it })
        assertTrue(bad.any { "used twice" in it })
        assertTrue(bad.any { "question text is empty" in it })
        assertTrue(bad.any { "'${Questionnaire.VERSION_FIELD}'" in it })
    }

    @Test fun `schema has one field per item plus the version`() {
        val schema = questionnaire().schemaDefinition()
        assertEquals("questionnaire:daily-check-in", schema.schemaName)
        assertEquals(listOf("wellbeing", "mood", "sleep_hours", "symptoms", "notes", Questionnaire.VERSION_FIELD),
            schema.fields.map { it.name })
        assertEquals(FieldKind.NUMBER, schema.primary.kind)
        assertEquals(FieldKind.ENUM, schema.field("mood")!!.kind)
        assertEquals(FieldKind.QUANTITY, schema.field("sleep_hours")!!.kind)
        assertEquals("survey", schema.terminology.category)
    }

    @Test fun `required and invalid answers are flagged`() {
        val q = questionnaire()
        assertEquals(listOf("wellbeing", "mood"), QuestionnaireAnswers.invalidItems(q, emptyMap()))
        assertEquals(listOf("wellbeing", "sleep_hours"), QuestionnaireAnswers.invalidItems(q, mapOf(
            "wellbeing" to 11, "mood" to "good", "sleep_hours" to Double.NaN)))
        assertEquals(listOf("mood"), QuestionnaireAnswers.invalidItems(q, mapOf(
            "wellbeing" to 7, "mood" to "furious")))
        assertEquals(emptyList<String>(), QuestionnaireAnswers.invalidItems(q, mapOf(
            "wellbeing" to 7, "mood" to "good", "notes" to "   ")))
    }

    @Test fun `answers become a self-reported data point with the version`() {
        val q = questionnaire()
        QuestionnaireRegistry.set(q.studyId, listOf(q))
        val point = QuestionnaireAnswers.dataPoint(q, mapOf(
            "wellbeing" to 7, "mood" to "good", "sleep_hours" to 7.5,
            "symptoms" to setOf("nausea", "headache"), "notes" to "a very long note"
        ), startedAtMs = 1_000_000, submittedAtMs = 1_060_000)

        assertEquals("self-reported", point.header.acquisitionProvenance.modality)
        assertEquals("3", point.header.schemaId.version)
        val body = point.body.extra
        assertEquals("7.0", body["wellbeing"]!!.jsonPrimitive.content)
        assertEquals("good", body["mood"]!!.jsonPrimitive.content)
        assertEquals("h", body["sleep_hours"]!!.jsonObject["unit"]!!.jsonPrimitive.content)
        assertEquals("headache,nausea", body["symptoms"]!!.jsonPrimitive.content)   // option order
        assertEquals("a very lon", body["notes"]!!.jsonPrimitive.content)          // maxLength
        assertEquals("3.0", body[Questionnaire.VERSION_FIELD]!!.jsonPrimitive.content)
    }

    @Test fun `registry registers answer schemas and resolves their data type`() {
        val q = questionnaire()
        QuestionnaireRegistry.set(q.studyId, listOf(q))
        assertEquals("daily_check_in", QuestionnaireRegistry.dataTypeFor("questionnaire:daily-check-in"))
        assertTrue(OmhSchemas.definitionFor("questionnaire:daily-check-in") != null)
        QuestionnaireRegistry.clear()
        assertEquals(null, OmhSchemas.definitionFor("questionnaire:daily-check-in"))
    }

    @Test fun `definition json round-trips with the wire names`() {
        val json = """{"format":"mbpulse-questionnaire-v1","studyId":"s","questionnaireId":"q","version":1,
            "issuedAt":"2026-01-01T00:00:00Z","dataType":"t","title":"T",
            "items":[{"id":"a","type":"multichoice","text":"?","options":[{"value":"x","label":"X"},{"value":"y","label":"Y"}]}]}"""
        val q = Json.decodeFromString(Questionnaire.serializer(), json)
        assertEquals(QuestionnaireItemType.MULTICHOICE, q.items.single().type)
        assertEquals(emptyList<String>(), q.problems())
    }
}
