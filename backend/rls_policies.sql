-- =============================================================================
-- Row-Level Security policies and RPCs for the mbPulse self-hosted backend
-- =============================================================================
--
-- Apply after 00_schema.sql (the wizard does). Re-runnable: every policy is
-- dropped before being recreated, functions use create-or-replace.
--
-- THREAT MODEL
-- ------------
-- * Participants have Supabase accounts derived from their recovery
--   credentials: requests run as `authenticated` with `auth.uid()` = the
--   participant's account. Every participant-specific operation checks that
--   the account owns the pseudonym — knowing a pseudo_id is worth nothing.
-- * `anon` (the public key in the APK) only reaches what is needed BEFORE an
--   account exists: the study list, the join-code check, username
--   availability and the login id.
-- * CONFIDENTIALITY still comes from end-to-end encryption, not from RLS.
-- * PI exports require a staff account for the study (study_staff).
-- * The `service_role` key (Edge Function, back-office) bypasses RLS.
-- =============================================================================


-- =============================================================================
-- 0) Function privileges: closed by default (see section 8 at the end)
-- =============================================================================
-- Supabase grants EXECUTE on new public functions to anon AND authenticated
-- (per-schema default privileges), and Postgres grants it to PUBLIC globally,
-- which anon inherits. Remove Supabase's per-schema defaults for future
-- functions; the PUBLIC default cannot be removed per schema, so section 8
-- revokes EVERYTHING in public once this file's functions exist and grants the
-- intended set explicitly. The files applied after this one (join_code,
-- withdraw, seed_study, retention) revoke/grant their own functions.
alter default privileges in schema public revoke execute on functions from anon, authenticated;


-- =============================================================================
-- 1) Ownership helpers (SECURITY DEFINER: policies cannot read `participants`
--    themselves — it has RLS and no client policies)
-- =============================================================================

-- The calling login session: the `session_id` claim Supabase puts in every
-- access token (unchanged by token refresh; a new sign-in is a new session).
create or replace function current_session_id()
returns uuid
language sql
stable
set search_path = public
as $$
  select nullif(auth.jwt() ->> 'session_id', '')::uuid;
$$;

-- Device binding (00_schema.sql): a participant row acts only through its
-- bound session — or any session of the account while none is bound yet
-- (enrolled before binding existed, or by an app without it).
create or replace function session_acts_for(p_bound_session_id uuid)
returns boolean
language sql
stable
set search_path = public
as $$
  select p_bound_session_id is null or p_bound_session_id = current_session_id();
$$;

-- The calling session's participant (pseudo_id), or null.
create or replace function my_pseudo_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select pseudo_id from participants
   where auth_user_id = auth.uid() and session_acts_for(bound_session_id)
   limit 1;
$$;

-- True when the calling account owns participant p_pseudo_id (in study
-- p_study_id, if given) AND this session acts for it (device binding). Every
-- participant policy and RPC uses this — a phone replaced by a recovery
-- elsewhere is refused even after it signs in again.
create or replace function owns_participant(p_pseudo_id uuid, p_study_id uuid default null)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and exists (
    select 1 from participants
     where pseudo_id = p_pseudo_id
       and auth_user_id = auth.uid()
       and (p_study_id is null or study_id = p_study_id)
       and session_acts_for(bound_session_id)
  );
$$;

