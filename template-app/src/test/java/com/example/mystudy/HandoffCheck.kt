package com.example.mystudy

import pt.ua.mbpulse.CollectionMode
import pt.ua.mbpulse.CollectionScreenAccess
import pt.ua.mbpulse.MbPulseConfig
import pt.ua.mbpulse.collection.ProcessedEpoch
import pt.ua.mbpulse.collection.SensorSample
import pt.ua.mbpulse.data.model.OmhDataPoint
import pt.ua.mbpulse.data.model.OmhSchemas
import pt.ua.mbpulse.fhir.FhirMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

enum class Level { OK, WARN, FAIL }

enum class Area(val title: String) {
    IDENTITY("App identity and library pin"),
    BACKEND("Backend address and key"),
    KEYS("Pinned public keys"),
    COLLECTION("Collection mode"),
    MAPPING("Schema → consent-type mapping"),
    SCHEMAS("Custom schemas"),
    PROCESSOR("Signal processor on the replay CSV"),
    MANIFEST("Manifest"),
    AUDIT("For the human audit (never fails)")
}

data class Finding(val area: Area, val level: Level, val message: String)

/** The CSV the processor check replays, in [CsvReplaySensorSource]'s format. */
data class ReplaySpec(
    val path: String,
    val timestampColumn: String = "timestamp_ms",
    val timestampMultiplier: Double = 1.0,
    val columnToSignal: (String) -> String? = { it }
)

/**
 * Checks this app against the setup wizard's handoff.json — the structural
 * conditions of the brief's definition of done that a machine can decide.
 * It cannot judge whether the processor's values are scientifically right, or
 * whether code deliberately sends values elsewhere: [Area.AUDIT] lists the
 * lines a person must read.
 */
