# Backend in a box — Supabase setup for an mbPulse study

Everything needed to take a **fresh Supabase project** to a working study
backend. No programming required: two scripts to paste, one script to run, one
file to fill in. (Supersedes Parts 1–3 of the old `setup.md`; in particular its
"Disable Row Level Security" section is obsolete and must not be used.)

## What you need

- A [supabase.com](https://supabase.com) project (free tier is fine). From
  **Settings → API** note the **Project URL** and **anon/publishable key** —
  they go into the app's `local.properties`, never into source code.
- The [Supabase CLI](https://supabase.com/docs/guides/cli) logged in and linked
  (`supabase link --project-ref <ref>`) — needed only for the Edge Function.
- `openssl` (already inside Git Bash on Windows).

## Steps

### 1. Apply the SQL — in this order

Open the Supabase dashboard **SQL Editor** and run each file in full, in
order (or use the setup wizard's "Database setup"). All are idempotent —
re-running them is always safe.

| # | File | What it creates |
|---|------|-----------------|
| 1 | `00_schema.sql` | All base tables (StudyConfig, participants, consent_records, encrypted_records, data_exports, study_definitions) |
| 2 | `rls_policies.sql` | Deny-by-default RLS + the SECURITY DEFINER RPCs the app calls |
| 3 | `join_code.sql` | Vault-backed join-code check + `get_active_studies` |
| 4 | `withdraw.sql` | Signed-challenge withdrawal + deletion receipts |
| 5 | `seed_study.sql` | The validating `seed_study()` loader used in step 3 below; records every applied definition in `study_definitions` |
| 6 | `questionnaires.sql` | PI-signed questionnaires (published in the setup wizard) |
| 7 | `retention.sql` | `purge_expired_records()` + a daily pg_cron job deleting records past their data type's `retentionDays` |

**Backend version.** Every file stamps its part of the backend API version
when it runs; the app checks `select public.backend_version();` when it starts
and refuses to run against a backend that is incomplete or of the wrong
version, with a message saying which side needs updating. After any upgrade,
re-run **all** files in order — a file left at an older version holds the whole
backend back.

**Consent ↔ terms.** Every consent record names the exact study definition
the participant agreed to (`consent_records.definition_sha256`, also inside
their signature), and `study_definitions` keeps every definition ever applied
(append-only). To audit what each participant agreed to:

```sql
select c.participant_pseudo_id, c.study_version, c.completed_at_ms, c.decisions, d.definition
  from consent_records c join study_definitions d using (definition_sha256)
 order by c.created_at;
```

Records made before this existed have `definition_sha256 = null` (linked by
version label only).

### 2. Generate the keys

```bash
bash keygen.sh            # writes ./keys/ — keep it OUT of git, back it up offline
```

Produces the **PI keypair** (decrypts study data in the web frontend) and the
**receipt-signing keypair** (signs deletion receipts). The script prints exactly
where each artifact goes; the private keys never leave your machine except the
receipt key's PKCS#8 blob, which becomes an Edge Function secret in step 4.

### 3. Define your study — as data, no code

```bash
cp study.example.json study.json     # edit: name, data types, retention, join code
bash seed_study.sh study.json ./keys
```

The JSON **is** the study definition — the app's consent screens render
directly from its `dataTypes`. The script bundles the JSON with your
`pi_public.pem` into `_generated_seed_study.sql`; paste that into the SQL
editor (or set `DATABASE_URL` to run it directly). Server-side, `seed_study()`
validates everything (required fields, unique ids, positive retention,
consent wording present, PEM shape) and reports **all** problems in one error —
nothing is applied until the definition is clean.

**Retention:** each record is deleted from `encrypted_records` `retentionDays`
after its measurement time, by a daily pg_cron job (`purge-expired-records`,
03:17 UTC) from `retention.sql`. The server only knows a record's schema, so
list which schemas feed which data type in `"schemaDataTypes"` (the same
mapping as the app's `schemaToConsentType`; the wizard fills it in). Schemas
not listed fall back to the study's longest retention. Every run that deletes
something is logged, counts only, in `retention_purges`. Run it by hand with
`select public.purge_expired_records();`; check the schedule with
`select * from cron.job;` and past runs with `select * from cron.job_run_details order by start_time desc;`.

Join codes go to Vault automatically (`"joinCode": "..."` sets/rotates,
`""` removes → open enrolment, omit → unchanged). The backend enforces them:
the app trades the code for a single-use join ticket (`issue_join_ticket`,
7 days) and `enroll_participant` refuses an enrolment without a valid one, or
for a study whose enrolment is closed. To update an existing study, add its
`"studyId"` to the JSON and re-run (`"isActive"` omitted keeps the enrolment
status; an ended study cannot be updated).

### 4. Deploy the withdrawal Edge Function

Via the **dashboard** (no CLI needed — the setup wizard guides this and can
verify it): Edge Functions → *Deploy a new function* → *Via Editor* → name it
exactly `confirm-withdrawal` → paste `functions/confirm-withdrawal/index.ts` →
Deploy, keeping **Verify JWT ON** (the default), and under *Edge Functions →
Secrets* add `DELETION_SIGNING_KEY_PKCS8_B64` with the value printed by
`keygen.sh`.

Or via the CLI:

```bash
supabase secrets set DELETION_SIGNING_KEY_PKCS8_B64=<printed by keygen.sh>
supabase functions deploy confirm-withdrawal
```

Withdrawal needs both the participant's own session (the function checks the
JWT's account owns the pseudonym) and the RSA signature over a server-issued
challenge (see `withdraw.sql` header). It then deletes the participant's account.

**Auth settings for participant accounts**:
Authentication → Providers → Email enabled, **Confirm email OFF** (accounts use
generated `…@participants.invalid` addresses), sign-ups enabled. PI staff
accounts are normal users you create under Authentication → Users and link to a
study in the wizard (or `insert into study_staff (user_id, study_id) …`).
Sanity check (the gateway still wants the anon key as `apikey` even with
verify-JWT off — the app sends it automatically):

```bash
curl -X POST https://<ref>.supabase.co/functions/v1/confirm-withdrawal \
  -H "Authorization: Bearer <anon key>" -H "apikey: <anon key>" \
  -H "Content-Type: application/json" \
  -d '{"pseudoId":"00000000-0000-0000-0000-000000000000","signatureB64":"x"}'
# healthy => 401 {"error":"no challenge — request one first"}
```

(The setup wizard's "Check deployment" button does exactly this probe.)

### 5. Point the app at the backend

In the Android project's `local.properties`:

```properties
mbpulse.supabaseUrl=https://<ref>.supabase.co
mbpulse.supabaseKey=<anon key>
```

And in the host app's `MbPulseConfig`: `deletionReceiptPublicKeyB64 = "<printed
by keygen.sh>"` (enables the "receipt signature verified" badge).

## Verify it worked

1. App start → study selection lists your study (`get_active_studies` + seed OK).
2. Enrol with consent + passphrase → a `participants` row appears (RLS inserts OK).
3. Record briefly → `encrypted_records` rows appear (sync OK).
4. Withdraw from the ⋮ menu → deletion certificate shows counts, "0 records
   remain", and "signature verified" → a `deletion_receipts` row exists
   (Edge Function + secret OK).

## Security model in one paragraph

The anon key ships inside the APK and is treated as public. Confidentiality
comes from end-to-end encryption (AES-256-GCM per record; the DEK is wrapped
under the participant's hardware key and the PI's public key — the server only
ever stores ciphertext). RLS's job is least privilege: anon can enrol and
upload, everything else goes through SECURITY DEFINER RPCs or the Edge
Function. Destructive actions (withdrawal) additionally require proof of key
possession via a signed challenge. Details: `rls_policies.sql` header.

## Maintenance snippets

```sql
-- Close (or reopen) enrolment — enrolled participants carry on (wizard: "Close enrolment")
select public.set_study_enrolment('<id>', false);

-- End the study, for good (wizard: "End study…"): the backend refuses new
-- enrolments, consent and records measured after now; apps stop collecting,
-- upload what was recorded before and tell participants. Data stays until its
-- retention period ends or the participant withdraws.
select public.end_study('<id>');

-- Delete a test participant (cascades consent + records). Usernames are not
-- stored (only a peppered token), so delete by pseudo id — shown in the app's
-- logcat (tag LoadingViewModel) or picked from the newest rows:
--   select pseudo_id, created_at from public.participants order by created_at desc limit 5;
delete from public.participants where pseudo_id = '<pseudo-id>';

-- Username pepper (pseudonymisation): BACK IT UP with your keys; never rotate
-- it — losing or changing it makes every account unrecoverable.
select decrypted_secret from vault.decrypted_secrets where name = 'username_pepper';

-- Rotate a join code
select id from vault.secrets where name = 'join_code_<studyId>';
select vault.update_secret('<id-from-above>', 'NEW-CODE');
```
