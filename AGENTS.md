# mbPulse — self-hosted mHealth research platform (thesis project)

An Android **library** for end-to-end-encrypted clinical data collection
(`mbpulse`, package `pt.ua.mbpulse`), plus a sample
**host app** (`sample-app/`, package `com.clinicalTests`) that wires it to the Empatica
E4 / DREAMT dataset. Researchers build their own study app by writing a thin
host app against the library's extension points — the library owns crypto,
consent, storage, sync, and (most of) the UI.

## Module map

| Path | What it is |
|---|---|
| `sample-app/` | Sample host app (`MeasuresApp.kt` = the integration example) |
| `mbpulse/` | The library — all reusable logic lives here |
| `backend/` | Backend in a box: SQL, Edge Functions, keygen, runbook |
| `template-app/` | Clean starting point for a new study app — a **standalone Gradle build** (own settings, wrapper, catalogue; not in the root build) using the published library at a pinned version; `TODO(agent)` stubs mark every customisation point; runs end-to-end on CSV-replay simulated data; `StudyConfig.kt` holds the whole `MbPulseConfig`, checked against the wizard's `handoff.json` by `HandoffConformanceTest`. Study apps are copies of it in their own folder |
| `docs/recipes/` | Playbooks with verification checklists: provision backend, integrate sensor, add data type, replace screen, participant input |
| `setup-wizard/` | Researcher-facing local web app (`gradlew :setup-wizard:run`): backend provisioning, keygen, study builder, agent-brief handoff |
| `frontend/` | PI web tooling — stand-alone: opened from the folder (`file://`), chart libraries pinned in `frontend/vendor/` (hashes in its README), a CSP refuses scripts from anywhere else; only network is HTTPS to the backend |

## Build & verify

