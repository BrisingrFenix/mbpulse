# Guide screenshots

The wizard's step-1 guides show real screenshots under each "where to click"
instruction. Until a file exists, the UI shows a dashed placeholder naming the
path and what to capture — drop a PNG here with the exact filename and it
appears on next reload (no rebuild needed; the page serves these statically).

| File | What to capture |
|---|---|
| `create-project.png` | The Supabase **New project** form (name, database password, region). |
| `api-keys.png` | **Settings → API** — draw boxes/arrows on the *Project URL* and the *anon / publishable key*. |
| `connect-pooler.png` | The **Connect** dialog — highlight the *Session pooler* tab and the `postgresql://…` connection string. |
| `local-status.png` | *(local-instance guide)* Terminal output of `supabase status` with the anon key line highlighted. |

Tips:

- Capture from a **throwaway project** so nothing sensitive is shown; the anon
  key is public by design, but blur the project ref/URL if you prefer.
- Crop tightly to the relevant panel; ~1200 px wide renders crisply.
- Annotate with a red box or arrow around the exact click target — that is the
  whole point of the image.
- PNG is preferred (`.jpg` also serves if you update the filename in
  `index.html`).