class HandoffCheck(
    private val handoff: Handoff,
    private val config: MbPulseConfig,
    private val projectDir: File,
    private val replay: ReplaySpec,
    /** Mapped schemas the processor does not emit, with the reason (e.g. Health Connect import). */
    private val notFromProcessor: Map<String, String>,
    private val applicationId: String,
    private val appPackage: String
) {
    private val out = mutableListOf<Finding>()

    private fun ok(area: Area, msg: String) { out += Finding(area, Level.OK, msg) }
    private fun warn(area: Area, msg: String) { out += Finding(area, Level.WARN, msg) }
    private fun fail(area: Area, msg: String) { out += Finding(area, Level.FAIL, msg) }

    fun run(): List<Finding> {
        identity(); backend(); keys(); collection(); mapping(); schemas(); processor(); manifest(); audit()
        return out
    }

    private val builtInNames = OmhSchemas.BUILT_IN.associateBy { "${it.namespace}:${it.name}" }
    private val customByName = config.customSchemas.associateBy { it.schemaName }
    private val stimulusEvent = "${OmhSchemas.STIMULUS_EVENT.namespace}:${OmhSchemas.STIMULUS_EVENT.name}"

    private fun mapped(schema: String): Result<String?> = runCatching { config.schemaToConsentType(schema) }

    // ------------------------------------------------------------------ identity

    private fun identity() {
        val a = Area.IDENTITY
        if (handoff.format != Handoff.FORMAT) fail(a, "handoff.json format is '${handoff.format}', expected '${Handoff.FORMAT}' — regenerate it in the wizard")
        if (handoff.packageId == applicationId) ok(a, "applicationId $applicationId")
        else fail(a, "applicationId is $applicationId, the study expects ${handoff.packageId} — rename namespace, applicationId and the source packages together")

        val catalogue = File(projectDir, "gradle/libs.versions.toml")
        val pinned = catalogue.takeIf { it.exists() }?.readText()
            ?.let { Regex("""(?m)^mbpulse\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        when {
            pinned == null -> fail(a, "no `mbpulse = \"…\"` in gradle/libs.versions.toml")
            pinned.contains("SNAPSHOT") -> fail(a, "the library is pinned to $pinned — a study is built on a release, never a snapshot")
            pinned != handoff.libraryVersion -> fail(a, "the library is pinned to $pinned, the study's brief to ${handoff.libraryVersion}")
            else -> ok(a, "library pinned to $pinned")
        }

        // Which library is on the classpath: the published AAR, or a source build?
        val location = MbPulseConfig::class.java.protectionDomain?.codeSource?.location?.toString().orEmpty()
        val fromSource = "mbpulse/build" in location.replace('\\', '/')
        val inRepository = File(projectDir, "../mbpulse/build.gradle").exists()
        when {
            !fromSource -> ok(a, "built against the published library")
            inRepository -> warn(a, "built against the library SOURCE (-Pmbpulse.librarySource) — fine for template development, never for a study")
            else -> fail(a, "built against the library source (-Pmbpulse.librarySource) — a study app builds on the pinned release")
        }
    }

    // ------------------------------------------------------------------ backend

    private fun backend() {
        val a = Area.BACKEND
        fun check(name: String, value: String, expectedSha: String?) {
            when {
                value.isBlank() -> fail(a, "$name is empty — local.properties lacks it (the wizard writes it in step 4)")
                expectedSha == null -> warn(a, "$name: the wizard had no value to compare (set the credentials in step 1, write the brief again)")
                sha256Hex(value) == expectedSha -> ok(a, "$name is the study's backend")
                else -> fail(a, "$name is not the one the study was provisioned with — use the wizard's local.properties, never a hardcoded value")
            }
        }
        check("supabaseUrl", config.supabaseUrl, handoff.supabaseUrlSha256)
        check("supabaseKey", config.supabaseKey, handoff.supabaseKeySha256)
    }

    // ------------------------------------------------------------------ keys

    private fun keys() {
        val a = Area.KEYS
        fun pin(name: String, expected: String?, actual: String?) = when {
            expected == null -> warn(a, "$name: the wizard had no key — generate keys (step 2) and write the brief again")
            actual == expected -> ok(a, "$name pinned")
            actual == null -> fail(a, "$name is not set — paste it from the brief")
            else -> fail(a, "$name differs from the study's — paste it exactly from the brief")
        }
        pin("studySigningPublicKeyB64", handoff.studySigningPublicKeyB64, config.studySigningPublicKeyB64)
        pin("deletionReceiptPublicKeyB64", handoff.deletionReceiptPublicKeyB64, config.deletionReceiptPublicKeyB64)
    }

    // ------------------------------------------------------------------ collection

    private fun collection() {
        val a = Area.COLLECTION
        if (config.collectionMode.name == handoff.collectionMode) ok(a, "collectionMode ${config.collectionMode}")
        else fail(a, "collectionMode is ${config.collectionMode}, the study's is ${handoff.collectionMode}")

        val expected = when {
            config.collectionMode == CollectionMode.SESSION -> CollectionScreenAccess.ALWAYS
            handoff.collectionScreenInRelease -> CollectionScreenAccess.ALWAYS
            else -> CollectionScreenAccess.DEBUG_BUILDS_ONLY
        }
        if (config.collectionScreenAccess == expected) ok(a, "collection screen: $expected")
        else fail(a, "collection screen is ${config.collectionScreenAccess}, the study's is $expected")
    }

    // ------------------------------------------------------------------ mapping

    private fun mapping() {
        val a = Area.MAPPING
        handoff.schemaToConsentType.forEach { (schema, type) ->
            mapped(schema).fold(
                onSuccess = { got ->
                    when (got) {
                        type -> ok(a, "$schema → $type")
                        null -> fail(a, "$schema → null, the study maps it to '$type' (null = no consent type: dropped, never collected)")
                        else -> fail(a, "$schema → '$got', the study maps it to '$type' (wrong consent type: dropped or filed under the wrong consent)")
                    }
                },
                onFailure = { fail(a, "schemaToConsentType throws for $schema: $it") }
            )
        }
        val probe = "handoffcheck:not-a-study-schema"
        mapped(probe).onSuccess { if (it != null) fail(a, "an unknown schema maps to '$it' — end the mapping with `else -> null`") }

        // A schema the app knows but the study does not list must map to nothing.
        (builtInNames.keys + customByName.keys - handoff.schemaToConsentType.keys).sorted().forEach { schema ->
            mapped(schema).onSuccess { got ->
                if (got != null) fail(a, "$schema → '$got', but the study does not list $schema — remove it, or add it to the study in the wizard")
            }
        }
        val types = handoff.schemaToConsentType.values.toSet()
        types.filter { it !in handoff.dataTypeIds }.forEach {
            fail(a, "the handoff maps to '$it', which is not one of the study's data types — regenerate the brief")
        }

        val supported = config.supportedDataTypeIds
        if (supported == handoff.supportedDataTypeIds) ok(a, "supportedDataTypeIds ${supported.sorted()}")
        else fail(a, "supportedDataTypeIds is ${supported?.sorted()}, the study's is ${handoff.supportedDataTypeIds.sorted()}")
    }

    // ------------------------------------------------------------------ custom schemas

    private fun schemas() {
        val a = Area.SCHEMAS
        val expected = handoff.customSchemas.associateBy { it.id }
        expected.values.forEach { e ->
            val d = customByName[e.id]
            if (d == null) { fail(a, "${e.id} is not in customSchemas — declare it exactly as the brief's CustomSchemas.kt"); return@forEach }
            val problems = buildList {
                if (d.id.version != e.version) add("version ${d.id.version} ≠ ${e.version}")
                if (d.isEvent != e.isEvent) add("isEvent ${d.isEvent} ≠ ${e.isEvent}")
                val got = d.fields.map { Triple(it.name, it.kind.name, if (it.kind.name == "QUANTITY") it.unit else null) to it.values }
                val want = e.fields.map { Triple(it.name, it.kind, if (it.kind == "QUANTITY") it.unit else null) to it.values }
                if (got != want) add("fields ${d.fields.map { "${it.name}:${it.kind}" }} ≠ ${e.fields.map { "${it.name}:${it.kind}" }} (names, kinds, order, units and enum values must match)")
            }
            if (problems.isEmpty()) ok(a, "${e.id} matches the study") else fail(a, "${e.id}: ${problems.joinToString("; ")}")
        }
        config.customSchemas.forEach { d ->
            val name = d.schemaName
            if (name in handoff.templateSchemas) ok(a, "$name: the template's, used by this study")
            else if (name !in expected) {
                fail(a, "$name is declared but the study has no such schema — remove it (and its screen), or define it in the wizard")
            }
            val shape = buildList {
                if (d.fields.isEmpty()) add("no fields")
                if (d.fields.map { it.name }.toSet().size != d.fields.size) add("duplicate field names")
                d.fields.filter { it.kind.name == "QUANTITY" && it.unit.isNullOrBlank() }.forEach { add("${it.name}: QUANTITY without a unit") }
                d.fields.filter { it.kind.name == "ENUM" && it.values.size < 2 }.forEach { add("${it.name}: ENUM with fewer than two values") }
            }
            if (shape.isNotEmpty()) fail(a, "$name: ${shape.joinToString("; ")}")
        }
        handoff.schemaToConsentType.keys.filter { it !in builtInNames && it !in customByName }.forEach {
            fail(a, "$it is mapped but neither built in nor in customSchemas — its points cannot be stored")
        }
        if (config.customSchemas.isEmpty() && expected.isEmpty()) ok(a, "no custom schemas")
    }

    // ------------------------------------------------------------------ processor

    private fun processor() {
        val a = Area.PROCESSOR
        OmhSchemas.register(config.customSchemas)

        val csv = File(projectDir, replay.path)
        if (!csv.exists()) { fail(a, "replay CSV ${replay.path} does not exist"); return }
        val samples: List<SensorSample> = try {
            csv.bufferedReader().use {
                CsvReplaySensorSource.readSamples(it, replay.path, replay.timestampColumn, replay.timestampMultiplier, replay.columnToSignal).toList()
            }
        } catch (e: Exception) { fail(a, "cannot read ${replay.path}: ${e.message}"); return }
        if (samples.isEmpty()) { fail(a, "${replay.path} has no data rows"); return }

        val processor = try { config.signalProcessor() } catch (e: Exception) { fail(a, "signalProcessor() throws: $e"); return }
        if (runCatching { config.signalProcessor() }.getOrNull() === processor) {
            fail(a, "signalProcessor returns the same instance each time — it must be a factory (`{ MyProcessor() }`): processors keep state")
        }

        val epochs = mutableListOf<ProcessedEpoch>()
        for ((i, sample) in samples.withIndex()) {
            try { epochs += processor.feedAll(sample) } catch (e: Exception) {
                fail(a, "the processor throws on sample ${i + 1} (t=${sample.timestampMs}): $e"); return
            }
        }
        val points = epochs.flatMap { e -> e.dataPoints.map { e to it } }
        val firstMs = samples.first().timestampMs
        val lastMs = samples.last().timestampMs
        ok(a, "${samples.size} samples (${replay.path}) → ${epochs.size} epochs, ${points.size} data points")
        if (points.isEmpty()) { fail(a, "the processor emitted nothing for the whole replay"); return }

        fun name(p: OmhDataPoint) = "${p.header.schemaId.namespace}:${p.header.schemaId.name}"
        val emitted = points.map { name(it.second) }.toSortedSet()

        // Coverage: every schema the study expects from the processor appears.
        val expected = handoff.schemaToConsentType.keys - handoff.templateSchemas - handoff.eventSchemas - notFromProcessor.keys
        expected.sorted().forEach { s ->
            if (s in emitted) ok(a, "emits $s")
            else fail(a, "never emits $s on the replay — the study collects it (if it comes from elsewhere, e.g. Health Connect, list it in NOT_FROM_PROCESSOR with the reason)")
        }
        notFromProcessor.forEach { (s, why) -> warn(a, "$s is not expected from the processor: $why") }
        handoff.eventSchemas.filter { it !in emitted }.forEach {
            warn(a, "event $it was not triggered by the replay — check it with a CSV that contains the marker")
        }

        for (s in emitted) {
            val ofSchema = points.filter { name(it.second) == s }
            mapped(s).onSuccess { if (it == null) fail(a, "emits $s, which maps to no consent type — its points are dropped, never collected") }
            val registered = builtInNames[s]?.version ?: customByName[s]?.id?.version
            if (registered == null) { fail(a, "emits $s, which is neither built in nor declared in customSchemas"); continue }
            ofSchema.map { it.second.header.schemaId.version }.toSet().filter { it != registered }.forEach {
                fail(a, "$s points carry version $it, the registered schema is $registered")
            }
            val isEvent = customByName[s]?.isEvent ?: (s == stimulusEvent)
            var badFrame = 0; var outside = 0; var reversed = 0; var noValue = 0; var unmappable = 0
            var example: String? = null
            val primary = mutableListOf<Double>()
            for ((_, p) in ofSchema) {
                val tf = p.body.effectiveTimeFrame
                if (tf.isInstant != isEvent) badFrame++
                val times = listOfNotNull(tf.dateTime, tf.timeInterval?.startDateTime, tf.timeInterval?.endDateTime)
                    .mapNotNull { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                if (times.any { it < firstMs - HOUR_MS || it > lastMs + HOUR_MS }) {
                    outside++; example = example ?: times.first().toString()
                }
                if (times.size == 2 && times[1] < times[0]) reversed++
                val fhir = runCatching { Json.parseToJsonElement(FhirMapper.toObservationJson(p, "handoff-check")).jsonObject }
                fhir.onFailure { unmappable++ }.onSuccess { o ->
                    if (o.keys.none { it.startsWith("value") || it == "component" }) noValue++
                    primaryNumber(o)?.let { primary += it }
                }
            }
            val n = ofSchema.size
            if (badFrame > 0) fail(a, "$s: $badFrame/$n points have ${if (isEvent) "an interval — an event needs OmhEffectiveTimeFrame.instant(ms)" else "an instant — a measurement needs a time interval"}")
            if (outside > 0) fail(a, "$s: $outside/$n points are timed outside the replayed samples (e.g. $example ms vs $firstMs..$lastMs) — stamp points with the samples' time, not the clock or a wrong unit")
            if (reversed > 0) fail(a, "$s: $reversed/$n intervals end before they start")
            if (unmappable > 0) fail(a, "$s: $unmappable/$n points cannot be serialised (a NaN or infinite number?)")
            if (noValue > 0) fail(a, "$s: $noValue/$n points carry no value")
            if (primary.size >= 3 && primary.toSet().size == 1) {
                warn(a, "$s: every one of ${primary.size} values is ${primary.first()} — a constant often means an empty buffer emitting a default")
            }
            if (badFrame + outside + reversed + unmappable + noValue == 0) ok(a, "$s: $n points, well-formed")
        }

        // The backend keeps one record per participant, epoch start and schema.
        points.groupBy { it.first.epochStartMs to name(it.second) }.filterValues { it.size > 1 }.entries
            .take(3).forEach { (key, list) ->
                fail(a, "${list.size} ${key.second} points share epoch start ${key.first} — the backend keeps only one; give each window its own start")
            }
        val ids = points.map { it.second.header.id }
        if (ids.toSet().size != ids.size) warn(a, "some data points share a header id — give each point a fresh UUID")
    }

    private fun primaryNumber(o: JsonObject): Double? {
        (o["valueQuantity"] as? JsonObject)?.get("value")?.let { return (it as? JsonPrimitive)?.doubleOrNull }
        (o["valueInteger"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        (o["valueDecimal"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ manifest

    private fun manifest() {
        val a = Area.MANIFEST
        val file = File(projectDir, "src/main/AndroidManifest.xml")
        if (!file.exists()) { fail(a, "src/main/AndroidManifest.xml not found"); return }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        fun Element.android(attr: String) = getAttributeNS(ANDROID_NS, attr)
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        val permissions = elements("uses-permission").map { it.android("name") }.toSet()

        val application = elements("application").firstOrNull()
        val appName = application?.android("name").orEmpty()
        val appClass = when {
            appName.isEmpty() -> null
            appName.startsWith(".") -> appPackage + appName
            !appName.contains('.') -> "$appPackage.$appName"
            else -> appName
        }?.let { runCatching { Class.forName(it) }.getOrNull() }
        when {
            appClass == null -> fail(a, "<application android:name> ('$appName') is not a class of this app — MbPulseLibrary.init never runs")
            !android.app.Application::class.java.isAssignableFrom(appClass) -> fail(a, "$appName is not an Application")
            !androidx.work.Configuration.Provider::class.java.isAssignableFrom(appClass) ->
                fail(a, "$appName does not implement Configuration.Provider — the library's workers need the Hilt worker factory")
            else -> ok(a, "Application ${appClass.simpleName} (Configuration.Provider)")
        }
        val initializerRemoved = elements("meta-data").any {
            it.android("name") == "androidx.work.WorkManagerInitializer" && it.getAttributeNS(TOOLS_NS, "node") == "remove"
        }
        if (initializerRemoved) ok(a, "WorkManager's default initializer removed")
        else fail(a, "WorkManager's default initializer is not removed (tools:node=\"remove\") — the first worker fails")
        if (application?.android("allowBackup") != "false") warn(a, "android:allowBackup is not false — keep it false so no app data is cloud-backed-up")

        val prerequisite = when (config.collectionMode) {
            CollectionMode.PASSIVE_CONNECTED_DEVICE -> CONNECTED_DEVICE_PREREQUISITES
            CollectionMode.PASSIVE_HEALTH -> HEALTH_PREREQUISITES
            CollectionMode.SESSION -> null
        }
        when {
            prerequisite == null -> ok(a, "SESSION mode needs no foreground-service prerequisite")
            permissions.any { p -> p in prerequisite || (config.collectionMode == CollectionMode.PASSIVE_HEALTH && p.startsWith("android.permission.health.READ_")) } ->
                ok(a, "${config.collectionMode} prerequisite declared (${permissions.filter { it in prerequisite }.joinToString { it.substringAfterLast('.') }})")
            else -> fail(a, "${config.collectionMode} needs one of ${prerequisite.joinToString { it.substringAfterLast('.') }} declared (Android 14+), or collection never starts")
        }
        if (config.deviceSelection != null) {
            if (BLUETOOTH_CONNECT in permissions) ok(a, "deviceSelection: BLUETOOTH_CONNECT declared")
            else fail(a, "deviceSelection is set but BLUETOOTH_CONNECT is not declared — the device step cannot ask for it")
            // Android 11 and lower (minSdk 26) have no BLUETOOTH_CONNECT.
            val legacyMissing = LEGACY_BLUETOOTH - permissions
            if (legacyMissing.isEmpty()) ok(a, "deviceSelection: BLUETOOTH / BLUETOOTH_ADMIN declared for Android 11 and lower")
            else fail(a, "deviceSelection is set but ${legacyMissing.joinToString { it.substringAfterLast('.') }} " +
                "(android:maxSdkVersion=\"30\") is not declared — on Android 11 and lower the device step and connectGatt fail")
        }

        val sources = config.healthConnectSources
        if (sources.isNotEmpty()) {
            sources.forEach { s ->
                val missing = s.permissions - permissions
                if (missing.isEmpty()) ok(a, "Health Connect source ${s.id}: permissions declared")
                else fail(a, "Health Connect source ${s.id}: ${missing.joinToString()} not declared — the request silently fails")
            }
            if (config.healthConnectImportDays > 30 && HC_HISTORY !in permissions) {
                fail(a, "healthConnectImportDays is ${config.healthConnectImportDays} (> 30) but READ_HEALTH_DATA_HISTORY is not declared")
            }
            if (config.healthConnectBackgroundImport != null && HC_BACKGROUND !in permissions) {
                fail(a, "background import is on but READ_HEALTH_DATA_IN_BACKGROUND is not declared")
            }
            val actions = (elements("activity") + elements("activity-alias")).flatMap { el ->
                el.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as Element).android("name") } }
            }
            if (RATIONALE_ACTION !in actions || VIEW_USAGE_ACTION !in actions) {
                fail(a, "Health Connect needs the privacy-rationale activity and its VIEW_PERMISSION_USAGE alias (see the template manifest) — without them permission requests fail on Android 14+")
            } else ok(a, "Health Connect rationale activity and alias declared")
        }
    }

    // ------------------------------------------------------------------ audit

    private fun audit() {
        val a = Area.AUDIT
        val sourceRoot = File(projectDir, "src/main/java")
        val implementing = Regex("""[:,]\s*(SensorSource|SignalProcessor)\b""")
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" && implementing.containsMatchIn(it.readText()) }.toList()
        ok(a, "read every line of: ${files.joinToString { it.relativeTo(projectDir).path.replace('\\', '/') }.ifEmpty { "(no SensorSource/SignalProcessor found)" }}")
        files.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                SINKS.firstOrNull { it.containsMatchIn(code) }?.let {
                    warn(a, "${f.relativeTo(projectDir).path.replace('\\', '/')}:${i + 1}: `${code.take(120)}` — does it write a measured value anywhere?")
                }
            }
        }
        warn(a, "these two components see every value before it is encrypted: no check here replaces reading them")
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val TOOLS_NS = "http://schemas.android.com/tools"
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private val LEGACY_BLUETOOTH = setOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")
        private const val HC_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        private const val HC_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        private const val VIEW_USAGE_ACTION = "android.intent.action.VIEW_PERMISSION_USAGE"

        /** Android 14 `connectedDevice` foreground-service prerequisites. */
        private val CONNECTED_DEVICE_PREREQUISITES = setOf(
            BLUETOOTH_CONNECT, "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_SCAN",
            "android.permission.CHANGE_NETWORK_STATE", "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE", "android.permission.NFC",
            "android.permission.TRANSMIT_IR", "android.permission.UWB_RANGING"
        )

        /** Android 14 `health` prerequisites (+ any Health Connect read permission on 16+). */
        private val HEALTH_PREREQUISITES = setOf(
            "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.HIGH_SAMPLING_RATE_SENSORS"
        )

        /** Calls that can move a value out of the app: logs, files, network, analytics. */
        private val SINKS = listOf(
            Regex("""\bLog\.[vdiwe]\("""), Regex("""\bprintln\("""), Regex("""\bprint\("""),
            Regex("""FileOutputStream|openFileOutput|FileWriter|\.writeText\(|\.appendText\(|\bFile\("""),
            Regex("""HttpURLConnection|OkHttp|Retrofit|\bURL\(|Socket\(|HttpClient"""),
            Regex("""SharedPreferences|DataStore|ContentResolver|sendBroadcast|Firebase|Analytics|Crashlytics|Sentry""")
        )

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