Gradle needs JDK 17+; the default PATH java is JDK 8. Always set:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :sample-app:assembleDebug                 # full build
.\gradlew.bat :mbpulse:compileDebugKotlin       # fast library check
.\gradlew.bat :mbpulse:testDebugUnitTest        # JVM unit tests
.\gradlew.bat :mbpulse:publishToMavenLocal      # AAR + sources → ~/.m2
```

The library publishes as `pt.ua.mbpulse:mbpulse:<version>`
(`maven-publish`, release variant + sources jar; `releaseVersion` in
`mbpulse/build.gradle`). A **release** version is only published from
committed `mbpulse/` code and never re-published from another commit
(the POM records `gitCommit`; the publish task refuses otherwise) — so a pinned
version always means the same code. Work in progress:
`publishToMavenLocal "-Pmbpulse.snapshot"` → `<releaseVersion>-SNAPSHOT`
(commit recorded with `-dirty`). A new release = raise `releaseVersion`, commit,
publish. Types in its public API come from `api`
dependencies (coroutines, Compose runtime/UI/Material 3, Health Connect) — a
new public type from another dependency needs that dependency moved to `api`.
`sample-app` (in the root build) keeps `project(":mbpulse")`.
`template-app/` and every study app copied from it are standalone builds that
pin the published version (`mbpulse` in their `gradle/libs.versions.toml`;
`mavenLocal()` in their settings).

**Handoff check:** the wizard writes `handoff.json` (`setup-wizard/.../Handoff.kt`,
format `mbpulse-handoff-v1`, no secrets — credential *hashes* only) into
the study app's folder with the brief; the app commits it.
`template-app/src/test/.../HandoffConformanceTest.kt` (+ `HandoffCheck.kt`)
checks the app against it on the JVM — `testDebugUnitTest`: the config from
`StudyConfig.kt` (`studyConfig(context)`, the app's only config), a CSV replay
through the processor (`CsvReplaySensorSource.readSamples`), the manifest — and
writes `build/reports/handoff-check.md` with an audit list of log/file/network
calls in the sensor source and processor. Skipped in the template itself (no
handoff; `"-Pmbpulse.handoff=<file>"` supplies one). A change to what the wizard
generates or the library requires of a host updates `Handoff.kt`,
`HandoffTest` and `HandoffCheck` together.

To build the template against the library
**source** (after changing the library, without publishing):

```powershell
.\gradlew.bat -p template-app assembleDebug "-Pmbpulse.librarySource=.."   # composite build
.\gradlew.bat -p template-app assembleDebug                               # published artifact
```

Bumping the library `version` means publishing it and raising `mbpulse` in
the template's catalogue; the wizard brief pins whatever `version` says.

Backend credentials live in the gitignored `local.properties`
(`mbpulse.supabaseUrl`, `mbpulse.supabaseKey`) and reach the app via
`BuildConfig`. **Never hardcode keys in source.**

## Host-app integration contract

1. `@HiltAndroidApp` Application calls `MbPulseLibrary.init(MbPulseConfig(...))`
   in `onCreate` (see `sample-app/src/main/java/com/clinicalTests/MeasuresApp.kt`).
2. Activity shows the turnkey UI: `setContent { MbPulseApp() }`
   (or embeds `MbPulseNavHost` to override screens — see below).
3. Host manifest declares the Health Connect permissions for any registered
   import sources, plus the HC privacy-rationale activity
   (`PermissionsRationaleActivity` + `ViewPermissionUsageActivity` alias).

### Public API (curated — what a published version promises)

Everything a host may use is public; everything else is `internal`. Public:
`MbPulseConfig` / `CollectionMode` / `CollectionScreenAccess`, `MbPulseBranding`,
`MbPulseLibrary`; `DeviceSelection` + `SelectedDevice`, `DeviceConnection` +
`DeviceConnectionState`, `DeviceStatusCard()`; `SensorSource`, `SensorSample`, `SignalProcessor`,
`ProcessedEpoch`, `HealthConnectSource` + `readAll`, `PassiveCollection.resume`;
the OMH model (`OmhDataPoint` …, `OmhSchemas`, `StimulusType`),
`SchemaDefinition` / `SchemaField` / `FieldKind` / `SchemaTerminology`,
`FhirMapper`; `ParticipantDataRecorder` + `RecordResult` / `RecordAvailability`;
`MbPulseApp`, `MbPulseNavHost`, `HostScreen` + `LocalMbPulseNavigator`,
`MbPulseTheme`, `mbPulseTopAppBarColors`; the replaceable slots' default
screens and the view models / state they hand over (`LoadingViewModel` +
`LoadingState`, `CollectionViewModel`, `HistoryViewModel` + `HistoryUiState`
/ `HistoryEntry`, `StudySelectionViewModel` + `StudySummary`,
`CollectionMeasurementsViewModel`, `stageText`); `QuestionnaireCards()`,
`SyncStatusCard()`. Public view models have `@Inject internal constructor`s
(hosts get them from Hilt, never build them); public composables take no
library view model as a parameter. Database, repositories, Supabase client,
workers, service, uploader, encryptor, caches and the internal UI pieces are
`internal`. The library builds in **explicit API mode** (`explicitApi()` in
`mbpulse/build.gradle`): every public declaration and member says
`public` and its type, or the build fails — nothing becomes API by accident.
**Rules:** start minimal — a type becomes public only when a host
needs it (adding is compatible, removing breaks every app that used it); a
public signature may not mention an internal type (the compiler enforces it);
make whole *classes* internal — an internal *member* is renamed in bytecode,
which breaks Hilt/Room generated code that calls it (`@Provides`, `@Inject`).

### MbPulseConfig (`mbpulse/MbPulseConfig.kt`) — the wiring surface

- `supabaseUrl` / `supabaseKey` — backend (anon key; RLS assumes it is public).
- `sensorSource: () -> SensorSource` — factory, one per collection session.
- `signalProcessor: () -> SignalProcessor` — factory; processors are stateful.
- `schemaToConsentType: (schemaName) -> String?` — maps OMH `"namespace:name"`
  to a study consent-type id. **Fails closed** (`collection/ConsentFilter`,
  pure + tested; also used by `ParticipantDataRecorder`): a point is kept only
  when its schema maps to a type the participant accepted — a wrong mapping or
  `null` (the default) drops it. Both are logged once per schema by
  `EpochEncryptor` (tag `EpochEncryptor`) — check logcat when data is missing;
  `MbPulseLibrary.init` warns about an unmapped custom schema and a mapper
  that maps nothing.
  On every study load the library also compares it with the study's
  server-side copy (`StudyConfig."schemaDataTypes"`, which drives retention)
  and logs each disagreement under tag `SchemaMapping`
  (`data/repository/SchemaMappingCheck.kt`, pure + tested; diagnostic only).

`MbPulseLibrary.init` **validates the config** (`MbPulseConfigValidator`) and
throws with a list of actionable messages on bad credentials, a throwing/
inconsistent consent mapper, duplicate Health Connect source ids, or a
malformed receipt key; non-fatal issues are logged under `MbPulseLibrary`.
- `supportedDataTypeIds` — data types this build can collect; studies whose
  essential types aren't covered are greyed out in study selection.
- `healthConnectSources: List<HealthConnectSource>` — optional HC import.
- `deletionReceiptPublicKeyB64` — optional; enables signed-receipt verification.
- `collectionMode` — `PASSIVE_CONNECTED_DEVICE` (default; FGS type
  `connectedDevice`), `PASSIVE_HEALTH` (`health`) or `SESSION` (`dataSync`).
  Passive: `PassiveCollection` starts collection after onboarding / on every
  app start / on boot (`CollectionBootReceiver`), reconnects with backoff when
  the sensor stream ends, and is disabled by withdrawal. Session: started and
  stopped on the collection screen. `CollectionRequirements` checks the type's
  Android 14+ prerequisite (e.g. `BLUETOOTH_CONNECT`, declared by the **host**)
  before starting and reports what is missing instead of crashing.
- `syncInterval` — background upload cadence (default 30 min, min 15). All
  uploads go through `collection/RecordUploader` (serialised, batched, epoch
  progress as a `StateFlow`): `CollectionService` uploads itself on this
  cadence while collecting (WorkManager jobs get deferred by Doze / standby
  buckets; the foreground service does not), `SyncWorker` runs periodically
  (scheduled in `BackgroundWork.enable`), and the home screen's
  `ui/components/SyncStatusCard` syncs on resume with a progress bar.
- `healthConnectImportDays` — history window of the import screen's manual
  import (1–90, default 30; validated). Above 30 the host manifest must declare
  `READ_HEALTH_DATA_HISTORY` (requested with the read permissions where supported).
- `healthConnectBackgroundImport` — interval (default 1 h, min 15 min, `null`
  = manual only) of `HealthConnectBackgroundImport`, a WorkManager job that
  uses Health Connect change tokens per source (`HealthConnectSource.recordTypes`;
  without them it polls the last 6 h) and re-reads only the changed time
  window, aligned to `readAlignment`, through `HealthConnectImporter` →
  `EpochSink` (overlap is deduplicated). Needs
  `READ_HEALTH_DATA_IN_BACKGROUND` in the host manifest; the import screen
  requests it. Enrollment for background work (passive collection + this job)
  lives in `EnrollmentStore`; `BackgroundWork.enable/disable` turns both on
  (onboarded, every app start) and off (withdrawal, reset).
- `collectionScreen` — `ALWAYS` / `DEBUG_BUILDS_ONLY` / `NEVER`; default
  `DEBUG_BUILDS_ONLY` for passive (CSV testing in debug, gone from the release
  APK), `ALWAYS` for session. Enforced in `NavGraph`; custom home screens check
  `MbPulseLibrary.isCollectionScreenEnabled(context)`. The dev "Reset" button
  is debug-build-only too.
- `deviceSelection` — `DeviceSelection(serviceUuids, namePattern)` turns on the
  library-owned **device step** for a Bluetooth LE wearable (`null` = none):
  after onboarding (and on every start until a device is chosen; "Not now"
  postpones) the participant picks the device in Android's Companion Device
  Manager chooser (`collection/DeviceAssociation.kt` — no scan/location
  permission; the step requests `BLUETOOTH_CONNECT` on 12+, declared by the
  host, which also declares `BLUETOOTH` / `BLUETOOTH_ADMIN` with
  `maxSdkVersion="30"` for Android 11 and lower). Below API 33
  `CompanionDeviceManager.associate` casts its context to `Activity`, so the
  step passes the hosting Activity (`LocalActivity`), never the application
  context. The choice is `SelectedDevice` (`observe(context)` / `current`),
  stored with the enrolment (`DeviceSelectionStore`; withdrawal/reset clear it
  and end the association); the home screen's device card and
  `LocalMbPulseNavigator.current.openDeviceSelection()` change it. The
  library only selects — connecting and decoding stay in the host's
  `SensorSource` (base example: `template-app/.../BleSensorSource.kt`, follows
  `SelectedDevice` and reconnects with backoff). **Connected status:** the
  source calls `DeviceConnection.report(CONNECTING / CONNECTED / DISCONNECTED)`
  (only it knows the protocol); `CollectionService` records the last sample
  time and marks a reported link DISCONNECTED when the stream ends
  (`collection/DeviceConnection.kt`, in memory; `display` is pure + tested).
  The device card (`DeviceStatusCard()`, public for custom home screens) and
  device screen show "Connected · last data …", "Connecting…", "Not connected
  since …"; a source that never reports gets "Receiving data" from samples of
  the last 2 min.

### Extension points (what a new study implements)

- **`SensorSource`** (`mbpulse/collection/SensorSource.kt`): stream of
  `SensorSample(timestampMs, signals: Map<String, Float>)`. The library is
  **completely sensor-agnostic** — it only ever consumes this flow; every
  source implementation (real device or simulated) lives in the host app.
  For development without hardware, copy `template-app/`'s
  `CsvReplaySensorSource` (header-driven CSV replay from assets; delta-paced,
  `loop`, `rebaseToNow`, bundled `sample_data.csv`).
- **`SignalProcessor`** (`mbpulse/collection/SignalProcessor.kt`):
  `feed(sample) -> ProcessedEpoch?` — accumulate samples, emit one epoch per
  window containing OMH `OmhDataPoint`s. Reference: `sample-app/.../E4SignalProcessor.kt`.
- **`HealthConnectSource`** (`mbpulse/collection/HealthConnectSource.kt`): one
  per HC category; declares its permissions, reads records (`readAll` helper),
  maps to `ProcessedEpoch`s. Reference: `HeartRateSource` in `MeasuresApp.kt`.
  Imported data must tag `acquisitionProvenance` (source + modality).
- **Schemas**: `mbpulse/data/model/OmhDataPoint.kt` — `OmhSchemas` registry
  (+ `SchemaTerminology` FHIR/LOINC/UCUM bindings, consumed by
  `mbpulse/fhir/FhirMapper.kt`). **A study never edits the library for a new
  measure**: the host declares a `SchemaDefinition` (`data/model/SchemaDefinition.kt`:
  id, terminology, ordered `SchemaField`s of kind QUANTITY/NUMBER/TEXT/ENUM,
  `isEvent`) in `MbPulseConfig.customSchemas` and emits with
  `definition.dataPoint(...)`. Its fields live in `OmhBody.extra`, written flat
  into the body JSON by `OmhDataPointSerializer` (decoded by schema id, so names
  may repeat built-in ones); the first field is the primary value for
  `EpochDisplay`, charts and FHIR `value[x]`, the rest become components.
  `MbPulseLibrary.init` validates and registers them (`OmhSchemas.register`).
  Built-in schemas (`OmhSchemas.BUILT_IN`) keep typed `OmhBody` fields —
  change those only when extending the library itself. Measurement cards come
  from inverting `schemaToConsentType` over all registered schemas. **Events** (stimulus triggers etc.) use an instant time frame
  (`OmhEffectiveTimeFrame.instant`), are emitted via `SignalProcessor.feedAll`
  as their own epoch, and are never throttled by `SamplingGate`; built-in:
  `mbpulse:stimulus-event`.
- **Participant-entered data** (questionnaires, diaries, manual readings):
  `collection/ParticipantDataRecorder` (inject it, or
  `MbPulseLibrary.recorder(context)`) — `availability(schemaName)` and
  `record(dataPoints) -> RecordResult` (Recorded / NotEnrolled / NotConsented /
  TooSoon / Duplicate / Failed; all or nothing). Uses the persisted
  `Enrollment` (keys + effective consent) and the same `EpochEncryptor` →
  `EpochSink` → upload path; no collection session needed. The checks are
  the pure, tested `RecordPolicy`: consent as in `EpochEncryptor`, and the
  type's `samplingSeconds` measured against the last stored reading
  (`display_epochs`) — so `samplingSeconds` sets a questionnaire's frequency.
  Hosts never build `EpochEncryptor`/`EpochSink` themselves. Recipe:
  `docs/recipes/add-participant-input.md`; example: `template-app/.../SymptomLog.kt`.
  Questionnaires need no app code: published in the wizard
  (`backend/questionnaires.sql`, PI-signed, no re-consent — consent is to the
  data type), verified/cached by `QuestionnaireRepository`, rendered by the
  library (`ui/screens/questionnaire/`), answers stored as `questionnaire:<id>`.

### Screen replacement

`MbPulseNavHost` (`mbpulse/ui/navigation/NavGraph.kt`, Navigation 3) exposes
replaceable slots: `studySelectionScreen`, `measurementsScreen`,
`collectionScreen`, `historyScreen`. Loading, onboarding (consent/passphrase),
recovery and the device step are **deliberately not replaceable** — they are the security- and
ethics-critical path and stay library-owned. The compiler enforces it: their
screens and view models, and `crypto/`'s signers, key managers and derivations,
are `internal` (see "Public API" above — `LoadingViewModel`, which custom
screens read, is public with an internal constructor). Worked example:
`template-app/.../CustomHomeScreen.kt` (+ `docs/recipes/replace-a-screen.md`).
`LoadingViewModel` and `CollectionViewModel` are activity-scoped and shared
across screens.

**Host screens** (added, not replaced): `MbPulseApp(hostScreens = …)` /
`MbPulseNavHost(hostScreens = …)` take `HostScreen(id, titleRes, icon,
showOnHome, subtitle, content)` (`ui/navigation/HostScreens.kt`). The default
home screen lists them as `HostScreenCard`s; custom screens open them with
`LocalMbPulseNavigator.current.openHostScreen(id)`. Route `HostScreenKey(id)`;
opening is refused unless the current screen is an enrolled one
(`isEnrolledScreen`), and the re-consent screen and export notice cover host
screens like the library's own.

### Branding and wording (no screen replacement needed)

- **Look:** `MbPulseConfig.branding = MbPulseBranding(...)` (`mbpulse/MbPulseBranding.kt`):
  `primaryColor` (ARGB; light + dark schemes derived by `brandColorScheme` in
  `ui/theme/Color.kt`), optional `secondaryColor`, `homeTitle`, `logoRes`
  (study selection + consent intro), `coloredTopBars`, `dynamicColor`,
  `darkTheme`, or full `colorScheme` / `typography` / `shapes`. Applied by
  `MbPulseTheme` (`ui/theme/Theme.kt`; `MbPulseApp()` uses it — wrap
  `MbPulseNavHost` in it when embedding). Screens never hard-code colours: use
  `MaterialTheme.colorScheme` and `mbPulseTopAppBarColors()`; `tertiary` is
  the attention colour (stale / offline), `error` for failures.
- **Wording:** every participant-facing text is a string resource prefixed
  `mbpulse_` in `mbpulse/src/main/res/values/strings_*.xml` (one file
  per area: common, home, history, collection, consent, onboarding, recovery,
  study_selection, loading, health_connect, questionnaire, device). Hosts change wording by defining
  the same name in their own `res/values/`, translate with `values-<lang>/`.
  Consent wording is ethics-reviewed — override, don't edit the library copy.
- **Rules for library code:** no user-visible literals. In composables use
  `stringResource` / `pluralStringResource` (counts are `<plurals>`); in view
  models/services use the injected `@ApplicationContext` (`getString`,
  `getQuantityString`) and `Context.errorText(e, fallback)` (`ui/UserMessages.kt`:
  translated text + technical detail). State that is worded later is typed, not
  text: `CollectionStage` (→ `stageText`), `TermChange`, `RelativeTime`
  (→ `relativeTimeText`), history bucket sizes. Not translated on purpose:
  logs, developer diagnostics (manifest prerequisites), built-in measurement
  labels stored with each reading (`EpochDisplay`), and study names/descriptions
  (from the study definition, in the researcher's language).
- **Logging:** library code imports `pt.ua.mbpulse.logging.Log`
  (same calls as `android.util.Log`), never `android.util.Log`. `d`/`i`/`v`
  are written only when the host app is debuggable (set at process start by
  `LogInitializer`, androidx.startup — library manifest); `w`/`e` always — so
  pseudonyms, raw server rows and study definitions go in `d`/`i` only, never
  in a warning or error.

## Data pipeline (do not reorder)

```
SensorSource → SignalProcessor.feed() → ProcessedEpoch
  → EpochSink.store():  EpochEncryptor (consent filter → AES-256-GCM per point)
       → encrypted_records (Room, syncState=PENDING)
       + plaintext display/history rows (local UI only)
  → RecordUploader (CollectionService / SyncWorker / home screen) → Supabase encrypted_records
