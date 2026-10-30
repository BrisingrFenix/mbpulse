package pt.ua.mbpulse

import pt.ua.mbpulse.data.model.FieldKind
import pt.ua.mbpulse.data.model.OmhSchemas
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Validates an [MbPulseConfig] at [MbPulseLibrary.init] time so that
 * misconfiguration fails loudly with actionable messages instead of surfacing
 * later as silent misbehaviour (dropped data, dead network calls, an import
 * button that does nothing).
 *
 * Pure JVM — no Android dependencies — so it is unit-testable. All problems
 * are collected and reported together (not first-failure) so a whole config
 * can be fixed in one pass.
 */
internal object MbPulseConfigValidator {

    data class Result(val errors: List<String>, val warnings: List<String>)

    fun validate(config: MbPulseConfig): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // -- Backend credentials -------------------------------------------
        if (config.supabaseUrl.isBlank()) {
            errors += "supabaseUrl is blank — set mbpulse.supabaseUrl in local.properties " +
                "(see backend/README.md, step 5)."
        } else if (!config.supabaseUrl.startsWith("https://") &&
            !config.supabaseUrl.startsWith("http://")
        ) {
            errors += "supabaseUrl '${config.supabaseUrl}' must start with https:// " +
                "(copy the Project URL from Supabase Settings → API)."
        }
        if (config.supabaseKey.isBlank()) {
            errors += "supabaseKey is blank — set mbpulse.supabaseKey in local.properties " +
                "(the anon/publishable key from Supabase Settings → API)."
        }

        // -- Consent mapping vs. the schema registry ------------------------
        // The mapper is a host lambda; probe it with every registered schema so
        // a crash or an inconsistent mapping is caught at init, not mid-session.
        // Unmapped schemas are dropped (fail closed, ConsentFilter), so a custom
        // schema — declared because the app emits it — without a mapping is
        // never collected, and a mapper that maps nothing collects nothing.
        val supported = config.supportedDataTypeIds
        val customIds = config.customSchemas.map { it.id }
        var anyMapped = false
        for (schema in OmhSchemas.BUILT_IN + customIds) {
            val schemaName = "${schema.namespace}:${schema.name}"
            val mapped = try {
                config.schemaToConsentType(schemaName)
            } catch (e: Exception) {
                errors += "schemaToConsentType threw ${e::class.simpleName} for '$schemaName' — " +
                    "the mapper must handle every schema (return null for ones this study ignores)."
                continue
            }
            if (mapped != null) anyMapped = true
            if (mapped == null && schema in customIds) {
                warnings += "customSchemas declares '$schemaName', but schemaToConsentType maps it " +
                    "to no data type — its data points are dropped. Map it to the study's data-type id."
            }
            if (mapped != null && supported != null && mapped !in supported) {
                warnings += "schemaToConsentType maps '$schemaName' → '$mapped', but " +
                    "supportedDataTypeIds does not contain '$mapped'. Study gating and " +
                    "collection will disagree — add it to supportedDataTypeIds or fix the mapping."
            }
        }
        if (!anyMapped) {
            warnings += "schemaToConsentType maps no schema to a data type — nothing will be " +
                "collected (unmapped schemas are dropped). Map each schema the app emits to " +
                "the study's data-type id."
        }

        // -- Custom (host-defined) schemas -----------------------------------
        validateCustomSchemas(config, errors, warnings)

