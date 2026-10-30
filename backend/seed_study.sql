-- =============================================================================
-- seed_study.sql — "study definition as data"
-- =============================================================================
--
-- Apply once (after 00_schema.sql; re-runnable). Defines seed_study(), a
-- validating loader for study definitions:
--
--   1. Researcher edits a copy of study.example.json — that file IS the study
--      (name, version, consent data types, retention, join code).
--   2. `bash seed_study.sh my_study.json` wraps the JSON + the PI public key
--      into a generated SQL call (and can run it directly via psql).
--   3. seed_study() validates EVERYTHING and either applies the definition or
--      raises one exception listing every problem — nothing half-applied.
--
-- The consent screens render directly from "dataTypes", so editing the JSON is
-- editing the study; no app code changes are involved.
--
-- ADMIN-ONLY: executable by the dashboard/service_role, revoked from anon.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- record_study_definition — INTERNAL: the study's current definition into the
-- append-only history (study_definitions) and its hash into
-- "StudyConfig"."definitionSha256". The definition is the exact PI-signed text
-- when there is one (what the app verifies and shows); otherwise a canonical
-- JSON of the consent-relevant columns. Hash = hex SHA-256 of its UTF-8 bytes
-- (StudyDefinitionHash.kt in the library computes the same). Re-applying an
-- identical definition reuses its row.
-- -----------------------------------------------------------------------------
create or replace function public.record_study_definition(p_study_id uuid)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  s       "StudyConfig"%rowtype;
  v_text  text;
  v_hash  text;
begin
  select * into s from "StudyConfig" where "studyId" = p_study_id;
  if not found then
    raise exception 'record_study_definition: no study %', p_study_id;
  end if;
  v_text := coalesce(s."signedDefinition", jsonb_build_object(
    'format',          'mbpulse-study-unsigned',
    'studyId',         s."studyId",
    'studyName',       s."studyName",
    'studyVersion',    s."studyVersion",
    'piPublicKey',     s."piPublicKey",
    'dataTypes',       s."dataTypes"::jsonb,
    'schemaDataTypes', s."schemaDataTypes")::text);
  v_hash := encode(sha256(convert_to(v_text, 'UTF8')), 'hex');

  insert into study_definitions (definition_sha256, study_id, study_version, definition,
                                 definition_signature, issued_at)
  values (v_hash, p_study_id, s."studyVersion"::text, v_text, s."definitionSignature",
          case when s."signedDefinition" is not null
               then (s."signedDefinition"::jsonb ->> 'issuedAt')::timestamptz end)
  on conflict (definition_sha256) do nothing;

  update "StudyConfig" set "definitionSha256" = v_hash where "studyId" = p_study_id;
  return v_hash;
end;
$$;
revoke execute on function public.record_study_definition(uuid) from public, anon, authenticated;

