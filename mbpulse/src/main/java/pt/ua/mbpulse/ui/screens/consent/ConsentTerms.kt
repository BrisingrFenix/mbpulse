package pt.ua.mbpulse.ui.screens.consent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a participant agreed to, per data type — the terms shown on the consent
 * screen. Stored on the device at enrolment / re-consent (the signed consent
 * record on the server carries the decisions and the study version, not the
 * wording), and compared with the current signed study definition on every
 * load to detect a study update that needs the participant's consent again.
 */
@Serializable
private data class TermsEntry(
    val id: String,
    val name: String,
    val description: String,
    val purpose: String,
    val retentionDays: Int,
    val isEssential: Boolean,
    val samplingSeconds: Int? = null
)

/** One aspect of a data type's consent terms that changed (worded by the UI). */
public sealed interface TermChange {
    public data object Name : TermChange
    public data object Description : TermChange
    public data object Purpose : TermChange
    public data class Retention(val fromDays: Int, val toDays: Int) : TermChange
    public data object Sampling : TermChange
    public data object NowRequired : TermChange
    public data object NowOptional : TermChange
}

/** One data type whose consent terms changed, and what changed (for the participant). */
public data class ChangedDataType(val before: ConsentDataType, val after: ConsentDataType) {
    val whatChanged: List<TermChange> get() = buildList {
        if (before.name != after.name) add(TermChange.Name)
        if (before.description != after.description) add(TermChange.Description)
        if (before.purpose != after.purpose) add(TermChange.Purpose)
        if (before.retentionDays != after.retentionDays) add(TermChange.Retention(before.retentionDays, after.retentionDays))
        if (before.samplingSeconds != after.samplingSeconds) add(TermChange.Sampling)
        if (before.isEssential != after.isEssential) add(if (after.isEssential) TermChange.NowRequired else TermChange.NowOptional)
    }
}

/**
 * Differences between the terms the participant agreed to and the current study.
 * [needsReview] = something new or changed must be accepted before it is collected;
 * removed types simply stop being collected.
 */
public data class ConsentChanges(
    val added: List<ConsentDataType>,
    val changed: List<ChangedDataType>,
    val removed: List<ConsentDataType>
) {
    val needsReview: Boolean get() = added.isNotEmpty() || changed.isNotEmpty()

    /** The current data types the participant must decide on again. */
    val toReview: List<ConsentDataType> get() = added + changed.map { it.after }
}

internal object ConsentTerms {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TermsEntry.serializer())

    /** Encodes [dataTypes] for storage. `"[]"` = agreed to nothing (forces a full review). */
    fun encode(dataTypes: List<ConsentDataType>): String =
        json.encodeToString(serializer, dataTypes.sortedBy { it.id }.map {
            TermsEntry(it.id, it.name, it.description, it.purpose, it.retentionDays, it.isEssential, it.samplingSeconds)
        })

    /** Decodes [encode]'s output; null when absent or unreadable. */
    fun decode(stored: String?): List<ConsentDataType>? = stored?.let {
        runCatching {
            json.decodeFromString(serializer, it).map { e ->
                ConsentDataType(e.id, e.name, e.description, e.purpose, e.retentionDays, e.isEssential, e.samplingSeconds)
            }
        }.getOrNull()
    }

    fun diff(agreed: List<ConsentDataType>, current: List<ConsentDataType>): ConsentChanges {
        val before = agreed.associateBy { it.id }
        val now = current.associateBy { it.id }
        return ConsentChanges(
            added   = current.filter { it.id !in before },
            changed = current.mapNotNull { c -> before[c.id]?.takeIf { it != c }?.let { ChangedDataType(it, c) } },
            removed = agreed.filter { it.id !in now }
        )
    }

    /**
     * What may be collected right now: types the participant accepted whose
     * terms are unchanged and which are still in the study. New and changed
     * types wait for the participant's decision — fail closed.
     */
    fun effectiveConsent(consentedTypeIds: Set<String>, changes: ConsentChanges, current: List<ConsentDataType>): Set<String> {
        val blocked = changes.changed.map { it.after.id }.toSet() + changes.added.map { it.id }
        val inStudy = current.map { it.id }.toSet()
        return consentedTypeIds.filterTo(LinkedHashSet()) { it in inStudy && it !in blocked }
    }

    /**
     * The full decision list for a re-consent record: [reviewed] decisions for
     * the types shown again, the previous choice for every other current type.
     * In the study's order, one line per current data type.
     */
    fun mergeDecisions(
        current: List<ConsentDataType>,
        previouslyConsented: Set<String>,
        reviewed: List<ConsentDecision>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ConsentDecision> {
        val byId = reviewed.associateBy { it.dataTypeId }
        return current.map { dt ->
            byId[dt.id] ?: ConsentDecision(dt.id, dt.name, accepted = dt.id in previouslyConsented, timestampMs = nowMs)
        }
    }
}
