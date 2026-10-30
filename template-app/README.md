# template-app — starting point for a new study app

A clean, buildable host app for the mbPulse library. Out of the box it runs the
**entire pipeline on simulated data** (a bundled CSV replayed as a fake sensor):
enrolment, consent, encrypted collection, sync, history charts, and withdrawal
all work before a single line of sensor code exists.

Every place a study must customise is marked **`TODO(agent)`** — searching for
that string is the complete work list. **`TODO(researcher)`** marks what only
the researcher can supply (ethics-approved privacy wording). An AI agent (or developer) fills them in;
the integration contract is documented in the repo-root `AGENTS.md`.

## Make it your study — in order

This folder is a **standalone Gradle build** — its own `settings.gradle.kts`,
Gradle wrapper and `gradle/libs.versions.toml` — that uses the library as a
pinned Maven artifact, `pt.ua.mbpulse:mbpulse` (version
`mbpulse` in the catalogue). It is not part of the repository's root build.

0. **Copy, don't edit** — copy this folder to its own location (e.g.
   `../my-study-app/`, skipping `build/`, `.gradle/`, `.kotlin/` and
   `local.properties`). The steps below happen in the copy; `template-app/`
   stays the clean starting point for the next study.
   - Publish the library once, in the mbPulse repository:
     `.\gradlew.bat :mbpulse:publishToMavenLocal`. The copy's
     `settings.gradle.kts` finds it in `~/.m2` (or add the repository it is
     hosted in).
   - Pin it: `mbpulse = "<version>"` in `gradle/libs.versions.toml`. Keep
     `agp`/`kotlin`/`ksp`/`hilt` at the library's versions (Hilt's generated
     code must match) — the catalogue's header says where the library's
     build declares them.
   - `git init` — the study app is its own repository.

1. **Backend** — follow `backend/README.md`
   (schema → RLS → keys → study definition → Edge Function). The app reads the
   project's URL/key and the SDK path from its own `local.properties`
   (gitignored). The setup wizard writes it into the app folder when it writes
   the agent brief, from the repository's `local.properties` (where step 1
   keeps the credentials). Without the wizard, create it yourself. Inside the
   mbPulse repository, `template-app/` also falls back to the
   repository's file for anything its own does not set, so it runs in place.
   The file looks like this:
   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   mbpulse.supabaseUrl=https://<ref>.supabase.co
   mbpulse.supabaseKey=<anon key>
   ```
2. **Identity** — `settings.gradle.kts`: `rootProject.name`;
   `build.gradle.kts`: `namespace`/`applicationId` + rename the source package
   (then `.\gradlew.bat clean` — stale Hilt classes otherwise). Manifest:
   `android:label`.
3. **Sensor** — replace `CsvReplaySensorSource` in `StudyConfig.kt` with a
   `SensorSource` for the study's device (BLE example in the interface docs).
   Keep the CSV replay for development; put a recording of the real device in
   `src/main/assets/` once available. For a Bluetooth LE wearable, start from
   `BleSensorSource.kt` (fill in its service, characteristic and `decode`) and
   turn on `deviceSelection` in `StudyConfig.kt`. The library then adds a
   device step after onboarding, using Android's device chooser, and a device
   card on the home screen. The Bluetooth permissions are commented in the
   manifest.
4. **Processing** — extend `TemplateSignalProcessor` with the study's derived
   measures (full example: `E4SignalProcessor` in `sample-app/`).
5. **Consent mapping** — `schemaToConsentType` in `StudyConfig.kt` must cover
   every schema the processor emits, using the data-type ids from your
   `study.json`. Unmapped and mis-mapped schemas are dropped (consent fails
   closed) — both logged under the `EpochEncryptor` tag.
6. **Participant input** — questionnaires need no code: publish them in the
   setup wizard (step 3 → Questionnaires); the library renders them.
   `SymptomLog.kt` is a worked host screen for input a questionnaire does not
   fit (a one-tap symptom event, saved encrypted through
   `ParticipantDataRecorder`). Adapt it, or delete it and its registrations
   (`customSchemas`, `schemaToConsentType`, `hostScreens`, the `symptom_*`
   strings) if the study has none. It needs the `symptom_log` data type from
   `backend/study.example.json`; without it the screen says it is not consented.
7. **Optional** — Health Connect import sources (+ manifest permissions),
   screen overrides (`CustomHomeScreen.kt` is the worked, compiling example —
   swap `MbPulseApp()` for `AppWithCustomHome()` in `MainActivity.kt`),
   receipt-verification key.

Step-by-step playbooks for each of these — with verification checklists —
live in `docs/recipes/`.

## Build & run

In the app's folder (this one, or your copy):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug                                   # against the published library
.\gradlew.bat assembleDebug "-Pmbpulse.librarySource=.."      # against the library SOURCE (developing both)
```

`"-Pmbpulse.librarySource=<path to the mbPulse repository>"` swaps the
published artifact for the repository's `mbpulse/` (a Gradle
composite build), so library changes are tested without publishing. From the
repository root, `.\gradlew.bat -p template-app assembleDebug "-Pmbpulse.librarySource=.."`
builds the template itself. Open the folder in Android Studio on its own (it
is not in the root project).

## Check the app against the study (`handoff.json`)

The setup wizard writes `handoff.json` into a study app's folder with the
brief (step 4): the study's mapping, schemas, pinned keys, collection mode and
hashes of the backend credentials — no secrets, so commit it with the app.
`HandoffConformanceTest` (src/test) checks the app against it on the JVM:

```powershell
.\gradlew.bat testDebugUnitTest
```

It builds the config `StudyConfig.kt` returns, replays a CSV (`REPLAY` in the
test) through the processor, and reads the manifest. One test per area:

| Area | Fails when |
|---|---|
| identity | applicationId or the pinned library version differ from the study's; a snapshot or source-built library |
| backend | BuildConfig's URL or key is not the provisioned backend's |
| keys | the study-signing or receipt public key is missing or different |
| collection | collection mode or collection-screen access differ |
| mapping | a study schema maps to the wrong type or to null; an unknown or unlisted schema maps to a type; `supportedDataTypeIds` differ |
| schemas | a custom schema is missing, or its version, kind or fields differ; a declared schema the study does not have |
| processor | a study schema is never emitted; a point is unmapped, unregistered, timed outside the samples, reversed, has the wrong time-frame kind or no value; two points share an epoch start and schema; the processor factory returns one shared instance |
| manifest | the Application is not a `Configuration.Provider`; WorkManager's initializer not removed; the collection mode's prerequisite, `BLUETOOTH_CONNECT` for `deviceSelection`, or a Health Connect permission / rationale entry missing |

The full result is written to `build/reports/handoff-check.md`, including an
**audit** list that never fails: every log, file, network or storage call in
the sensor source and processor — the two components that see values before
encryption — for a person to read. Passing means the app is wired as the
study asked, not that its values are right.

The template itself has no `handoff.json`, so there the tests are skipped;
`"-Pmbpulse.handoff=<file>"` checks it against one. Never edit `handoff.json`
to make a check pass — regenerate it in the wizard if the study changed.

## Verify on a device

Verify end-to-end on a device/emulator: select the seeded study → consent →
create username/passphrase → collection starts by itself (passive mode; the
CSV replays in real time, looping — the first cards fill within about a
minute) → cards fill on the home screen → history charts show data → ⋮ →
*Withdraw from study* shows the deletion certificate.
