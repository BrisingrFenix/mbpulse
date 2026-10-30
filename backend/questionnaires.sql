-- =============================================================================
-- questionnaires.sql — questionnaires defined on the backend, rendered by the app
-- =============================================================================
--
-- Apply after seed_study.sql (and before retention.sql, which reads this
-- table). Re-runnable.
--
-- A questionnaire is content, not consent: participants consent to a DATA
-- TYPE of the study ("Daily check-in": what, why, retention, how often),
-- and a questionnaire names the data type its answers belong to. Publishing
-- or changing a questionnaire therefore does not change the signed study
-- definition and does not ask participants to consent again. Keeping the
-- questions within what the data type describes is the research team's
-- (and their ethics committee's) responsibility; a change that needs
-- re-consent is made on the data type instead.
--
-- Integrity: the definition is a JSON text signed by the PI's study-signing
-- key (setup wizard), the same key apps pin for the study definition; apps
-- refuse unsigned/altered questionnaires and older versions (rollback). The
-- signature cannot be checked in Postgres (no RSA) — the app verifies it.
-- What is checked here: the text is well-formed, names this study and an
-- existing data type, and its version increases.
--
-- Answers are stored like any other record: schema "questionnaire:<id>",
-- encrypted on the device. Retention follows the named data type
-- (retention.sql).
-- =============================================================================

create table if not exists public.questionnaires (
  study_id          uuid        not null references public."StudyConfig" ("studyId") on delete cascade,
  questionnaire_id  text        not null,
  version           int         not null,
  data_type         text        not null,
  definition        text        not null,   -- signed JSON text (format mbpulse-questionnaire-v1)
  signature         text        not null,   -- SHA256withRSA, base64, study-signing key
  is_active         boolean     not null default true,
  published_at      timestamptz not null default now(),
  primary key (study_id, questionnaire_id)
);
alter table public.questionnaires enable row level security;
-- No policies: read through get_study_questionnaires(), written by publish_questionnaire().

-- -----------------------------------------------------------------------------
-- publish_questionnaire — ADMIN-ONLY (setup wizard, dashboard)
-- -----------------------------------------------------------------------------
create or replace function public.publish_questionnaire(p_definition text, p_signature text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_errors   text[] := '{}';
  q          jsonb;
  v_study    uuid;
  v_id       text;
  v_version  int;
  v_type     text;
  v_current  int;
  v_ids      text[] := '{}';
  it         jsonb;
  i          int := 0;
begin
  begin
    q := p_definition::jsonb;
  exception when others then
    raise exception 'publish_questionnaire: the definition is not valid JSON';
  end;
  if jsonb_typeof(q) is distinct from 'object' then
    raise exception 'publish_questionnaire: the definition must be a JSON object';
  end if;
  if coalesce(length(p_signature), 0) = 0 then
    v_errors := v_errors || text 'the definition is not signed';
  end if;

  if q->>'format' is distinct from 'mbpulse-questionnaire-v1' then
    v_errors := v_errors || text 'unknown "format" (expected mbpulse-questionnaire-v1)';
  end if;

  begin
    v_study := (q->>'studyId')::uuid;
  exception when others then
    v_errors := v_errors || text '"studyId" must be a UUID';
  end;
  if v_study is not null and not exists (select 1 from "StudyConfig" where "studyId" = v_study) then
    v_errors := v_errors || format('study %s does not exist — create it first', v_study);
  end if;

  v_id := q->>'questionnaireId';
  if v_id is null or v_id !~ '^[a-z][a-z0-9-]{0,62}$' then
    v_errors := v_errors || text '"questionnaireId" must be lowercase kebab-case (e.g. daily-check-in)';
  end if;

  if jsonb_typeof(q->'version') is distinct from 'number'
     or (q->>'version')::numeric < 1
     or (q->>'version')::numeric <> floor((q->>'version')::numeric) then
    v_errors := v_errors || text '"version" must be a whole number ≥ 1';
  else
    v_version := (q->>'version')::int;
  end if;

  v_type := q->>'dataType';
  if v_study is not null and not exists (
       select 1 from "StudyConfig" s, json_array_elements(s."dataTypes") t
        where s."studyId" = v_study and t->>'id' = v_type) then
    v_errors := v_errors || format('"dataType" "%s" is not a data type of the study — add it to the study first '
      || '(participants consent to data types, not to questionnaires)', coalesce(v_type, ''));
  end if;

  if length(trim(coalesce(q->>'title', ''))) = 0 then
    v_errors := v_errors || text '"title" is required';
  end if;
  if (q->>'issuedAt') is null then
    v_errors := v_errors || text '"issuedAt" is required';
  end if;

  if jsonb_typeof(q->'items') is distinct from 'array' or jsonb_array_length(q->'items') = 0 then
    v_errors := v_errors || text '"items" must be a non-empty array of questions';
  else
    for it in select * from jsonb_array_elements(q->'items') loop
      i := i + 1;
      if it->>'id' is null or it->>'id' !~ '^[a-z][a-z0-9_]{0,62}$'
         or it->>'id' in ('effective_time_frame', 'extra', 'questionnaire_version') then
        v_errors := v_errors || format('items[%s]: "id" must be snake_case and not reserved', i);
      elsif it->>'id' = any(v_ids) then
        v_errors := v_errors || format('items[%s]: "id" "%s" is used twice', i, it->>'id');
      else
        v_ids := v_ids || (it->>'id');
      end if;
      if coalesce(it->>'type', '') not in ('scale', 'number', 'choice', 'multichoice', 'text') then
        v_errors := v_errors || format('items[%s]: "type" must be scale, number, choice, multichoice or text', i);
      end if;
      if length(trim(coalesce(it->>'text', ''))) = 0 then
        v_errors := v_errors || format('items[%s]: "text" (the question) is required', i);
      end if;
      if it->>'type' in ('choice', 'multichoice')
         and coalesce(jsonb_array_length(case when jsonb_typeof(it->'options') = 'array' then it->'options' end), 0) < 2 then
        v_errors := v_errors || format('items[%s]: a choice needs at least two "options"', i);
      end if;
      if it->>'type' = 'scale' and (jsonb_typeof(it->'min') is distinct from 'number'
         or jsonb_typeof(it->'max') is distinct from 'number'
         or (it->>'min')::numeric >= (it->>'max')::numeric) then
        v_errors := v_errors || format('items[%s]: a scale needs numeric "min" < "max"', i);
      end if;
    end loop;
  end if;

  if v_study is not null and exists (
       select 1 from "StudyConfig" where "studyId" = v_study and "endedAt" is not null) then
    v_errors := v_errors || text 'the study has ended — questionnaires can no longer be published';
  end if;

  if v_study is not null and v_id is not null then
    select version into v_current from questionnaires
     where study_id = v_study and questionnaire_id = v_id;
    if v_current is not null and v_version is not null and v_version <= v_current then
      v_errors := v_errors || format('"version" must be higher than the published %s', v_current);
    end if;
  end if;

  if array_length(v_errors, 1) > 0 then
    raise exception E'publish_questionnaire: % problem(s):\n • %',
      array_length(v_errors, 1), array_to_string(v_errors, E'\n • ');
  end if;

  insert into questionnaires (study_id, questionnaire_id, version, data_type, definition, signature, is_active, published_at)
  values (v_study, v_id, v_version, v_type, p_definition, p_signature, true, now())
  on conflict (study_id, questionnaire_id) do update set
    version      = excluded.version,
    data_type    = excluded.data_type,
    definition   = excluded.definition,
    signature    = excluded.signature,
    is_active    = true,
    published_at = now();

  return jsonb_build_object(
    'action', case when v_current is null then 'published' else 'updated' end,
    'questionnaireId', v_id, 'version', v_version, 'dataType', v_type);
end;
$$;

-- -----------------------------------------------------------------------------
-- set_questionnaire_active — ADMIN-ONLY: retire (or restore) a questionnaire.
-- Availability, not integrity (like StudyConfig."isActive"): a retired one is
-- no longer offered; answers already given stay.
-- -----------------------------------------------------------------------------
create or replace function public.set_questionnaire_active(p_study_id uuid, p_questionnaire_id text, p_active boolean)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
begin
  update questionnaires set is_active = p_active
   where study_id = p_study_id and questionnaire_id = p_questionnaire_id;
  if not found then
    raise exception 'set_questionnaire_active: no questionnaire "%" in study %', p_questionnaire_id, p_study_id;
  end if;
  return p_active;
end;
$$;

-- -----------------------------------------------------------------------------
-- get_study_questionnaires — the app (any role: the definitions are public,
-- like the consent wording) and the PI tool. Active ones only.
-- -----------------------------------------------------------------------------
create or replace function public.get_study_questionnaires(p_study_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'questionnaireId', questionnaire_id,
           'dataType',        data_type,
           'version',         version,
           'definition',      definition,
           'signature',       signature
         ) order by questionnaire_id), '[]'::jsonb)
    from questionnaires
   where study_id = p_study_id and is_active;
$$;

revoke execute on function public.publish_questionnaire(text, text)                  from public, anon, authenticated;
revoke execute on function public.set_questionnaire_active(uuid, text, boolean)       from public, anon, authenticated;
revoke execute on function public.get_study_questionnaires(uuid)                      from public;
grant  execute on function public.publish_questionnaire(text, text)                  to service_role;
grant  execute on function public.set_questionnaire_active(uuid, text, boolean)       to service_role;
grant  execute on function public.get_study_questionnaires(uuid)                      to anon, authenticated;

-- Backend API version of this file (00_schema.sql, backend_version()).
select public.stamp_backend_component('questionnaires', 4, 3);

notify pgrst, 'reload schema';
