-- =============================================================================
-- Participant-initiated withdrawal / right-to-erasure  (signed challenge)
-- =============================================================================
--
-- Apply after rls_policies.sql. Re-runnable (`create or replace` / if-not-exists).
--
-- Erasure is gated by a challenge–response that proves possession of the
-- participant's private key:
--
--   1. Signed-in device calls request_withdrawal_challenge(pseudo_id) -> random
--      nonce (only for the caller's own participant).
--   2. Device signs the nonce with its hardware RSA key (SHA256withRSA).
--   3. Device posts {pseudo_id, signature} with its session JWT to the
--      `confirm-withdrawal` Edge Function, which checks the JWT's account owns
--      pseudo_id AND the signature against participants.public_key_b64, then
--      calls withdraw_participant() with the service_role and deletes the account.
--
-- RSA signature verification cannot be done in pgcrypto, which is why step 3 is
-- an Edge Function (Deno + Web Crypto) rather than an RPC. The unsigned delete
-- path is therefore removed from anon: withdraw_participant() is executable only
-- by the service_role the Edge Function uses.
-- =============================================================================

-- Short-lived per-participant nonce. FK cascade means a successful withdrawal
-- (which deletes the participants row) also clears the challenge.
create table if not exists withdrawal_challenges (
  pseudo_id  uuid primary key
             references participants (pseudo_id) on delete cascade,
  challenge  text        not null,
  expires_at timestamptz not null default now() + interval '5 minutes'
);

alter table withdrawal_challenges enable row level security;
-- No anon policies: only the SECURITY DEFINER issuer below and the service_role
-- (Edge Function) touch this table.

-- Append-only audit of completed erasures. Stores a hash of the pseudo_id (not
-- the value) so the log cannot re-identify a withdrawn participant. The Edge
-- Function writes one row per successful withdrawal and returns the receipt to
-- the device as proof of deletion.
create table if not exists deletion_receipts (
  receipt_id          uuid primary key default gen_random_uuid(),
  pseudo_id_hash      text        not null,
  study_id            uuid        null   -- null: participant was already gone
                      references "StudyConfig" ("studyId") on delete restrict,
  deleted_at          timestamptz not null default now(),
  records_deleted     int         not null,
  consent_deleted     int         not null,
  participant_deleted int         not null,
  signature_b64       text
);
-- If the table already exists from an earlier version, add the columns:
alter table deletion_receipts add column if not exists signature_b64 text;
alter table deletion_receipts add column if not exists account_deleted int not null default 0;

alter table deletion_receipts enable row level security;
-- No anon policies: written by the service_role (Edge Function), read by PI tooling.

-- 1) Issue a challenge for a pseudo_id. Pure SQL — no crypto needed to mint a
--    nonce. Upsert so a re-request simply rotates the nonce.
create or replace function request_withdrawal_challenge(p_pseudo_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_nonce text;
begin
  -- Only for the caller's own participant (account → pseudonym).
  if not owns_participant(p_pseudo_id) then
    raise exception 'not your participant';
  end if;

  -- 256 bits of CSPRNG randomness as a 64-char hex string. Uses core
  -- gen_random_uuid() (Postgres 13+) to avoid depending on the pgcrypto
  -- extension, which Supabase installs outside the public search_path.
  v_nonce := replace(gen_random_uuid()::text, '-', '')
          || replace(gen_random_uuid()::text, '-', '');

  insert into withdrawal_challenges (pseudo_id, challenge, expires_at)
  values (p_pseudo_id, v_nonce, now() + interval '5 minutes')
  on conflict (pseudo_id)
  do update set challenge = excluded.challenge,
                expires_at = excluded.expires_at;

  return v_nonce;
end;
$$;

-- 2) The actual erasure. SECURITY DEFINER so it can DELETE despite the
--    append-only anon policies. NOT exposed to anon — only the Edge Function,
--    running as service_role after verifying the signature, may call it.
--    Returns the exact per-table counts it deleted (via DELETE ... RETURNING)
--    so the Edge Function can issue a truthful deletion receipt.
create or replace function withdraw_participant(p_pseudo_id uuid)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  v_study       text;
  v_records     int;
  v_consent     int;
  v_participant int;
begin
  select study_id into v_study from participants where pseudo_id = p_pseudo_id;

  with d as (
    delete from encrypted_records where participant_pseudo_id = p_pseudo_id returning 1
  ) select count(*) into v_records from d;

  with d as (
    delete from consent_records where participant_pseudo_id = p_pseudo_id returning 1
  ) select count(*) into v_consent from d;

  with d as (
    delete from participants where pseudo_id = p_pseudo_id returning 1  -- cascades the challenge
  ) select count(*) into v_participant from d;

  return json_build_object(
    'studyId',            v_study,
    'recordsDeleted',     v_records,
    'consentDeleted',     v_consent,
    'participantDeleted', v_participant
  );
end;
$$;

-- Grants: the signed-in participant may only request a challenge for itself;
-- erasure is the Edge Function's (service_role, after JWT + signature checks).
revoke execute on function request_withdrawal_challenge(uuid) from public, anon;
grant  execute on function request_withdrawal_challenge(uuid) to authenticated;

revoke execute on function withdraw_participant(uuid) from public, anon, authenticated;
grant  execute on function withdraw_participant(uuid) to service_role;

-- Backend API version of this file (00_schema.sql, backend_version()).
select public.stamp_backend_component('withdraw', 4, 3);
