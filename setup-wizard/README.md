# setup-wizard — point-and-click study setup

A local web app that takes a researcher from "nothing" to "provisioned backend,
defined study, and an AI-agent brief for the app" without touching SQL or code.

It runs on the tooling this repo already requires (JDK + Gradle) — nothing to
install:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :setup-wizard:run        # opens http://localhost:8765
```

## The four steps

1. **Backend** — choose a new Supabase project (guided), an existing mbPulse
   backend (multi-study: new studies sit next to old ones), or a local
   `supabase start` instance. Test the connection. With an **existing**
   backend, step 2 switches to verify-only: a "Verify backend" check queries
   the database for every expected table/function instead of reinstalling,
   the setup button becomes an optional "re-run to apply updates", and the
   withdrawal-function deploy steps stay hidden unless its check fails.
2. **Provision** — one click runs the five SQL files in order over JDBC,
   generates the PI + receipt keypairs (JDK crypto, no openssl) into the
   gitignored `backend/keys/`, and writes the URL/anon key into
   `local.properties` for the app build. The one manual step — the withdrawal
   Edge Function — is guided through the **Supabase dashboard** (deploy via the
   browser editor with a "Copy function code" button, switch Verify JWT off,
   add the signing secret with its value ready to copy), and a **Check
   deployment** button probes the live function and reports which step, if
   any, is still missing. A CLI alternative is tucked behind "Prefer the CLI?".
3. **Your study** — a form for the study definition: name, version, join code,
   and data types (consent wording, retention, sampling interval, essential
   flag). For each data type you tick the **measurement schemas** that feed it;
   a schema can feed only one type. Each ticked schema gets a **preview card**
   (LOINC/UCUM/FHIR binding, the recording cadence implied by the sampling
   interval, and an example OMH data point exactly as the library stores it). A measure the library lacks can be added
   as a **custom schema** (`namespace:name`, what it measures, unit, optional
   LOINC); the brief turns it into a ready-made `SchemaDefinition` the app
   declares in `MbPulseConfig.customSchemas` — the library is never edited
   per study. The first field is the primary value (card, chart, FHIR
   `value[x]`). The custom-schema form can be pre-filled by
   **searching Open mHealth** (live from the `openmhealth/schemas` GitHub repo,
   any version) or **importing a JSON Schema file**; each property is mapped
   to value + unit / number / text / **enum** (units and enum values taken
   from the schema, `$ref`s followed), and anything the library cannot
   represent is listed as skipped. Field names are per schema — any name but
   `effective_time_frame` / `extra` is allowed. A custom schema is either a **measurement**
   (summarises a window) or an **event / trigger** (a single instant, e.g. a
   stimulus delivered — `date_time` time frame, one point per trigger, never
   sampled). Triggers come from a sensor marker channel, an in-app button, or
   an app-run schedule. The library ships one event schema built in —
   ⚡ **Stimulus trigger** (`mbpulse:stimulus-event`). Seeding calls the server-side validating
   `seed_study()`, so a bad definition comes back as one readable error list.
4. **Build the app** — choose how data is collected (**passive — connected
   device**, the default; **passive — body sensors**; or **sessions** started
   on the collection screen, e.g. in a lab), whether a passive app keeps the
   collection screen in its release build (by default it exists in debug
   builds only, for CSV testing), then describe the sensor, screen wishes, and
   Health Connect imports. The wizard writes `AGENT_BRIEF.md` (study table, the **generated
   `schemaToConsentType` / `supportedDataTypeIds` / receipt-key config**,
   recipe pointers, and a definition-of-done checklist) and hands off to any
   coding agent: pick Claude Code, Codex CLI or Gemini CLI for a ready command
   (plus a launch button when that CLI is on PATH), or "Other" to copy the bare
   prompt into any agent. Agents get the repo rules from `AGENTS.md`
   (`CLAUDE.md` only imports it, for Claude Code).

## Guide screenshots

Step 1 shows real dashboard screenshots under each "where to click"
instruction. They are drop-in files — see `public/img/shots/README.md` for the
expected filenames and capture tips. Until a file exists, the wizard shows a
dashed placeholder naming exactly what to capture and where to put it.

## Notes

- Everything sensitive lands in gitignored locations (`local.properties`,
  `backend/keys/`, `backend/study.json`). `AGENT_BRIEF.md` contains no
  secrets (the join code never appears in it).
- The schema list in step 3 mirrors the schemas **built into** the library
  (`OmhSchemas.BUILT_IN`; `SCHEMA_REGISTRY` in `Main.kt`, including each
  schema's `exampleBody`, which must match `OmhBody`'s serial names). Extend
  both only when a schema becomes part of the library itself — study-specific
  schemas stay custom (the brief forbids adding them here).
- The server binds to 127.0.0.1 only.
