package pt.ua.mbpulse.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A questionnaire defined on the backend (setup wizard → `questionnaires`
 * table) and rendered by the library — no app code, no app update.
 *
 * The exact JSON the PI signed (format [FORMAT], verified by
 * [pt.ua.mbpulse.crypto.QuestionnaireSignature] against the pinned
 * study-signing key; the wizard's `QuestionnaireSigning.kt` writes it — keep
 * in sync). Answers belong to the study data type [dataType]: participants
 * consent to that data type, so publishing a new version of the questions does
 * not ask them to consent again.
 *
 * Answers are stored as one data point of schema `questionnaire:<id>` — a
 * [SchemaDefinition] built from the items ([schemaDefinition]); the first item
 * is the primary value (home card, history chart).
 */
@Serializable
internal data class Questionnaire(
    val format: String,
    val studyId: String,
    val questionnaireId: String,
    val version: Int,
    val issuedAt: String,
    val dataType: String,
    val title: String,
    /** Shown on the home card, e.g. "Takes about a minute". */
    val description: String? = null,
    /** Shown above the questions. */
    val intro: String? = null,
    val items: List<QuestionnaireItem>
) {
    /** The `"namespace:name"` key of its answers. */
    val schemaName: String get() = "$NAMESPACE:$questionnaireId"

    val issuedAtInstant: Instant get() = Instant.parse(issuedAt)

    /**
     * The schema of the answers: one field per item, in order, plus
     * [VERSION_FIELD] so every answer names the version of the questions.
     * Item ids keep their meaning across versions (the wizard warns when one
     * changes type), so answers of all versions chart together.
     */
    fun schemaDefinition(): SchemaDefinition = SchemaDefinition(
        id = OmhSchemaId(namespace = NAMESPACE, name = questionnaireId, version = version.toString()),
        terminology = SchemaTerminology(
            loincCode = null, display = title, category = "survey", ucumUnit = null, ucumCode = null
        ),
        fields = items.map { it.field() } +
            SchemaField(VERSION_FIELD, FieldKind.NUMBER, label = "Questionnaire version")
    )

    /**
     * Everything that makes the definition unusable (empty = valid). The app
     * drops a questionnaire with problems; the wizard and `publish_questionnaire`
     * check the same rules before it is ever published.
     */
    fun problems(): List<String> = buildList {
        if (format != FORMAT) add("unknown format '$format'")
        if (!ID.matches(questionnaireId)) add("questionnaireId '$questionnaireId' is not lowercase kebab-case")
        if (version < 1) add("version must be ≥ 1")
        if (runCatching { issuedAtInstant }.isFailure) add("issuedAt is not a valid timestamp")
        if (dataType.isBlank()) add("dataType is empty")
        if (title.isBlank()) add("title is empty")
        if (items.isEmpty()) add("no items")
        val ids = items.map { it.id }
        ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { add("item id '$it' is used twice") }
        items.forEach { item -> item.problems().forEach { add("item '${item.id}': $it") } }
    }

    companion object {
        const val FORMAT = "mbpulse-questionnaire-v1"
        const val NAMESPACE = "questionnaire"
        /** Body field naming the version of the questions an answer was given to. */
        const val VERSION_FIELD = "questionnaire_version"
        private val ID = Regex("^[a-z][a-z0-9-]{0,62}$")
    }
}

@Serializable
internal data class QuestionnaireItem(
    /** Body field name (snake_case) — keep it stable across versions. */
    val id: String,
    val type: QuestionnaireItemType,
    /** The question as participants read it. */
    val text: String,
    val required: Boolean = false,
    /** Short label for charts, history and the PI export; default from [id]. */
    val label: String? = null,
    /** SCALE: integer range (inclusive). NUMBER: optional bounds. */
    val min: Double? = null,
    val max: Double? = null,
    /** SCALE: words under the two ends, e.g. "very bad" / "excellent". */
    val minLabel: String? = null,
    val maxLabel: String? = null,
    /** NUMBER: unit shown after the field (e.g. "hours"); stored with the value. */
    val unit: String? = null,
    /** CHOICE / MULTICHOICE: stored [QuestionnaireOption.value], shown [QuestionnaireOption.label]. */
    val options: List<QuestionnaireOption> = emptyList(),
    /** TEXT: maximum length (default [DEFAULT_TEXT_LENGTH]). */
    val maxLength: Int? = null
) {
    val displayLabel: String get() = label?.takeIf { it.isNotBlank() } ?: id.replace('_', ' ')
    val textLimit: Int get() = maxLength ?: DEFAULT_TEXT_LENGTH

    /** SCALE points, e.g. 0..10. */
    val scaleRange: IntRange get() = (min ?: 0.0).toInt()..(max ?: 0.0).toInt()

    internal fun field(): SchemaField = when (type) {
        QuestionnaireItemType.SCALE -> SchemaField(id, FieldKind.NUMBER, label = displayLabel)
        QuestionnaireItemType.NUMBER ->
            if (unit.isNullOrBlank()) SchemaField(id, FieldKind.NUMBER, label = displayLabel)
            else SchemaField(id, FieldKind.QUANTITY, unit = unit, label = displayLabel)
        QuestionnaireItemType.CHOICE ->
            SchemaField(id, FieldKind.ENUM, values = options.map { it.value }, label = displayLabel)
        // Several values, stored as one comma-separated text (option values never contain commas).
        QuestionnaireItemType.MULTICHOICE -> SchemaField(id, FieldKind.TEXT, label = displayLabel)
        QuestionnaireItemType.TEXT -> SchemaField(id, FieldKind.TEXT, label = displayLabel)
    }

    internal fun problems(): List<String> = buildList {
        if (!FIELD.matches(id) || id in RESERVED) add("id must be snake_case and not reserved")
        if (text.isBlank()) add("the question text is empty")
        when (type) {
            QuestionnaireItemType.SCALE -> {
                val lo = min; val hi = max
                if (lo == null || hi == null || lo >= hi) add("a scale needs min < max")
                else if (lo % 1.0 != 0.0 || hi % 1.0 != 0.0) add("scale bounds must be whole numbers")
                else if (hi - lo > MAX_SCALE_POINTS) add("a scale has at most ${MAX_SCALE_POINTS + 1} points")
            }
            QuestionnaireItemType.NUMBER ->
                if (min != null && max != null && min >= max) add("min must be below max")
            QuestionnaireItemType.CHOICE, QuestionnaireItemType.MULTICHOICE -> {
                if (options.size < 2) add("a choice needs at least two options")
                val values = options.map { it.value }
                if (values.toSet().size != values.size) add("option values must be unique")
                if (options.any { it.value.isBlank() || it.label.isBlank() }) add("every option needs a value and a label")
                if (type == QuestionnaireItemType.MULTICHOICE && values.any { ',' in it })
                    add("multichoice option values cannot contain commas")
            }
            QuestionnaireItemType.TEXT ->
                if (maxLength != null && maxLength !in 1..MAX_TEXT_LENGTH) add("maxLength must be 1–$MAX_TEXT_LENGTH")
        }
    }

    companion object {
        const val DEFAULT_TEXT_LENGTH = 500
        const val MAX_TEXT_LENGTH = 5000
        const val MAX_SCALE_POINTS = 100
        private val FIELD = Regex("^[a-z][a-z0-9_]{0,62}$")
        private val RESERVED = setOf("effective_time_frame", "extra", Questionnaire.VERSION_FIELD)
    }
}

