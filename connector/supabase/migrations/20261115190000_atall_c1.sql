-- Atall C1 relay: paired phones (device keys + replay guard), phone-signed tasks, the event feed the phone
-- reads (countersigned by the function at read time), questions/answers, agent manifests, per-agent task
-- states, and TTL cleanup. Additive and idempotent on top of 20261115000000_atall_c0.sql: no C0 table is
-- dropped or emptied, existing connections and tokens are untouched.

-- ---------------------------------------------------------------------------
-- Tasks: phone provenance, C1 card fields, expiry
-- ---------------------------------------------------------------------------

alter table public.atall_tasks drop constraint if exists atall_tasks_created_by_check;
alter table public.atall_tasks add constraint atall_tasks_created_by_check check (created_by in ('owner_admin', 'seed', 'phone'));
alter table public.atall_tasks add column if not exists output        text;
alter table public.atall_tasks add column if not exists done_criteria jsonb not null default '[]'::jsonb;
alter table public.atall_tasks add column if not exists expires_at    timestamptz; -- null: never (seed task, C0 rows)
alter table public.atall_tasks add column if not exists phone_sig     jsonb;       -- what the phone signed (see PhoneSignature)
create index if not exists atall_tasks_expires_idx on public.atall_tasks (expires_at);

-- ---------------------------------------------------------------------------
-- New tables
-- ---------------------------------------------------------------------------

create table if not exists public.atall_devices (
  device_id       text primary key,
  device_name     text not null,
  public_key_spki text not null,          -- P-256 SubjectPublicKeyInfo, base64
  created_at      timestamptz not null default now(),
  last_seen       timestamptz,
  revoked         boolean not null default false,
  revoked_at      timestamptz,
  revoked_reason  text
);

-- Replay guard: one row per accepted (device, ts, normalized signature). Kept 10 min (the ts window is ±120 s).
create table if not exists public.atall_phone_nonces (
  device_id  text not null,
  ts         bigint not null,
  sig_hash   text not null,
  expires_at timestamptz not null,
  primary key (device_id, ts, sig_hash)
);
create index if not exists atall_phone_nonces_exp_idx on public.atall_phone_nonces (expires_at);

-- The phone's event feed. seq is the cursor. Acked events go 24 h after the ack; unacked ones after 7 d;
-- task events also go with their task.
create table if not exists public.atall_events (
  seq         bigserial primary key,
  type        text not null check (type in ('result', 'progress', 'question', 'claim', 'approval_request', 'presence', 'manifest')),
  task_id     text references public.atall_tasks (task_id) on delete cascade,
  agent_id    text not null,
  conn_id     text not null,
  client_name text not null,
  at          timestamptz not null default now(),
  payload     jsonb not null default '{}'::jsonb,
  acked_at    timestamptz,
  expires_at  timestamptz not null default now() + interval '7 days'
);
create index if not exists atall_events_exp_idx on public.atall_events (expires_at);
create index if not exists atall_events_task_idx on public.atall_events (task_id);

create table if not exists public.atall_questions (
  question_id text primary key,
  task_id     text not null references public.atall_tasks (task_id) on delete cascade,
  agent_id    text not null,
  conn_id     text not null,
  text        text not null,
  choices     jsonb not null default '[]'::jsonb,
  asked_at    timestamptz not null default now(),
  answer      text,
  answered_at timestamptz,
  answered_by text
);
create index if not exists atall_questions_task_agent_idx on public.atall_questions (task_id, agent_id);
create index if not exists atall_questions_agent_idx on public.atall_questions (agent_id);

create table if not exists public.atall_manifests (
  agent_id    text primary key,
  conn_id     text not null,
  client_name text not null,
  manifest    jsonb not null,
  updated_at  timestamptz not null
);

-- ---------------------------------------------------------------------------
-- JSON shapes
-- ---------------------------------------------------------------------------

create or replace function public.atall_task_json(t public.atall_tasks) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'task_id', t.task_id, 'ref', t.ref, 'title', t.title, 'goal', t.goal,
    'constraints', t.constraints, 'context', t.context, 'done_when', t.done_when,
    'do_not_share', t.do_not_share, 'to', to_jsonb(t."to"), 'deadline', t.deadline,
    'created_at', public.atall_iso(t.created_at), 'created_by', t.created_by, 'per_agent', t.per_agent)
    || case when t.output is not null then jsonb_build_object('output', t.output) else '{}'::jsonb end
    || case when t.done_criteria <> '[]'::jsonb then jsonb_build_object('done_criteria', t.done_criteria) else '{}'::jsonb end
    || case when t.expires_at is not null then jsonb_build_object('expires_at', public.atall_iso(t.expires_at)) else '{}'::jsonb end
    || case when t.phone_sig is not null then jsonb_build_object('phone_sig', t.phone_sig) else '{}'::jsonb end