```

Live collection runs in `CollectionService` (foreground, dataSync type);
Health Connect import reuses the same `EpochSink` so the paths cannot diverge.

Local display data: `EpochDisplay.summarize` → `display_epochs` (latest per
schema, home cards) + `epoch_history` rows via `EpochDisplay.historyRow`
(shared with recovery's `OmhImporter`). History rows carry a `kind`
(NUMBER / CATEGORY / EVENT), the category, and every numeric field
(`seriesJson`) — DB v6 (`MIGRATION_5_6`). Room exports each version's schema
to `mbpulse/schemas/` (committed); `AppDatabaseMigrationTest` (JVM,
sqlite-jdbc) runs every migration from a frozen old schema and checks the data
survives and the result equals the exported schema. **A schema change** = bump
the version, add the migration to `AppDatabase.MIGRATIONS`, add the previous
version's frozen schema + a test there (the build fails otherwise: missing
migrations wipe unsynced records). `HistoryViewModel.buildState` (pure,
tested) charts them on a real time axis with gaps and bucket averages
(`HistoryAggregation`); charts are Compose Canvas (`ui/components/Charts.kt`).
Home: `StatusPanel` (offline / recording / upload) + cards with last-reading
time, staleness and a 24 h sparkline. Offline start: `StudyConfigCache` keeps
the last verified study definition; the repository falls back to it (same
verification) and `Ready.offlineSinceMs` is set until the server is reached —
retried on every resume and, via a default-network callback in
`LoadingViewModel` (`ACCESS_NETWORK_STATE`), whenever a network appears (also
from the loading error screen); back online it syncs and checks export notices.
Pull-to-refresh (and ⋮ → Refresh) on the home screen calls
`LoadingViewModel.refresh()` — study reloaded in place (a study update shows
the re-consent review at once; a definition that fails to load/verify keeps
the current one), background work restarted, upload + export check — and
refreshes the questionnaires. Custom home screens can call it too.

## Security architecture (DO NOT MODIFY without explicit request)

- `crypto/KeystoreManager.kt` — hardware RSA-2048 (OAEP encrypt/decrypt **and**
  PKCS1 sign; purposes are fixed at key creation — pre-signing keys are
  regenerated on next enrolment). DEK = AES-256-GCM, wrapped under participant
  key + PI public key (`piWrappedDekHex`) per record.
- `crypto/KeyRecoveryManager.kt` — passphrase recovery blob (PBKDF2 600k).
  Uses `java.util.Base64` deliberately (JVM-testable).
- `data/local/DatabaseKeyProvider.kt` + SQLCipher — whole local DB encrypted;
  `System.loadLibrary("sqlcipher")` in `di/AppModule.kt` is required.
- Withdrawal = challenge–response: RPC nonce → device signs (SHA256withRSA) →
  `confirm-withdrawal` Edge Function verifies (Web Crypto RSASSA-PKCS1-v1_5)
  → service_role deletes → signed deletion receipt. The receipt
  `signingString` format is duplicated in `index.ts` and
  `LoadingViewModel.verifyReceiptSignature` — **keep in sync**.
- Consent records are **signed by the participant**: onboarding signs the
  canonical string from `crypto/ConsentSignature.kt` (format documented there —
  a contract for any verifier) with the device key and submits it through the
  `submit_consent_record` RPC, which requires the signer key to be the
  participant's registered key; a trigger makes `consent_records` immutable
  (deletion only via withdrawal). Recovery fetches them (`get_consent_records`),
  verifies signatures and restores the latest valid record's choices — with
  none valid (or one for another study definition) it **fails closed**: nothing
  is collected until the participant reviews consent again. `EpochEncryptor`
  treats an empty consent set as "nothing consented" (never "no filter").
- **Consent is linked to the exact definition agreed to**: `seed_study()` keeps
  every definition ever applied in the append-only `study_definitions` table
  (`record_study_definition()`: the PI-signed text, or a canonical JSON for an
  unsigned study, keyed by its hex SHA-256 — `crypto/StudyDefinitionHash.kt`
  computes the same; **keep in sync**) and sets `StudyConfig."definitionSha256"`.
  Consent format **v2** (`mbpulse-consent-v2`) signs a
  `definition:<sha256>` line; `consent_records.definition_sha256` references
  the history and `submit_consent_record` / `enroll_participant` accept only the
  study's current definition. With a pinned key the device hashes the verified
  signed text itself. v1 records (before tracking) still verify and stay
  linked by version label only. Audit query:
  `select c.*, d.definition from consent_records c join study_definitions d using (definition_sha256)`.
- **Study updates → re-consent**: the device stores the terms agreed to
  (`OnboardingViewModel.CONSENTED_TERMS`, `ui/screens/consent/ConsentTerms.kt`).
  `LoadingViewModel.buildReady` diffs them against the current signed
  definition: removed types stop at once; new or changed types (name, wording,
  retention, sampling, essential) are **not collected** and
  `Ready.consentReview` makes `NavGraph` show the library-owned
  `ReconsentScreen` (not replaceable; "Not now" postpones). Accepting signs a
  new consent record over every current type for the new version
  (`submit_consent_record`, append-only). `CollectionService` restarts when its
  consent/sampling/PI-key parameters change. `Ready.consentedTypeIds` is the
  effective set — essential types are never implied.
- **Updating a study (wizard)**: step 3 "Update an existing study" lists the
  backend's studies (`StudyUpdate.kt`), loads one into the form and re-seeds it
  under the same `studyId`. Checks: stored definition must verify with the
  local study-signing key (apps pin it), version must increase, and the PI key
  must be the local one — or, for a **handover**, the current PI's private key
  must sign a challenge that verifies against the stored key (optionally
  removing the previous staff links). Data collected before a handover stays
  encrypted for the old PI key.
- Enrolment is one atomic, idempotent RPC (`enroll_participant`: participant
  + rotation key + signed consent); `OnboardingViewModel` resends the identical
  prepared enrolment on retry. Anon has no direct access to `participants`.
  The backend enforces the enrolment gate, not the app: `enroll_participant`
  requires the study to be open (`"isActive"`, not ended) and, when it has a
  join code, a single-use **join ticket** (`issue_join_ticket` in
  `join_code.sql`, 7 days) that `StudySelectionViewModel.joinStudy` gets for
  the code and keeps in `JoinTicketStore` until enrolment (a custom study
  selection screen must join through it).
- **Study lifecycle**: `"isActive"` = enrolment open (`set_study_enrolment`,
  wizard "Close enrolment"; `seed_study()` keeps it when omitted).
  `"endedAt"` = ended for good (`end_study`, wizard "End study…"): the backend
  refuses enrolment, consent, questionnaire publishing, study updates and
  records measured after the end (upload policy `record_before_study_end`).
  The app learns it on load (`Ready.endedAtMs`) or, unopened, from
  `RecordUploader` (`collection/StudyEnd`, asked at most every 30 min):
  collection, Health Connect import and `ParticipantDataRecorder` stop
  (`NotEnrolled`), records after the end are dropped locally, the rest
  uploads, and `NavGraph` shows the library-owned `StudyEndedScreen` (not
  replaceable) with withdrawal. `"endedAt"` is availability, not signed.
- Recovery usernames are pseudonymised: the app sends only
  `UsernameToken.of(username)` (PBKDF2 of the normalised name,
  `crypto/UsernameToken.kt` — a contract, changing it orphans every account);
  the backend stores `HMAC(Vault 'username_pepper', token)` in
  `participants.username_token`. No plaintext username exists server-side.
- Key rotation (recovery on a new device) is challenge-gated:
  `request_key_rotation_challenge` → `rotate_participant_keys` checks an
  HMAC proof keyed by the rotation key = HMAC(DEK, label)
  (`crypto/KeyRotationAuth.kt`; message format duplicated in
  `rls_policies.sql` — **keep in sync**), so only whoever decrypted the
  recovery blob can replace the participant's key. Since API 4 also
  `KeyRotationAuth.passphraseProof`, keyed by `ParticipantCredentials.passphraseKey`
  (PBKDF2 of the passphrase, registered at enrolment, **never stored** on a
  phone), so a phone that holds the DEK but not the passphrase can't rotate.
- **Device binding** (API 4, `docs/design/participant-accounts.md` §13): only
  ONE login session acts for a participant — `participants.bound_session_id`
  (the JWT `session_id`), required by `owns_participant` / `my_pseudo_id` /
  `belongs_to_study`; account-level `owns_participant_account` only for the
  recovery steps and binding. The phone holds a random **device key**
  (`auth/DeviceKeyStore`, registered at enrolment/recovery); a new login of it
  re-attaches via `request_session_challenge` → `bind_session` (HMAC proof,
  `crypto/DeviceBindingAuth.kt` — message format duplicated in
  `rls_policies.sql`, **keep in sync**). `SupabaseMeasurementRepository.ensureSession`
  runs the check once per login session (only with stored credentials — not
  during enrolment/recovery); refused uploads/RPCs re-check (`asParticipant`).
  Recovery on another phone replaces the key and binds its session, then
  `signOut(OTHERS)` → the old phone gets `DeviceReplacedException`:
  `collection/DeviceReplaced` stops all work and `NavGraph` shows the
  library-owned `DeviceReplacedScreen` ("Remove from this phone" = local wipe).
  Participants enrolled before API 4 stay unbound (any session works) until the
  first updated app claims them (`register_device_key`).
- Study definitions are **signed by the PI**: the wizard signs the exact
  definition text (`setup-wizard/.../StudySigning.kt`: studyId, issuedAt,
  name, version, PI key, dataTypes, schemaDataTypes) with the PI-held
  study-signing key (`backend/keys/study_signing_*`, never uploaded) into
  `StudyConfig."signedDefinition"` / `"definitionSignature"`. With
  `MbPulseConfig.studySigningPublicKeyB64` pinned, `getStudyConfig` verifies
  it (`crypto/StudyDefinitionSignature.kt` — format in sync with the wizard)
  and uses ONLY the signed text; `LoadingViewModel` refuses older `issuedAt`
  (rollback). `seed_study()` checks the text matches the columns it writes.
- **Participant accounts** (`docs/design/participant-accounts.md`): the
  recovery credentials ARE a Supabase account — email
  `<participant_login_id(token)>@participants.invalid` (peppered, no username),
  password `ParticipantCredentials.authSecret` (PBKDF2 of the passphrase; the
  passphrase itself never leaves the device). Created at enrolment
  (`signUpParticipant` → `enroll_participant` binds `auth_user_id`); recovery
  is a sign-in. Session sealed under the Keystore (`auth/ParticipantAuthStorage.kt`,
  custom `SessionManager`); `SupabaseMeasurementRepository.ensureSession()`
  silently re-signs-in with the sealed credentials. Withdrawal deletes the
  account (receipt `v2`). PI exports need a `study_staff` account.
- PI export is **paged** (the API caps responses at 1000 rows):
  `export_study_records(study, note, export_id, after_id, limit)` — the first
  call logs ONE `data_exports` row and opens an `export_sessions` row (caller,
  1 h, frozen at the max record id); later pages must quote it. The PI tool
  (`frontend/`) derives charts/CSV columns from the decrypted data + the
  study's `dataTypes`/`schemaDataTypes`, so custom schemas need no tool change.
- RLS threat model (`backend/rls_policies.sql`): anon key is public and
  reaches only pre-sign-in RPCs; every participant RPC/policy checks
  `auth.uid()` ownership via SECURITY DEFINER helpers (`owns_participant`, …;
  policies can't read `participants` themselves). Function EXECUTE is revoked
  from PUBLIC/anon/authenticated for everything in `public` (section 8) and
  granted explicitly — Postgres's global PUBLIC default can't be removed per
  schema, so **every new function needs an explicit grant**. Confidentiality
  still comes from E2E encryption.

## Backend runbook (Supabase)

Full provisioning guide: `backend/README.md`
("backend in a box" — includes `keygen.sh` for the PI + receipt keypairs).
Apply SQL in order (all idempotent): `00_schema.sql` → `rls_policies.sql` →
`join_code.sql` → `withdraw.sql` → `seed_study.sql` → `retention.sql`
(daily pg_cron purge: each record deleted `retentionDays` after its measurement
time, per data type via `StudyConfig."schemaDataTypes"` — the server-side copy
of `schemaToConsentType`, set by `seed_study()`). Then:

```bash
supabase functions deploy confirm-withdrawal   # JWT verified: caller must own the participant (+ device signature)
supabase secrets set DELETION_SIGNING_KEY_PKCS8_B64=<base64 PKCS#8 DER>
# join codes: Vault secrets named per join_code.sql conventions
```

**Backend API version** (app ↔ backend compatibility): every SQL file ends
with `stamp_backend_component('<file>', api, compatible_since)`, and the RPC
`backend_version()` (`00_schema.sql`; anon + authenticated) reports the lowest
`api`, the highest `compatibleSince` and files never applied. The library speaks
`BackendCompatibility.CLIENT_API` (`data/repository/`, pure + tested);
`LoadingViewModel.checkBackend()` runs it before the first study load and shows
a participant-facing mismatch (incomplete / backend too old / app too old) on
the loading screen; unreachable server = skipped (offline start unchanged).
**When a change alters what clients rely on** (RPC signatures, tables,
policies, signed formats): raise `api` in every file's stamp, `CLIENT_API`
and the wizard's `Db.BACKEND_API` together, and add the step to the list in
`00_schema.sql`; raise `compatible_since` too if older apps stop working.
The wizard's database check reports files stamped below `BACKEND_API`.

Studies are **defined as data**: copy `study.example.json`, edit it (name,
version, `dataTypes` array of `{id, name, description, purpose, retentionDays,
isEssential, samplingSeconds?}`, optional Vault `joinCode`), then
`bash seed_study.sh study.json`. `samplingSeconds` caps how often that type is
recorded — enforced library-side by `SamplingGate` in `EpochSink` (per schema,
via `schemaToConsentType`) for both live collection and HC import; omitted =
the processor's natural cadence. Shown to participants on the consent screen.
The server-side `seed_study()` function validates the whole definition and
reports every problem in one error before applying anything. The consent flow
renders directly from `dataTypes`.

## Known gotchas

- KSP occasionally fails with a transient `NoSuchFileException` — rerun.
- PowerShell splits an unquoted `-Pname.with.dots=value` at the first dot
  (Gradle then reports "Task '.librarySource=..' not found") — quote it:
  `"-Pmbpulse.librarySource=.."`, `"-Pmbpulse.snapshot"`.
- `hiltViewModel` moved to `androidx.hilt.lifecycle.viewmodel.compose` (old
  `androidx.hilt.navigation.compose` import is deprecated).
- pgcrypto lives outside `search_path=public` on Supabase — SQL functions use
  core `gen_random_uuid()` instead of `gen_random_bytes()`.
- Health Connect: permission requests silently fail on Android 14+ without the
  rationale activity/intent filters in the host manifest.
- Text fields: use `singleLine` + IME actions + `imePadding()`/`adjustResize`
  (see `RecoveryScreen.kt` for the pattern).
- `backend/` and `frontend/` are versioned, but their secrets are gitignored
  (`backend/keys/`, every `*.pem`, `*_pkcs8.der`) — never commit a key
  another way. `setup.md` (old notes) and `backend/study.json` (the
  researcher's own study) are local; leave them out of commits.

## Secrets policy

Never commit: Supabase keys (use `local.properties`), `Frontend/pi_private.pem`,
receipt-signing private keys (Edge Function secret only), Vault join codes.
