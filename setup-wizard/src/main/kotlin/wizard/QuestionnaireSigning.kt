package wizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Publishing a backend questionnaire: the exact JSON text below is signed
 * with the study-signing key (the one apps pin) and stored by
 * `publish_questionnaire()` (backend/questionnaires.sql). The app verifies it
 * and uses only that text (`QuestionnaireSignature.kt` / `Questionnaire.kt`
 * in the library — keep the format and the rules in [problems] in sync).
 *
 * A questionnaire is not part of the signed study definition: publishing a
 * new version never asks participants to consent again. They consented to
 * the data type it names — its name, description, retention and frequency.
 */
object QuestionnaireSigning {

    const val FORMAT = "mbpulse-questionnaire-v1"

    private val ID = Regex("^[a-z][a-z0-9-]{0,62}$")
    private val FIELD = Regex("^[a-z][a-z0-9_]{0,62}$")
    private val RESERVED = setOf("effective_time_frame", "extra", "questionnaire_version")

    /**
     * Signs and publishes [form] (`{questionnaireId, dataType, title,
     * description?, intro?, items}`) for [studyId] as the next version.
     * Throws with every problem before anything is published.
     */
    fun publish(dbUrl: String, studyId: String, form: JsonObject, now: Instant = Instant.now()): JsonObject {
        val study = Json.parseToJsonElement(Db.listStudies(dbUrl)).jsonArray.map { it.jsonObject }
            .firstOrNull { it["studyId"]?.jsonPrimitive?.content.equals(studyId, ignoreCase = true) }
            ?: error("Study $studyId not found on this backend — create it first (step 3).")
        require(StudyUpdate.signatureStatus(study) == "valid") {
            "The study's definition does not verify with this wizard's study-signing key, so its apps pin " +
                "another key and would refuse questionnaires signed with this one — copy the original " +
                "backend/keys/study_signing_* files here first."
        }

        val id = form.str("questionnaireId")
        val published = Json.parseToJsonElement(Db.listQuestionnaires(dbUrl, studyId)).jsonArray
            .map { it.jsonObject }.firstOrNull { it.str("questionnaireId") == id }
        val version = (published?.get("version")?.jsonPrimitive?.intOrNull ?: 0) + 1

        val definition = buildJsonObject {
            put("format", FORMAT)
            put("studyId", studyId.lowercase())
            put("questionnaireId", id)
            put("version", version)
            put("issuedAt", now.toString())
            put("dataType", form.str("dataType"))
            put("title", form.str("title"))
            form.str("description").takeIf { it.isNotEmpty() }?.let { put("description", it) }
            form.str("intro").takeIf { it.isNotEmpty() }?.let { put("intro", it) }
            put("items", buildJsonArray { form.items().forEach { add(cleanItem(it)) } })
        }

        val dataTypes = (study["dataTypes"] as? JsonArray).orEmpty().map { it.jsonObject.str("id") }
        val previousItems = published?.str("definition")?.let { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject.items() }.getOrNull()
        }.orEmpty()
        val problems = problems(definition, dataTypes) + typeChanges(previousItems, definition.items())
        require(problems.isEmpty()) { problems.joinToString("\n• ", prefix = "Cannot publish the questionnaire:\n• ") }

