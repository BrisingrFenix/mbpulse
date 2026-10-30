package pt.ua.mbpulse.collection

import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhBody
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.SchemaDefinition
import pt.ua.mbpulse.data.model.valuesIn
import pt.ua.mbpulse.data.local.EpochHistoryEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext

/**
 * Plaintext summary of a data point for the local UI: the card's display
 * string and the numeric value charted in history. Shared by live collection
 * ([EpochSink]) and recovery ([OmhImporter]) so recovered data looks exactly
 * like freshly collected data.
 */
internal object EpochDisplay {

    /** How history shows a schema: a numeric series, a category timeline, or instant events. */
    enum class Kind { NUMBER, CATEGORY, EVENT }

    /**
     * @param numericValue the primary numeric value; null for categorical data
     *   that must not be drawn as a numeric series (e.g. stimulus events).
     * @param kind how history charts it.
     * @param series every numeric field by label, primary first — one chart
     *   line each (e.g. SDNN + RMSSD).
     * @param category the category value for [Kind.CATEGORY] / [Kind.EVENT] (e.g. "awake").
     */
    data class Summary(
        val displayValue: String,
        val unit: String,
        val numericValue: Float?,
        val kind: Kind = Kind.NUMBER,
        val series: Map<String, Float> = emptyMap(),
        val category: String? = null
    )

    /**
     * Summary for [point]: host-defined schemas ([SchemaDefinition]) from their
     * definition, built-in ones from their typed fields; null if neither applies.
     */
    fun summarize(point: OmhDataPoint): Summary? {
        val schemaName = "${point.header.schemaId.namespace}:${point.header.schemaId.name}"
        OmhSchemas.definitionFor(schemaName)?.let { return summarize(it, point.body) }
        return summarizeBuiltIn(point.body)
    }

    private fun summarizeBuiltIn(body: OmhBody): Summary? = when {
        body.heartRate != null -> Summary(
            "%.1f".format(body.heartRate.value), body.heartRate.unit, body.heartRate.value,
            series = mapOf("Heart rate" to body.heartRate.value))
        body.sdnn != null -> Summary(
            "SDNN ${"%.1f".format(body.sdnn.value)} · RMSSD ${"%.1f".format(body.rmssd?.value ?: 0f)}",
            "ms", body.sdnn.value,
            series = listOfNotNull("SDNN" to body.sdnn.value, body.rmssd?.let { "RMSSD" to it.value }).toMap())
        body.sleepStage != null -> Summary(
            body.sleepStage.uppercase(), "", if (body.sleepStage == "awake") 1f else 0f,
            kind = Kind.CATEGORY, category = body.sleepStage.lowercase())
        body.bodyTemperature != null -> Summary(
            "%.1f".format(body.bodyTemperature.value), body.bodyTemperature.unit, body.bodyTemperature.value,
            series = mapOf("Temperature" to body.bodyTemperature.value))
        body.meanEda != null -> Summary(
            "${"%.3f".format(body.meanEda.value)} (σ ${"%.3f".format(body.sdEda?.value ?: 0f)})",
            body.meanEda.unit, body.meanEda.value,
            series = listOfNotNull("Mean" to body.meanEda.value,
                body.minEda?.let { "Min" to it.value }, body.maxEda?.let { "Max" to it.value }).toMap())
        // Event: show what was delivered; never charted as a numeric series.
        body.stimulusType != null -> Summary(
            listOfNotNull(
                body.stimulusType.code.uppercase(),
                body.stimulusLabel,
                body.stimulusIntensity?.let { "intensity %.1f".format(it) }
            ).joinToString(" · "),
            "", null,
            kind = Kind.EVENT, category = body.stimulusType.code.lowercase())
        else -> null
    }

    /**
     * Host-defined schema: the primary (first) field is the value and unit;
     * the other present fields follow as "label value unit". Charted only when
     * the primary field is numeric and the schema is not an event.
     */
    internal fun summarize(definition: SchemaDefinition, body: OmhBody): Summary? {
        val values = definition.valuesIn(body)
        if (values.isEmpty()) return null
        val primary = definition.primary
        val primaryElement = body.extra[primary.name]

        val primaryNumber = primary.numberIn(primaryElement)
        val primaryText = primaryNumber?.let(::format)
            ?: primary.textIn(primaryElement)?.let { if (primary.kind == FieldKind.ENUM) it.uppercase() else it }
        val others = values.filter { (f, _) -> f != primary }.mapNotNull { (f, element) ->
            val text = f.numberIn(element)?.let { n ->
                listOfNotNull(format(n), f.unitIn(element).takeIf { f.kind == FieldKind.QUANTITY })
                    .joinToString(" ")
            } ?: f.textIn(element)
            text?.let { "${f.label} $it" }
        }

        val series = values.mapNotNull { (f, element) -> f.numberIn(element)?.let { f.label to it } }.toMap()
        val kind = when {
            definition.isEvent -> Kind.EVENT
            primaryNumber == null -> Kind.CATEGORY      // text / enum primary value
            else -> Kind.NUMBER
        }
        return Summary(
            displayValue = (listOfNotNull(primaryText) + others).joinToString(" · ").ifEmpty { "—" },
            unit = if (primaryNumber != null && primary.kind == FieldKind.QUANTITY)
                primary.unitIn(primaryElement).orEmpty() else "",
            numericValue = primaryNumber.takeUnless { definition.isEvent },
            kind = kind,
            series = if (kind == Kind.NUMBER) series else emptyMap(),
            category = if (kind == Kind.NUMBER) null else primary.textIn(primaryElement)
        )
    }

    /**
     * The history row for [summary], or null when there is nothing to chart.
     * Shared by live collection and recovery so both store the same rows.
     */
    fun historyRow(schemaName: String, epochStartMs: Long, summary: Summary): EpochHistoryEntity? {
        if (summary.kind == Kind.NUMBER && summary.numericValue == null) return null
        return EpochHistoryEntity(
            schemaName   = schemaName,
            epochStartMs = epochStartMs,
            numericValue = summary.numericValue ?: 0f,
            displayValue = summary.displayValue,
            unit         = summary.unit,
            kind         = summary.kind.name,
            category     = summary.category,
            seriesJson   = summary.series.takeIf { it.isNotEmpty() }
                ?.let { Json.encodeToString(SERIES, it) }
        )
    }

    /** Decodes [EpochHistoryEntity.seriesJson]; falls back to the primary value. */
    fun seriesOf(row: EpochHistoryEntity): Map<String, Float> =
        row.seriesJson?.let { runCatching { Json.decodeFromString(SERIES, it) }.getOrNull() }
            ?: mapOf("" to row.numericValue)

    fun kindOf(row: EpochHistoryEntity): Kind =
        row.kind?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: Kind.NUMBER

    private val SERIES = MapSerializer(String.serializer(), Float.serializer())

    /** Up to four significant digits, no trailing zeros: 14.5, 0.012, 72.53, 1200. */
    private fun format(value: Float): String =
        BigDecimal(value.toDouble()).round(MathContext(4)).stripTrailingZeros().toPlainString()
}
