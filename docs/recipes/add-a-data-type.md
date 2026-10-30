# Recipe: add a data type end-to-end

**Goal:** a new measure flows from sensor signal to consent screen to history
card. Example used throughout: respiratory rate (`rr`, breaths/min).

Touch points span **study definition → host schema → host processor → host
config**. A study never edits the library: measures the library does not ship
are declared in the app as a `SchemaDefinition` (`MbPulseConfig.customSchemas`),
and the library displays, charts, recovers and FHIR-maps them from it.

## Steps

1. **Study definition** — add a `dataTypes` entry to `study.json`
   (id e.g. `respiratory_rate`, participant-facing name/description/purpose,
   `retentionDays`, `isEssential`, and optionally `samplingSeconds` — the
   minimum seconds between recorded readings, enforced by the library and
   shown on the consent screen; omit for the processor's natural cadence),
   then re-seed: `bash seed_study.sh study.json ./keys` (include the existing
   `"studyId"` to update in place).
2. **Schema** — if `OmhSchemas` already has it (heart rate, HRV, sleep
   episode, body temperature, EDA, stimulus event), use it and skip to step 3.
   Otherwise declare it in the app (e.g. `CustomSchemas.kt`):

   ```kotlin
   val RESPIRATORY_RATE = SchemaDefinition(
       id = OmhSchemaId("omh", "respiratory-rate", "3.0"),   // official OMH id if one exists,
                                                             // else the "mbpulse" namespace
       terminology = SchemaTerminology(
           loincCode = "9279-1", display = "Respiratory rate", category = "vital-signs",
           ucumUnit = "breaths/minute", ucumCode = "/min"    // or null → Ucum table / raw unit
       ),
       fields = listOf(
           SchemaField("respiratory_rate", FieldKind.QUANTITY, unit = "breaths/min")
       )
   )
   ```

   - Field kinds: `QUANTITY` (`{value, unit}`), `NUMBER`, `TEXT`, `ENUM`
     (`values = listOf(...)`, at least two).
   - **The first field is the primary value:** card value, charted series,
     FHIR `value[x]`. The other fields are shown next to it and exported as
     FHIR `component`s.
   - Imported OMH schemas: keep their field names and units exactly.
   - Field names are free (even ones a built-in schema uses); only
     `effective_time_frame` and `extra` are reserved. `MbPulseLibrary.init`
     validates the definitions and lists every problem.

**Event / trigger measures** (something happened at an instant, e.g. a
stimulus was delivered — reuse the built-in `mbpulse:stimulus-event`
where it fits): declare the definition with `isEvent = true`; its
`dataPoint(source, triggerMs, values = …)` uses an instant time frame. In the
host processor override `SignalProcessor.feedAll` to return the trigger as its
own `ProcessedEpoch` (`epochStartMs` = the trigger instant) next to any window
the same sample closes. Events bypass the study's sampling interval, are never
charted, and export to FHIR as `effectiveDateTime` automatically.

3. **Host processor**: accumulate the signal and emit the point per epoch.
   Custom schema: `RESPIRATORY_RATE.dataPoint(sourceName, startMs, endMs,
   mapOf("respiratory_rate" to meanRr))` — values are checked against the
   definition. Built-in schema: copy the heart-rate block in
   `TemplateSignalProcessor.buildEpoch`. The sensor (or CSV) must emit the
   `rr` signal.
4. **Host config** (`StudyConfig.kt`): add custom definitions to
   `customSchemas = listOf(RESPIRATORY_RATE)`; map the schema in
   `schemaToConsentType` (`"omh:respiratory-rate" -> "respiratory_rate"`); if
   `supportedDataTypeIds` is set, add the id there too (the init validator
   warns on mismatch).

## Verification checklist

- [ ] `.\gradlew.bat assembleDebug` (in the app's folder) succeeds — and app start does
      NOT throw a config-validation error (a warning about the mapping vs
      `supportedDataTypeIds` means step 4 is half-done).
- [ ] `git status mbpulse` shows no changes.
- [ ] `.\gradlew.bat testDebugUnitTest` passes — after regenerating the
      brief (and so `handoff.json`) in the wizard for the new data type.
- [ ] Add an `rr` column to `sample_data.csv` — replay it.
- [ ] Consent flow shows the new data type with the wording from `study.json`.
- [ ] Consent **accepted**: a new card appears after the first epoch; tapping
      it opens a history chart with sensible values/unit.
- [ ] Consent **declined** (non-essential): no card, and logcat shows the
      `EpochEncryptor` "Dropping ..." line for exactly this schema — that is
      the consent filter working.
- [ ] No other `EpochEncryptor` warnings in logcat.
