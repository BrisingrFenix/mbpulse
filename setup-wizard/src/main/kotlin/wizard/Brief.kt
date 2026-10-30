package wizard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Renders AGENT_BRIEF.md — the handoff document from the researcher's wizard
 * choices to the AI agent that builds the app. Contains no secrets (the join
 * code deliberately never appears here).
 */
object Brief {

    /** `mbpulse:pain-rating` → `PAIN_RATING` (Kotlin constant for a custom schema). */
    internal fun constName(schemaId: String) =
        schemaId.substringAfter(':').uppercase().replace('-', '_')

    /**
     * Custom schemas the template itself declares (schema id → the Kotlin
     * expression in the template). A study that maps one keeps that code
     * instead of getting a generated definition.
     */
    internal val TEMPLATE_SCHEMAS = mapOf("mystudy:symptom-log" to "SymptomLog.schema")

    private val RESERVED_MODULES = setOf("template-app", "sample-app", "mbpulse", "setup-wizard", "backend", "frontend", "docs")

    /**
     * Where the study app is created: the researcher's choice, else a sibling
     * of this repository (`../pain-study-app`) — its own Gradle build, its own
     * git repository. Forward slashes, no trailing slash.
     */
    internal fun appFolder(choice: String, module: String): String =
        choice.trim().replace('\\', '/').trimEnd('/').ifEmpty { "../$module" }

