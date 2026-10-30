# Changelog

All changes from `bc2e2bf` ("backend function omission fix") to `398643b`,
plus the uncommitted work at the top ("Unreleased"), newest first. Grouped
by component: **Library** (`mbpulse/`, formerly `mhealth-library`), **Backend** (`backend/`),
**Wizard** (`setup-wizard/`), **PI tool** (`frontend/`), **Template**
(`template-app/`), **Sample** (`sample-app/`), **Docs**.

---

## Unreleased — PBKDF2 iterations raised to 600,000

**Library**
- `KeyRecoveryManager` (recovery blob) and `ParticipantCredentials` (`authSecret`, `passphraseKey`): 310,000 → 600,000 iterations, the OWASP recommendation for PBKDF2-HMAC-SHA256. Both are contracts: blobs and accounts made with 310,000 no longer open.
- `RecoveryViewModel` runs `recoverDek` off the main thread, like the other derivations.
- `UsernameToken` stays at 100,000: it pseudonymises a username and does not protect the passphrase.

---

## Unreleased — Library renamed to mbPulse

The library is now **mbPulse**: package `pt.ua.mbpulse` (was `com.clinicalTests.mHealth`), Maven `pt.ua.mbpulse:mbpulse:1.0.0` (was `com.clinicaltests.mhealth:mhealth-library`), module `:mbpulse` (folder `mbpulse/`). A clean break — no app or backend ran the old names, so nothing is migrated and nothing old is still accepted. Sections below use the names of their time.

**Library**
- Public API: `MHealth*` → `MbPulse*` (`MbPulseConfig`, `MbPulseLibrary`, `MbPulseApp`, `MbPulseNavHost`, `MbPulseTheme`, `MbPulseBranding`, `LocalMbPulseNavigator`, …), `mhealthTopAppBarColors` → `mbPulseTopAppBarColors`.
- String resources `mhealth_*` → `mbpulse_*` (host overrides use the new names).
- On-device names: database, key files, Keystore aliases, DataStores, notification channels `mhealth_*` → `mbpulse_*`.
- Formats: `clinicaltests-consent-v1/v2`, `-study-v1`, `-study-unsigned`, `-questionnaire-v1`, `-auth-v1`, `-rotate-v1`, `-key-rotation-v1`, `-username-token-v1` → `mbpulse-…`; built-in schema namespace `clinicaltests:` → `mbpulse:`; FHIR identifier system `urn:mbpulse:omh-datapoint`.
- Room schema folder `schemas/pt.ua.mbpulse.data.local.AppDatabase`.

**Backend:** `seed_study.sql` / `questionnaires.sql` accept only the `mbpulse-` formats; `study.example.json` uses `mbpulse:` schemas.

**Wizard / Template / Sample:** Gradle properties `mhealth.*` → `mbpulse.*` (`local.properties` keys, `-Pmbpulse.snapshot`, `-Pmbpulse.handoff`, `-Pmbpulse.librarySource`); handoff format `mbpulse-handoff-v1`; template catalogue key `mbpulse`; brief and docs use the new names. "Open mHealth" (the data standard) is unchanged.

**Checked:** 183 library tests and 6 wizard tests pass, `sample-app` builds, the template builds against the library source.

---

## Unreleased — Consent mapping fails closed

Before, a schema that `schemaToConsentType` mapped to `null` skipped the consent filter: its data points were encrypted and uploaded whatever the participant had accepted, with one logcat warning. `null` is also the default for every schema, so an app that never set the mapping uploaded everything unfiltered. `ParticipantDataRecorder` did the same. Now `null` means "belongs to no data type", so nobody consented to it and it is dropped.

**Library**
- New `collection/ConsentFilter` (pure, tested): a point is kept only when its schema maps to a type in the participant's accepted set. Unmapped → dropped; mapped but not accepted → dropped; an empty set keeps nothing.
- `EpochEncryptor` (live collection, Health Connect import, participant input) uses it; the once-per-schema warning now says the unmapped schema is dropped.
- `RecordPolicy` uses it too: an unmapped schema gives `NotConsented`, so the recorder's all-or-nothing rule refuses the whole batch.
- `MHealthConfigValidator` warns when a custom schema has no mapping (its points would be dropped) and when the mapper maps no schema at all (nothing would be collected).
- `SchemaMappingCheck`: the "app maps it to nothing" message says the app drops it.
- `MHealthConfig.schemaToConsentType` has a KDoc stating the rule.

**Template:** `HandoffCheck` messages and the `StudyConfig.kt` comment say an unmapped schema is dropped. The checks themselves are unchanged — they already failed on it.

**Docs:** AGENTS.md (`schemaToConsentType`), template README step 5, `integrate-a-sensor.md` checklist.

**Checked:** 183 library tests pass (5 new in `ConsentFilterTest`, 2 new validator tests, the `RecordPolicyTest` unmapped case flipped), `sample-app` builds. The sample app and the template map every schema they emit, so neither loses data.

---

## 2026-10-26 — Device step works on Android 11 and lower (`398643b`)

Found in the validation test (Fitbit Air over Bluetooth LE, an Android 11 phone). Before, the device step failed there in two ways. Below API 33, `CompanionDeviceManager.associate` casts the manager's context to `Activity`, and the library passed the application context, so opening the chooser threw `ClassCastException`. And Android 11 has no `BLUETOOTH_CONNECT`: `BluetoothAdapter.isEnabled()`, which the step checks before the chooser, and `connectGatt` need the legacy install-time `BLUETOOTH` / `BLUETOOTH_ADMIN`, which the template did not declare.

**Library**
- `DeviceAssociation.associate` takes the hosting `Activity` instead of a `Context`, and gets the Companion Device Manager from it.
- `DeviceSelectionViewModel.choose(activity)` and `onPermissionsResult(activity, granted)` pass it on; `DeviceSelectionScreen` takes it from `LocalActivity`.
- `DeviceSelection` KDoc lists the legacy permissions.

**Template**
- The manifest's commented Bluetooth block now also has `BLUETOOTH` and `BLUETOOTH_ADMIN` with `android:maxSdkVersion="30"`, and says why. `BleSensorSource` KDoc names them.
- `HandoffCheck` fails when `deviceSelection` is set and either legacy permission is missing, so the handoff check catches the second failure.

**Wizard:** the brief tells the agent to uncomment the legacy permissions with the rest of the Bluetooth block.

**Docs:** AGENTS.md (the `deviceSelection` entry), `docs/recipes/integrate-a-sensor.md` step 4.

**Checked:** the fixes are the ones made in the validation app and its copy of the library. Here, 176 library tests pass, `sample-app` builds, the wizard tests pass, and the template builds with its handoff tests skipped as designed. The new `HandoffCheck` rule has not been run against an app with `deviceSelection` set.

---

## 2026-10-26 — Handoff check: study apps are tested against the study they were built for (`83a5e16`)

Before, the brief's definition of done was a list of checkboxes the agent ticked itself, and nothing compared an app with what the study asked for. Nothing compared the two copies of the schema → data-type mapping either: the app's copy decides consent filtering, the server's (`schemaDataTypes`) decides retention, and they agreed only because one form produced both. A wrong mapping uploads data without consent filtering, or drops consented data, and no error appears anywhere.

