# Recipe: provision the backend

**Goal:** a fresh Supabase project becomes a working study backend.
**Full runbook:** `backend/README.md` — this recipe is
the condensed order of operations plus verification.

## Steps

1. Create a Supabase project; note Project URL + anon key (Settings → API).
2. In the SQL editor, run in order (all idempotent):
   `00_schema.sql` → `rls_policies.sql` → `join_code.sql` → `withdraw.sql` →
   `seed_study.sql` → `retention.sql` (daily pg_cron purge of records past
   their data type's `retentionDays`; needs `"schemaDataTypes"` in the study
   JSON to tell types apart — see `study.example.json`).
3. Generate keys: `bash keygen.sh` (in the `backend/` dir). Keep `keys/` out
   of git; back it up offline.
4. Define the study: `cp study.example.json study.json`, edit (name, data
   types, retention, consent wording, join code), then
   `bash seed_study.sh study.json ./keys` and run the generated SQL.
   `seed_study()` reports **all** definition problems in one error.
5. Deploy withdrawal — via the dashboard (Edge Functions → *Deploy a new
   function* → *Via Editor* → name `confirm-withdrawal` → paste
   `functions/confirm-withdrawal/index.ts`; keep **Verify JWT ON**;
   Secrets → `DELETION_SIGNING_KEY_PKCS8_B64`), or via the CLI:
   ```bash
   supabase secrets set DELETION_SIGNING_KEY_PKCS8_B64=<printed by keygen.sh>
   supabase functions deploy confirm-withdrawal
   ```
   Then Authentication → Providers → Email: enabled, **Confirm email OFF**
   (participant accounts use generated `…@participants.invalid` addresses).
6. Wire the app — repo-root `local.properties`:
   ```properties
   mbpulse.supabaseUrl=https://<ref>.supabase.co
   mbpulse.supabaseKey=<anon key>
   ```
   and in the host config: `deletionReceiptPublicKeyB64 = "<printed by keygen.sh>"`.

## Verification checklist

- [ ] `.\gradlew.bat assembleDebug` (in the app's folder) succeeds (blank credentials
      fail at app start with a `local.properties` message — that error means
      step 6 was missed).
- [ ] App start → study selection lists the seeded study.
- [ ] Wrong join code rejected; correct one proceeds to consent.
- [ ] Consent screen shows exactly the `dataTypes` from `study.json`
      (names, descriptions, retention).
- [ ] After enrolment: a `participants` row exists in the dashboard.
- [ ] After a short recording: `encrypted_records` rows appear; content is
      ciphertext (no readable values).
- [ ] ⋮ → *Withdraw from study* → certificate shows deletion counts,
      "0 records remain on the server", and "receipt signature verified";
      a `deletion_receipts` row exists.