    /** "Pain Study" → `pain-study-app`: the new app's folder name (never the template itself). */
    internal fun moduleName(appName: String): String {
        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "study" }
        val name = if (slug.endsWith("app")) slug else "$slug-app"
        return if (name in RESERVED_MODULES) "$name-study" else name
    }

    private fun kotlinString(v: String) =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    /** Kotlin `SchemaDefinition` for one wizard custom schema (`app.customSchemas[i]`). */
    internal fun definitionKotlin(c: JsonObject): String {
        fun s(key: String) = c[key]?.jsonPrimitive?.content.orEmpty()
        val (namespace, name) = s("id").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val isEvent = s("role") == "event"
        val fields = c["fields"]?.jsonArray.orEmpty().joinToString(",\n") { el ->
            val f = el.jsonObject
            fun fs(key: String) = f[key]?.jsonPrimitive?.content.orEmpty()
            val args = buildList {
                add(kotlinString(fs("name")))
                add("FieldKind.${fs("kind").uppercase().ifEmpty { "NUMBER" }}")
                if (fs("kind") == "quantity") add("unit = ${kotlinString(fs("unit"))}")
                if (fs("kind") == "enum") add("values = listOf(" +
                    f["values"]?.jsonArray.orEmpty().joinToString(", ") { kotlinString(it.jsonPrimitive.content) } + ")")
            }
            "        SchemaField(${args.joinToString(", ")})"
        }
        val loinc = s("loinc").ifEmpty { null }
        return """
val ${constName(s("id"))} = SchemaDefinition(
    id = OmhSchemaId(${kotlinString(namespace)}, ${kotlinString(name)}, ${kotlinString(s("version").ifEmpty { "1.0" })}),
    terminology = SchemaTerminology(
        loincCode = ${loinc?.let(::kotlinString) ?: "null"},
        display   = ${kotlinString(s("description"))},
        category  = ${kotlinString(if (isEvent) "procedure" else "exam")},  // TODO(agent): FHIR observation-category — verify
        ucumUnit  = null,               // null → library Ucum table / raw unit
        ucumCode  = null
    ),
    fields = listOf(
$fields
    )${if (isEvent) ",\n    isEvent = true" else ""}
)""".trim()
    }

    /** schema → consent-type id, from the per-type schema selection (`app.dataTypeSchemas`). Shared with [Handoff]. */
    internal fun mapping(app: JsonObject): LinkedHashMap<String, String> {
        val mapping = linkedMapOf<String, String>()
        app["dataTypeSchemas"]?.jsonObject?.forEach { (typeId, schemas) ->
            schemas.jsonArray.forEach { schema -> mapping[schema.jsonPrimitive.content] = typeId }
        }
        return mapping
    }

    /** Data types some app schema feeds — the generated `supportedDataTypeIds`. */
    internal fun supportedDataTypeIds(app: JsonObject): Set<String> =
        app["dataTypeSchemas"]?.jsonObject.orEmpty().filterValues { it.jsonArray.isNotEmpty() }.keys

    fun build(
        study: JsonObject,
        app: JsonObject,
        receiptPublicKeyB64: String? = null,
        studySigningPublicKeyB64: String? = null,
        /** The library version to pin (`version` in mbpulse/build.gradle). */
        libraryVersion: String = "1.0.0"
    ): String {
        val dataTypes = study["dataTypes"]?.jsonArray ?: error("study has no dataTypes")
        val schemasByType = app["dataTypeSchemas"]?.jsonObject ?: JsonObject(emptyMap())

        // Blank = not given: the wizard sends empty fields, which must get the default too.
        fun s(o: JsonObject, key: String, default: String = "") =
            o[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: default

        val appName = s(app, "appName", "My Study App")
        val packageId = s(app, "packageId", "com.example.mystudy")
        val module = moduleName(appName)
        val dir = appFolder(s(app, "appFolder"), module)

        val mapping = mapping(app)
        val supportedIds = supportedDataTypeIds(app)

        // Data types no app schema feeds: backend questionnaires (published in
        // the wizard, answers stored as questionnaire:<id> — no app code).
        val questionnaireTypes = dataTypes.map { it.jsonObject }
            .filter { schemasByType[s(it, "id")]?.jsonArray.isNullOrEmpty() }

        val typeRows = dataTypes.joinToString("\n") { el ->
            val t = el.jsonObject
            val id = s(t, "id")
            val schemas = schemasByType[id]?.jsonArray?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.jsonPrimitive.content } ?: "— (backend questionnaire)"
            "| $id | ${s(t, "name")} | ${if (s(t, "isEssential") == "true") "yes" else "no"} " +
                "| ${s(t, "retentionDays")} d | ${t["samplingSeconds"]?.jsonPrimitive?.content ?: "natural"} | $schemas |"
        }

        // Researcher-defined schemas the library does not ship: declared in the
        // host app as SchemaDefinitions (MbPulseConfig.customSchemas) — never
        // by editing the library.
        val customSchemas = app["customSchemas"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val customIds = customSchemas.map { s(it, "id") }.toSet()
        // Mapped schemas the template already declares, and ones nothing declares.
        val templateDeclared = mapping.keys.filter { it in TEMPLATE_SCHEMAS && it !in customIds }
        val builtInIds = SCHEMA_REGISTRY.map { it.id }.toSet()
        val undeclared = mapping.keys.filter { it !in builtInIds && it !in customIds && it !in TEMPLATE_SCHEMAS }
        val customConstants = customSchemas.map { constName(s(it, "id")) } +
            templateDeclared.map { TEMPLATE_SCHEMAS.getValue(it) }
        val customSection = if (customSchemas.isEmpty()) "" else {
            val rows = customSchemas.joinToString("\n") { c ->
                val source = s(c, "source").let { if (it.isEmpty()) "defined in wizard" else "[imported]($it)" } +
                    if (s(c, "deprecated").isNotEmpty()) " — deprecated upstream" else ""
                val kind = if (s(c, "role") == "event") "event (instant)" else "measurement"
                "| `${s(c, "id")}` | ${s(c, "version", "1.0")} | $kind | ${s(c, "dataType")} | ${s(c, "description")} | $source |"
            }
            val definitions = customSchemas.joinToString("\n\n") { definitionKotlin(it) }
            """

## Custom schemas — declare them in the app (do NOT edit the library)

The researcher defined measures the library does not ship. Declare each as a
`SchemaDefinition` in the app and pass them to `MbPulseConfig.customSchemas`
(already in the generated config below). The library then displays, charts,
recovers and FHIR-maps them from the definition. **Never modify
`mbpulse/` (`OmhBody`, `OmhSchemas`, `EpochDisplay`, `FhirMapper`, …)
or the wizard's `SCHEMA_REGISTRY` for a study** — if something seems to need
it, stop and report what is missing instead.

| schema id | version | kind | feeds data type | measures | source |
|---|---|---|---|---|---|
$rows

Create `CustomSchemas.kt` in the app package with exactly this (imports from
`pt.ua.mbpulse.data.model`):

```kotlin
$definitions
```

- **Emit** them from the processor with `DEFINITION.dataPoint(sourceName, startMs, endMs,
  mapOf("field" to value, …))` — it builds the header and checks every value
  (unknown field, wrong type, enum value not listed → exception). Events take
  only `startMs` (the instant).
- **Field order matters:** the first field is the primary value — the card
  value, the charted series (unless an event), the FHIR `value[x]`; the rest
  are shown next to it and become FHIR `component`s.
- Imported schemas (Open mHealth / JSON Schema source) are external
  definitions — keep their field names and units as-is.
- Fill in `category` (FHIR observation-category: `vital-signs`, `activity`,
  `exam`, `survey`, `procedure`, `laboratory`, …) and, if known, `loincCode`.
  UCUM: leave `ucumUnit`/`ucumCode` null when the unit is common (the library's
  `Ucum` table maps e.g. mV, μS, %, Hz, breaths/min); set them for others.
- An ambiguous unit (normalised, arbitrary scale): say so in a KDoc comment on
  the definition rather than guessing a UCUM code.
"""
        }

        val schemaNotes = buildString {
            if (templateDeclared.isNotEmpty()) append("""

## Schemas the template already declares

${templateDeclared.joinToString("\n") { "- `$it` → `${TEMPLATE_SCHEMAS[it]}` (data type `${mapping[it]}`)" }}

Keep that code and its `customSchemas` entry (in the generated config below);
adapt its wording and options to the study if the researcher described them.
Do not generate a second definition for it.
""")
            if (undeclared.isNotEmpty()) append("""

## Schemas with no definition — stop and report

${undeclared.joinToString("\n") { "- `$it` (data type `${mapping[it]}`)" }}

The study maps these schemas, but they are neither built into the library,
defined in the wizard, nor declared by the template. Do not invent a
definition: report it to the researcher, who defines the schema in the wizard
(step 3 → custom schema) and regenerates this brief.
""")
            if (questionnaireTypes.isNotEmpty()) append("""

## Data types without an app schema — backend questionnaires

${questionnaireTypes.joinToString("\n") { "- `${s(it, "id")}` (${s(it, "name")})" }}

No schema in the app feeds these. They are filled by **questionnaires the
researcher publishes in the setup wizard** (step 3 → Questionnaires): the
library renders them, stores the answers as `questionnaire:<id>` and maps them
to their data type itself (`MbPulseLibrary.consentTypeFor`) — **no app code,
no `schemaToConsentType` entry**. Until one is published, nothing is collected
for the type; that is expected, not a bug to fix.
""")
        }

        // Event schemas (instant time frame — e.g. stimulus triggers): custom
        // ones plus ticked built-ins. The library supports them natively.
        val builtInEvents = SCHEMA_REGISTRY.filter { it.role == "event" }.map { it.id }.toSet()
        val events = customSchemas.filter { s(it, "role") == "event" }
            .map { Triple(s(it, "id"), s(it, "dataType"), s(it, "trigger")) } +
            mapping.filterKeys { it in builtInEvents }.map { (id, typeId) -> Triple(id, typeId, "") }
        val eventSection = if (events.isEmpty()) "" else {
            val howTo = mapOf(
                "sensor" to "a marker channel in the sensor stream (device event button, TTL " +
                    "line, or a CSV column when replaying). The `SensorSource` exposes it as a " +
                    "signal; the processor emits one event on each marker (rising edge / " +
                    "non-zero value), timestamped with that sample.",
                "app" to "a button in the app. Put it on a replaced `collectionScreen` " +
                    "(recipe: replace-a-screen) and have it push a marker `SensorSample` into the " +
                    "host's `SensorSource` flow (e.g. merged from a `MutableSharedFlow`), so it " +
                    "passes through the same processor → `EpochSink` path as sensor data. Never " +
                    "write to storage directly.",
                "protocol" to "the app itself delivering the stimulus on a schedule. The host-side " +
                    "scheduler that delivers it also pushes a marker `SensorSample` into the " +
                    "`SensorSource` flow at the delivery instant (as for an in-app button).",
            )
            val eventRows = events.joinToString("\n") { (id, typeId, trigger) ->
                "- `$id` (data type `$typeId`) — triggered by " +
                    (howTo[trigger] ?: "a marker in the sensor stream unless the sensor description " +
                        "below says otherwise (in-app button or app-run schedule: push a marker " +
                        "`SensorSample` into the `SensorSource` flow).")
            }
            """
## Event / trigger schemas (stimulus markers)

These record *that something happened at an instant* (e.g. a stimulus was
delivered), not a summary of a window. The library supports them natively:

$eventRows

- **Time frame:** `effective_time_frame = OmhEffectiveTimeFrame.instant(ms)`
  (OMH `date_time`), never an interval — custom event schemas get this from
  `dataPoint(sourceName, triggerMs, values = …)` automatically. FHIR export
  maps it to `effectiveDateTime`.
- **Emit immediately:** override `SignalProcessor.feedAll` and return the
  trigger as its own `ProcessedEpoch` (`epochStartMs` = trigger instant)
  alongside any window closed by the same sample — see the KDoc on
  `feedAll`. Never hold a trigger until the window closes, never merge them.
- **Sampling:** `SamplingGate` never throttles instant data points, so every
  trigger is kept even if the data type has `samplingSeconds`.
- **Display:** the card shows the last trigger; events are never charted as a
  numeric series (`EpochDisplay` returns no numeric value for them).
- `mbpulse:stimulus-event` is built in (`StimulusType` enum:
  auditory/visual/tactile/thermal/cognitive, plus `stimulus_label` and
  `stimulus_intensity`) — reuse it rather than defining a new trigger schema
  unless the researcher asked for one.

"""
        }

        // How collection runs: foreground-service type + collection-screen access.
        val collectionMode = s(app, "collectionMode", "PASSIVE_CONNECTED_DEVICE")
            .takeIf { it in setOf("PASSIVE_CONNECTED_DEVICE", "PASSIVE_HEALTH", "SESSION") }
            ?: "PASSIVE_CONNECTED_DEVICE"
        val passive = collectionMode != "SESSION"
        val screenInRelease = passive && s(app, "collectionScreenInRelease") == "true"
        val collectionScreenLine = when {
            !passive -> "// collectionScreen: default ALWAYS — sessions are started on it"
            screenInRelease -> "collectionScreen = CollectionScreenAccess.ALWAYS  // researcher asked to keep it in release (import pt.ua.mbpulse.CollectionScreenAccess)"
            else -> "// collectionScreen: default DEBUG_BUILDS_ONLY — for CSV testing; absent from the release APK"
        }
        val passiveCommon = """
- Collection starts by itself once the participant finishes onboarding, again
  on every app start, and after reboot / app update (library-owned:
  `PassiveCollection`, `CollectionBootReceiver`). When the sensor stream ends
  or throws it reconnects with backoff — so `SensorSource.samples()` may simply
  complete when the device disconnects.
- Withdrawal stops it permanently. Do not add a start/stop button for
  participants; ${if (screenInRelease) "the researcher asked to keep the collection screen in the release app (the validator warns about this — expected)."
            else "the collection screen exists in debug builds only (CSV-replay testing) and disappears from the release APK automatically. A custom home screen must hide its own collection button with `MbPulseLibrary.isCollectionScreenEnabled(context)`."}
- If a prerequisite is missing, collection does not start and the reason shows
  on the home screen (and logcat tag `CollectionService`) — fix the manifest /
  permission flow, do not catch it.
- Release build: the library declares all three foreground-service type
  permissions; remove the two this mode does not use **for release only**, in
  a new `src/release/AndroidManifest.xml` (merged into release builds; debug
  keeps everything for CSV testing):
  ```xml
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_${if (collectionMode == "PASSIVE_HEALTH") "CONNECTED_DEVICE" else "HEALTH"}" tools:node="remove" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" tools:node="remove" />
  </manifest>
  ```
  `assembleRelease` produces an **unsigned** APK — signing is the researcher's
  (Play App Signing or their own keystore); do not create a keystore."""
        val collectionGuidance = when (collectionMode) {
            "PASSIVE_CONNECTED_DEVICE" -> """
Continuous background collection from a connected wearable — foreground-service
type `connectedDevice`.

- **Manifest prerequisite (Android 14+):** the template declares
  `CHANGE_NETWORK_STATE`, which is enough for CSV-replay development. For the
  real device replace it with what the sensor uses. Network sensor: keep
  `CHANGE_NETWORK_STATE`.
- **Bluetooth LE wearable:** do not write a scan or pairing screen. Start from
  the template's `BleSensorSource.kt` (fill in its `TODO(agent)`s: the GATT
  service, the data characteristic, `decode`) and turn on the library's device
  step: `deviceSelection = DeviceSelection(serviceUuids = listOf(BleSensorSource.SERVICE_UUID))`.
  The participant chooses the device in Android's chooser after onboarding;
  the step requests `BLUETOOTH_CONNECT` and resumes collection itself.
  Uncomment the Bluetooth / companion permissions in the template manifest,
  including the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`)
  that Android 11 and lower need (no `BLUETOOTH_SCAN` or location needed). A vendor SDK that pairs by itself
  keeps `deviceSelection = null` and requests `BLUETOOTH_CONNECT` on its own
  screen, then calls `PassiveCollection.resume(context)`.$passiveCommon"""
            "PASSIVE_HEALTH" -> """
Continuous background collection from phone / body sensors — foreground-service
type `health`.

- **Manifest prerequisite (Android 14+):** `BODY_SENSORS` or
  `ACTIVITY_RECOGNITION` granted at runtime, or `HIGH_SAMPLING_RATE_SENSORS`
  declared (Android 16+: a granted Health Connect read permission also
  counts). Remove the template's `CHANGE_NETWORK_STATE` (only needed for
  connectedDevice). Request the runtime permission, then call
  `PassiveCollection.resume(context)`. Note: a `health` service relying on a
  while-in-use permission such as `BODY_SENSORS` may not start from boot —
  prefer `HIGH_SAMPLING_RATE_SENSORS` or a permission granted "all the time".$passiveCommon"""
            else -> """
Time-boxed sessions — e.g. a lab protocol while stimuli are shown —
foreground-service type `dataSync`.

- The collection screen is always reachable: participants or researchers start
  and stop each session there. Keep its Start/Stop (a replaced
  `collectionScreen` slot must too).
- No runtime prerequisite: remove the template's `CHANGE_NETWORK_STATE`.
- Android 15+ caps `dataSync` at about 6 h per 24 h and it cannot start from
  the background, so nothing auto-starts; long unattended recordings need a
  passive mode instead.
- Release build: remove the `connectedDevice` / `health` foreground-service
  permissions the library declares, for release only, in a new
  `src/release/AndroidManifest.xml`:
  ```xml
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" tools:node="remove" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" tools:node="remove" />
  </manifest>
  ```
  `assembleRelease` produces an **unsigned** APK — signing is the researcher's;
  do not create a keystore."""
        }.trim()

        val collectionDone = if (passive) """
- [ ] Debug build: after onboarding, collection starts without pressing
      anything (notification "Recording health data"); after a reboot it
      resumes on its own; withdrawal stops it for good.
- [ ] `.\gradlew.bat assembleRelease` in `$dir/` (or a release-type build)
      has ${if (screenInRelease) "the collection button (requested)" else "no collection button and no Reset button"}.""".trimStart('\n')
        else """
- [ ] The collection screen starts and stops a session; nothing starts on its
      own after onboarding or reboot.""".trimStart('\n')

        val mapperBranches = mapping.entries
            .groupBy({ it.value }, { it.key })
            .entries.joinToString("\n") { (typeId, schemas) ->
                schemas.joinToString(",\n") { "                \"$it\"" } + " -> \"$typeId\""
            }

        return """
# AGENT BRIEF — build the "${s(study, "studyName")}" study app

Generated by the setup wizard on ${LocalDate.now()}. The backend is already
provisioned and the study is seeded; your job is the Android app.

**Read first:** `AGENTS.md` (integration contract), then the relevant playbooks
in `docs/recipes/`. Do not modify the security layer.

## Create the app: `$dir/` — its own build (do NOT edit `template-app/`)

`template-app/` is the pristine starting point for every study — leave it
untouched. The study app is a **standalone Gradle build in its own folder**
that uses the mbPulse library as a pinned Maven artifact
(`pt.ua.mbpulse:mbpulse:$libraryVersion`), never as source.
Paths are relative to this repository's root.

0. **Record this repository's state first:** save the output of
   `git status --porcelain` (it may already list the researcher's own
   uncommitted work). The final check compares against it — you must leave
   this repository exactly as you found it.
1. **Publish the library** (in this repository):
   `.\gradlew.bat :mbpulse:publishToMavenLocal` — puts release
   $libraryVersion in `~/.m2`, where the app's `settings.gradle.kts` finds it.
   A release is only published from committed library code; if Gradle refuses
   ("uncommitted changes" / "already published from commit …"), **stop and
   tell the researcher** — do not work around it with `-Pmbpulse.snapshot` or
   `-Pmbpulse.librarySource`, which would build the study on unreleased code.
   Already in `~/.m2/repository/pt/ua/mbpulse/mbpulse/$libraryVersion/`?
   Then skip this step.
2. **Copy** the *contents* of `template-app/` into `$dir/` — the folder
   already exists (the wizard created it with `local.properties` and
   `handoff.json`, step 4), so copy into it rather than creating
   `$dir/template-app/`. Skip `build/`, `.gradle/`, `.kotlin/` and
   `local.properties` (never overwrite the wizard's files). It is complete on its own: settings, Gradle wrapper,
   `gradle/libs.versions.toml`, `.gitignore`. (Your tools may ask for
   permission to write outside this repository — the researcher chose this
   folder.)
3. **Pin the library:** in `$dir/gradle/libs.versions.toml` set
   `mbpulse = "$libraryVersion"`. Leave `agp`, `kotlin`, `ksp` and `hilt` as
   they are (they must match the library's build).
4. **Credentials — already done by the wizard:** `$dir/local.properties`
   holds `sdk.dir`, `mbpulse.supabaseUrl` and `mbpulse.supabaseKey`. Check
   that the three keys are present **without printing their values**; if one
   is missing, stop and ask the researcher to set the backend credentials in
   the wizard (step 1) and write the brief again. Never copy the values
   anywhere, never paste them into source, never commit the file (the copy's
   `.gitignore` excludes it).
5. In `$dir/` only: set `rootProject.name` in `settings.gradle.kts`, change
   `namespace`, `applicationId`, the source package folders + `package` lines
   in `src/main/java/` **and** `src/test/java/` (`com.example.mystudy` →
   `$packageId`) and the manifest label together.
   Run `.\gradlew.bat clean` in `$dir/` after the package rename (stale Hilt
   classes otherwise).
6. `grep -r "TODO(agent)" $dir/` lists every stub to fill in.
   `TODO(researcher)` marks what only the researcher can supply (e.g. the
   ethics-approved privacy wording) — leave those markers in place and list
   them in your final report.
7. Recommended: `git init` in `$dir/` — the study app is its own repository.

Every path below refers to `$dir/`, and every Gradle command runs **there**
(`.\gradlew.bat assembleDebug`, no module prefix) — not in this repository.
The library's sources, for reference only, are `mbpulse/` here (or the
sources jar Gradle downloads with it); never change them for a study.

## App identity

- App name: **$appName**
- App folder: **$dir/** (Gradle project `$module`)
- Package / applicationId: **$packageId**
- mbPulse library: **$libraryVersion** (pinned)

## Study definition (already seeded — do not re-seed)

This is the study as the researcher seeded it in the wizard (a copy is saved in
`backend/study.json` when it was seeded from the wizard). If study selection
does not show "${s(study, "studyName")}" with these data types, the brief and
the backend do not match — stop and tell the researcher; never re-seed or edit
the study yourself. Data types:

| id | name | essential | retention | sampling (s) | OMH schemas |
|---|---|---|---|---|---|
$typeRows
$customSection$schemaNotes
$eventSection## Generated config (drop into the MbPulseConfig in `StudyConfig.kt`)

The app's whole config is built in `StudyConfig.kt` (`studyConfig(context)`),
which `HandoffConformanceTest` checks — never build a second one elsewhere.

```kotlin
schemaToConsentType = { schema ->
    when (schema) {
$mapperBranches
        else -> null
    }
},
supportedDataTypeIds = setOf(${supportedIds.joinToString(", ") { "\"$it\"" }}),
${if (customConstants.isEmpty()) "// customSchemas: none — every schema is built into the library"
  else "customSchemas = listOf(${customConstants.joinToString(", ")}),   // " +
      listOfNotNull("CustomSchemas.kt".takeIf { customSchemas.isNotEmpty() },
          "declared by the template".takeIf { templateDeclared.isNotEmpty() }).joinToString(" + ")}
${receiptPublicKeyB64?.let { "deletionReceiptPublicKeyB64 = \"$it\"" } ?: "// deletionReceiptPublicKeyB64: generate keys in the wizard first"},
${studySigningPublicKeyB64?.let { "studySigningPublicKeyB64 = \"$it\",   // pinned: the app only accepts study definitions signed by the PI" } ?: "// studySigningPublicKeyB64: generate keys in the wizard first"}
collectionMode = CollectionMode.$collectionMode,
$collectionScreenLine
```

Credentials come from `$dir/local.properties` (written by the wizard, step 4
above) — the template reads them via BuildConfig; never hardcode them.

## Collection mode: `$collectionMode`

$collectionGuidance

## Sensor (recipe: docs/recipes/integrate-a-sensor.md)

The researcher describes the device as:

> ${s(app, "sensorDescription", "(not described — keep the CSV replay source)").replace("\n", "\n> ")}

Develop and verify against `CsvReplaySensorSource` + a sample CSV first; the
processor must emit the schemas listed above (recipe:
docs/recipes/add-a-data-type.md; measures the library lacks are custom
schemas declared in the app, see above).

## Screens (recipe: docs/recipes/replace-a-screen.md)

> ${s(app, "screenNotes", "(no customisation requested — keep the turnkey UI)").replace("\n", "\n> ")}

Replaceable slots only (`studySelectionScreen`, `measurementsScreen`,
`collectionScreen`, `historyScreen`); loading/onboarding/recovery are
library-owned by design. Worked example: `template-app/.../CustomHomeScreen.kt`.
Questionnaires need **no app code**: the researcher publishes them in the
setup wizard and the library renders them (cards on the home screen; a custom
home screen shows them with `QuestionnaireCards()`). Do not build questionnaire
forms in the app. Other extra screens (diaries, one-tap logs, device pairing)
are **added** as `hostScreens`, not by replacing a slot; data the participant
enters there is saved with `ParticipantDataRecorder` (recipe:
docs/recipes/add-participant-input.md, example: `template-app/.../SymptomLog.kt`).
${if ("mystudy:symptom-log" in templateDeclared)
    "The template ships that example and this study uses it (`mystudy:symptom-log` → " +
        "`${mapping["mystudy:symptom-log"]}`): keep `SymptomLog.kt` and its registrations, " +
        "adapting the symptom list and wording only if the researcher described them."
else
    "The template ships that example — this study does not map its schema " +
        "(`mystudy:symptom-log`), so delete `SymptomLog.kt`, the `symptom_*` strings and " +
        "their registrations (`customSchemas`, `schemaToConsentType`, `hostScreens`)."}

## Health Connect import

> ${s(app, "healthConnect", "(none requested)").replace("\n", "\n> ")}

One `HealthConnectSource` per category + matching manifest permission
(reference: `HeartRateSource` in `sample-app/.../MeasuresApp.kt`). Set
`healthConnectImportDays` (1–90, default 30) to how much history the import
screen reads; above 30 also declare
`android.permission.health.READ_HEALTH_DATA_HISTORY` in the manifest.

## Definition of done

- [ ] `.\gradlew.bat assembleDebug` in `$dir/` builds clean, against the
      published library $libraryVersion (no `-Pmbpulse.librarySource`).
- [ ] `.\gradlew.bat testDebugUnitTest` in `$dir/` passes: 8 tests, none
      failed **and none skipped** (skipped = `handoff.json` is not in `$dir/`). It checks the app against `handoff.json` — the
      machine-readable copy of this brief. Fix the app, never `handoff.json` or
      the test; a check that looks wrong is reported to the researcher. Set
      `REPLAY` in the test to a CSV of the real sensor once there is one, and
      `NOT_FROM_PROCESSOR` only for a schema that truly comes from elsewhere,
      with the reason.
- [ ] Your final report includes `build/reports/handoff-check.md` — every
      WARN in its audit section (lines in the sensor source and processor
      that log, write or send) with a sentence on whether it can reveal a
      measured value.
- [ ] `studySigningPublicKeyB64` is set exactly as generated above — never
      remove it to "fix" a study that fails to load: that error means the
      definition on the server is not the one the PI signed.
- [ ] `git status --porcelain` in this repository is identical to what you
      recorded in step 0 — the study lives entirely in `$dir/`.
- [ ] No `local.properties` (or other secret) is tracked in `$dir/`.
- [ ] App start shows the study; consent lists exactly the data types above.
      No system action bar above the app (the manifest keeps the template's
      NoActionBar `Theme.Study` — Compose screens draw their own top bars).
- [ ] A simulated (CSV) collection session fills the cards and history charts,
      with no `EpochEncryptor` warnings in logcat.
- [ ] Withdrawal produces a deletion certificate ("0 records remain",
      signature verified).
$collectionDone
""".trimIndent() + "\n"
    }
}