$$;

create or replace function public.atall_event_json(e public.atall_events) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'cursor', e.seq, 'type', e.type, 'agent_id', e.agent_id, 'conn_id', e.conn_id, 'client_name', e.client_name,
    'at', public.atall_iso(e.at), 'payload', e.payload)
    || case when e.task_id is not null then jsonb_build_object('task_id', e.task_id) else '{}'::jsonb end
$$;

create or replace function public.atall_device_json(d public.atall_devices) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'device_id', d.device_id, 'device_name', d.device_name, 'public_key_spki_b64', d.public_key_spki,
    'created_at', public.atall_iso(d.created_at),
    'last_seen', case when d.last_seen is null then null else public.atall_iso(d.last_seen) end)
    || case when d.revoked then jsonb_build_object('revoked', true, 'revoked_reason', d.revoked_reason) else '{}'::jsonb end
$$;

create or replace function public.atall_question_json(q public.atall_questions, ref text) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'question_id', q.question_id, 'task_id', q.task_id, 'ref', ref, 'agent_id', q.agent_id, 'conn_id', q.conn_id,
    'text', q.text, 'choices', q.choices, 'asked_at', public.atall_iso(q.asked_at),
    'answer', q.answer, 'answered_at', case when q.answered_at is null then null else public.atall_iso(q.answered_at) end)
$$;

-- Internal: append one event. Not exposed to the function directly (no grant beyond service_role anyway).
create or replace function public.atall_emit(p_type text, p_task_id text, p_agent_id text, p_conn_id text, p_client_name text, p_payload jsonb)
returns bigint
language plpgsql set search_path = public, pg_temp as $$
declare s bigint;
begin
  insert into public.atall_events (type, task_id, agent_id, conn_id, client_name, at, payload)
  values (p_type, p_task_id, p_agent_id, p_conn_id, p_client_name, clock_timestamp(), coalesce(p_payload, '{}'::jsonb))
  returning seq into s;
  return s;
end $$;

-- ---------------------------------------------------------------------------
-- Tasks (replaces the C0 versions; same signatures)
-- ---------------------------------------------------------------------------

