-- Atall C3: approval tokens and MCP Events subscriptions. Additive only (new tables, one new column, new functions,
-- a replaced atall_device_json that adds the approval key). Same lock-down as C0/C1: RLS on, service_role only.

alter table public.atall_devices add column if not exists approval_key_spki text;

create table if not exists public.atall_approvals (
  approval_id  text primary key,
  task_id      text not null references public.atall_tasks (task_id) on delete cascade,
  agent_id     text not null,
  conn_id      text not null,
  client_name  text not null,
  payload      jsonb not null,
  action_hash  text not null,
  requested_at timestamptz not null default now(),
  status       text not null default 'pending' check (status in ('pending', 'approved', 'denied')),
  decided_at   timestamptz,
  decided_by   text,
  token        jsonb,
  token_sig    text,
  jti          text unique
);
create index if not exists atall_approvals_agent_idx on public.atall_approvals (agent_id, task_id);

create table if not exists public.atall_event_subs (
  sub_id     text primary key,
  agent_id   text not null,
  conn_id    text not null,
  name       text not null,
  arguments  jsonb not null default '{}'::jsonb,
  url        text not null,
  secret     text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index if not exists atall_event_subs_agent_idx on public.atall_event_subs (agent_id);

create or replace function public.atall_device_json(d public.atall_devices) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'device_id', d.device_id, 'device_name', d.device_name, 'public_key_spki_b64', d.public_key_spki,
    'created_at', public.atall_iso(d.created_at),
    'last_seen', case when d.last_seen is null then null else public.atall_iso(d.last_seen) end,
    'approval_key_spki_b64', d.approval_key_spki)
    || case when d.revoked then jsonb_build_object('revoked', true, 'revoked_reason', d.revoked_reason) else '{}'::jsonb end
$$;

create or replace function public.atall_approval_json(a public.atall_approvals, ref text) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'approval_id', a.approval_id, 'task_id', a.task_id, 'ref', ref, 'agent_id', a.agent_id, 'conn_id', a.conn_id,
    'client_name', a.client_name, 'payload', a.payload, 'action_hash', a.action_hash,
    'requested_at', public.atall_iso(a.requested_at), 'status', a.status,
    'decided_at', case when a.decided_at is null then null else public.atall_iso(a.decided_at) end,
    'decided_by', a.decided_by, 'token', a.token, 'token_sig', a.token_sig, 'jti', a.jti)
$$;

-- "set" the first time, "same" for the same key again, "conflict" for a different key, "no_device".
create or replace function public.atall_set_approval_key(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare cur text; found_dev boolean;
begin
  select approval_key_spki, true into cur, found_dev from public.atall_devices
   where device_id = p->>'device_id' and not revoked for update;
  if found_dev is null then return to_jsonb('no_device'::text); end if;
  if cur is null then
    update public.atall_devices set approval_key_spki = p->>'spki' where device_id = p->>'device_id';
    return to_jsonb('set'::text);
  end if;
  return to_jsonb(case when cur = p->>'spki' then 'same' else 'conflict' end);
end $$;

create or replace function public.atall_put_approval(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_approvals (approval_id, task_id, agent_id, conn_id, client_name, payload, action_hash, requested_at, status)
  values (p->>'approval_id', p->>'task_id', p->>'agent_id', p->>'conn_id', p->>'client_name', p->'payload', p->>'action_hash',
          coalesce((p->>'requested_at')::timestamptz, now()), 'pending');
  return '{}'::jsonb;
end $$;

create or replace function public.atall_get_approval(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_approval_json(a, t.ref) from public.atall_approvals a join public.atall_tasks t on t.task_id = a.task_id
   where a.approval_id = p->>'approval_id' and (t.expires_at is null or t.expires_at > now())
$$;

-- One decision per approval, one use per jti. Moves the agent from AUTH_REQUIRED back to WORKING when nothing else waits.
create or replace function public.atall_decide_approval(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare a public.atall_approvals; r text;
begin
  select * into a from public.atall_approvals where approval_id = p->>'approval_id' for update;
  if not found then return jsonb_build_object('ok', false, 'reason', 'not_found'); end if;
  if a.status <> 'pending' then return jsonb_build_object('ok', false, 'reason', 'already_decided'); end if;
  if p->>'jti' is not null and exists (select 1 from public.atall_approvals where jti = p->>'jti') then
    return jsonb_build_object('ok', false, 'reason', 'already_decided');
  end if;
  update public.atall_approvals set status = p->>'status', decided_at = now(), decided_by = p->>'device_id',
         token = p->'token', token_sig = p->>'token_sig', jti = p->>'jti'
   where approval_id = a.approval_id returning * into a;
  if not exists (select 1 from public.atall_approvals where task_id = a.task_id and agent_id = a.agent_id and status = 'pending') then
    update public.atall_tasks set per_agent = jsonb_set(per_agent, array[a.agent_id],
             (per_agent->a.agent_id) || jsonb_build_object('state', 'WORKING', 'updated_at', public.atall_iso(now())))
     where task_id = a.task_id and per_agent->a.agent_id->>'state' = 'AUTH_REQUIRED';
  end if;
  select ref into r from public.atall_tasks where task_id = a.task_id;
  return jsonb_build_object('ok', true, 'approval', public.atall_approval_json(a, r));
end $$;

create or replace function public.atall_list_approvals(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(public.atall_approval_json(a, t.ref) order by a.requested_at, a.approval_id), '[]'::jsonb)
    from public.atall_approvals a join public.atall_tasks t on t.task_id = a.task_id
   where a.agent_id = p->>'agent_id' and (p->>'task_id' is null or a.task_id = p->>'task_id')
$$;

create or replace function public.atall_put_event_sub(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  delete from public.atall_event_subs where expires_at < now();
  insert into public.atall_event_subs (sub_id, agent_id, conn_id, name, arguments, url, secret, created_at, expires_at)
  values (p->>'sub_id', p->>'agent_id', p->>'conn_id', p->>'name', coalesce(p->'arguments', '{}'::jsonb), p->>'url', p->>'secret',
          coalesce((p->>'created_at')::timestamptz, now()), (p->>'expires_at')::timestamptz)
  on conflict (sub_id) do update set conn_id = excluded.conn_id, secret = excluded.secret, expires_at = excluded.expires_at;
  return '{}'::jsonb;
end $$;

create or replace function public.atall_list_event_subs(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'sub_id', s.sub_id, 'agent_id', s.agent_id, 'conn_id', s.conn_id, 'name', s.name, 'arguments', s.arguments,
      'url', s.url, 'secret', s.secret, 'created_at', public.atall_iso(s.created_at), 'expires_at', public.atall_iso(s.expires_at))
      order by s.created_at), '[]'::jsonb)
    from public.atall_event_subs s
   where s.expires_at > now() and s.agent_id in (select jsonb_array_elements_text(p->'agent_ids'))
$$;

create or replace function public.atall_delete_event_sub(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare n int;
begin
  delete from public.atall_event_subs where sub_id = p->>'sub_id';
  get diagnostics n = row_count;
  return to_jsonb(n = 1);
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
