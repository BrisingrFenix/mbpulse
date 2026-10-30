package pt.ua.mbpulse.data.repository

/**
 * Compares the app's schema → data-type mapping (`MbPulseConfig.schemaToConsentType`,
 * via `MbPulseLibrary.consentTypeFor`) with the study's server-side copy
 * (`StudyConfig."schemaDataTypes"`, set by `seed_study()`). The wizard
 * generates both from one form, but nothing else keeps them equal: the app's
 * copy decides consent filtering, the server's decides retention.
 *
 * Pure (no Android) — called on every study load, logged under
 * [SupabaseMeasurementRepository]'s `SchemaMapping` tag.
 */
internal object SchemaMappingCheck {

    /**
     * Every disagreement, one message each; empty when the copies agree.
     *
     * @param server     the study's `schemaDataTypes`, or null when it has none.
     * @param appSchemas schemas the app knows (`OmhSchemas.ALL`, `"namespace:name"`).
     * @param appTypeOf  the app's mapping; null = no data type, dropped.
     */
    fun differences(
        server: Map<String, String>?,
        appSchemas: Collection<String>,
        appTypeOf: (String) -> String?
    ): List<String> {
        if (server == null) {
            return listOf("the study has no schemaDataTypes — the server keeps every record for " +
                "the study's longest retention period, whatever the app maps it to")
        }
        val out = mutableListOf<String>()
        for (schema in (server.keys + appSchemas).toSortedSet()) {
            val serverType = server[schema]
            val appType = runCatching { appTypeOf(schema) }.getOrElse {
                out += "$schema: the app's schemaToConsentType throws (${it::class.simpleName})"
                continue
            }
            when {
                serverType == appType -> Unit
                serverType == null ->
                    out += "$schema: the app files it under consent type '$appType', the study maps " +
                        "it to nothing — retention falls back to the study's longest period"
                appType == null ->
                    out += "$schema: the study maps it to '$serverType', the app maps it to nothing — " +
                        "the app drops it, so it is never collected"
                else ->
                    out += "$schema: the app files it under consent type '$appType', the study " +
                        "under '$serverType' — consent and retention follow different types"
            }
        }
        return out
    }
}