-- The account owns p_pseudo_id, whatever session — ONLY for the steps before
-- a session is attached: recovery (challenge + rotation, which then binds it)
-- and binding itself. Each of those proves more than the account password.
create or replace function owns_participant_account(p_pseudo_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and exists (
    select 1 from participants where pseudo_id = p_pseudo_id and auth_user_id = auth.uid()
  );
$$;

-- True when the calling session is a participant of (device binding applies),
-- or the account is staff on, study p_study_id.
create or replace function belongs_to_study(p_study_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and (
    exists (select 1 from participants
             where auth_user_id = auth.uid() and study_id = p_study_id
               and session_acts_for(bound_session_id))
    or exists (select 1 from study_staff where user_id = auth.uid() and study_id = p_study_id)
  );
$$;

-- Study lifecycle: "isActive" = enrolment open; "endedAt" = the study ended.
-- True when new participants may enrol in p_study_id.
create or replace function study_open_for_enrolment(p_study_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from "StudyConfig"
                  where "studyId" = p_study_id and "isActive" and "endedAt" is null);
$$;

-- True when p_study_id has ended (end_study(), seed_study.sql).
create or replace function study_has_ended(p_study_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from "StudyConfig"
                  where "studyId" = p_study_id and "endedAt" is not null);
$$;

-- True unless the study ended before the record's measurement time. Records
-- measured before the end may still arrive afterwards (the app uploads what
-- was waiting); nothing measured later is accepted. (The time is the device's,
-- as everywhere — this stops collection, it is not tamper-proof.)
create or replace function record_before_study_end(p_study_id uuid, p_epoch_start_ms bigint)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select not exists (select 1 from "StudyConfig"
                      where "studyId" = p_study_id and "endedAt" is not null
                        and p_epoch_start_ms > (extract(epoch from "endedAt") * 1000)::bigint);
$$;



-- =============================================================================
-- 2) Tables
-- =============================================================================

-- -- StudyConfig: the study list and definitions, readable before and after sign-in.
alter table "StudyConfig" enable row level security;
drop policy if exists "anon reads study config" on "StudyConfig";
drop policy if exists "app reads study config"  on "StudyConfig";
create policy "app reads study config"
  on "StudyConfig" for select
  to anon, authenticated
  using (true);
-- No write policy: studies are seeded by the wizard (database owner) only.

-- -- participants: no direct access — RPCs below.
alter table participants enable row level security;
drop policy if exists "anon enrolls"            on participants;
drop policy if exists "anon reads participants" on participants;
drop policy if exists "anon rotates own keys"   on participants;

-- -- study_staff: written by the wizard (database owner); read via helpers only.
alter table study_staff enable row level security;

-- -- consent_records: participant-signed, append-only; RPCs below.
alter table consent_records enable row level security;
drop policy if exists "anon writes consent" on consent_records;

-- Immutable for everyone, service_role included: a change of consent is a NEW
-- record. Deletion only through erasure (withdraw_participant, cascade).
create or replace function consent_records_immutable()
returns trigger
language plpgsql
as $$
begin
  raise exception 'consent_records are immutable (signed by the participant) — submit a new record instead';
end;
$$;
drop trigger if exists consent_records_no_update on consent_records;
create trigger consent_records_no_update
  before update on consent_records
  for each row execute function consent_records_immutable();

-- -- study_definitions: append-only history of the terms participants consent
-- to (written by seed_study(); read by research staff / auditors directly).
-- Never changed or removed — a consent record must always be able to show
-- what it was given for.
alter table study_definitions enable row level security;
create or replace function study_definitions_immutable()
returns trigger
language plpgsql
as $$
begin
  raise exception 'study_definitions are append-only — consent records refer to them';
end;
$$;
drop trigger if exists study_definitions_no_change on study_definitions;
create trigger study_definitions_no_change
  before update or delete on study_definitions
  for each row execute function study_definitions_immutable();

-- -- encrypted_records: the account may upload ONLY its own participant's rows.
-- (Before accounts, anyone knowing a pseudonym could insert junk into a
-- participant's future (epoch, schema) slots; the real upload then hit the
-- unique constraint and was treated as synced — silent data loss.)
alter table encrypted_records enable row level security;
drop policy if exists "anon uploads records"  on encrypted_records;
drop policy if exists "anon reads records"    on encrypted_records;
drop policy if exists "participant uploads own records" on encrypted_records;
create policy "participant uploads own records"
  on encrypted_records for insert
  to authenticated
  with check (owns_participant(participant_pseudo_id, study_id)
              and record_before_study_end(study_id, epoch_start_ms));
-- No select/update/delete: reads via get_my_encrypted_records / export_study_records;
-- records are immutable once written.

-- -- data_exports: the "your data was accessed" notice — the study's own
-- participants (and staff) only. Written only by export_study_records().
alter table data_exports enable row level security;
drop policy if exists "anon reads export log" on data_exports;
drop policy if exists "anon logs export"      on data_exports;
drop policy if exists "study members read export log" on data_exports;
create policy "study members read export log"
  on data_exports for select
  to authenticated
  using (belongs_to_study(study_id));


-- =============================================================================
-- 3) Usernames and login ids (pseudonymised; callable before sign-in)
-- =============================================================================
-- The app never sends a username — only p_username_token = PBKDF2 of the
-- normalised username (UsernameToken.kt). Stored: HMAC-SHA256(Vault pepper,
-- token). BACK UP the Vault secret 'username_pepper'; never rotate it.
do $$
begin
  if to_regclass('vault.decrypted_secrets') is null then
    raise warning 'Supabase Vault is not available — username tokens (accounts) will fail until it is';
  elsif not exists (select 1 from vault.decrypted_secrets where name = 'username_pepper') then
    perform vault.create_secret(
      replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
      'username_pepper',
      'Pepper for participant username tokens. Back it up; losing it disables account recovery. Never rotate.');
  end if;
