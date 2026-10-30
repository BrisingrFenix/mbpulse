package pt.ua.mbpulse.ui.screens.consent

/**
 * A single data type that the study wishes to collect.
 * Driven by the study configuration fetched from Supabase — no app update
 * needed when the PI adds or removes a data category.
 */
public data class ConsentDataType(
    val id: String,
    val name: String,
    val description: String,       // plain-language "what we collect"
    val purpose: String,           // plain-language "why we collect it"
    val retentionDays: Int,        // how long this category is kept
    val isEssential: Boolean,      // if true, declining triggers withdrawal dialog
    /**
     * Minimum seconds between recorded data points of this type, or null for
     * the processor's natural cadence. Enforced library-side by [SamplingGate]
     * in EpochSink; shown to participants on the consent screen.
     */
    val samplingSeconds: Int? = null
)

/**
 * The user's decision for a single data type, with a UTC timestamp.
 * One entry per data type — stored locally and synced to Supabase.
 */
internal data class ConsentDecision(
    val dataTypeId: String,
    val dataTypeName: String,
    val accepted: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * The complete consent record produced at the end of the flow.
 * This is signed and stored — it is the IRB-auditable artefact.
 */
internal data class ConsentRecord(
    val studyId: String,
    val participantPseudoId: String,
    val studyVersion: String,
    /** The exact study definition consented to (crypto/StudyDefinitionHash) — signed with the record. */
    val definitionSha256: String,
    val decisions: List<ConsentDecision>,
    val completedAtMs: Long = System.currentTimeMillis()
)