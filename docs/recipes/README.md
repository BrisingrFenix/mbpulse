# Recipes — repeatable playbooks for customising a study app

Step-by-step procedures for the customisations a new study needs.
Written to be followed by an AI agent (or developer) against the integration
contract in the repo-root `AGENTS.md`; each ends with a **verification
checklist** a researcher can run without reading code.

| Recipe | When |
|---|---|
| [provision-the-backend.md](provision-the-backend.md) | New Supabase project / new study definition |
| [integrate-a-sensor.md](integrate-a-sensor.md) | Connecting the study's real measurement device |
| [add-a-data-type.md](add-a-data-type.md) | Collecting a new measure end-to-end |
| [replace-a-screen.md](replace-a-screen.md) | Custom UI for one of the replaceable screens |
| [add-participant-input.md](add-participant-input.md) | Questionnaires, diaries, manual readings — data the participant enters |

Ground rules that apply to every recipe:

- **Start from a copy of `template-app/`** — copy it to the study app's own
  folder (e.g. `../my-study-app/`, named in `AGENT_BRIEF.md`; see
  `template-app/README.md` step 0) and never edit the template itself. The
  copy is a standalone Gradle build using the published library at a pinned
  version. `grep -r "TODO(agent)" ../my-study-app/` lists every stub.
  Develop on simulated data (`CsvReplaySensorSource` + `sample_data.csv`)
  before touching hardware.
- **Build/verify commands** (JDK: Android Studio's JBR), **in the app's
  folder** — "assembleDebug" in a checklist means this:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat assembleDebug
  ```
  Library tests run in this repository: `.\gradlew.bat :mbpulse:testDebugUnitTest`.
- **Never touch** the security layer (`crypto/`, `EpochEncryptor`, SQLCipher
  setup, withdrawal flow) unless the task is explicitly about it — see the
  "DO NOT MODIFY" section of `AGENTS.md`.
- Secrets live in `local.properties` / Supabase secrets, never in source.