end;
$$;

-- HMAC-SHA256 (RFC 2104) on core sha256() — no pgcrypto dependency. Internal.
create or replace function hmac_sha256(p_key bytea, p_message bytea)
returns bytea
language plpgsql
immutable
set search_path = public
as $$
declare
  k    bytea := p_key;
  ipad bytea;
  opad bytea;
begin
  if length(k) > 64 then k := sha256(k); end if;
  k := k || decode(repeat('00', 64 - length(k)), 'hex');
  ipad := k;
  opad := k;
  for i in 0..63 loop
    ipad := set_byte(ipad, i, get_byte(k, i) # 54);   -- 0x36
    opad := set_byte(opad, i, get_byte(k, i) # 92);   -- 0x5c
  end loop;
  return sha256(opad || sha256(ipad || p_message));
end;
$$;

-- Internal: stored form of a client username token.
create or replace function participant_username_token(p_client_token text)
returns text
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_pepper text;
begin
  if coalesce(p_client_token, '') = '' then
    raise exception 'username token required';
  end if;
  select decrypted_secret into v_pepper from vault.decrypted_secrets where name = 'username_pepper';
  if v_pepper is null then
    raise exception 'username pepper missing from Vault — re-run rls_policies.sql';
  end if;
  return encode(hmac_sha256(convert_to(v_pepper, 'UTF8'), convert_to(p_client_token, 'UTF8')), 'base64');
end;
$$;

drop function if exists is_username_available(text);
create or replace function is_username_available(p_username_token text)
returns boolean
language sql
security definer
set search_path = public
as $$
  select not exists (
    select 1 from participants where username_token = participant_username_token(p_username_token)
  );
$$;

-- Login id for the participant's account: the account email is
-- '<login id>@participants.invalid' (ParticipantCredentials.kt). It is the
-- PEPPERED token, so auth.users holds no username and cannot be dictionary-
-- attacked back to one without the Vault secret. Deterministic and returned
-- whether or not the account exists — reveals nothing about taken usernames.
create or replace function participant_login_id(p_username_token text)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select left(encode(decode(participant_username_token(p_username_token), 'base64'), 'hex'), 40);
$$;



-- =============================================================================
-- 4) Enrolment and consent (signed-in participant)
-- =============================================================================

-- Signed consent submit: the caller must own the participant, and the signer
-- key must be its registered device key. RSA can't be verified here; anyone
-- verifies the signature against signer_public_key_b64 (ConsentSignature.kt).
-- The record names the study definition it was given for (its SHA-256, also
-- signed): it must be the study's CURRENT definition — consent to terms that
-- have since changed is refused (the app reloads and asks again).
drop function if exists submit_consent_record(uuid, uuid, text, bigint, json, text, text);
create or replace function submit_consent_record(
  p_pseudo_id             uuid,
  p_study_id              uuid,
  p_study_version         text,
  p_completed_at_ms       bigint,
  p_decisions             json,
  p_signature_b64         text,
  p_signer_public_key_b64 text,
  p_definition_sha256     text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not owns_participant(p_pseudo_id, p_study_id) then
    raise exception 'not your participant';
  end if;
  if coalesce(p_signature_b64, '') = '' or coalesce(p_signer_public_key_b64, '') = '' then
    raise exception 'consent record must be signed by the participant';
  end if;
  if study_has_ended(p_study_id) then
    raise exception 'study_ended: this study has ended — no new consent is recorded';
  end if;
  if not exists (
    select 1 from "StudyConfig"
     where "studyId" = p_study_id and "definitionSha256" = lower(p_definition_sha256)
  ) then
    raise exception 'consent must name the study''s current definition — the study changed, review it again';
  end if;
  if json_typeof(p_decisions) is distinct from 'array' then
    raise exception 'decisions must be a JSON array';
  end if;
  if not exists (
    select 1 from participants
     where pseudo_id = p_pseudo_id and study_id = p_study_id
       and public_key_b64 = p_signer_public_key_b64
  ) then
    raise exception 'consent signer key does not match the participant''s registered key';
  end if;

  insert into consent_records (participant_pseudo_id, study_id, study_version,
                               completed_at_ms, decisions, signature_b64, signer_public_key_b64,
                               definition_sha256)
  values (p_pseudo_id, p_study_id, p_study_version,
          p_completed_at_ms, p_decisions, p_signature_b64, p_signer_public_key_b64,
          lower(p_definition_sha256));
end;
$$;

-- Atomic enrolment, as the just-created account: participant (bound to the
-- account) + rotation key + signed consent — all or nothing. Idempotent for
-- the identical enrolment (retry after a lost response).
-- With p_device_key_b64 (API 4) the participant is bound to this session and
-- device key; p_passphrase_key_b64 registers the passphrase proof for later
-- recoveries. An app without them (API 3) enrols unbound, as before.
-- The study must be open for enrolment, and when it has a join code the caller
-- must present a join ticket: issued by issue_join_ticket() for the right code,
-- single use, consumed here (consume_join_ticket(), join_code.sql — resolved
-- at call time, so apply every backend file before enrolling anyone).
drop function if exists enroll_participant(uuid, uuid, text, text, text, text, text, text, bigint, json, text);
drop function if exists enroll_participant(uuid, uuid, text, text, text, text, text, text, bigint, json, text, text);
drop function if exists enroll_participant(uuid, uuid, text, text, text, text, text, text, bigint, json, text, text, uuid);
create or replace function enroll_participant(
  p_pseudo_id                  uuid,
  p_study_id                   uuid,
  p_username_token             text,
  p_public_key_b64             text,
  p_wrapped_dek_b64            text,
  p_recovery_blob              text,
  p_rotation_key_b64           text,
  p_consent_study_version      text,
  p_consent_completed_at_ms    bigint,
  p_consent_decisions          json,
  p_consent_signature_b64      text,
  p_consent_definition_sha256  text,
  p_join_ticket                uuid,
  p_device_key_b64             text default null,
  p_passphrase_key_b64         text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid   uuid := auth.uid();
  v_token text := participant_username_token(p_username_token);
begin
  if v_uid is null then
    raise exception 'sign in first — enrolment needs the participant''s account';
  end if;
  if exists (select 1 from participants where pseudo_id = p_pseudo_id) then
    if exists (
      select 1 from participants p
        join participant_rotation_keys r on r.pseudo_id = p.pseudo_id
        join consent_records c on c.participant_pseudo_id = p.pseudo_id
       where p.pseudo_id = p_pseudo_id and p.study_id = p_study_id and p.auth_user_id = v_uid
         and p.username_token = v_token
         and p.public_key_b64 = p_public_key_b64 and p.wrapped_dek_b64 = p_wrapped_dek_b64
         and p.recovery_blob = p_recovery_blob and r.rotation_key_b64 = p_rotation_key_b64
         and c.signature_b64 = p_consent_signature_b64
         and p.device_key_b64 is not distinct from p_device_key_b64
    ) then
      -- Identical enrolment already stored. The retry may come from a new
      -- login of the same phone (it holds the same device key): bind it.
      if p_device_key_b64 is not null then
        update participants set bound_session_id = current_session_id() where pseudo_id = p_pseudo_id;
      end if;
      return;
    end if;
    raise exception 'participant already enrolled';
  end if;
  if not study_open_for_enrolment(p_study_id) then
    raise exception 'study_closed: this study is not open for enrolment';
  end if;
  if not consume_join_ticket(p_study_id, p_join_ticket) then
    raise exception 'join_ticket_invalid: enter the study''s join code again';
  end if;
  if exists (select 1 from participants where auth_user_id = v_uid) then
    raise exception 'this account is already enrolled';
  end if;
  if exists (select 1 from participants where username_token = v_token) then
    raise exception 'username already taken';
  end if;
  if coalesce(p_rotation_key_b64, '') = '' then
    raise exception 'rotation key required';
  end if;
  if p_device_key_b64 is not null and length(decode(p_device_key_b64, 'base64')) < 32 then
    raise exception 'device key must be at least 32 bytes';
  end if;
  if p_device_key_b64 is not null and current_session_id() is null then
    raise exception 'no session id in the access token — cannot bind the device';
  end if;

  insert into participants (pseudo_id, study_id, username_token, auth_user_id,
                            public_key_b64, wrapped_dek_b64, recovery_blob,
                            device_key_b64, bound_session_id)
  values (p_pseudo_id, p_study_id, v_token, v_uid,
          p_public_key_b64, p_wrapped_dek_b64, p_recovery_blob,
          p_device_key_b64, case when p_device_key_b64 is not null then current_session_id() end);

  insert into participant_rotation_keys (pseudo_id, rotation_key_b64, passphrase_key_b64)
  values (p_pseudo_id, p_rotation_key_b64, nullif(p_passphrase_key_b64, ''));

  perform submit_consent_record(p_pseudo_id, p_study_id, p_consent_study_version,
                                p_consent_completed_at_ms, p_consent_decisions,
                                p_consent_signature_b64, p_public_key_b64,
                                p_consent_definition_sha256);
end;
$$;

-- The participant's consent records, oldest first (recovery restores the
-- latest one whose signature verifies).
drop function if exists get_consent_records(uuid);
drop function if exists get_my_consent_records();   -- return type gained definition_sha256
create or replace function get_my_consent_records()
returns table (
  participant_pseudo_id text,
  study_id              text,
  study_version         text,
  completed_at_ms       bigint,
  decisions             json,
  signature_b64         text,
  signer_public_key_b64 text,
  definition_sha256     text
)
language sql
stable
security definer
set search_path = public
as $$
  select participant_pseudo_id::text, study_id::text, study_version, completed_at_ms, decisions,
         signature_b64, signer_public_key_b64, definition_sha256
    from consent_records
   where participant_pseudo_id = my_pseudo_id()
   order by created_at, id;
$$;



-- =============================================================================
-- 5) Recovery (signed-in participant on a new device)
-- =============================================================================

-- Recovery material ONLY after sign-in — previously anyone with a username got
-- the blob and could guess the passphrase offline; now guessing is online,
-- against the rate-limited sign-in. Account-level on purpose (no session
-- binding): the recovering phone needs it before its rotation binds it, and it
-- is only the passphrase-encrypted blob and the DEK wrapped for the device.
drop function if exists get_recovery_material(text);
create or replace function get_my_recovery_material()
returns table (
  pseudo_id       text,
  study_id        text,
  recovery_blob   text,
  wrapped_dek_b64 text
)
language sql
stable
security definer
set search_path = public
as $$
  select pseudo_id::text, study_id::text, recovery_blob, wrapped_dek_b64
    from participants
   where auth_user_id = auth.uid()
   limit 1;
$$;

drop function if exists get_encrypted_records(uuid);
create or replace function get_my_encrypted_records()
returns table (
  epoch_start_ms bigint,
  schema_name    text,
  ciphertext_hex text
)
language sql
stable
security definer
set search_path = public
as $$
  select epoch_start_ms, schema_name, ciphertext_hex
    from encrypted_records
   where participant_pseudo_id = my_pseudo_id();
$$;

-- Key rotation: the owning account AND an HMAC proof keyed by the rotation key
-- = HMAC-SHA256(DEK, label) over the nonce and the new key material
-- (KeyRotationAuth.kt — keep the message format in sync).
-- Since API 4 also a proof keyed by the PASSPHRASE key (00_schema.sql) once one
-- is registered: the DEK is on every phone that was ever enrolled, the
-- passphrase is not — so a replaced phone cannot rotate the account back.
-- Account-level ownership (owns_participant_account): the recovering phone's
-- session is not bound yet; the rotation binds it.
create or replace function request_key_rotation_challenge(p_pseudo_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_nonce text;
begin
  if not owns_participant_account(p_pseudo_id) then
    raise exception 'not your participant';
  end if;
  if not exists (select 1 from participant_rotation_keys where pseudo_id = p_pseudo_id) then
    raise exception 'key rotation is not available for this participant (enrolled without a rotation key — re-enrol)';
  end if;
  v_nonce := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into key_rotation_challenges (pseudo_id, challenge, expires_at)
  values (p_pseudo_id, v_nonce, now() + interval '5 minutes')
  on conflict (pseudo_id) do update
    set challenge = excluded.challenge, expires_at = excluded.expires_at;
  return v_nonce;
end;
$$;

drop function if exists rotate_participant_keys(uuid, text, text);
drop function if exists rotate_participant_keys(uuid, text, text, text, text);
create or replace function rotate_participant_keys(
  p_pseudo_id            uuid,
  p_public_key_b64       text,
  p_wrapped_dek_b64      text,
  p_nonce                text,
  p_proof_b64            text,
  p_device_key_b64       text default null,
  p_passphrase_key_b64   text default null,
  p_passphrase_proof_b64 text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_challenge  text;
  v_expires    timestamptz;
  v_key        text;
  v_pass_key   text;
  v_expected   bytea;
begin
  -- Ownership first: a stranger must not even burn the owner's challenge.
  if not owns_participant_account(p_pseudo_id) then
    raise exception 'not your participant';
  end if;
  if current_session_id() is null then
    raise exception 'no session id in the access token — cannot bind the device';
  end if;
  if p_device_key_b64 is not null and length(decode(p_device_key_b64, 'base64')) < 32 then
    raise exception 'device key must be at least 32 bytes';
  end if;
  -- Consume the nonce whatever the outcome: one attempt per challenge.
  delete from key_rotation_challenges where pseudo_id = p_pseudo_id
    returning challenge, expires_at into v_challenge, v_expires;
  if v_challenge is null or v_challenge <> p_nonce or v_expires < now() then
    raise exception 'invalid or expired key-rotation challenge';
  end if;

  select rotation_key_b64, passphrase_key_b64 into v_key, v_pass_key
    from participant_rotation_keys where pseudo_id = p_pseudo_id;
  v_expected := hmac_sha256(
    decode(v_key, 'base64'),
    convert_to('rotate|' || lower(p_pseudo_id::text) || '|' || p_nonce || '|' ||
               p_public_key_b64 || '|' || p_wrapped_dek_b64, 'UTF8'));
  if v_key is null or p_proof_b64 is null or decode(p_proof_b64, 'base64') <> v_expected then
    raise exception 'key-rotation proof rejected';
  end if;

  -- Passphrase proof (KeyRotationAuth.passphraseProof — keep in sync): required
  -- once a passphrase key is registered. Without one (enrolled before API 4)
  -- this rotation registers it — until then the DEK proof alone decides.
  if v_pass_key is not null then
    v_expected := hmac_sha256(
      decode(v_pass_key, 'base64'),
      convert_to('rotate-passphrase|' || lower(p_pseudo_id::text) || '|' || p_nonce || '|' ||
                 p_public_key_b64 || '|' || p_wrapped_dek_b64 || '|' || coalesce(p_device_key_b64, ''), 'UTF8'));
    if p_passphrase_proof_b64 is null or decode(p_passphrase_proof_b64, 'base64') <> v_expected then
      raise exception 'passphrase_proof_rejected: key rotation needs the passphrase';
    end if;
  elsif coalesce(p_passphrase_key_b64, '') <> '' then
    update participant_rotation_keys set passphrase_key_b64 = p_passphrase_key_b64
     where pseudo_id = p_pseudo_id;
  end if;

  -- The recovering phone becomes the participant's device: its key, its
  -- session. Every other session — the replaced phone's included, also after
  -- it signs in again — is refused from now on (owns_participant).
  update participants
     set public_key_b64   = p_public_key_b64,
         wrapped_dek_b64  = p_wrapped_dek_b64,
         device_key_b64   = p_device_key_b64,
         bound_session_id = current_session_id()
   where pseudo_id = p_pseudo_id;
  delete from session_challenges where pseudo_id = p_pseudo_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- Device binding (00_schema.sql): attach a new login of the SAME phone.
-- -----------------------------------------------------------------------------

-- Where the calling session stands, for the app (SupabaseMeasurementRepository
-- bindingCheckLocked, DeviceBindingAuth.nextStep):
--   {"pseudoId": …, "session": "this" | "other" | "none", "deviceKey": bool},
-- or null when the account has no participant. "other" = another session is bound: a new login
-- of this phone (re-attach with bind_session) or a replaced phone (its proof
-- fails). "none" = not bound yet (enrolled before binding existed).
create or replace function my_session_status()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select jsonb_build_object(
           'pseudoId', pseudo_id,
           'session', case when bound_session_id is null then 'none'
                           when bound_session_id = current_session_id() then 'this'
                           else 'other' end,
           'deviceKey', device_key_b64 is not null)
    from participants
   where auth_user_id = auth.uid()
   limit 1;
$$;

-- One-time nonce (5 min) for THIS session; account-level ownership.
create or replace function request_session_challenge(p_pseudo_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_nonce text;
begin
  if not owns_participant_account(p_pseudo_id) then
    raise exception 'not your participant';
  end if;
  if current_session_id() is null then
    raise exception 'no session id in the access token';
  end if;
  v_nonce := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into session_challenges (pseudo_id, challenge, session_id, expires_at)
  values (p_pseudo_id, v_nonce, current_session_id(), now() + interval '5 minutes')
  on conflict (pseudo_id) do update
    set challenge = excluded.challenge, session_id = excluded.session_id, expires_at = excluded.expires_at;
  return v_nonce;
end;
$$;

-- Binds the calling session after an HMAC proof keyed by the device key over
-- the nonce (DeviceBindingAuth.kt — keep the message format in sync). Only the
-- phone holding the registered device key can; a replaced phone gets
-- 'device_replaced'.
create or replace function bind_session(p_pseudo_id uuid, p_nonce text, p_proof_b64 text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_challenge text;
  v_session   uuid;
  v_expires   timestamptz;
  v_key       text;
begin
  if not owns_participant_account(p_pseudo_id) then
    raise exception 'not your participant';
  end if;
  delete from session_challenges where pseudo_id = p_pseudo_id
    returning challenge, session_id, expires_at into v_challenge, v_session, v_expires;
  if v_challenge is null or v_challenge <> p_nonce or v_expires < now()
     or v_session is distinct from current_session_id() then
    raise exception 'invalid or expired session challenge';
  end if;
  select device_key_b64 into v_key from participants where pseudo_id = p_pseudo_id;
  if v_key is null or p_proof_b64 is null or decode(p_proof_b64, 'base64') <> hmac_sha256(
       decode(v_key, 'base64'),
       convert_to('bind|' || lower(p_pseudo_id::text) || '|' || p_nonce, 'UTF8')) then
    raise exception 'device_replaced: this phone is no longer the participant''s device';
  end if;
  update participants set bound_session_id = v_session where pseudo_id = p_pseudo_id;
end;
$$;

-- First binding of a participant enrolled before binding existed: the first
-- updated app to ask registers its device key and binds its session. Only
-- while NOTHING is bound — afterwards changing the device is a recovery.
create or replace function register_device_key(p_pseudo_id uuid, p_device_key_b64 text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not owns_participant_account(p_pseudo_id) then
    raise exception 'not your participant';
  end if;
  if current_session_id() is null then
    raise exception 'no session id in the access token';
  end if;
  if p_device_key_b64 is null or length(decode(p_device_key_b64, 'base64')) < 32 then
    raise exception 'device key must be at least 32 bytes';
  end if;
  update participants
     set device_key_b64 = p_device_key_b64, bound_session_id = current_session_id()
   where pseudo_id = p_pseudo_id and device_key_b64 is null and bound_session_id is null;
  if not found then
    raise exception 'device_replaced: the participant already has a device';
  end if;
end;
$$;



-- =============================================================================
-- 6) PI export (staff account of the study)
-- =============================================================================
-- Returns the study's ciphertext AND logs the access in data_exports in the
-- same transaction (participants are told). Staff: a normal Supabase account
-- linked to the study in study_staff by the setup wizard.
--
-- Paged (the API returns at most 1000 rows per request): the first call
-- (p_export_id null) logs ONE export and opens an export session; further
-- pages pass its export_id and the last record_id seen (p_after_id). A
-- session belongs to the caller, expires after an hour and covers only the
-- records that existed when it was logged — no unlogged reads.
drop function if exists export_study_records(uuid, text);
create or replace function export_study_records(
  p_study_id  uuid,
  p_note      text   default null,
  p_export_id bigint default null,
  p_after_id  bigint default 0,
  p_limit     int    default 1000
)
returns table (
  record_id             bigint,
  export_id             bigint,
  total_count           int,
  participant_pseudo_id text,
  epoch_start_ms        bigint,
  schema_name           text,
  ciphertext_hex        text,
  pi_wrapped_dek_hex    text
)
language plpgsql
security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  v_export bigint;
  v_max    bigint;
  v_count  int;
begin
  if not exists (select 1 from study_staff s where s.user_id = auth.uid() and s.study_id = p_study_id) then
    raise exception 'only the study''s research staff can export its data';
  end if;

  if p_export_id is null then
    delete from export_sessions s where s.expires_at < clock_timestamp();
    select count(*), coalesce(max(e.id), 0) into v_count, v_max
      from encrypted_records e where e.study_id = p_study_id;
    insert into data_exports (study_id, exported_at_ms, record_count, note)
    values (p_study_id, (extract(epoch from clock_timestamp()) * 1000)::bigint, v_count,
            coalesce(nullif(p_note, ''), 'PI export'))
    returning id into v_export;
    insert into export_sessions (export_id, user_id, study_id, max_id, expires_at)
    values (v_export, auth.uid(), p_study_id, v_max, clock_timestamp() + interval '1 hour');
  else
    select s.export_id, s.max_id into v_export, v_max
      from export_sessions s
     where s.export_id = p_export_id and s.user_id = auth.uid()
       and s.study_id = p_study_id and s.expires_at > clock_timestamp();
    if v_export is null then
      raise exception 'export session expired or not yours — start a new export';
    end if;
    select d.record_count into v_count from data_exports d where d.id = v_export;
  end if;

  return query
    select e.id, v_export, v_count, e.participant_pseudo_id::text, e.epoch_start_ms,
           e.schema_name, e.ciphertext_hex, e.pi_wrapped_dek_hex
      from encrypted_records e
     where e.study_id = p_study_id
       and e.id > coalesce(p_after_id, 0) and e.id <= v_max
     order by e.id
     limit least(greatest(coalesce(p_limit, 1000), 1), 1000);
end;
$$;



-- =============================================================================
-- 7) Removed
-- =============================================================================
-- verify_join_code lives only in join_code.sql (text, text). An old (uuid,
-- text) overload here made the RPC ambiguous and accepted any code.
drop function if exists verify_join_code(uuid, text);


-- =============================================================================
-- 8) Function privileges: revoke everything, grant exactly what the app needs
-- =============================================================================
-- Admin and internal functions (seed_study, withdraw_participant, the purges,
-- hmac_sha256, participant_username_token, …) end up callable by NO client
-- role. Run the backend files in order: join_code / withdraw / seed_study /
-- retention re-grant their own client functions after this.
revoke execute on all functions in schema public from public, anon, authenticated;

-- Before sign-in (and after).
grant execute on function backend_version()                                   to anon, authenticated;
grant execute on function is_username_available(text)                         to anon, authenticated;
grant execute on function participant_login_id(text)                          to anon, authenticated;

-- Signed-in participant (each checks ownership via auth.uid(); data access
-- also the session binding — owns_participant / my_pseudo_id).
grant execute on function current_session_id()                                to authenticated;   -- used by policies
grant execute on function session_acts_for(uuid)                              to authenticated;
grant execute on function my_pseudo_id()                                      to authenticated;
grant execute on function owns_participant(uuid, uuid)                        to authenticated;
grant execute on function owns_participant_account(uuid)                      to authenticated;
grant execute on function belongs_to_study(uuid)                              to authenticated;
grant execute on function record_before_study_end(uuid, bigint)               to authenticated;   -- upload policy
grant execute on function enroll_participant(uuid, uuid, text, text, text, text, text, text, bigint, json, text, text, uuid, text, text) to authenticated;
grant execute on function submit_consent_record(uuid, uuid, text, bigint, json, text, text, text) to authenticated;
grant execute on function get_my_consent_records()                            to authenticated;
grant execute on function get_my_recovery_material()                          to authenticated;
grant execute on function get_my_encrypted_records()                          to authenticated;
grant execute on function request_key_rotation_challenge(uuid)                to authenticated;
grant execute on function rotate_participant_keys(uuid, text, text, text, text, text, text, text) to authenticated;
grant execute on function my_session_status()                                 to authenticated;
grant execute on function request_session_challenge(uuid)                     to authenticated;
grant execute on function bind_session(uuid, text, text)                      to authenticated;
grant execute on function register_device_key(uuid, text)                     to authenticated;

-- Research staff (checks study_staff).
grant execute on function export_study_records(uuid, text, bigint, bigint, int) to authenticated;

-- Backend API version of this file (00_schema.sql, backend_version()).
select stamp_backend_component('rls_policies', 4, 3);

-- Make PostgREST (the REST API) see changed function signatures immediately,
-- instead of answering PGRST202 "function not found" from a stale cache.
notify pgrst, 'reload schema';