        val text = Json.encodeToString(JsonObject.serializer(), definition)
        val result = Db.publishQuestionnaire(dbUrl, text, Keys.signStudyDefinition(keysDir, text))
        return Json.parseToJsonElement(result).jsonObject
    }

    /** Same rules as the library's `Questionnaire.problems()` + the data type must exist. */
    internal fun problems(q: JsonObject, studyDataTypes: List<String>): List<String> = buildList {
        val id = q.str("questionnaireId")
        if (!ID.matches(id)) add("Id must be lowercase kebab-case, e.g. daily-check-in.")
        val type = q.str("dataType")
        if (type !in studyDataTypes)
            add("Data type \"$type\" is not one of the study's (${studyDataTypes.joinToString()}) — " +
                "add it to the study and update the study first: participants consent to data types.")
        if (q.str("title").isBlank()) add("Give the questionnaire a title.")
        val items = q.items()
        if (items.isEmpty()) add("Add at least one question.")
        val ids = items.map { it.str("id") }
        ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { add("Question id \"$it\" is used twice.") }
        items.forEachIndexed { i, item ->
            val where = "Question ${i + 1} (${item.str("id").ifEmpty { "no id" }})"
            val itemId = item.str("id")
            if (!FIELD.matches(itemId) || itemId in RESERVED) add("$where: the id must be snake_case and not reserved.")
            if (item.str("text").isBlank()) add("$where: write the question.")
            val min = item["min"]?.jsonPrimitive?.doubleOrNull
            val max = item["max"]?.jsonPrimitive?.doubleOrNull
            when (item.str("type")) {
                "scale" -> when {
                    min == null || max == null || min >= max -> add("$where: a scale needs a minimum below its maximum.")
                    min % 1.0 != 0.0 || max % 1.0 != 0.0 -> add("$where: scale ends must be whole numbers.")
                    max - min > 100 -> add("$where: a scale has at most 101 points.")
                }
                "number" -> if (min != null && max != null && min >= max) add("$where: the minimum must be below the maximum.")
                "choice", "multichoice" -> {
                    val options = (item["options"] as? JsonArray).orEmpty().map { it.jsonObject }
                    val values = options.map { it.str("value") }
                    if (options.size < 2) add("$where: give at least two options.")
                    if (values.toSet().size != values.size) add("$where: option values must be unique.")
                    if (options.any { it.str("value").isBlank() || it.str("label").isBlank() })
                        add("$where: every option needs a value and a label.")
                    if (item.str("type") == "multichoice" && values.any { ',' in it })
                        add("$where: option values of a multiple choice cannot contain commas.")
                }
                "text" -> item["maxLength"]?.jsonPrimitive?.intOrNull?.let {
                    if (it !in 1..5000) add("$where: the maximum length must be 1–5000.")
                }
                else -> add("$where: unknown question type \"${item.str("type")}\".")
            }
        }
    }

    /**
     * An item id keeps its meaning across versions — answers of all versions
     * are charted and exported in one column. A question that changes type
     * needs a new id.
     */
    private fun typeChanges(before: List<JsonObject>, after: List<JsonObject>): List<String> {
        val old = before.associate { it.str("id") to it.str("type") }
        return after.mapNotNull { item ->
            val was = old[item.str("id")] ?: return@mapNotNull null
            if (was == item.str("type")) null
            else "Question \"${item.str("id")}\" was a $was question in the published version — give the " +
                "changed question a new id, so earlier answers keep their meaning."
        }
    }

    /** Keeps only the fields the app reads, with their JSON types. */
    private fun cleanItem(item: JsonObject): JsonObject = buildJsonObject {
        put("id", item.str("id"))
        val type = item.str("type")
        put("type", type)
        put("text", item.str("text"))
        put("required", item["required"]?.jsonPrimitive?.booleanOrNull ?: false)
        item.str("label").takeIf { it.isNotEmpty() }?.let { put("label", it) }
        when (type) {
            "scale", "number" -> {
                item["min"]?.jsonPrimitive?.doubleOrNull?.let { put("min", it) }
                item["max"]?.jsonPrimitive?.doubleOrNull?.let { put("max", it) }
                if (type == "scale") {
                    item.str("minLabel").takeIf { it.isNotEmpty() }?.let { put("minLabel", it) }
                    item.str("maxLabel").takeIf { it.isNotEmpty() }?.let { put("maxLabel", it) }
                } else {
                    item.str("unit").takeIf { it.isNotEmpty() }?.let { put("unit", it) }
                }
            }
            "choice", "multichoice" -> put("options", buildJsonArray {
                (item["options"] as? JsonArray).orEmpty().forEach { o ->
                    add(buildJsonObject { put("value", o.jsonObject.str("value")); put("label", o.jsonObject.str("label")) })
                }
            })
            "text" -> item["maxLength"]?.jsonPrimitive?.intOrNull?.let { put("maxLength", it) }
        }
    }

    private fun JsonObject.items(): List<JsonObject> = (this["items"] as? JsonArray).orEmpty().map { it.jsonObject }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}