        // -- Health Connect sources -----------------------------------------
        val ids = config.healthConnectSources.map { it.id }
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors += "healthConnectSources contains duplicate id(s): $duplicates — " +
                "each source needs a unique id."
        }
        config.healthConnectSources.forEach { source ->
            if (source.id.isBlank()) {
                errors += "A HealthConnectSource has a blank id — give it a stable id like \"heart-rate\"."
            }
            if (source.permissions.isEmpty()) {
                warnings += "HealthConnectSource '${source.id}' declares no permissions — " +
                    "reads will fail unless the record type needs none (unlikely). Declare " +
                    "HealthPermission.getReadPermission(<RecordType>::class)."
            }
        }

        config.healthConnectBackgroundImport?.let { interval ->
            if (interval < java.time.Duration.ofMinutes(15)) {
                errors += "healthConnectBackgroundImport = $interval is below WorkManager's 15-minute " +
                    "minimum for periodic work — use at least 15 minutes (or null to disable)."
            }
            config.healthConnectSources.filter { it.recordTypes.isEmpty() }.forEach { source ->
                warnings += "HealthConnectSource '${source.id}' declares no recordTypes — the background " +
                    "import re-reads only a recent window for it and misses data synced later. Set " +
                    "recordTypes (e.g. setOf(HeartRateRecord::class)) to enable change tracking."
            }
        }

        if (config.syncInterval < java.time.Duration.ofMinutes(15)) {
            errors += "syncInterval = ${config.syncInterval} is below WorkManager's 15-minute minimum " +
                "for periodic work — use at least 15 minutes."
        }

        if (config.healthConnectImportDays !in 1..MbPulseConfig.MAX_HEALTH_CONNECT_IMPORT_DAYS) {
            errors += "healthConnectImportDays = ${config.healthConnectImportDays} is out of range — " +
                "use 1 to ${MbPulseConfig.MAX_HEALTH_CONNECT_IMPORT_DAYS} days."
        } else if (config.healthConnectImportDays > 30 && config.healthConnectSources.isNotEmpty()) {
            warnings += "healthConnectImportDays = ${config.healthConnectImportDays}: Health Connect serves " +
                "data older than 30 days before the first grant only with " +
                "android.permission.health.READ_HEALTH_DATA_HISTORY — declare it in the manifest."
        }

        // -- Device selection ---------------------------------------------------
        config.deviceSelection?.namePattern?.let { pattern ->
            if (runCatching { Regex(pattern) }.isFailure) {
                errors += "deviceSelection.namePattern '$pattern' is not a valid regular expression."
            }
        }
        if (config.deviceSelection != null && config.collectionMode == CollectionMode.PASSIVE_HEALTH) {
            warnings += "deviceSelection is set with collectionMode = PASSIVE_HEALTH — a Bluetooth wearable " +
                "normally uses PASSIVE_CONNECTED_DEVICE (foreground-service type connectedDevice)."
        }

        // -- Deletion-receipt verification key --------------------------------
        config.deletionReceiptPublicKeyB64?.let { keyB64 ->
            try {
                val der = Base64.getDecoder().decode(keyB64.trim())
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            } catch (e: Exception) {
                errors += "deletionReceiptPublicKeyB64 is not a base64-encoded DER X.509 (SPKI) " +
                    "RSA public key (${e::class.simpleName}) — paste the exact " +
                    "deletionReceiptPublicKeyB64 value from AGENT_BRIEF.md (or printed by backend/keygen.sh)."
            }
        }

        // -- Study-definition signing key ----------------------------------------
        val studyKey = config.studySigningPublicKeyB64
        if (studyKey == null) {
            warnings += "studySigningPublicKeyB64 is not set — study definitions from the backend are " +
                "NOT verified, so whoever controls the backend could change the PI key or the consent " +
                "wording. Set it to the value the setup wizard printed in AGENT_BRIEF.md."
        } else {
            try {
                KeyFactory.getInstance("RSA").generatePublic(
                    X509EncodedKeySpec(Base64.getDecoder().decode(studyKey.trim()))
                )
            } catch (e: Exception) {
                errors += "studySigningPublicKeyB64 is not a base64-encoded DER X.509 (SPKI) RSA public " +
                    "key (${e::class.simpleName}) — paste the exact value from AGENT_BRIEF.md."
            }
            if (studyKey.trim() == config.deletionReceiptPublicKeyB64?.trim()) {
                errors += "studySigningPublicKeyB64 equals deletionReceiptPublicKeyB64 — they are " +
                    "different keys (the receipt key lives on the server, the study key with the PI)."
            }
        }

        // -- Collection mode vs. collection screen -----------------------------
        val access = config.collectionScreenAccess
        if (config.collectionMode == CollectionMode.SESSION && access != CollectionScreenAccess.ALWAYS) {
            errors += "collectionMode = SESSION but collectionScreen = $access — session collection " +
                "is started and stopped on the collection screen, so it must stay reachable " +
                "(use ALWAYS, or a PASSIVE_* mode for collection without the screen)."
        }
        if (config.collectionMode.isPassive && access == CollectionScreenAccess.ALWAYS) {
            warnings += "collectionMode = ${config.collectionMode} with collectionScreen = ALWAYS — " +
                "participants of the release app can stop passive collection by hand. Use " +
                "DEBUG_BUILDS_ONLY (the default) to keep the screen for testing only."
        }

        return Result(errors, warnings)
    }

    private val SCHEMA_NAME_PART = Regex("^[a-z][a-z0-9-]*$")
    private val FIELD_NAME = Regex("^[a-z][a-z0-9_]*$")
    /** Body keys every schema shares; any other name is free (custom bodies are read by schema id). */
    private val RESERVED_FIELDS = setOf("effective_time_frame", "extra")

    private fun validateCustomSchemas(
        config: MbPulseConfig,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val builtIn = OmhSchemas.BUILT_IN.mapTo(HashSet()) { "${it.namespace}:${it.name}" }
        val seen = HashSet<String>()
        for (definition in config.customSchemas) {
            val name = definition.schemaName
            val where = "customSchemas '$name'"
            if (!SCHEMA_NAME_PART.matches(definition.id.namespace) || !SCHEMA_NAME_PART.matches(definition.id.name)) {
                errors += "$where: namespace and name must be lowercase kebab-case (e.g. mbpulse:pain-rating)."
            }
            if (name in builtIn) {
                errors += "$where is built into the library — remove it from customSchemas (use OmhSchemas)."
            }
            if (definition.id.namespace == pt.ua.mbpulse.data.model.Questionnaire.NAMESPACE) {
                errors += "$where: the namespace '${definition.id.namespace}' is reserved for backend " +
                    "questionnaires (published in the setup wizard) — use your own namespace."
            }
            if (!seen.add(name)) errors += "$where is declared more than once."
            if (definition.fields.isEmpty()) {
                errors += "$where has no fields — declare at least the primary value."
                continue
            }
            val fieldNames = HashSet<String>()
            definition.fields.forEach { field ->
                val f = "$where field '${field.name}'"
                if (!FIELD_NAME.matches(field.name)) errors += "$f: names must be snake_case (e.g. emg_rms)."
                if (field.name in RESERVED_FIELDS) {
                    errors += "$f is reserved (${RESERVED_FIELDS.joinToString()}) — rename it."
                }
                if (!fieldNames.add(field.name)) errors += "$f is declared twice."
                when (field.kind) {
                    FieldKind.QUANTITY -> if (field.unit.isNullOrBlank()) {
                        errors += "$f is a QUANTITY without a unit — set unit (e.g. \"mV\") or use NUMBER."
                    }
                    FieldKind.ENUM -> if (field.values.distinct().size < 2) {
                        errors += "$f is an ENUM with fewer than two values."
                    }
                    else -> Unit
                }
                if (field.kind != FieldKind.QUANTITY && field.unit != null) {
                    warnings += "$f has a unit but is ${field.kind} — the unit is ignored (only QUANTITY stores one)."
                }
            }
        }
    }
}