@Serializable
internal enum class QuestionnaireItemType {
    /** A whole number in [QuestionnaireItem.min]..[QuestionnaireItem.max], picked on a scale. */
    @SerialName("scale") SCALE,
    /** A typed number (hours slept, weight …), optionally bounded and with a unit. */
    @SerialName("number") NUMBER,
    /** One of the options. */
    @SerialName("choice") CHOICE,
    /** Any of the options. */
    @SerialName("multichoice") MULTICHOICE,
    /** Free text. */
    @SerialName("text") TEXT
}

@Serializable
internal data class QuestionnaireOption(val value: String, val label: String)

/**
 * Answers to a [Questionnaire] — values as the form collects them: Int for
 * SCALE, Double for NUMBER, String for CHOICE and TEXT, Set<String> for
 * MULTICHOICE; null / blank / empty = not answered. Pure, unit-tested.
 */
internal object QuestionnaireAnswers {

    /** Items that are required but unanswered, or answered with an invalid value. */
    fun invalidItems(questionnaire: Questionnaire, answers: Map<String, Any?>): List<String> =
        questionnaire.items.filter { item ->
            val value = normalised(item, answers[item.id])
            if (value == null) item.required else !isValid(item, value)
        }.map { it.id }

    /** The answers as body values for [Questionnaire.schemaDefinition] (unanswered omitted). */
    fun bodyValues(questionnaire: Questionnaire, answers: Map<String, Any?>): Map<String, Any?> =
        questionnaire.items.associate { item ->
            item.id to normalised(item, answers[item.id])?.let { v ->
                if (item.type == QuestionnaireItemType.MULTICHOICE) {
                    // Option order, not tap order — the same answer always stores the same text.
                    @Suppress("UNCHECKED_CAST") val chosen = v as Set<String>
                    item.options.map { it.value }.filter { it in chosen }.joinToString(",")
                } else v
            }
        } + (Questionnaire.VERSION_FIELD to questionnaire.version)

    /**
     * The data point for submitted answers: a measurement over the time the
     * form was open ([startedAtMs]..[submittedAtMs]), modality self-reported.
     */
    fun dataPoint(questionnaire: Questionnaire, answers: Map<String, Any?>, startedAtMs: Long, submittedAtMs: Long): OmhDataPoint {
        require(invalidItems(questionnaire, answers).isEmpty()) { "answers are incomplete or invalid" }
        return questionnaire.schemaDefinition().dataPoint(
            sourceName = "${questionnaire.schemaName} v${questionnaire.version}",
            startMs    = startedAtMs,
            endMs      = maxOf(submittedAtMs, startedAtMs + 1),
            values     = bodyValues(questionnaire, answers),
            modality   = "self-reported"
        )
    }

    private fun normalised(item: QuestionnaireItem, value: Any?): Any? = when (value) {
        null -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Collection<*> -> value.filterIsInstance<String>().toSet().takeIf { it.isNotEmpty() }
        else -> value
    }.let { v -> if (item.type == QuestionnaireItemType.TEXT && v is String) v.take(item.textLimit) else v }

    private fun isValid(item: QuestionnaireItem, value: Any): Boolean = when (item.type) {
        QuestionnaireItemType.SCALE -> value is Int && value in item.scaleRange
        QuestionnaireItemType.NUMBER -> value is Number && value.toDouble().let { d ->
            !d.isNaN() && (item.min == null || d >= item.min) && (item.max == null || d <= item.max)
        }
        QuestionnaireItemType.CHOICE -> value is String && item.options.any { it.value == value }
        QuestionnaireItemType.MULTICHOICE -> value is Set<*> && value.all { v -> item.options.any { it.value == v } }
        QuestionnaireItemType.TEXT -> value is String
    }
}
