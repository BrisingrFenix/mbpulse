# mbPulse

**A privacy-preserving, self-hosted Mobile Backend as a Service for mHealth research.**

mbPulse lets a research team run a mobile data-collection study on its own
backend, without building the sensitive parts itself. It provides an Android
library (`pt.ua.mbpulse:mbpulse`) that handles encryption, consent, storage, sync
and most of the UI, a [Supabase](https://supabase.com) backend you provision
yourself, and tools for setting up a study and reading its data.

A study app is a thin host app: you supply the sensor and the signal processing,
and the library does the rest.

```
SensorSource → SignalProcessor → consent filter → per-record AES-256-GCM
                                                        │
                       local encrypted database (SQLCipher)
                                                        │
                       upload to your backend → PI decrypts in the web tool
```

## Features

- **End-to-end encryption.** Measurements are encrypted on the phone and can be
  read by the participant and the principal investigator (PI). The backend stores
  ciphertext.
- **Signed consent.** Each participant signs a consent record tied to the exact
  study definition they agreed to. Collection stops for any data type without
  valid consent, and a study update triggers re-consent.
- **Signed study definitions.** The PI signs each definition, and apps can pin the
  signing key to refuse altered or rolled-back definitions.
- **Withdrawal with a receipt.** The device signs a challenge, the backend deletes
  the participant's data and account, and returns a signed deletion receipt.
- **Recovery and device binding.** A passphrase restores a participant on a new
  phone, and only one device can act for a participant at a time.
- **Automatic retention.** Records are deleted per data type after the study's
  `retentionDays`.
- **Sensor-agnostic.** Bluetooth LE devices, Health Connect and simulated CSV
  replay all feed the same pipeline, and custom data types need no library changes.
- **Open mHealth and FHIR.** Data uses Open mHealth schemas and can be mapped to
  FHIR.

## Repository layout

| Path | Contents |
|---|---|
| [`mbpulse/`](mbpulse/) | The Android library |
| [`backend/`](backend/) | SQL, Edge Function, key generation and provisioning guide |
| [`setup-wizard/`](setup-wizard/) | Local web app that provisions the backend and builds a study |
| [`template-app/`](template-app/) | Starting point for a new study app; runs on simulated data |
| [`sample-app/`](sample-app/) | Example host app for the Empatica E4 and the DREAMT dataset |
| [`frontend/`](frontend/) | PI web tool for exporting, decrypting and charting data |
| [`docs/recipes/`](docs/recipes/) | Step-by-step guides with verification checklists |
| [`AGENTS.md`](AGENTS.md) | Full integration contract and architecture reference |

## Getting started

### Requirements

- Android Studio or the Android SDK (`compileSdk` 36; devices need Android 8.0,
  API 26, or later)
- JDK 17 or later, for Gradle. Android Studio's bundled JDK works
- A Supabase project (the free tier is enough) or a local `supabase start` instance

### 1. Publish the library locally

mbPulse is not on a public Maven repository yet, so study apps use a copy
published to your local Maven repository (`~/.m2`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :mbpulse:publishToMavenLocal
```

On macOS or Linux, use `./gradlew` and set `JAVA_HOME` to your JDK. If Gradle
can't find the Android SDK, set `ANDROID_HOME` or add `sdk.dir` to
`local.properties`.

### 2. Set up the backend and study

```powershell
.\gradlew.bat :setup-wizard:run    # opens http://localhost:8765
```

The wizard provisions the backend, generates the keys, defines the study and
writes a brief for building the app. To do this by hand, follow
[`backend/README.md`](backend/README.md).

Keys are written to the gitignored `backend/keys/`, and the Supabase URL and
anon key to the gitignored `local.properties`. Never commit either.

### 3. Build the study app

Copy `template-app/` to its own folder and follow
[`template-app/README.md`](template-app/README.md). It is a standalone Gradle
build, and it runs end to end on simulated data before you write any sensor code.
Every customisation point is marked `TODO(agent)` or `TODO(researcher)`.

A minimal host app initialises the library and shows its UI:

```kotlin
@HiltAndroidApp
class MyStudyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MbPulseLibrary.init(studyConfig(this))
    }
}

// In your activity:
setContent { MbPulseApp() }
```

### 4. Read the data

Open `frontend/index.html` directly from the folder. The PI signs in with a study
staff account, and the private key stays in the browser. See
[`backend/README.md`](backend/README.md) for staff accounts.

## Extending a study

| To do this | Follow |
|---|---|
| Connect a real device | [integrate-a-sensor.md](docs/recipes/integrate-a-sensor.md) |
| Collect a new measure | [add-a-data-type.md](docs/recipes/add-a-data-type.md) |
| Add questionnaires or diaries | [add-participant-input.md](docs/recipes/add-participant-input.md) |
| Replace a screen | [replace-a-screen.md](docs/recipes/replace-a-screen.md) |

The loading, onboarding (consent and passphrase), recovery and device selection
screens can't be replaced, because they are the security- and ethics-critical
path. Branding and all participant-facing wording can be changed without
replacing screens.

## Tests

```powershell
.\gradlew.bat :mbpulse:testDebugUnitTest
```

## The sample app needs DREAMT data

`sample-app` replays a recording from the
[DREAMT dataset](https://physionet.org/content/dreamt/), which is restricted and
**not included here**. To run the replay, get access through PhysioNet and place
an extract at `sample-app/src/main/assets/dreamt_patient.csv`, with these columns:

```
TIMESTAMP,BVP,ACC_X,ACC_Y,ACC_Z,TEMP,EDA,HR,IBI,Sleep_Stage,Obstructive_Apnea,Central_Apnea,Hypopnea,Multiple_Events
```

## Status and limitations

mbPulse is research software, developed for the thesis *mbPulse: a
privacy-preserving Mobile Backend as a Service for mHealth data*. It has not been
independently security-audited and is not a medical device. Get your own ethics
and data-protection review before collecting data from real participants.

Known limitations:

- **Metadata is visible to the backend.** Payloads are encrypted, but the operator
  can see metadata such as each record's data type and measurement time.
- **Recovery depends on the passphrase.** PBKDF2 with 600,000 iterations, the
  OWASP recommendation for PBKDF2-HMAC-SHA256, slows down guessing, but a weak
  passphrase can still be guessed from the recovery blob.
- **A deletion receipt is an attestation.** It is the backend's signed statement
  that the deletion happened, not cryptographic proof of erasure.
- **An interrupted withdrawal can't always be resumed.** If the server deletes the
  data but the app never gets the response, or the local wipe fails, retrying
  fails because the account is already gone.

## License

[MIT](LICENSE). Third-party files in `frontend/vendor/` keep their own licences,
included alongside them.