create or replace function public.seed_study(
  spec              jsonb,
  pi_public_key_pem text default null   -- overrides spec."piPublicKey" when given
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_errors    text[] := '{}';
  v_notices   text[] := '{}';
  v_pem       text;
  v_join_code text;
  v_study_id  uuid;
  v_action    text;
  v_ids       text[];
  e           jsonb;
  i           int := 0;
  v_essential int := 0;
  v_secret_id uuid;
  v_signed    jsonb;
  v_definition text;
begin
  -- ---------------------------------------------------------------- validate
  -- Messages are appended as `text '...'`: an untyped literal after `text[] ||`
  -- is parsed as an array literal and fails when it starts with a double quote.
  if jsonb_typeof(spec) is distinct from 'object' then
    raise exception 'seed_study: spec must be a JSON object';
  end if;

  if jsonb_typeof(spec->'studyName') is distinct from 'string'
     or length(trim(spec->>'studyName')) = 0 then
    v_errors := v_errors || text '"studyName" must be a non-empty string';
  end if;

  if jsonb_typeof(spec->'studyVersion') is distinct from 'number' then
    v_errors := v_errors || text '"studyVersion" must be a number (e.g. 1.0)';
  end if;

  if spec ? 'isActive' and jsonb_typeof(spec->'isActive') is distinct from 'boolean' then
    v_errors := v_errors || text '"isActive" must be true or false';
  end if;

  v_pem := coalesce(nullif(trim(coalesce(pi_public_key_pem, '')), ''),
                    spec->>'piPublicKey');
  if v_pem is null or position('BEGIN PUBLIC KEY' in v_pem) = 0 then
    v_errors := v_errors ||
      (text 'PI public key missing or not a PEM ("BEGIN PUBLIC KEY") — run keygen.sh, '
       || 'then seed via seed_study.sh (it passes keys/pi_public.pem automatically)');
  end if;

  if jsonb_typeof(spec->'dataTypes') is distinct from 'array'
     or jsonb_array_length(spec->'dataTypes') = 0 then
    v_errors := v_errors || text '"dataTypes" must be a non-empty array — it defines the consent screens';
  else
    for e in select * from jsonb_array_elements(spec->'dataTypes') loop
      i := i + 1;
      if jsonb_typeof(e) is distinct from 'object' then
        v_errors := v_errors || format('dataTypes[%s] must be an object', i);
        continue;
      end if;
      if length(trim(coalesce(e->>'id', ''))) = 0 then
        v_errors := v_errors || format('dataTypes[%s]: "id" must be a non-empty string', i);
      end if;
      if length(trim(coalesce(e->>'name', ''))) = 0
         or length(trim(coalesce(e->>'description', ''))) = 0
         or length(trim(coalesce(e->>'purpose', ''))) = 0 then
        v_errors := v_errors || format(
          'dataTypes[%s] ("%s"): "name", "description" and "purpose" are all required '
          || '(participants read them on the consent screen)', i, coalesce(e->>'id','?'));
      end if;
      if jsonb_typeof(e->'retentionDays') is distinct from 'number'
         or (e->>'retentionDays')::numeric <= 0
         or (e->>'retentionDays')::numeric <> floor((e->>'retentionDays')::numeric) then
        v_errors := v_errors || format(
          'dataTypes[%s] ("%s"): "retentionDays" must be a positive whole number matching '
          || 'the ethics-approved retention period', i, coalesce(e->>'id','?'));
      end if;
      if jsonb_typeof(e->'isEssential') is distinct from 'boolean' then
        v_errors := v_errors || format(
          'dataTypes[%s] ("%s"): "isEssential" must be true or false', i, coalesce(e->>'id','?'));
      elsif (e->>'isEssential')::boolean then
        v_essential := v_essential + 1;
      end if;
      if e ? 'samplingSeconds' and jsonb_typeof(e->'samplingSeconds') <> 'null' then
        if jsonb_typeof(e->'samplingSeconds') is distinct from 'number'
           or (e->>'samplingSeconds')::numeric <= 0
           or (e->>'samplingSeconds')::numeric <> floor((e->>'samplingSeconds')::numeric) then
          v_errors := v_errors || format(
            'dataTypes[%s] ("%s"): "samplingSeconds" must be a positive whole number of seconds '
            || 'between recorded readings (omit it for the sensor''s natural rate)',
            i, coalesce(e->>'id','?'));
        end if;
      end if;
      v_ids := v_ids || (e->>'id');
    end loop;

    if (select count(*) from unnest(v_ids) x) <> (select count(distinct x) from unnest(v_ids) x) then
      v_errors := v_errors || format('dataTypes ids must be unique (got: %s)', array_to_string(v_ids, ', '));
    end if;
    if v_essential = 0 then
      v_notices := v_notices ||
        text 'no dataTypes entry is essential — every participant can decline everything';
    end if;
  end if;

  -- Optional schema -> data-type map ({"omh:heart-rate": "heart_rate"}): the
  -- server-side copy of the app's schemaToConsentType, used by retention.sql
  -- to apply each data type's retentionDays to the right records.
  if spec ? 'schemaDataTypes' and jsonb_typeof(spec->'schemaDataTypes') <> 'null' then
    if jsonb_typeof(spec->'schemaDataTypes') is distinct from 'object' then
      v_errors := v_errors || text '"schemaDataTypes" must be an object mapping "namespace:name" to a dataTypes id';
    else
      for e in select jsonb_build_object('k', key, 'v', value)
               from jsonb_each(spec->'schemaDataTypes') loop
        if (e->>'k') !~ '^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$' then
          v_errors := v_errors || format('"schemaDataTypes": "%s" is not a schema id (namespace:name)', e->>'k');
        end if;
        if jsonb_typeof(e->'v') is distinct from 'string'
           or not ((e->>'v') = any (coalesce(v_ids, '{}'))) then
          v_errors := v_errors || format(
            '"schemaDataTypes": "%s" maps to "%s", which is not a dataTypes id',
            e->>'k', coalesce(e->>'v', 'null'));
        end if;
      end loop;
    end if;
  else
    v_notices := v_notices ||
      text 'no "schemaDataTypes" — retention purges every record after the study''s longest retentionDays';
  end if;

  -- Optional: the setup wizard's custom-schema specs, stored for the wizard
  -- only (it loads them back on update). Apps never read them.
  if spec ? 'customSchemas' and jsonb_typeof(spec->'customSchemas') not in ('array', 'null') then
    v_errors := v_errors || text '"customSchemas" must be an array (the setup wizard''s custom-schema specs)';
  end if;

  if spec ? 'joinCode' and jsonb_typeof(spec->'joinCode') not in ('string', 'null') then
    v_errors := v_errors || text '"joinCode" must be a string ("" removes the code; omit to leave unchanged)';
  end if;
  v_join_code := case when jsonb_typeof(spec->'joinCode') = 'string' then spec->>'joinCode' end;
  if v_join_code is not null and v_join_code <> '' and length(v_join_code) < 6 then
    v_notices := v_notices || text 'joinCode is shorter than 6 characters — easy to brute-force';
  end if;

  -- "studyId": an existing study is updated; an unknown one is CREATED with
  -- that id (a signed definition names its study, so the id is chosen before
  -- signing). Omitted: a new study with a generated id.
  if spec ? 'studyId' then
    begin
      v_study_id := (spec->>'studyId')::uuid;
    exception when others then
      v_errors := v_errors || text '"studyId" must be a UUID (omit it to create a new study)';
    end;
  end if;

  -- PI-signed definition (optional here, required by apps that pin a
  -- study-signing key). The signature itself cannot be checked in Postgres
  -- (no RSA) — the app verifies it. What is checked: the signed text is the
  -- definition being applied, so the columns can never disagree with it.
  if spec ? 'signedDefinition' then
    if jsonb_typeof(spec->'signedDefinition') is distinct from 'string'
       or jsonb_typeof(spec->'definitionSignature') is distinct from 'string'
       or length(spec->>'definitionSignature') = 0 then
      v_errors := v_errors || text '"signedDefinition" and "definitionSignature" must both be non-empty strings';
    else
      begin
        v_signed := (spec->>'signedDefinition')::jsonb;
      exception when others then
        v_errors := v_errors || text '"signedDefinition" is not valid JSON';
      end;
      if v_signed is not null then
        if v_signed->>'format' is distinct from 'mbpulse-study-v1' then
          v_errors := v_errors || text 'signedDefinition: unknown "format" (expected mbpulse-study-v1)';
        end if;
        if v_study_id is null or v_signed->>'studyId' is distinct from v_study_id::text then
          v_errors := v_errors || text 'signedDefinition: "studyId" must equal the study''s "studyId"';
        end if;
        if v_signed->'studyName' is distinct from spec->'studyName'
           or v_signed->'studyVersion' is distinct from spec->'studyVersion'
           or v_signed->'dataTypes' is distinct from spec->'dataTypes'
           or coalesce(v_signed->'schemaDataTypes', 'null') is distinct from coalesce(spec->'schemaDataTypes', 'null')
           or trim(both E' \t\r\n' from coalesce(v_signed->>'piPublicKey', ''))
              is distinct from trim(both E' \t\r\n' from coalesce(v_pem, '')) then
          v_errors := v_errors || (text 'signedDefinition does not match the definition being applied '
            || '(studyName, studyVersion, dataTypes, schemaDataTypes and the PI key must be identical) — re-sign it');
        end if;
      end if;
    end if;
  else
    v_notices := v_notices || text 'definition is NOT signed — apps that pin a study-signing key will refuse this study';
  end if;

  if v_study_id is not null and exists (
       select 1 from "StudyConfig" where "studyId" = v_study_id and "endedAt" is not null) then
    v_errors := v_errors || text 'this study has ended (end_study) — an ended study cannot be changed';
  end if;

  if array_length(v_errors, 1) > 0 then
    raise exception E'seed_study: the study definition has % problem(s):\n • %',
      array_length(v_errors, 1), array_to_string(v_errors, E'\n • ');
  end if;

  -- ------------------------------------------------------------------ apply
  -- An update always replaces the signature: a re-seed without one clears it,
  -- so a stale signed text can never sit next to changed columns.
  if v_study_id is not null and exists (select 1 from "StudyConfig" where "studyId" = v_study_id) then
    update "StudyConfig" set
      "studyName"    = spec->>'studyName',
      "studyVersion" = (spec->>'studyVersion')::real,
      -- Omitted = keep: re-seeding an amendment must not reopen enrolment.
      "isActive"     = coalesce((spec->>'isActive')::boolean, "isActive"),
      "piPublicKey"  = v_pem,
      "dataTypes"    = (spec->'dataTypes')::json,
      -- Omitted = keep the stored mapping.
      "schemaDataTypes" = case when spec ? 'schemaDataTypes'
                               then nullif(spec->'schemaDataTypes', 'null'::jsonb)
                               else "schemaDataTypes" end,
      "signedDefinition"    = spec->>'signedDefinition',
      "definitionSignature" = spec->>'definitionSignature',
      -- Omitted = keep (seed_study.sh does not send it).
      "customSchemas" = case when spec ? 'customSchemas'
                             then nullif(spec->'customSchemas', 'null'::jsonb)
                             else "customSchemas" end
    where "studyId" = v_study_id;
    v_action := 'updated';
  else
    insert into "StudyConfig"
      ("studyId", "studyName", "studyVersion", "isActive", "piPublicKey", "dataTypes", "schemaDataTypes",
       "signedDefinition", "definitionSignature", "customSchemas")
    values
      (coalesce(v_study_id, gen_random_uuid()), spec->>'studyName', (spec->>'studyVersion')::real,
       coalesce((spec->>'isActive')::boolean, true), v_pem, (spec->'dataTypes')::json,
       nullif(spec->'schemaDataTypes', 'null'::jsonb),
       spec->>'signedDefinition', spec->>'definitionSignature',
       nullif(spec->'customSchemas', 'null'::jsonb))
    returning "studyId" into v_study_id;
    v_action := 'inserted';
  end if;

  -- The terms just applied, kept for ever: consent records name them by hash.
  v_definition := record_study_definition(v_study_id);

  -- Join code lives in Vault (join_code.sql), never in a table column.
  if v_join_code is not null then
    select id into v_secret_id from vault.secrets where name = 'join_code_' || v_study_id;
    if v_join_code = '' then
      if v_secret_id is not null then
        delete from vault.secrets where id = v_secret_id;
        v_notices := v_notices || text 'join code removed — enrolment is open';
      end if;
    elsif v_secret_id is not null then
      perform vault.update_secret(v_secret_id, v_join_code);
      v_notices := v_notices || text 'join code rotated';
    else
      perform vault.create_secret(v_join_code, 'join_code_' || v_study_id,
                                  'Join code for ' || (spec->>'studyName'));
      v_notices := v_notices || text 'join code set';
    end if;
  end if;

  return jsonb_build_object(
    'action',    v_action,
    'studyId',   v_study_id,
    'studyName', spec->>'studyName',
    'dataTypes', jsonb_array_length(spec->'dataTypes'),
    'definitionSha256', v_definition,
    'notices',   to_jsonb(v_notices)
  );
end;
$$;

-- Admin-only: the app must never (re)define studies.
revoke execute on function public.seed_study(jsonb, text) from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- Study lifecycle (ADMIN-ONLY; the setup wizard's "Close enrolment" / "End study")
-- -----------------------------------------------------------------------------
-- Opens or closes enrolment. Existing participants are unaffected: they keep
-- collecting under their consent. Refused once the study has ended.
create or replace function public.set_study_enrolment(p_study_id uuid, p_open boolean)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
begin
  if not exists (select 1 from "StudyConfig" where "studyId" = p_study_id) then
    raise exception 'set_study_enrolment: no study %', p_study_id;
  end if;
  if exists (select 1 from "StudyConfig" where "studyId" = p_study_id and "endedAt" is not null) then
    raise exception 'set_study_enrolment: the study has ended — enrolment cannot be reopened';
  end if;
  update "StudyConfig" set "isActive" = p_open where "studyId" = p_study_id;
  return jsonb_build_object('studyId', p_study_id, 'isActive', p_open);
end;
$$;
revoke execute on function public.set_study_enrolment(uuid, boolean) from public, anon, authenticated;

-- Ends the study, for good: enrolment closes, and from now on the backend
-- refuses enrolment, consent and records measured after the end
-- (rls_policies.sql); apps stop collecting, upload what was measured before,
-- and show the participant that the study has ended. Data already collected
-- stays until its retention period ends (retention.sql) or the participant
-- withdraws. Irreversible through the tools: seed_study() refuses to change
-- an ended study.
create or replace function public.end_study(p_study_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_ended timestamptz;
begin
  select "endedAt" into v_ended from "StudyConfig" where "studyId" = p_study_id;
  if not found then
    raise exception 'end_study: no study %', p_study_id;
  end if;
  if v_ended is not null then
    raise exception 'end_study: the study already ended at %', v_ended;
  end if;
  update "StudyConfig" set "endedAt" = now(), "isActive" = false
   where "studyId" = p_study_id
  returning "endedAt" into v_ended;
  return jsonb_build_object(
    'studyId', p_study_id,
    'endedAt', v_ended,
    'participants', (select count(*) from participants where study_id = p_study_id));
end;
$$;
revoke execute on function public.end_study(uuid) from public, anon, authenticated;

-- Studies seeded before definitions were tracked: record their current one, so
-- new consent records can name it. (Their earlier consent records stay
-- unlinked — format v1, version label only — the earlier text is gone.)
do $do$
declare r record;
begin
  for r in select "studyId" from "StudyConfig" where "definitionSha256" is null loop
    perform record_study_definition(r."studyId");
  end loop;
end
$do$;

-- Backend API version of this file (00_schema.sql, backend_version()).
select public.stamp_backend_component('seed_study', 4, 3);