**Wizard**
- New `Handoff.kt`. Writing the brief now also writes `<app folder>/handoff.json` (format `clinicaltests-handoff-v1`), next to the app's `local.properties`. It is the brief in machine-readable form:
  - the mapping, `supportedDataTypeIds`, the data types and whether each is essential;
  - the custom schemas field by field (kind, unit, enum values), the event schemas, and the schemas the template declares (`mystudy:symptom-log`);
  - the collection mode, collection-screen choice, both public keys, the library version and the package id;
  - SHA-256 hashes of the backend address and key, read the way the app's Gradle build reads them (`java.util.Properties`, unescaped), never the values.
- It holds no secret, so the app commits it. Step 4 says where it went.
- `Brief.mapping()` and `Brief.supportedDataTypeIds()` are shared by the brief and the handoff, so the two cannot disagree.
- Brief changes:
  - the config goes into `StudyConfig.kt`;
  - step 2 keeps the wizard's `handoff.json`;
  - step 5 renames the package in `src/test/java` too;
  - the definition of done requires `testDebugUnitTest` to pass with none skipped, never by editing `handoff.json` or the test;
  - the final report includes the check's audit list.
- First wizard unit tests (`HandoffTest`, 6): mapping and supported types equal the brief's, kinds and units and enum values, events and template schemas, no secret in the file (the hash is checked against the SHA-256 "abc" vector), missing values are `null`, collection mode. `setup-wizard` gains `testImplementation(junit)`.

**Template**
- New `StudyConfig.kt`: `studyConfig(context)` builds the whole `MHealthConfig`, moved out of `TemplateApp.onCreate`, so the test checks exactly what the app starts with. `TemplateApp` only checks the credentials and calls `MHealthLibrary.init(studyConfig(applicationContext))`.
- `CsvReplaySensorSource.readSamples()` is the CSV parsing pulled out of the flow (pure, raw timestamps). Replay behaviour is unchanged.
- New JVM test `HandoffConformanceTest` (+ `HandoffCheck`, `Handoff` in `src/test`), run with `.\gradlew.bat testDebugUnitTest`. One test per area:
  - **identity**: applicationId and the pinned `mhealth` version match the study; no snapshot; the library comes from the published artifact (a source build is a warning inside this repository and a failure outside it);
  - **backend**: BuildConfig's URL and key hash to the study's;
  - **keys**: both public keys are set and equal;
  - **collection**: the mode and collection-screen access;
  - **mapping**: every study schema maps to its type, an unknown schema to null, and no unlisted built-in or custom schema to a type; mapped types exist; `supportedDataTypeIds` match;
  - **schemas**: each custom schema is declared with the same version, kind and fields in order; none is declared that the study lacks; basic shape (fields, units, enum values);
  - **processor**: a CSV (`REPLAY`) goes through the real `signalProcessor()`:
    - the factory must return a fresh instance;
    - every study schema must appear, except events and the ones in `NOT_FROM_PROCESSOR`, which holds each with its reason and is listed for the reviewer;
    - each point must be mapped, registered and of the registered version, with the right time-frame kind (instant for events), timed within the replayed samples (catches clock stamps and wrong units), not reversed, serialisable (no NaN) and carrying a value;
    - no two points may share an epoch start and schema, which the backend would keep only once;
    - a schema whose values are all equal and repeated header ids are warnings;
  - **manifest**: the Application is a `Configuration.Provider`; WorkManager's initializer is removed; the collection mode's Android 14 prerequisite is declared, plus `BLUETOOTH_CONNECT` for `deviceSelection`; each Health Connect source's permissions, history and background permissions, and the rationale activity and alias are present; `allowBackup` is a warning.