create or replace function public.atall_create_task(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare t public.atall_tasks;
begin
  -- An expired task that hasn't been purged yet must not hold its ref.
  delete from public.atall_tasks where ref = p->>'ref' and expires_at is not null and expires_at < now();
  insert into public.atall_tasks (task_id, ref, title, goal, constraints, context, done_when, do_not_share, "to", deadline,
                                  created_at, created_by, output, done_criteria, expires_at, phone_sig)
  values (p->>'task_id', p->>'ref', p->>'title', p->>'goal', coalesce(p->'constraints', '[]'), coalesce(p->'context', '[]'),
          coalesce(p->>'done_when', ''), coalesce(p->'do_not_share', '[]'), public.atall_text_array(p->'to'),
          p->>'deadline', coalesce((p->>'created_at')::timestamptz, now()), p->>'created_by',
          p->>'output', coalesce(nullif(p->'done_criteria', 'null'::jsonb), '[]'), (p->>'expires_at')::timestamptz,
          nullif(p->'phone_sig', 'null'::jsonb))
  returning * into t;
  return public.atall_task_json(t);
end $$;

create or replace function public.atall_get_task(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_task_json(t) from public.atall_tasks t
   where ((p ? 'task_id' and t.task_id = p->>'task_id') or (p ? 'ref' and t.ref = p->>'ref'))
     and (t.expires_at is null or t.expires_at > now())
   limit 1
$$;

create or replace function public.atall_list_tasks(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(j order by seq), '[]'::jsonb)
  from (select public.atall_task_json(t) as j, t.seq from public.atall_tasks t
         where t.expires_at is null or t.expires_at > now()
         order by t.seq desc limit 200) s
$$;

-- One transaction: lock the task, upsert this agent's result, set its per-agent state, emit a result event.
create or replace function public.atall_post_result(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare r jsonb := p->'result'; prev boolean; res public.atall_results; t public.atall_tasks;
begin
  select * into t from public.atall_tasks where task_id = r->>'task_id' for update;
  if not found then raise exception 'unknown task %', r->>'task_id' using errcode = 'P0002'; end if;
  select exists(select 1 from public.atall_results where task_id = r->>'task_id' and agent_id = r->>'agent_id') into prev;
  insert into public.atall_results (task_id, agent_id, result_id, ref, conn_id, client_name, status, summary, claims, artifacts, recorded_at, replaced_previous)
  values (r->>'task_id', r->>'agent_id', r->>'result_id', r->>'ref', r->>'conn_id', r->>'client_name', r->>'status', r->>'summary',
          coalesce(r->'claims', '[]'), coalesce(r->'artifacts', '[]'), (r->>'recorded_at')::timestamptz, prev)
  on conflict (task_id, agent_id) do update set
    result_id = excluded.result_id, ref = excluded.ref, conn_id = excluded.conn_id, client_name = excluded.client_name,
    status = excluded.status, summary = excluded.summary, claims = excluded.claims, artifacts = excluded.artifacts,
    recorded_at = excluded.recorded_at, replaced_previous = excluded.replaced_previous
  returning * into res;
  update public.atall_tasks
     set per_agent = per_agent || jsonb_build_object(r->>'agent_id',
           coalesce(per_agent->(r->>'agent_id'), '{}'::jsonb) || jsonb_build_object('state', p->>'state', 'updated_at', r->>'recorded_at'))
   where task_id = r->>'task_id';
  perform public.atall_emit('result', res.task_id, res.agent_id, res.conn_id, res.client_name, jsonb_build_object(
    'result_id', res.result_id, 'ref', res.ref, 'status', res.status, 'state', p->>'state', 'summary', res.summary,
    'claims', res.claims, 'artifacts', res.artifacts, 'replaced_previous', res.replaced_previous,
    'recorded_at', public.atall_iso(res.recorded_at)));
  return public.atall_result_json(res);
end $$;

-- ---------------------------------------------------------------------------
-- Agent steps: claim / progress / ask / approval (one locked transaction each)
-- ---------------------------------------------------------------------------

create or replace function public.atall_agent_step(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare
  t public.atall_tasks; cur jsonb; entry jsonb; cur_state text; now_ts timestamptz := clock_timestamp();
  s bigint; wait_s int; agent text := p->>'agent_id';
begin
  select * into t from public.atall_tasks
   where task_id = p->>'task_id' and (expires_at is null or expires_at > now()) for update;
  if not found then return jsonb_build_object('ok', false, 'reason', 'unknown_task'); end if;
  cur := coalesce(t.per_agent->agent, '{}'::jsonb);
  cur_state := coalesce(cur->>'state', 'SUBMITTED');
  if coalesce((p->>'refuse_if_terminal')::boolean, false) and cur_state in ('DONE', 'PARTIAL', 'COMPLETED', 'REJECTED') then
    return jsonb_build_object('ok', false, 'reason', 'terminal', 'state', cur_state);
  end if;
  if p ? 'throttle_s' and cur ? 'progress_at'
     and now_ts - (cur->>'progress_at')::timestamptz < make_interval(secs => (p->>'throttle_s')::int) then
    wait_s := ceil((p->>'throttle_s')::int - extract(epoch from (now_ts - (cur->>'progress_at')::timestamptz)))::int;
    return jsonb_build_object('ok', false, 'reason', 'throttled', 'state', cur_state, 'retry_after_s', greatest(wait_s, 1));
  end if;
  entry := cur;
  if p->>'state' is not null then
    entry := entry || jsonb_build_object('state', p->>'state', 'updated_at', public.atall_iso(now_ts));
  end if;
  if p ? 'throttle_s' then entry := entry || jsonb_build_object('progress_at', public.atall_iso(now_ts)); end if;
  if entry is distinct from cur then
    update public.atall_tasks set per_agent = per_agent || jsonb_build_object(agent, entry) where task_id = t.task_id;
  end if;
  if p ? 'question' then
    insert into public.atall_questions (question_id, task_id, agent_id, conn_id, text, choices, asked_at)
    values (p->'question'->>'question_id', t.task_id, agent, p->>'conn_id', p->'question'->>'text',
            coalesce(p->'question'->'choices', '[]'::jsonb), now_ts);
  end if;
  if p ? 'event' then
    s := public.atall_emit(p->'event'->>'type', t.task_id, agent, p->>'conn_id', p->>'client_name', p->'event'->'payload');
  end if;
  return jsonb_build_object('ok', true, 'ref', t.ref, 'state', coalesce(entry->>'state', 'SUBMITTED'), 'prev_state', cur_state)
      || case when s is not null then jsonb_build_object('cursor', s) else '{}'::jsonb end;
end $$;

-- Kush's answer from the phone. Flips the agent back to WORKING once none of its questions on the task is open.
create or replace function public.atall_answer_question(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare q public.atall_questions; r text;
begin
  update public.atall_questions set answer = p->>'text', answered_at = clock_timestamp(), answered_by = p->>'device_id'
   where question_id = p->>'question_id' and task_id = p->>'task_id' and answer is null
  returning * into q;
  if q.question_id is null then
    if exists (select 1 from public.atall_questions where question_id = p->>'question_id' and task_id = p->>'task_id') then
      return jsonb_build_object('ok', false, 'reason', 'already_answered');
    end if;
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;
  update public.atall_tasks
     set per_agent = per_agent || jsonb_build_object(q.agent_id,
           per_agent->q.agent_id || jsonb_build_object('state', 'WORKING', 'updated_at', public.atall_iso(clock_timestamp())))
   where task_id = q.task_id and per_agent->q.agent_id->>'state' = 'INPUT_REQUIRED'
     and not exists (select 1 from public.atall_questions where task_id = q.task_id and agent_id = q.agent_id and answer is null);
  select ref into r from public.atall_tasks where task_id = q.task_id;
  return jsonb_build_object('ok', true, 'agent_id', q.agent_id, 'ref', r);
end $$;

create or replace function public.atall_list_questions(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(public.atall_question_json(q, t.ref) order by q.asked_at, q.question_id), '[]'::jsonb)
  from public.atall_questions q join public.atall_tasks t on t.task_id = q.task_id
  where q.agent_id = p->>'agent_id' and (not (p ? 'task_id') or q.task_id = p->>'task_id')
    and (t.expires_at is null or t.expires_at > now())
$$;

-- ---------------------------------------------------------------------------
-- Manifests
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_manifest(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare s bigint;
begin
  insert into public.atall_manifests (agent_id, conn_id, client_name, manifest, updated_at)
  values (p->>'agent_id', p->>'conn_id', p->>'client_name', p->'manifest', (p->>'updated_at')::timestamptz)
  on conflict (agent_id) do update set conn_id = excluded.conn_id, client_name = excluded.client_name,
    manifest = excluded.manifest, updated_at = excluded.updated_at;
  s := public.atall_emit('manifest', null, p->>'agent_id', p->>'conn_id', p->>'client_name', p->'manifest');
  return to_jsonb(s);
end $$;

create or replace function public.atall_get_manifest(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object('agent_id', agent_id, 'conn_id', conn_id, 'client_name', client_name, 'manifest', manifest,
                            'updated_at', public.atall_iso(updated_at))
  from public.atall_manifests where agent_id = p->>'agent_id'
$$;

-- ---------------------------------------------------------------------------
-- Presence (replaces C0's): also emits a presence event when an agent is new or comes back after 10+ min.
-- ---------------------------------------------------------------------------

create or replace function public.atall_touch_presence(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare prev timestamptz;
begin
  select last_seen into prev from public.atall_presence where agent_id = p->>'agent_id';
  insert into public.atall_presence as cur (agent_id, conn_id, client_name, last_seen, last_tool)
  values (p->>'agent_id', p->>'conn_id', p->>'client_name', (p->>'last_seen')::timestamptz, p->>'last_tool')
  on conflict (agent_id) do update set
    conn_id = excluded.conn_id, client_name = excluded.client_name, last_seen = excluded.last_seen, last_tool = excluded.last_tool
  where not (cur.last_tool = excluded.last_tool and cur.conn_id = excluded.conn_id
             and excluded.last_seen - cur.last_seen < interval '60 seconds');
  if prev is null or (p->>'last_seen')::timestamptz - prev >= interval '10 minutes' then
    perform public.atall_emit('presence', null, p->>'agent_id', p->>'conn_id', p->>'client_name',
      jsonb_build_object('status', 'active', 'last_tool', p->>'last_tool',
                         'idle_s', case when prev is null then null else floor(extract(epoch from ((p->>'last_seen')::timestamptz - prev)))::bigint end));
  end if;
  return '{}'::jsonb;
end $$;

-- ---------------------------------------------------------------------------
-- Devices and the replay guard
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_device(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_devices (device_id, device_name, public_key_spki, created_at)
  values (p->>'device_id', p->>'device_name', p->>'public_key_spki_b64', coalesce((p->>'created_at')::timestamptz, now()));
  return '{}'::jsonb;
end $$;

create or replace function public.atall_get_device(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_device_json(d) from public.atall_devices d where d.device_id = p->>'device_id'
$$;

create or replace function public.atall_list_devices(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(public.atall_device_json(d) order by d.created_at, d.device_id), '[]'::jsonb) from public.atall_devices d
$$;

create or replace function public.atall_revoke_device(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare n int;
begin
  update public.atall_devices set revoked = true, revoked_at = coalesce(revoked_at, now()), revoked_reason = coalesce(revoked_reason, p->>'reason')
   where device_id = p->>'device_id';
  get diagnostics n = row_count;
  return to_jsonb(n = 1);
end $$;

-- True if this (device, ts, signature) is new. Also stamps the device's last_seen and drops old nonces.
create or replace function public.atall_phone_nonce(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare n int;
begin
  delete from public.atall_phone_nonces where expires_at < now();
  insert into public.atall_phone_nonces (device_id, ts, sig_hash, expires_at)
  values (p->>'device_id', (p->>'ts')::bigint, p->>'sig_hash', now() + interval '10 minutes')
  on conflict do nothing;
  get diagnostics n = row_count;
  if n = 1 then
    update public.atall_devices set last_seen = now()
     where device_id = p->>'device_id' and (last_seen is null or last_seen < now() - interval '60 seconds');
  end if;
  return to_jsonb(n = 1);
end $$;

-- ---------------------------------------------------------------------------
-- Event feed, acks and TTL cleanup
-- ---------------------------------------------------------------------------

create or replace function public.atall_purge_c1(p jsonb default '{}'::jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare nt int; ne int;
begin
  delete from public.atall_tasks where expires_at is not null and expires_at < now();   -- cascades results, events, questions
  get diagnostics nt = row_count;
  delete from public.atall_events where expires_at < now();
  get diagnostics ne = row_count;
  delete from public.atall_phone_nonces where expires_at < now();
  return jsonb_build_object('tasks', nt, 'events', ne);
end $$;

create or replace function public.atall_list_events(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare evs jsonb;
begin
  perform public.atall_purge_c1();
  select coalesce(jsonb_agg(public.atall_event_json(e) order by e.seq), '[]'::jsonb) into evs
    from public.atall_events e
   where e.seq in (select x.seq from public.atall_events x
                    where x.seq > coalesce((p->>'after')::bigint, 0) and x.expires_at > now()
                    order by x.seq limit least(greatest(coalesce((p->>'limit')::int, 100), 1), 500));
  return evs;
end $$;

create or replace function public.atall_ack_events(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare n int;
begin
  update public.atall_events set acked_at = now(), expires_at = least(expires_at, now() + interval '24 hours')
   where seq <= (p->>'cursor')::bigint and acked_at is null;
  get diagnostics n = row_count;
  return to_jsonb(n);
end $$;

-- /admin/reset (tests and the dev console) also clears the C1 feed and questions; devices stay paired.
create or replace function public.atall_reset(p jsonb default '{}'::jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  delete from public.atall_events where true;
  delete from public.atall_questions where true;
  delete from public.atall_results where true;
  delete from public.atall_tasks where true;
  delete from public.atall_pings where true;
  delete from public.atall_presence where true;
  return '{}'::jsonb;
end $$;

-- ---------------------------------------------------------------------------
-- Lock down the new tables, sequences and functions the same way as C0: RLS on, no policies, service_role only.
-- ---------------------------------------------------------------------------

do $$
declare r record;
begin
  for r in select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind = 'r' and c.relname like 'atall\_%' loop
    execute format('alter table public.%I enable row level security', r.relname);
    if exists (select 1 from pg_roles where rolname = 'anon') then execute format('revoke all on table public.%I from anon', r.relname); end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then execute format('revoke all on table public.%I from authenticated', r.relname); end if;
    if exists (select 1 from pg_roles where rolname = 'service_role') then execute format('grant select, insert, update, delete on table public.%I to service_role', r.relname); end if;
  end loop;

  for r in select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind = 'S' and c.relname like 'atall\_%' loop
    if exists (select 1 from pg_roles where rolname = 'anon') then execute format('revoke all on sequence public.%I from anon', r.relname); end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then execute format('revoke all on sequence public.%I from authenticated', r.relname); end if;
    if exists (select 1 from pg_roles where rolname = 'service_role') then execute format('grant usage, select on sequence public.%I to service_role', r.relname); end if;
  end loop;

  for r in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
            where n.nspname = 'public' and p.proname like 'atall\_%' loop
    execute format('revoke all on function %s from public', r.sig);
    if exists (select 1 from pg_roles where rolname = 'anon') then execute format('revoke all on function %s from anon', r.sig); end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then execute format('revoke all on function %s from authenticated', r.sig); end if;
    if exists (select 1 from pg_roles where rolname = 'service_role') then execute format('grant execute on function %s to service_role', r.sig); end if;
  end loop;
end $$;
