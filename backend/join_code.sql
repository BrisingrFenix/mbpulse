-- =============================================================================
-- Vault-backed study join code (hardening for the enrolment gate)
-- =============================================================================
--
-- Apply AFTER rls_policies.sql. This is the ONLY verify_join_code: the old
-- plaintext (uuid, text) overload is dropped below — with a different
-- signature, `create or replace` cannot replace it, and two overloads make the
-- RPC ambiguous for PostgREST.
--
-- Each study's join code is stored as an encrypted Supabase Vault secret named
-- 'join_code_<studyId>' and is NEVER returned to the client:
--   * verify_join_code() compares the client's input against the decrypted
--     secret server-side and returns only a boolean.
--   * get_active_studies() exposes a requires_join_code flag (derived from the
--     presence of a Vault secret), not the code, so the study list can't leak it.
--   * A per study + client-IP rate limit blunts online brute force of a
--     necessarily low-entropy, human-typed code.
--
-- Confidentiality of the code rests on Vault (encrypted at rest, unreadable by
-- anon — there is no hash for an attacker to grind offline). The rate limit
-- covers the only remaining path: online guessing.
--
-- Prerequisite: the `supabase_vault` extension (enabled by default on Supabase).
-- =============================================================================


-- 0) Admin: storing / rotating a study's code (run manually, NEVER from the app)
-- -----------------------------------------------------------------------------
--   Create:
--     select vault.create_secret('THE-CODE', 'join_code_' || 'STUDY_ID',
--                                'Join code for study STUDY_ID');
--   Rotate:
--     select id from vault.secrets where name = 'join_code_STUDY_ID';
--     select vault.update_secret('<id-from-above>', 'NEW-CODE');
--   Make a study open (no code required):
--     delete from vault.secrets where name = 'join_code_STUDY_ID';
--   Once migrated off the plaintext column, drop it so it can't linger:
--     alter table "StudyConfig" drop column if exists "joinCode";


-- 1) Rate-limit ledger ---------------------------------------------------------
create table if not exists public.join_code_attempts (
    id           bigint generated always as identity primary key,
    study_id     text        not null,
    client_ip    text        not null,
    attempted_at timestamptz not null default now()
);

create index if not exists idx_join_code_attempts_lookup
    on public.join_code_attempts (study_id, client_ip, attempted_at);

-- Only the SECURITY DEFINER functions below (running as the owner) touch this
-- table; with RLS on and no policies, anon has no direct access.
alter table public.join_code_attempts enable row level security;


-- 2) verify_join_code ----------------------------------------------------------
drop function if exists public.verify_join_code(uuid, text);

-- True when the study has no Vault secret (open enrolment) or the supplied code
-- matches. Returns false on a wrong code AND when rate-limited, so a brute-forcer
-- gets no signal distinguishing the two.
create or replace function public.verify_join_code(p_study_id text, p_code text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_ip     text;
    v_recent integer;
    v_secret text;
begin
    v_ip := coalesce(
        nullif(current_setting('request.headers', true)::json ->> 'x-forwarded-for', ''),
        'unknown'
    );

    -- Keep the ledger small: drop attempts outside the window.
    delete from public.join_code_attempts
    where attempted_at < now() - interval '15 minutes';

    select count(*) into v_recent
    from public.join_code_attempts
    where study_id = p_study_id
      and client_ip = v_ip
      and attempted_at > now() - interval '15 minutes';

    if v_recent >= 10 then
        return false;                          -- rate limited; no oracle
    end if;

    insert into public.join_code_attempts (study_id, client_ip)
    values (p_study_id, v_ip);

    select decrypted_secret into v_secret
    from vault.decrypted_secrets
    where name = 'join_code_' || p_study_id
    limit 1;

    return v_secret is null or v_secret = p_code;
end;
$$;


-- 2b) Join tickets -------------------------------------------------------------
-- verify_join_code() alone only gates the app's UI: enrolment itself
-- (enroll_participant, rls_policies.sql) must not trust the client. The app
-- trades the code for a ticket here, keeps it through consent and passphrase
-- set-up, and enrolment consumes it — one ticket, one enrolment, 7 days.
create table if not exists public.join_tickets (
    ticket     uuid        primary key default gen_random_uuid(),
    study_id   uuid        not null references public."StudyConfig" ("studyId") on delete cascade,
    issued_at  timestamptz not null default now()
);
alter table public.join_tickets enable row level security;   -- no policies: functions only