- Every result, plus an **audit** list that never fails, goes to `build/reports/handoff-check.md`. The audit lists each log, file, network or storage call in the classes implementing `SensorSource` or `SignalProcessor`, for a person to read.
- Without `handoff.json` the tests are skipped in the template itself (applicationId `com.example.mystudy`) and fail in a renamed app. `-Pmhealth.handoff=<file>` supplies one.
- `build.gradle.kts`: `unitTests.isReturnDefaultValues`, the handoff path and project dir passed to the test, and the handoff file as a test input. Catalogue: `junit` 4.13.2 and `kotlinx-serialization-json` 1.11.0 (the library's), test-only.
- README: a "Check the app against the study" section. The manifest, `TemplateSignalProcessor` and README now point to `StudyConfig.kt`.

**Library**
- New `data/repository/SchemaMappingCheck` (pure, 6 tests). On every study load, `SupabaseMeasurementRepository` compares `MHealthLibrary.consentTypeFor` with the study's `schemaDataTypes`:
  - from the verified signed text when a key is pinned, otherwise from the new `"schemaDataTypes"` column in the select;
  - over the app's registered schemas (questionnaire answers excluded, since they map through their questionnaire);
  - it logs each disagreement under the tag `SchemaMapping`: different types, unmapped on one side, no server copy at all, or a throwing mapper.
- Only a change to the result is logged again. It is diagnostic only: consent filtering keeps following the app's copy and retention the server's.

**Docs:** AGENTS.md (the handoff check, the `SchemaMapping` tag, `StudyConfig.kt` in the module map), `docs/recipes/integrate-a-sensor.md` (test item; a duplicated checklist line removed) and `add-a-data-type.md`.

**Checked**
- 176 library unit tests and 6 wizard tests pass. `sample-app` and the template build (`assembleDebug`, against the library source).
- Template tests:
  - without a handoff, 8 tests are skipped;
  - against a handoff the wizard would write for the template's default study, they report exactly the 3 values an agent still has to paste: both keys and `supportedDataTypeIds`;
  - a deliberately broken copy got 17 FAILs: wrong and extra mapping, an undeclared custom schema, a shared processor instance, clock-stamped and reversed points, duplicate epoch starts, the WorkManager initializer not removed, a wrong key and a source-built library. A planted line logging the heart rate appeared in the audit list.
- The `SchemaMapping` comparison has not run against a real backend yet.
- **Known:** the template pins library 1.0.0, which was published before `e36cd41` and has no device selection, so the template does not build against the published artifact until a 1.1.0 release. This predates this change.

---

## 2026-10-26 — PI tool runs stand-alone (`83a5e16`)

Before, the PI tool loaded Chart.js, hammerjs and chartjs-plugin-zoom from cdn.jsdelivr.net. Third-party code from another server ran in the page that holds the PI private key, and the tool needed that server to work.

**PI tool**
- The three libraries are copied into `frontend/vendor/` at the pinned versions (Chart.js 4.4.1 `chart.umd.js`, hammerjs 2.0.8, chartjs-plugin-zoom 2.0.1), with their MIT licences.
- Each npm tarball's SHA-512 was checked against the registry's `dist.integrity` before extraction, and the files are unmodified. `vendor/README.md` records both hashes and how to upgrade.
- `index.html` loads the local copies and sets a Content-Security-Policy:
  - scripts and styles only from the tool's folder (`'unsafe-inline'` remains for the existing `onclick` handlers);
  - network only over `https:`;
  - `default-src 'none'`, no `<base>` and no form submission.
- The tool runs from `file://` with no server and no network beyond the backend.

**Docs:** AGENTS.md module map.

**Checked:** in headless Chrome from `file://`, the page is a secure context with Web Crypto, all three libraries and the app's functions load, and there are no CSP violations. A test page confirmed that a CDN script is blocked.

---

## 2026-10-25 — Device binding: a recovered account locks out the old phone (backend API 4) (`e36cd41`)

Before, after a participant recovered their account on phone B, phone A kept working. A still held the account password (sealed for silent re-login), and the backend only checked the account, so A's uploads, reads and questionnaire answers were accepted. Revoking A's session would not have helped, because A signs in again by itself. A also holds the data key, so it could even run key rotation and take the account back. Design: `docs/design/participant-accounts.md` §13.

**Backend** (API 4, compatible since 3; every SQL file's stamp raised)
- `00_schema.sql`:
  - `participants.device_key_b64` and `bound_session_id`;
  - `participant_rotation_keys.passphrase_key_b64`;
  - a new `session_challenges` table;
  - API step 4 in the list.
- `rls_policies.sql`:
  - `current_session_id()` (the JWT `session_id`) and `session_acts_for()`.
  - `owns_participant`, `my_pseudo_id` and `belongs_to_study` (participant branch) now require the bound session. A participant with no binding is accepted from any session, as before.
  - New account-level `owns_participant_account`, used only for `request_key_rotation_challenge`, rotation and binding.
  - `enroll_participant` gains `p_device_key_b64` and `p_passphrase_key_b64` (both default null), and binds the session when a device key is given. An identical retry from a new login of the same phone binds that login.
  - `rotate_participant_keys` gains `p_device_key_b64`, `p_passphrase_key_b64` and `p_passphrase_proof_b64`:
    - once a passphrase key is registered, it requires the passphrase proof (`passphrase_proof_rejected`); otherwise it registers one;
    - it stores the new device key, binds the caller's session and clears pending session challenges.
  - New `my_session_status()`, `request_session_challenge()`, `bind_session()` (`device_replaced` when the proof fails) and `register_device_key()` (first binding of a participant enrolled before this change, only while nothing is bound). All have explicit grants.
- The withdrawal Edge Function is unchanged: its challenge goes through `owns_participant`, and the signature check already uses the replaced key.

**Library**
- New `crypto/DeviceBindingAuth`: the device key, the `bind|…` proof, `sessionIdOf(JWT)`, and the pure `nextStep` decision.
- `KeyRotationAuth.passphraseProof` and `ParticipantCredentials.passphraseKey` (`clinicaltests-rotate-v1|`, never stored). `authSecret` now shares the derivation and is unchanged; the existing test proves it.
- New `auth/DeviceKeyStore`, sealed like the credentials and cleared by `signOutParticipant`.
- `SupabaseMeasurementRepository`:
  - `ensureSession()` runs the binding check once per login session, only when credentials are stored (so not during enrolment or recovery). The check re-attaches a new login, registers a first binding, or throws `DeviceReplacedException`.
  - New `asParticipant {}` re-checks when uploads, consent or withdrawal are refused ownership (`42501` / "not your participant").
  - New `checkDeviceBinding()` and `signOutOtherSessions()`.
- Enrolment sends the device key and passphrase key. Recovery sends the passphrase proof and new device key, keeps the key, then signs out all other sessions.
- New `collection/DeviceReplaced`, a flag kept in the enrolment store:
  - when set, it stops collection, Health Connect import and uploads;
  - it also stops `ParticipantDataRecorder` (`NotEnrolled`), `BackgroundWork.enable/end` and `PassiveCollection.resume`.
  - `RecordUploader` sets it and returns the new `DEVICE_REPLACED`.
- `LoadingState.Ready.deviceReplaced`: `LoadingViewModel` checks it on load when online.
- New library-owned `DeviceReplacedScreen` (not replaceable, shown before "study ended"):
  - text: "This account is now used on another device", a warning about readings that were not uploaded, and "contact the research team if this wasn't you";
  - its only action, "Remove from this phone", calls `LoadingViewModel.removeReplacedDevice` (a local wipe, shared with the development reset).
- `BackendCompatibility.CLIENT_API` = 4.

**Wizard:** `Db.BACKEND_API` = 4. The database check expects `session_challenges` and the new functions.

**Docs:** AGENTS.md security section, and §13 of the design document.

**Checked:** 170 library unit tests pass, including new ones for the proof formats, standard HMAC (RFC 4231) as the SQL computes it, the passphrase key and the binding decision. `sample-app` builds and the wizard compiles. The SQL has not been run: it needs `00_schema.sql` and `rls_policies.sql` re-applied, then the other files, and a two-phone recovery test on a device.

---

## 2026-10-25 — Device-selection step and connected status for Bluetooth LE wearables (`e36cd41`)

Before, every study with a Bluetooth device wrote its own scan screen, permission handling and remembered device. The validation app's came to about 350 of its roughly 1,000 lines. The library now owns choosing the device. The study still owns connecting to it and decoding its data, so the library stays sensor-agnostic.

**Library**
- New `MHealthConfig.deviceSelection = DeviceSelection(serviceUuids, namePattern)`. The default `null` means no step.
  - The participant picks the device in Android's Companion Device Manager chooser (`collection/DeviceAssociation.kt`). The system scans and lists only devices advertising one of `serviceUuids`, so the app needs no `BLUETOOTH_SCAN` or location permission.
  - On Android 12+ the step requests `BLUETOOTH_CONNECT` first, since connecting needs it and it is the `connectedDevice` prerequisite.
  - After choosing, it calls `PassiveCollection.resume`.
  - `DeviceSelection.bluetoothUuid(0x180D)` expands 16-bit SIG ids.
- New public `SelectedDevice(address, name)` with `observe(context)` / `current(context)`, which is how a sensor source learns what to connect to.
  - It is stored in the enrolment store (`DeviceSelectionStore`, with the association id on API 33+).
  - Withdrawal and reset clear it and end the association (`BackgroundWork.disable`).
  - Changing devices ends the old association.
- Navigation (`NavGraph`):
  - Until a device is chosen, the library-owned `DeviceSelectionScreen` is shown in place of any post-onboarding screen on every start, after study-ended and re-consent. "Not now" postpones it until the next start.
  - New route `DeviceSelectionKey` and new `MHealthNavigator.openDeviceSelection()`.
  - The home screen has a device card (name, "Change"; highlighted while none is chosen).
  - The step is not replaceable; wording comes from the new `strings_device.xml` (`mhealth_device_*`).
- **Connected status.** New public `DeviceConnection.report(DeviceConnectionState)` (`CONNECTING` / `CONNECTED` / `DISCONNECTED`), which the sensor source calls, since only it knows the device protocol.
  - `CollectionService` records when the last sample arrived (at most once per second). When the stream ends, it marks a reported link `DISCONNECTED`.
  - The state is in memory, per process.
  - The device card and the device screen show "Connected · last data 1 min ago", "Connecting…" or "Not connected since …". A source that never reports gets "Receiving data" while samples arrived in the last 2 minutes. The card is highlighted while disconnected.
  - `DeviceStatusCard()` is public for custom home screens and shows nothing without `deviceSelection`.
  - The display logic is pure and covered by 6 tests (`DeviceConnectionTest`).
- The validator reports an invalid `namePattern` as an error, and the step combined with `PASSIVE_HEALTH` as a warning. Four new tests.
- The library manifest declares `android.software.companion_device_setup` as not required.

**Template**
- New `BleSensorSource.kt`, a base example rather than a ready-made driver:
  - follows `SelectedDevice` (a change applies to the running session);
  - one GATT connection per attempt, subscribing to one notifying characteristic;
  - reconnects with backoff (1 s … 60 s) and keeps timestamps strictly increasing;
  - reports `CONNECTING` when connecting, `CONNECTED` once notifications are on, and `DISCONNECTED` on close or with Bluetooth off;
  - `whenNoDevice` allows the CSV replay in debug builds;
  - `TODO(agent)` for `SERVICE_UUID` / `CHARACTERISTIC_UUID` (Heart Rate values as placeholders) and `decode()`, with the Heart Rate Measurement parsing as the documented example.
- `TemplateApp.kt` has `deviceSelection = null` and commented wiring. The manifest has the commented Bluetooth and companion permissions. The default build still runs on the CSV replay unchanged.

**Docs**
- AGENTS.md: the config entry, the public API list, and the non-replaceable screens.
- `docs/recipes/integrate-a-sensor.md`: Bluetooth LE path, permissions, and checklist items.
- Template README step 3.

**Checked**
- Library unit tests pass: 25 in the validator test and 6 in `DeviceConnectionTest`, including the new ones.
- `sample-app` builds, and the template builds against the library source.
- Not yet tested on a device.

---

## 2026-10-25 — Study apps get their credentials from the wizard (`7d96a42`; `sdk.dir` detection in `e36cd41`)

Before, the wizard kept the backend credentials only in the repository's `local.properties`. The standalone template and every study app read their own file, so the template needed its own copy, and the agent had to copy the Supabase key into a generated app by hand.

**Wizard**
- Writing the agent brief now also writes the study app's `<app folder>/local.properties` (`writeAppLocalProperties`). It contains `sdk.dir`, `mhealth.supabaseUrl` and `mhealth.supabaseKey` from the repository's file.
  - It updates only those keys and keeps every other line.
  - It creates the folder if needed.
  - It refuses a folder that is one of the repository's own modules (`template-app`, `mhealth-library`, `sample-app`) or the repository itself.
  - Writing the brief again refreshes the values, e.g. after new credentials.
- Step 4 says where the file went, or which keys were missing (credentials not set in step 1 yet).
- New helper `upsertProperties`, now also used by `writeCredentials`.
- A checkout never opened in Android Studio has no `sdk.dir`, so the study app got none and its build failed. If the repository's file has no `sdk.dir`, saving credentials or writing the brief now adds one there first, and the app's file copies it from there. The wizard takes the path from `ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then the installer's default location (`%LOCALAPPDATA%\Android\Sdk`, `~/Library/Android/sdk`, `~/Android/Sdk`), and escapes it for the properties format. Publishing the library from that checkout needs it too.
- Brief changes:
  - step 4 is now "already done by the wizard": the agent checks the three keys are there without printing them, and stops if one is missing;
  - step 2 copies the template's *contents* into the existing folder and never overwrites the wizard's `local.properties`.

**Template**
- `build.gradle.kts` reads its own `local.properties` first, then, inside the ClinicalTests repository (detected by `../mhealth-library`), the repository's file for anything its own doesn't set. A blank value in its own file doesn't hide the repository's. So `template-app/` runs in place without a copy of the credentials.
- A study app in its own folder doesn't find the repository and uses only its own file, which the wizard writes.
- `README.md` describes both.

**Checked**
- A temporary template copy with no credentials of its own got exactly the repository's values in `BuildConfig`.
- The wizard wrote a test app folder's file: the three keys equal the repository's, an existing unrelated line was kept, and a stale value was replaced.
- `template-app` as the app folder is refused.

---

## 2026-10-24 — Library publishing; app ↔ backend version check; standalone study apps (`1e228ec`)

Three changes:
- **Publishing:** the library can be published as a Maven artifact, so a study app outside this repository can use it.
- **Version check:** app and backend now check that they match. Before, an app and a backend from different versions failed late and obscurely, e.g. an enrolment refused because an RPC signature had changed.
- **Standalone template:** the template, and every study app copied from it, is now its own Gradle build that pins a library version. Before, study apps were modules of this repository's build, compiled against the library's source.

**Template**
- `template-app/` is a standalone build. It has its own:
  - `settings.gradle.kts`;
  - Gradle wrapper (`gradlew`, `gradle/wrapper/`);
  - `gradle/libs.versions.toml`;
  - `gradle.properties` and `.gitignore`.
- It's no longer included in the root `settings.gradle.kts`.
- It uses `com.clinicaltests.mhealth:mhealth-library`, pinned as `mhealth = "1.0.0"`, from `mavenLocal()`. The `mavenLocal()` repository is limited to the library's group.
- The plugins come from its own catalogue (`ksp`, `hilt`). Their versions match the library's build, since Hilt's generated code must match.
- `-Pmhealth.librarySource=<repo>` builds against the library's source instead, as a Gradle composite build with dependency substitution, so library changes can be tested without publishing. From the repository root: `.\gradlew.bat -p template-app assembleDebug -Pmhealth.librarySource=..`.
- `rootProject.name` is a new `TODO(agent)`.
- The README covers the copy steps, version pinning, the copy's own `local.properties`, and both build modes.

**Wizard**
- The agent brief no longer creates a module in this repository. Its steps now are:
  1. publish the library to Maven Local;
  2. copy the template to the app's own folder;
  3. pin the library version;
  4. create the copy's `local.properties` from this repository's;
  5. rename the package;
  6. `git init` the copy.

  All Gradle commands run in the app's folder, without a module prefix.
- The definition of done now checks that this repository is unchanged and that no `local.properties` is tracked in the app.
- The brief pins the library `version` read from `mhealth-library/build.gradle`.
- Step 4 has a new optional **App folder** field. Left empty, the app goes in a folder next to the repository, e.g. `../sleeptrack-app`.

**Library**
- `maven-publish`: `com.clinicaltests.mhealth:mhealth-library:1.0.0`, the release AAR plus a sources jar. `.\gradlew.bat :mhealth-library:publishToMavenLocal` puts it in `~/.m2`. Apps in the repository still use `project(":mhealth-library")`.
- Dependencies whose types appear in the public API are now `api`, so a host project outside the repository can compile against it:
  - coroutines (`Flow` in `SensorSource`);
  - the Compose BOM, runtime and UI (the composables and slot types);
  - Material 3 (`MHealthBranding`).
- New `BackendCompatibility` (pure): `CLIENT_API = 3`. It reports one of:
  - compatible;
  - backend incomplete (an SQL file was never applied);
  - backend too old;
  - app too old.
- New repository call `getBackendVersion()`. A backend from before versioning (no `backend_version()`, PGRST202) counts as version 0.
- `LoadingViewModel` checks the backend before the first study load, and again on Retry. A mismatch shows a participant-facing message on the loading screen (new `BackendMismatchException`). When the server can't be reached the check is skipped, so the app still starts offline.
- New strings: `mhealth_backend_incomplete`, `mhealth_backend_too_old`, `mhealth_app_too_old`.

**Backend**
- `00_schema.sql`:
  - new `backend_components` table and `stamp_backend_component()` (admin only);
  - new `backend_version()`, callable before and after sign-in. It returns `{api, compatibleSince, missing}`;
  - the list of API versions so far: 1 before definitions were tracked, 2 consent v2, 3 join tickets and study end.
- Every SQL file stamps its part at version 3, compatible since 3.

**Wizard**
- The database check includes the new objects. It also reports SQL files stamped below `Db.BACKEND_API`, or never applied.

**Docs**
- `AGENTS.md`:
  - publishing, in the build section;
  - the backend API version, with when and how to raise it;
  - the standalone template, in the module map and build commands.
- `backend/README.md`: the version check, and to re-run every file after an upgrade.
- `docs/recipes/README.md`: study apps start from a copy in their own folder. Recipe checklists run `.\gradlew.bat assembleDebug` in the app's folder, instead of `:template-app:assembleDebug`.

**Tests**
- `BackendCompatibilityTest`.

**Fixes from a full agent run of the brief**

An agent built "SleepTrack" from a rendered brief, in a standalone folder against published 1.0.0. Debug and release built; the device checks were not run. Its findings:
- **Template replay drifted into the future.** The template replayed at 30× in passive mode, which loops for as long as the app runs, so each loop pass started further ahead of the clock.
  - The template now replays in real time (`speedFactor = 1.0`).
  - `CsvReplaySensorSource` re-syncs every loop pass with the wall clock: it waits when ahead and jumps to now after a pause. A fast replay is now at most one pass ahead.
- **A pinned version didn't pin the code.** `publishToMavenLocal` published the working tree, uncommitted changes included, as "1.0.0".
  - A release version (`releaseVersion` in `mhealth-library/build.gradle`) now publishes only from committed library code.
  - The POM records `gitCommit`.
  - Republishing a release from a different commit is refused.
  - Work in progress publishes with `-Pmhealth.snapshot` (→ `1.0.0-SNAPSHOT`, commit recorded with `-dirty`).
- **Brief fixes:**
  - **Custom schemas:** schemas the template already declares (`mystudy:symptom-log` → `SymptomLog.schema`) are now named in "customSchemas" instead of "none", with a section saying to keep that code. SymptomLog is now kept or deleted depending on whether the study maps its schema.
  - **Missing definitions:** a schema the study maps but nothing defines gets a "stop and report" section.
  - **Questionnaires:** data types without an app schema are labelled "backend questionnaire", with a section explaining that the library fills them and no app code is needed.
  - **Repository check:** a new step 0 records `git status --porcelain`, and the final check compares against it, so the check also works in a repository with uncommitted work.
  - **Publishing refused:** if the library publish is refused, the agent must stop and tell the researcher, not fall back to a snapshot or the library source. It skips the publish when the version is already in `~/.m2`.
  - **Study mismatch:** if study selection doesn't show the study, the agent must stop. It must never re-seed. The `backend/study.json` statement is corrected.
  - **Release build:** the exact `src/release/AndroidManifest.xml` that removes the unused foreground-service permissions, per mode, and a note that release APKs are unsigned.
  - `TODO(researcher)` markers are left in place and listed in the agent's report.
  - Empty "screens" and "Health Connect" notes now show their defaults instead of an empty quote.
- **Template:** the privacy-rationale stub is `TODO(researcher)` (ethics-approved wording); `themes.xml` names `MHealthTheme`; the catalogue comment says where the library's build declares the KSP and Hilt plugin versions.
- **Library:** validator messages point to `backend/README.md` and `backend/keygen.sh` (not `supabase/`).
- **Recipes:** integrate-a-sensor's checklist covers passive collection (starts by itself, resumes after reboot), not only session mode. replace-a-screen names `MHealthTheme`.

**Release hardening**
- **Logging in release builds:** the library logged pseudonyms, raw study definitions, the raw study list and export rows in every build.
  - All library code now uses `logging/Log` (same calls as `android.util.Log`). Debug and info messages are written only when the *host app* is debuggable; warnings and errors are always written, and contain no participant identifiers.
  - `LogInitializer`, registered on androidx.startup's `InitializationProvider` in the library manifest, sets this at process start in every process (UI, collection service, workers, boot).
  - Both host apps keep the provider: they only remove WorkManager's initializer, as the merged release manifest confirms. If a host removes the whole provider, debug logging stays off.
  - New dependency: `androidx.startup:startup-runtime:1.2.0`, the version WorkManager already brought.
- **Database migrations tested:** `AppDatabase` exports its schema (`exportSchema = true`, `room.schemaLocation` → `mhealth-library/schemas/`, committed). The new `AppDatabaseMigrationTest` runs on the JVM with real SQLite (`sqlite-jdbc`, test only), so no device is needed:
  - it starts from a frozen version 4 schema and runs `MIGRATION_4_5` and `MIGRATION_5_6`;
  - it checks that a synced copy wins over duplicates, failed uploads are re-queued, unrelated records are untouched, and sleep rows become categories;
  - it checks every table's columns and indices against the exported version 6 schema, that every version from 4 has a migration, and that the unique index rejects a duplicate afterwards.

  Breaking the 4→5 migration on purpose (index not unique) makes two of the tests fail, so they catch real mistakes.

**Curated public API**
- 88 library declarations that no host uses are now `internal`:
  - **data layer:** the database, entities and DAOs; repositories and Supabase modules; caches, DTOs and exceptions; questionnaire models and registry; UCUM table;
  - **pipeline:** the collection service and boot receiver, workers, uploader, encryptor and sink, enrolment store, importers, sampling gate, requirements check;
  - **internal UI:** charts and cards, the status panel, the questionnaire form, the Health Connect import screen, colour and type constants, `LocalMHealthBranding`.

  `AGENTS.md` ("Public API") lists what stays public, and the rule for changing it.
- **Public view models:** `CollectionViewModel`, `HistoryViewModel`, `StudySelectionViewModel` and `CollectionMeasurementsViewModel` now have `@Inject internal constructor`s, like `LoadingViewModel`.
- **Simpler composables:** `SyncStatusCard()` and `QuestionnaireCards()` no longer take a view-model parameter, so their view models are internal.
- **`PassiveCollection.start`** is internal; hosts use `resume`.
- **Explicit API mode:** `explicitApi()` in `mhealth-library/build.gradle`. Every public declaration and member now says `public`, which took 195 modifiers across 32 files, and has an explicit type (9 added: the `OmhSchemas` ids, `OmhEffectiveTimeFrame.instant`, `LocalMHealthNavigator`, `MAX_HEALTH_CONNECT_IMPORT_DAYS`). A declaration without them fails the build, so nothing becomes API by accident.
- **`HistoryUiState.entries`** is now `List<HistoryEntry>` (time, value, display text, unit, category) instead of the Room entity, so the database schema isn't part of the API. The chart data (`SeriesData`, `CategorySegment`, `CountBar`) is public.
- **Removed:**
  - the library's unused `ui/MainActivity` (in no manifest; both apps have their own);
  - the deprecated `MeasuresTheme` alias.

**Repository clean-up**
- **Untracked:** `setup-wizard/bin/` (VS Code Java build output, now ignored with `bin/`) and `.idea/` (already ignored, but tracked from before). Both are removed from the index only; the files stay on disk.
- **sample-app:** trimmed to the dependencies its code uses. It no longer declares the Supabase clients, Ktor, serialization (and its plugin), Room, DataStore, the Vico chart library, navigation-compose, icons or mockk.
- `AGENTS.md`: corrected the stale "backend/ is gitignored" gotcha. `backend/` and `frontend/` are versioned with their secrets ignored; `setup.md` and `backend/study.json` stay local.
- Removed the test leftovers: `AGENT_BRIEF.md` and the scratch apps.

**Proof**
- The template builds standalone in both modes: against the published artifact, and with `-Pmhealth.librarySource=..`. Its Hilt component includes the library's modules from the AAR.

**Upgrade**
- Re-run all SQL files in order. Until then, apps with this change show "the server needs updating".
- Study apps created as modules of this repository's build (an `include(":<app>")` line in `settings.gradle.kts`) still build as before. To make one standalone, give it the template's settings, wrapper, catalogue and `gradle.properties`, and remove its `include` line.
- In Android Studio, open `template-app/` as its own project. It no longer appears in the root project.

---

## 2026-10-23 — Ending a study; enrolment enforced by the backend (`ad088cf`)

Two changes:
- **Ending a study:** a study can now be ended. Before, the only switch was `"isActive"`, which just hid the study from the list: enrolled phones kept collecting and uploading indefinitely.
- **Enrolment gate:** the backend now enforces it. Before, the join code and `"isActive"` were only checked by the app, so a modified app could enrol in any study.

**Backend**
- New `StudyConfig."endedAt"`. `"isActive"` now means only "enrolment open".
- `end_study(id)`, admin only: sets `"endedAt"` and closes enrolment. It's permanent, because `seed_study()` refuses to change an ended study.
- `set_study_enrolment(id, open)`, admin only: closes or reopens enrolment without a new version.
- `seed_study()` keeps `"isActive"` when it's omitted, so re-seeding an amendment no longer reopens enrolment.
- `enroll_participant` takes a join ticket (`p_join_ticket`, 13 parameters). It refuses a study that is closed or ended (`study_closed`). When the study has a join code, it refuses an enrolment without a valid ticket (`join_ticket_invalid`).
- Join tickets (`join_code.sql`):
  - `issue_join_ticket(study, code)` trades the code for a ticket, with the same rate limit as `verify_join_code`;
  - tickets are single use and valid for 7 days, in the new `join_tickets` table;
  - `consume_join_ticket` (internal) uses them up at enrolment.
- After the end, the backend refuses:
  - consent records (`submit_consent_record`);
  - questionnaire publishing;
  - uploads of records measured after the end (upload policy, new `record_before_study_end`).

  Records measured before the end are still accepted.
- `get_active_studies` hides ended studies.

**Library**
- Joining: `StudySelectionViewModel.joinStudy` gets a join ticket and keeps it in the new `JoinTicketStore` until enrolment.
  - New repository calls: `issueJoinTicket` (replaces `verifyJoinCode`) and `getStudyEndedAt`.
  - New exceptions: `StudyClosedException` and `JoinTicketInvalidException`, each with its own message.
- An ended study:
  - `StudyConfig.endedAtMs` and `Ready.endedAtMs` carry the end time;
  - the new `collection/StudyEnd` stores the end time with the enrollment and applies it: collection and Health Connect import stop for good, and records measured after the end are dropped locally;
  - uploads continue; `BackgroundWork.end` keeps them scheduled after collection stops;
  - while the app is closed, `RecordUploader` checks the server for the end (at most every 30 minutes);
  - `PassiveCollection`, `HealthConnectBackgroundImport` and `ParticipantDataRecorder` (which returns `NotEnrolled`) record nothing;
  - there is no re-consent review;
  - a participant who hadn't finished joining goes back to the study list.
- New library-owned `StudyEndedScreen` (not replaceable), in place of every enrolled screen. It shows the end date, what happens to the data, the upload card, and "Withdraw and delete my data".
- `EncryptedRecordDao.deleteUnsyncedAfter`.
- New strings: `mhealth_study_ended_*`, `mhealth_join_study_closed`, `mhealth_enrolment_study_closed`, `mhealth_enrolment_join_code_again`.

**Wizard**
- Step 3, when updating a study, has a new section, "Enrolment and end of study":
  - Close/Reopen enrolment;
  - End study…, which asks you to type the study name to confirm.
- The study list shows "enrolment closed" or "ended".
- An ended study can't be updated.
- An update no longer sends `isActive: true`.
- Database checks include the new objects.

**Docs**
- `AGENTS.md` (enrolment gate, study lifecycle) and `backend/README.md` (join tickets, maintenance snippets).
- `docs/recipes/replace-a-screen.md`:
  - a custom study-selection screen must join studies through `StudySelectionViewModel.joinStudy`, so the join ticket is issued;
  - the re-consent and "study ended" screens are library-owned, like onboarding.

**Tests**
- `StudyEndTimestampTest`: parsing `"endedAt"` from PostgREST.

**Upgrade**
- Re-run the SQL in order: `00_schema.sql` → `rls_policies.sql` → `join_code.sql` → … → `seed_study.sql` → `questionnaires.sql`.
- `enroll_participant`'s signature changed, so app builds from before this change can no longer enrol. Participants who are already enrolled are unaffected.

---

## 2026-10-23 — Locked components are internal (`df28b77`)

Host apps can no longer reach the security-critical code. Before, it was kept out of the extension points by convention only. Now the compiler rejects any use of it from outside the library.

**Library**
- `internal`, by area:
  - **Screens:** `LoadingScreen`, `ConsentFlowScreen`, `PassphraseSetupScreen`, `OnboardingOrchestrator`, `RecoveryScreen`.
  - **View models:** `OnboardingViewModel`, `ConsentViewModel`, `ReconsentViewModel`, `RecoveryViewModel`, and their state classes.
  - **Crypto:**
    - key managers: `KeystoreManager`, `KeyRecoveryManager`;
    - signers: `ConsentSignature`, `StudyDefinitionSignature` (plus its signed-definition classes), `QuestionnaireSignature`;
    - derivations: `StudyDefinitionHash`, `ParticipantCredentials`, `UsernameToken`, `KeyRotationAuth`.
  - **DI module:** `AppModule`.
- Internal constructors for the public classes that take the key manager, so hosts get them only by injection or `MHealthLibrary.recorder()`. They are `ParticipantDataRecorder`, `LoadingViewModel`, `HealthConnectImportViewModel`, `EpochEncryptor`, `HealthConnectImporter` and `HealthConnectBackgroundImport`. `OmhImporter.importRecord` is internal.
- Still public:
  - `LoadingViewModel`, which custom home screens read;
  - plain data classes in public signatures: `SignedConsent`, the consent data models, and the consent change types.

**Docs**
- `AGENTS.md` ("Screen replacement") says what is internal and why.

**Upgrade**
- No change for hosts that follow the extension points: the sample and template apps build unchanged. A host that called a locked screen or a crypto class directly no longer compiles.

---

## 2026-10-23 — Consent records linked to the exact study definition (`df28b77`)

Each consent record now names the exact terms the participant agreed to.
Before, it named only the study's version label, and older definitions were
overwritten when the study was updated.

**Backend**
- New append-only `study_definitions` table:
  - every definition ever applied (the PI-signed text, or a canonical JSON for an unsigned study), keyed by its SHA-256;
  - changes and deletions are blocked by a trigger.
- `seed_study()` records each definition it applies (`record_study_definition()`) and sets `StudyConfig."definitionSha256"`. Existing studies are backfilled when `seed_study.sql` is run.
- `consent_records.definition_sha256` references the history.
- `submit_consent_record` and `enroll_participant` take the hash and accept only the study's current definition. Consent to terms that have since changed is refused.
- `get_my_consent_records` returns the hash.

**Library**
- Consent format v2 (`clinicaltests-consent-v2`): the participant's signature covers a `definition:<sha256>` line. v1 records still verify.
- `StudyDefinitionHash`: with a pinned key, the device hashes the verified signed text itself.
- Recovery compares the definition hash instead of the version label. v1 records fall back to the label.
- A study without a recorded definition (a backend older than this change) can't be consented to and shows a clear error.

**Wizard**
- Database checks include `study_definitions`.

**Docs**
- `AGENTS.md` and `backend/README.md` describe the change and include an audit query.

**Tests**
- `ConsentSignatureTest`: v2 format, v1 still verifies, the definition is covered by the signature, hash vector.

**Upgrade**
- Re-run `00_schema.sql` → `rls_policies.sql` → `seed_study.sql` in order. The consent RPC signatures changed, so app builds from before this change can no longer submit consent.

---

## 2026-10-22 — Participant input: backend questionnaires, host screens, data recorder, pull-to-refresh (`18692d5`)

Questionnaires are defined on the backend and rendered by the library, so
they change without an app update. Changing them doesn't ask participants to
consent again: participants consent to the *data type* a questionnaire
belongs to, not to its questions.

**Backend**
- New `questionnaires.sql`:
  - a `questionnaires` table, with RLS on and no policies;
  - `publish_questionnaire()`, admin-only. It checks the format, the study, the data type, the items, and that the version goes up;
  - `set_questionnaire_active()` to retire or restore a questionnaire;
  - `get_study_questionnaires()` for anon and authenticated users, returning active questionnaires only.
- Retention: answers (`questionnaire:<id>`) are deleted according to the retention period of their questionnaire's data type.
- `StudyConfig."customSchemas"`: the wizard's custom-schema specs, stored by `seed_study()`. Only the wizard reads them; they are not signed.

**Library**
- The questionnaire model and answer validation (`Questionnaire`, `QuestionnaireAnswers`).
- `QuestionnaireSignature`: questionnaires are checked against the pinned study-signing key. They use a separate signed format, and older versions are refused (rollback protection).
- `QuestionnaireRepository`:
  - fetches questionnaires and keeps an offline cache that is re-checked on every load;
  - keeps the copy it already accepted when the server sends one that fails the check or is older;
  - `QuestionnaireRegistry` registers each questionnaire's answer schema.
- `MHealthLibrary.consentTypeFor`: answers to a questionnaire map to that questionnaire's data type. Answers to an unknown questionnaire are dropped, never uploaded unfiltered. Every stage of the data pipeline uses this lookup.
- A questionnaire form owned by the library, with scale, number, single-choice, multiple-choice and text questions. Questionnaires appear as cards on the home screen; `QuestionnaireCards` provides the same cards for custom home screens.
- `ParticipantDataRecorder` (or `MHealthLibrary.recorder`) records data the participant enters through the normal encrypted pipeline:
  - consent and `samplingSeconds` are checked first (`RecordPolicy`), all or nothing;
  - no collection session is needed.
- Host screens: `MHealthNavHost(hostScreens = …)` and `LocalMHealthNavigator` let host apps add their own screens. The re-consent screen and export notices still cover them.
- Pull-to-refresh and a "Refresh" menu item on the home screen (`LoadingViewModel.refresh()`). It reloads the study, which shows the re-consent screen right away after a study update, uploads data, checks for export notices and fetches questionnaires.
- The config validator reserves the `questionnaire` namespace.
- Fix: the home screen crashed because a questionnaire card and its answer's value card used the same list key.

**Wizard**
- A questionnaire editor in step 3:
  - create a questionnaire, edit it (publishing the next version), retire it;
  - questionnaires are signed with the study-signing key and checked against the same rules as the app;
  - changing a question's type under the same id is refused.
- Custom schemas are stored with the study and loaded back when updating it.
- Fix: the editor's buttons stopped working once the editor had been closed.

**PI tool**
- Questionnaire answers are labelled with the questionnaire's title and data type.

**Template**
- A `SymptomLog` example: a host screen that records an event through `ParticipantDataRecorder`.
- The custom home screen shows `QuestionnaireCards`.
- `omh:heart-rate` now maps to `heart_rate`, the wizard's default data-type id.

**Docs**
- New recipe `add-participant-input.md`. Updated `replace-a-screen.md` and `AGENTS.md`.

**Tests**
- `RecordPolicyTest`, `QuestionnaireTest`, `QuestionnaireSignatureTest`.

---

## 2026-10-20 — Agent-agnostic repository (`1d1ccdc`)

- `CLAUDE.md` renamed to `AGENTS.md`, the name most coding agents read. `CLAUDE.md` now only imports it.
- **Wizard:** the agent handoff works with any agent:
  - a ready command for Claude Code, Codex CLI or Gemini CLI, with a launch button when that tool is installed;
  - "Other" gives the bare prompt to paste into any agent.
- Docs and the agent brief point to `AGENTS.md`.

---

## 2026-10-19 — Offline start, default screens, theming, strings (`53bf2cd`)

**Library**
- **Offline start:**
  - `StudyConfigCache` keeps the last verified study definition, so the app opens without a network. The cached copy goes through the same verification as a fresh one.
  - The app reconnects on resume, and whenever a network appears (`ACCESS_NETWORK_STATE`). Once back online it uploads and checks for export notices.
- **Home screen:**
  - `StatusPanel` shows offline, recording and upload state;
  - each measurement card shows its last-reading time, a staleness flag and a 24-hour sparkline.
- **History:**
  - charts on a real time axis, with gaps and bucket averages (`HistoryAggregation`), drawn with Compose Canvas (`Charts.kt`);
  - number, category and event views.
  - Database v6 (`MIGRATION_5_6`): history rows keep their kind, category and every numeric field.
- **Branding:** `MHealthBranding` sets the primary and secondary colours (light and dark palettes are derived from them), title, logo and top-bar style. Typography, shapes or a full colour scheme can also be supplied. Screens never hard-code colours.
- **Strings:**
  - about 230 participant-facing texts are now `mhealth_*` string resources, with plurals;
  - host apps override them by name or add translations;
  - state that gets put into words later is stored as typed values (`CollectionStage`, `TermChange`, `RelativeTime`).

**Template**
- A branding example in `TemplateApp`.

---

## 2026-10-19 — PI data tool (`64867c6`)

**PI tool**
- A browser tool for the PI:
  - staff sign-in and study selection;
  - the PI private key is loaded and data is decrypted locally;
  - a submission-activity chart, plus signal charts for any schema (binned, with zoom and pan);
  - a records table and CSV export.

**Backend**
- Paged export, since the API returns at most 1000 rows per call:
  - `export_study_records(study, note, export_id, after_id, limit)` logs one `data_exports` row per export;
  - it opens an `export_sessions` row, valid for 1 hour, fixed at the highest record id at that moment.

---

## 2026-10-19 — Study updates, background sync, sync status, import window (`bc8dd2e`)

**Library**
- **Re-consent after a study update:**
  - the device stores the terms the participant agreed to (`ConsentTerms`);
  - types removed from the study stop being collected at once; new or changed types aren't collected until the participant reviews them on the library's `ReconsentScreen`;
  - accepting signs a new consent record.
- **Background sync:**
  - `RecordUploader` is a single serialised, batched upload path that reports progress;
  - `CollectionService` uploads on `syncInterval` itself, because the foreground service keeps running under Doze while WorkManager jobs get deferred;
  - a periodic `SyncWorker` uploads in the background, and the home screen gets a `SyncStatusCard` with a progress bar.
- `MHealthConfig.healthConnectImportDays` (1–90 days). Above 30 days the app also requests `READ_HEALTH_DATA_HISTORY`.
- Withdrawal dialogs moved into their own `WithdrawalDialogs`.

**Wizard**
- "Update an existing study":
  - lists the backend's studies and loads one into the form;
  - checks that the stored definition verifies with the local signing key and that the version goes up;
  - a **handover** to a new PI requires proof of the current PI's private key, and can optionally remove the previous staff links.
- The agent brief creates a new `<slug>-app/` module instead of editing `template-app`.

**Tests**
- `EpochProgressTest`, `ConsentTermsTest`.

---

## 2026-10-15 — Study signing, participant accounts (`101b17d`)

**Library / Wizard**
- **Study definitions are signed by the PI:**
  - the wizard signs the exact definition text with the study-signing key (`StudySigning.kt`), which never leaves the PI;
  - apps pin the public key (`studySigningPublicKeyB64`), use only the signed text, and refuse older definitions (rollback).
- **Participant accounts:**
  - the recovery credentials are a Supabase account: a pseudonymous email, with a password derived from the passphrase (`ParticipantCredentials`);
  - the session is sealed under the Keystore (`ParticipantAuthStorage`), and the app signs in again silently when needed;
  - withdrawal deletes the account (receipt `v2`).

**Backend**
- RLS rewritten:
  - every participant function and policy checks `auth.uid()` ownership through security-definer helpers;
  - EXECUTE is revoked from everyone in `public`, and each function is granted explicitly.
- `seed_study()` checks that the signed text matches the columns it writes.
- `confirm-withdrawal` requires the caller to own the participant.

**Tests**
- `StudyDefinitionSignatureTest`, `ParticipantCredentialsTest`.

---

## 2026-10-13 — Hashed usernames, export logging (`e66769a`)

- **Recovery usernames are pseudonymised:**
  - the app sends only `UsernameToken.of(username)` (PBKDF2);
  - the backend stores an HMAC of it with a pepper kept in Vault;
  - no plaintext username exists on the server.
- **Every PI export is logged:** `export_study_records()` returns the data and writes the `data_exports` row in the same transaction, so data can't leave without being logged.
- **Participants are told when their data was accessed:** the library shows a notice over any screen after onboarding. It can't be hidden by replacing the home screen.

**Tests**
- `UsernameTokenTest`.

---

## 2026-10-13 — Action bar fix (`e8b9c45`)

**Template**
- A NoActionBar window theme (light and dark), so no system action bar appears above the Compose UI.

---

## 2026-10-13 — Collection overhaul, background Health Connect, signed consent (`074b594`)

**Library**
- **Collection modes:**
  - `PASSIVE_CONNECTED_DEVICE`, `PASSIVE_HEALTH` and `SESSION`, each running the foreground service with the matching Android type;
  - passive collection starts after onboarding, on every app start and after a reboot (`CollectionBootReceiver`), and reconnects with backoff;
  - `CollectionRequirements` reports a missing Android 14+ prerequisite instead of crashing.
- `collectionScreen`: `ALWAYS`, `DEBUG_BUILDS_ONLY` or `NEVER`. The development "Reset" is limited to debug builds.
- **Background Health Connect import** (`HealthConnectBackgroundImport`):
  - change tokens per source; only the changed time window is re-read;
  - duplicates are ignored;
  - `EnrollmentStore` and `BackgroundWork` switch all background work on and off together.
- **Custom schemas without editing the library:** `SchemaDefinition` with quantity, number, text and enum fields, plus events, declared in `MHealthConfig.customSchemas`. They are shown, charted and mapped to FHIR from the definition.
- **Signed consent records:** onboarding signs a canonical consent string (`ConsentSignature`). Recovery checks the signatures, and with no valid record nothing is collected.
- **Key rotation is challenge-gated:** an HMAC proof keyed from the DEK (`KeyRotationAuth`).
- **Atomic enrolment:** one idempotent `enroll_participant` RPC.
- **An empty consent set now means "nothing consented"**, never "no filter".
- The sensor code was split out: the library consumes only `SensorSource`, and all sensor code lives in the host app.
- Fix: data from declined types is no longer shown or kept on the device.
- `consumer-rules.pro` keeps the Health Connect getters that the background import needs.

**Backend / Wizard / Template**
- RLS for consent records, enrolment and key rotation.
- The agent brief gained a collection-mode section.
- The template manifest declares the background permissions.

**Tests**
- `CollectionRequirementsTest`, `CustomSchemaTest`, `HealthConnectBackgroundImportTest`, `ConsentSignatureTest`, `KeyRotationAuthTest`.

---

## 2026-10-08 — Upload failures and duplicates (`71aa91c`)

**Library**
- `encrypted_records` is unique on (participant, epoch, schema). A re-delivered batch or a repeated import is no longer queued or shown twice.
- Records whose upload failed are now retried. Before, one network error left them on the device for good.
- Room v5 (`MIGRATION_4_5`):
  - removes existing duplicates, keeping the uploaded copy;
  - re-queues failed records;
  - every version bump now needs a migration, so unsent records are never wiped.

---

## 2026-10-08 — Retention-based deletion (`520b3a9`)

**Backend**
- New `retention.sql`:
  - a daily pg_cron purge deletes each record `retentionDays` after its measurement time, per data type, using `StudyConfig."schemaDataTypes"`;
  - schemas without a mapping fall back to the study's longest retention period;
  - each purge is logged in `retention_purges`.
- `seed_study()` validates the whole definition and reports every problem in one error.

**Wizard**
- Database setup includes `retention.sql`.

---

## 2026-09-30 — Open mHealth schema search, custom schemas, triggers, enums (`18654d6`)

**Wizard**
- Search the Open mHealth schema library, or import a JSON Schema file (`SchemaImport`), and turn it into a custom schema.
- Custom schemas support quantity, number, text and enum fields.
- The agent brief generates their Kotlin definitions.

**Library**
- **Events** (e.g. stimulus triggers):
  - use an instant time frame and are emitted through `SignalProcessor.feedAll` as their own epoch;
  - are never throttled by `SamplingGate`;
  - built-in event schema: `clinicaltests:stimulus-event`.
- Display and numeric extraction are shared in `EpochDisplay`, used by `EpochSink` and recovery.
- FHIR mapping for events.

**Tests**
- `StimulusEventTest`.
