# Recipe: add participant-entered data (questionnaire, diary, manual reading)

**Goal:** the participant answers questions or enters values, stored
encrypted and uploaded like sensor data — without touching the library.

Two ways, pick the first that fits:

| | Questionnaire (backend) | Host screen (app code) |
|---|---|---|
| For | Questions: scale, number, single / multiple choice, free text | Input a form does not fit (one-tap logging, custom UI, device-assisted readings) |
| Defined in | Setup wizard, step 3 → Questionnaires | The host app (`SchemaDefinition` + `HostScreen`) |
| Change it | Publish a new version — no app update, **no re-consent** | App update |
| Example | — (create one in the wizard) | `template-app/.../SymptomLog.kt` |

Both need a **study data type** — that is what participants consent to: name,
description, purpose, retention and, through `samplingSeconds`, how often
(`72000` ≈ once a day, `604800` = once a week, omitted = whenever). Shown on
the consent screen and enforced on the device. Describe the input broadly
enough in the data type's description that later wording changes stay within
it; a change that needs new consent is a change to the data type (study
update → re-consent).

## A. Questionnaire (no app code)

1. Add the data type to the study (wizard step 3; `isEssential: false`
   usually) and create/update the study.
2. Step 3 → **Questionnaires** → *New questionnaire*: id (kebab-case, fixed),
   data type, title, card text, introduction, questions. The **first question**
   is the headline value (home card, history chart) — make it a scale or number.
   Question ids are the export columns: keep them across versions, give a
   question that changes type a new id (the wizard refuses otherwise).
3. *Sign & publish*. The wizard signs it with the study-signing key; apps that
   pin the key refuse anything else. Editing publishes the next version;
   *Retire* stops offering it (answers already given stay).

The library verifies it, caches it for offline use, shows a card on the home
screen for participants who consented to the data type, renders the form and
stores the answers as `questionnaire:<id>` (plus `questionnaire_version`).
Phones pick up a new version on the next app start, resume or pull-to-refresh.
Custom home screens show the same cards with `QuestionnaireCards()`.

## B. Host screen + `ParticipantDataRecorder`

1. **Schema** — a custom `SchemaDefinition` (see
   [add-a-data-type.md](add-a-data-type.md)): `NUMBER` for scales/scores, `ENUM`
   for single choice (stored codes, labels translated in the UI), `TEXT` for
   free text; the first field is the primary value.
   - About a period (today, last night): a **measurement** (interval time frame).
   - Marking a moment ("symptom started now"): an **event** (`isEvent = true`,
     instant time frame) — never limited by sampling.
   Register it in `MbPulseConfig.customSchemas` and map it in
   `schemaToConsentType` to the data type (wizard: add it as a custom schema of
   that data type, so retention applies to it).
2. **Host screen** — `HostScreen(id, titleRes, icon, subtitle, content)` passed
   to `MbPulseApp(hostScreens = …)` (or `MbPulseNavHost(hostScreens = …)`). The
   default home screen lists it as a card; a custom home screen opens it with
   `LocalMbPulseNavigator.current.openHostScreen(id)`. Re-consent and the
   data-export notice still appear over it; it is unreachable before enrolment.
3. **Saving** — inject `ParticipantDataRecorder` into the screen's
   `@HiltViewModel` (or `MbPulseLibrary.recorder(context)` outside Hilt):

```kotlin
when (recorder.availability(schema.schemaName)) { … }   // show form / explain why not
val point = schema.dataPoint(sourceName = "…", startMs = now,
                             values = mapOf("symptom" to "headache", …), modality = "self-reported")
when (val r = recorder.record(point)) {
    is RecordResult.Recorded     -> …   // encrypted, stored, upload triggered
    is RecordResult.NotConsented -> …   // type declined, or awaiting re-consent
    is RecordResult.TooSoon      -> …   // samplingSeconds: r.nextAllowedAtMs
    RecordResult.NotEnrolled, RecordResult.Duplicate, is RecordResult.Failed -> …
}
```

The recorder uses the enrolled participant's keys and **effective** consent
(the same pipeline as live collection: consent filter → AES-256-GCM →
`encrypted_records` → upload). It works in every collection mode and needs no
running collection session. All or nothing: nothing is stored unless the
result is `Recorded`. Never build `EpochEncryptor`/`EpochSink` yourself.

Wording: host strings with the app's own prefix (e.g. `symptom_…`), never
`mbpulse_`. Questionnaire wording is part of what the ethics committee
reviews — keep the instrument's exact text.

## Verification checklist

- [ ] (B) `.\gradlew.bat :my-study-app:assembleDebug` succeeds; the app starts
      (config validation passes with the new schema).
- [ ] After onboarding (data type accepted), the home screen shows the card;
      submitting shows the saved message and a value card / history chart
      shows the answer.
- [ ] Upload: the home sync card shows it pending, then "All data uploaded";
      the PI tool export contains the answers as columns.
- [ ] Decline the data type during consent → no questionnaire card (A) / the
      screen says it is not part of the consent (B); nothing is stored.
- [ ] With `samplingSeconds` set, a second submission is refused with the
      time the next one opens.
- [ ] (A) Publish a new version in the wizard, pull to refresh on the phone:
      the new questions appear and no re-consent screen is shown.