-- A ticket for p_study_id when the code is right (or the study has none), null
-- when it is wrong or rate-limited (verify_join_code's limit and no-oracle
-- rule). Raises when the study is not open for enrolment.
create or replace function public.issue_join_ticket(p_study_id text, p_code text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_ticket uuid;
begin
    if not exists (select 1 from public."StudyConfig"
                    where "studyId"::text = p_study_id and "isActive" and "endedAt" is null) then
        raise exception 'study_closed: this study is not open for enrolment';
    end if;
    if not public.verify_join_code(p_study_id, coalesce(p_code, '')) then
        return null;
    end if;
    delete from public.join_tickets where issued_at < now() - interval '7 days';
    insert into public.join_tickets (study_id) values (p_study_id::uuid) returning ticket into v_ticket;
    return v_ticket;
end;
$$;

-- INTERNAL (enroll_participant): true when p_study_id has no join code, or
-- p_ticket is an unexpired ticket for it — which is then used up.
create or replace function public.consume_join_ticket(p_study_id uuid, p_ticket uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_found boolean;
begin
    if not exists (select 1 from vault.decrypted_secrets where name = 'join_code_' || p_study_id) then
        return true;                           -- open enrolment
    end if;
    delete from public.join_tickets
     where ticket = p_ticket and study_id = p_study_id
       and issued_at > now() - interval '7 days'
    returning true into v_found;
    return coalesce(v_found, false);
end;
$$;


-- 3) get_active_studies --------------------------------------------------------
-- Active study list with a requires_join_code flag derived from Vault (so the
-- code never reaches the client) and the study's essential data-type IDs (so the
-- app can tell whether it was built to collect them). Replaces the direct
-- StudyConfig SELECT in SupabaseMeasurementRepository.getActiveStudies().
create or replace function public.get_active_studies()
returns table (
    study_id                text,
    study_name              text,
    study_version           double precision,
    requires_join_code      boolean,
    essential_data_type_ids text[]
)
language sql
security definer
set search_path = ''
as $$
    select
        c."studyId",
        c."studyName",
        c."studyVersion"::double precision,
        exists (
            select 1 from vault.decrypted_secrets v
            where v.name = 'join_code_' || c."studyId"
        ),
        coalesce(
            (select array_agg(e ->> 'id')
             from jsonb_array_elements(coalesce(c."dataTypes"::jsonb, '[]'::jsonb)) e
             where coalesce((e ->> 'isEssential')::boolean, false)),
            array[]::text[]
        )
    from public."StudyConfig" c
    where c."isActive" = true and c."endedAt" is null;
$$;


-- 4) Grants --------------------------------------------------------------------
-- Before AND after sign-in (rls_policies.sql closes functions by default).
grant execute on function public.verify_join_code(text, text) to anon, authenticated;
grant execute on function public.issue_join_ticket(text, text) to anon, authenticated;
grant execute on function public.get_active_studies()         to anon, authenticated;
-- Internal: only enroll_participant (SECURITY DEFINER) calls it.
revoke execute on function public.consume_join_ticket(uuid, uuid) from public, anon, authenticated;

-- Backend API version of this file (00_schema.sql, backend_version()).
select public.stamp_backend_component('join_code', 4, 3);
notify pgrst, 'reload schema';

-- With get_active_studies() in place, the client no longer needs to read the
-- StudyConfig.joinCode column at all; tighten or drop that column per step 0.
-- =============================================================================
