package pt.ua.mbpulse.ui.screens.measurements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.ua.mbpulse.MbPulseLibrary
import pt.ua.mbpulse.collection.EpochDisplay
import pt.ua.mbpulse.data.local.DisplayEpochDao
import pt.ua.mbpulse.data.local.DisplayEpochEntity
import pt.ua.mbpulse.data.local.EpochHistoryDao
import pt.ua.mbpulse.data.local.EpochHistoryEntity
import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.data.model.QuestionnaireRegistry
import pt.ua.mbpulse.ui.components.downsample
import pt.ua.mbpulse.ui.screens.consent.ConsentDataType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

public data class CollectionCard(
    val schemaName: String,
    val title: String,
    val value: String,
    val unit: String,
    val hasData: Boolean,
    /** Time of the latest reading, null before the first. */
    val lastUpdatedMs: Long? = null,
    /** The last 24 h of the primary value, averaged down for a sparkline (empty = none). */
    val sparkline: List<Float> = emptyList(),
    /** Older than this ⇒ shown as out of date: a few typical intervals (min 15 min), 1 h without history. */
    val staleAfterMs: Long = 60 * 60_000L,
    /** Shows several values (e.g. SDNN · RMSSD) — spans the full width. */
    val wide: Boolean = false
)

@HiltViewModel
public class CollectionMeasurementsViewModel @Inject internal constructor(
    private val displayEpochDao: DisplayEpochDao,
    private val epochHistoryDao: EpochHistoryDao
) : ViewModel() {

    private val _cards = MutableStateFlow<List<CollectionCard>>(emptyList())
    public val cards: StateFlow<List<CollectionCard>> = _cards

    private var observing: Job? = null

    /**
     * Pass `LoadingState.Ready.consentedDataTypes` — not `dataTypes`, which
     * includes types the participant declined. Observes the latest values and
     * the last 24 h of history; calling again replaces the observation.
     */
    public fun observeForConsentedTypes(consentedTypes: List<ConsentDataType>) {
        observing?.cancel()
        val since = System.currentTimeMillis() - 24 * 60 * 60_000L
        observing = combine(displayEpochDao.observeAll(), epochHistoryDao.observeSince(since)) { latest, history ->
            buildCards(consentedTypes, latest, history.groupBy { it.schemaName })
        }
            .flowOn(Dispatchers.Default)
            .onEach { _cards.value = it }
            .launchIn(viewModelScope)
    }

    private fun buildCards(
        consentedTypes: List<ConsentDataType>,
        epochs: List<DisplayEpochEntity>,
        history: Map<String, List<EpochHistoryEntity>>
    ): List<CollectionCard> {
        val epochMap = epochs.associateBy { it.schemaName }
        val schemasByType = schemasByType()
        return consentedTypes.flatMap { type ->
            // One card per schema the host maps to this type (a type may
            // produce several, e.g. heart rate + HRV).
            // Questionnaire answers get a value card once answered (the
            // questionnaire itself has its own card to fill it in).
            val schemas = schemasByType[type.id].orEmpty()
                .filter { epochMap[it] != null || !QuestionnaireRegistry.isQuestionnaireSchema(it) }
            schemas.map { schemaName ->
                val epoch = epochMap[schemaName]
                val rows = history[schemaName].orEmpty()
                val numeric = rows.takeIf { it.isNotEmpty() && EpochDisplay.kindOf(it.last()) == EpochDisplay.Kind.NUMBER }
                CollectionCard(
                    schemaName    = schemaName,
                    title         = if (schemas.size == 1) type.name
                                    else OmhSchemas.terminologyFor(schemaName)?.display ?: type.name,
                    value         = epoch?.displayValue ?: "--",
                    unit          = epoch?.unit ?: defaultUnit(schemaName),
                    hasData       = epoch != null,
                    lastUpdatedMs = epoch?.epochStartMs,
                    sparkline     = numeric?.let { r -> downsample(r.map { it.numericValue }) }.orEmpty(),
                    staleAfterMs  = staleAfter(rows.map { it.epochStartMs }, type.samplingSeconds),
                    wide          = (epoch?.displayValue?.contains(" · ") == true) ||
                                    (epoch?.displayValue?.length ?: 0) > 12
                )
            }
        }
    }

    /** A reading is late after 3 typical intervals (study sampling or observed), at least 15 min. */
    private fun staleAfter(times: List<Long>, samplingSeconds: Int?): Long {
        val typical = samplingSeconds?.let { it * 1000L }
            ?: times.takeLast(20).zipWithNext { a, b -> b - a }.sorted().let { d -> d.getOrNull(d.size / 2) }
            ?: return 60 * 60_000L
        return maxOf(typical * 3, 15 * 60_000L)
    }

    // -------------------------------------------------------------------------
    // Consent data type id → schema names: the inverse of the host's
    // MbPulseConfig.schemaToConsentType over every registered schema
    // (built-in + MbPulseConfig.customSchemas).
    // -------------------------------------------------------------------------

    private fun schemasByType(): Map<String, List<String>> {
        val mapper: (String) -> String? = MbPulseLibrary::consentTypeFor
        return OmhSchemas.ALL
            .map { "${it.namespace}:${it.name}" }
            .distinct()
            .mapNotNull { schemaName ->
                runCatching { mapper(schemaName) }.getOrNull()?.let { it to schemaName }
            }
            .groupBy({ it.first }, { it.second })
    }

    /** Unit shown on a card before its first epoch arrives (the unit the epochs will carry). */
    private fun defaultUnit(schemaName: String): String =
        OmhSchemas.definitionFor(schemaName)?.primary
            ?.takeIf { it.kind == FieldKind.QUANTITY }?.unit
            ?: BUILT_IN_UNITS[schemaName].orEmpty()

    private companion object {
        val BUILT_IN_UNITS = mapOf(
            "omh:heart-rate"                       to "beats/min",
            "mbpulse:hrv"                    to "ms",
            "omh:body-temperature"                 to "°C",
            "mbpulse:electrodermal-activity" to "μS"
        )
    }
}
