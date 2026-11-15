-- Atall connector C0 on Supabase: the tables that replace the Worker's KV namespace, plus one SQL
-- function per data-layer operation. The Edge Function calls these through PostgREST RPC
-- (POST /rest/v1/rpc/<fn> with body {"p": {...}}) using the service-role key.
--
-- Security: RLS is enabled on every table with NO policies, and anon/authenticated have no table
-- privileges and no EXECUTE on the functions. Only service_role (which bypasses RLS) can touch them.
--
-- Atomicity (where KV was not atomic):
--   * auth codes, auth requests: DELETE ... RETURNING (single use, no double redemption)
--   * refresh tokens: UPDATE ... SET used_at WHERE used_at IS NULL RETURNING (rotation + reuse detection)
--   * rate limits: INSERT ... ON CONFLICT DO UPDATE SET n = n + 1 RETURNING n
--   * results: task row locked FOR UPDATE, upsert result, set per_agent with jsonb || (one transaction)
--   * static-client redirect pinning: conditional UPDATE (first approval wins)
--   * presence: conditional upsert (60 s write throttle evaluated in SQL)

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

create table if not exists public.atall_clients (
  client_id                  text primary key,
  client_name                text not null,
  redirect_uris              text[] not null default '{}',
  token_endpoint_auth_method text not null check (token_endpoint_auth_method in ('none', 'client_secret_post', 'client_secret_basic')),
  client_secret_hash         text,
  kind                       text not null check (kind in ('dcr', 'static', 'cimd')),
  pin_redirect_on_first_use  boolean not null default false,
  created_at                 timestamptz not null default now()
);

-- CIMD documents fetched from https client_id URLs (KV cache with a 1 h TTL in the Worker).
create table if not exists public.atall_cimd_cache (
  client_id  text primary key,
  client     jsonb not null,
  expires_at timestamptz not null
);

-- Pending consent-page requests (10 min).
create table if not exists public.atall_auth_requests (
  req_id     text primary key,
  data       jsonb not null,
  expires_at timestamptz not null
);

create table if not exists public.atall_connections (
  conn_id        text primary key,
  agent_id       text not null,
  client_id      text not null,
  client_name    text not null,
  kind           text not null check (kind in ('oauth', 'header')),
  created_at     timestamptz not null default now(),
  revoked        boolean not null default false,
  revoked_reason text
);
create index if not exists atall_connections_created_idx on public.atall_connections (created_at);

-- Authorization codes, access tokens and refresh tokens, stored only as SHA-256 hashes.
-- Refresh tokens are kept after use (used_at set) so a replay can be detected for their lifetime.
create table if not exists public.atall_tokens (
  token_hash text primary key,
  kind       text not null check (kind in ('code', 'access', 'refresh')),
  conn_id    text not null references public.atall_connections (conn_id) on delete cascade,
  agent_id   text not null,
  client_id  text not null,
  scope      text not null,
  exp        bigint not null,           -- epoch seconds
  used_at    timestamptz,               -- refresh tokens only
  extra      jsonb not null default '{}'::jsonb -- codes: redirect_uri, code_challenge, resource
);
create index if not exists atall_tokens_conn_idx on public.atall_tokens (conn_id);
create index if not exists atall_tokens_exp_idx on public.atall_tokens (exp);

create table if not exists public.atall_rate_limits (
  key          text not null,
  window_start bigint not null,         -- epoch seconds / window
  n            integer not null default 0,
  expires_at   timestamptz not null,
  primary key (key, window_start)
);

create table if not exists public.atall_tasks (
  task_id      text primary key,
  ref          text not null unique,
  seq          bigserial,
  title        text not null,
  goal         text not null,
  constraints  jsonb not null default '[]'::jsonb,
  context      jsonb not null default '[]'::jsonb,
  done_when    text not null default '',
  do_not_share jsonb not null default '[]'::jsonb,
  "to"         text[] not null default array['@all'],
  deadline     text,
  created_at   timestamptz not null default now(),
  created_by   text not null check (created_by in ('owner_admin', 'seed')),
  per_agent    jsonb not null default '{}'::jsonb
);
create index if not exists atall_tasks_seq_idx on public.atall_tasks (seq);

create table if not exists public.atall_results (
  task_id           text not null references public.atall_tasks (task_id) on delete cascade,
  agent_id          text not null,
  result_id         text not null,
  ref               text not null,
  conn_id           text not null,
  client_name       text not null,
  status            text not null check (status in ('done', 'partial', 'declined')),
  summary           text not null,
  claims            jsonb not null default '[]'::jsonb,
  artifacts         jsonb not null default '[]'::jsonb,
  recorded_at       timestamptz not null,
  replaced_previous boolean not null default false,
  primary key (task_id, agent_id)
);

create table if not exists public.atall_pings (
  ping_id     text primary key,
  seq         bigserial,
  agent_id    text not null,
  conn_id     text not null,
  client_name text not null,
  note        text not null default '',
  at          timestamptz not null
);
create index if not exists atall_pings_seq_idx on public.atall_pings (seq);

create table if not exists public.atall_presence (
  agent_id    text primary key,
  conn_id     text not null,
  client_name text not null,
  last_seen   timestamptz not null,
  last_tool   text not null
);

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

-- Same shape as JavaScript's Date#toISOString(), so API output matches the Worker byte for byte.
create or replace function public.atall_iso(t timestamptz) returns text
language sql stable set search_path = pg_catalog, pg_temp as $$
  select to_char(t at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
$$;

create or replace function public.atall_now_s() returns bigint
language sql stable set search_path = pg_catalog, pg_temp as $$
  select floor(extract(epoch from clock_timestamp()))::bigint
$$;

create or replace function public.atall_text_array(j jsonb) returns text[]
language sql immutable set search_path = pg_catalog, pg_temp as $$
  select coalesce(array(select jsonb_array_elements_text(coalesce(j, '[]'::jsonb))), '{}'::text[])
$$;

create or replace function public.atall_client_json(c public.atall_clients) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_strip_nulls(jsonb_build_object(
    'client_id', c.client_id,
    'client_name', c.client_name,
    'redirect_uris', to_jsonb(c.redirect_uris),
    'token_endpoint_auth_method', c.token_endpoint_auth_method,
    'client_secret_hash', c.client_secret_hash,
    'kind', c.kind,
    'pin_redirect_on_first_use', c.pin_redirect_on_first_use,
    'created_at', public.atall_iso(c.created_at)))
$$;

create or replace function public.atall_conn_json(c public.atall_connections) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'conn_id', c.conn_id, 'agent_id', c.agent_id, 'client_id', c.client_id, 'client_name', c.client_name,
    'kind', c.kind, 'created_at', public.atall_iso(c.created_at))
    || case when c.revoked then jsonb_build_object('revoked', true, 'revoked_reason', c.revoked_reason) else '{}'::jsonb end
$$;

create or replace function public.atall_token_json(t public.atall_tokens) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object('conn_id', t.conn_id, 'agent_id', t.agent_id, 'client_id', t.client_id, 'scope', t.scope, 'exp', t.exp)
$$;

create or replace function public.atall_task_json(t public.atall_tasks) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'task_id', t.task_id, 'ref', t.ref, 'title', t.title, 'goal', t.goal,
    'constraints', t.constraints, 'context', t.context, 'done_when', t.done_when,
    'do_not_share', t.do_not_share, 'to', to_jsonb(t."to"), 'deadline', t.deadline,
    'created_at', public.atall_iso(t.created_at), 'created_by', t.created_by, 'per_agent', t.per_agent)
$$;

create or replace function public.atall_result_json(r public.atall_results) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select jsonb_build_object(
    'result_id', r.result_id, 'task_id', r.task_id, 'ref', r.ref, 'agent_id', r.agent_id,
    'conn_id', r.conn_id, 'client_name', r.client_name, 'status', r.status, 'summary', r.summary,
    'claims', r.claims, 'artifacts', r.artifacts, 'recorded_at', public.atall_iso(r.recorded_at),
    'replaced_previous', r.replaced_previous)
$$;

-- ---------------------------------------------------------------------------
-- Housekeeping (KV TTLs did this for free). Called on every new consent request.
-- ---------------------------------------------------------------------------

create or replace function public.atall_purge_expired(p jsonb default '{}'::jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare now_s bigint := public.atall_now_s();
begin
  delete from public.atall_auth_requests where expires_at < now();
  delete from public.atall_cimd_cache where expires_at < now();
  delete from public.atall_rate_limits where expires_at < now();
  delete from public.atall_tokens where exp < now_s;
  return '{}'::jsonb;
end $$;

-- ---------------------------------------------------------------------------
-- Clients
-- ---------------------------------------------------------------------------

create or replace function public.atall_get_client(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_client_json(c) from public.atall_clients c where c.client_id = p->>'client_id'
$$;

create or replace function public.atall_put_client(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_clients (client_id, client_name, redirect_uris, token_endpoint_auth_method, client_secret_hash, kind, pin_redirect_on_first_use, created_at)
  values (p->>'client_id', p->>'client_name', public.atall_text_array(p->'redirect_uris'), p->>'token_endpoint_auth_method',
          p->>'client_secret_hash', p->>'kind', coalesce((p->>'pin_redirect_on_first_use')::boolean, false),
          coalesce((p->>'created_at')::timestamptz, now()))
  on conflict (client_id) do update set
    client_name = excluded.client_name, redirect_uris = excluded.redirect_uris,
    token_endpoint_auth_method = excluded.token_endpoint_auth_method, client_secret_hash = excluded.client_secret_hash,
    kind = excluded.kind, pin_redirect_on_first_use = excluded.pin_redirect_on_first_use;
  return '{}'::jsonb;
end $$;

-- First approved sign-in pins a static client's redirect_uri. Only one concurrent approval can win.
create or replace function public.atall_pin_client_redirect(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare n int;
begin
  update public.atall_clients
     set redirect_uris = array[p->>'redirect_uri'], pin_redirect_on_first_use = false
   where client_id = p->>'client_id' and kind = 'static' and pin_redirect_on_first_use and cardinality(redirect_uris) = 0;
  get diagnostics n = row_count;
  return to_jsonb(n = 1);
end $$;

create or replace function public.atall_get_cimd(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select client from public.atall_cimd_cache where client_id = p->>'client_id' and expires_at > now()
$$;

create or replace function public.atall_put_cimd(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_cimd_cache (client_id, client, expires_at)
  values (p->'client'->>'client_id', p->'client', now() + make_interval(secs => (p->>'ttl_s')::int))
  on conflict (client_id) do update set client = excluded.client, expires_at = excluded.expires_at;
  return '{}'::jsonb;
end $$;

-- ---------------------------------------------------------------------------
-- Consent requests
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_auth_request(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  perform public.atall_purge_expired();
  insert into public.atall_auth_requests (req_id, data, expires_at)
  values (p->>'req_id', p->'data', to_timestamp((p->'data'->>'exp')::bigint));
  return '{}'::jsonb;
end $$;

create or replace function public.atall_peek_auth_request(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select data from public.atall_auth_requests where req_id = p->>'req_id' and expires_at > now()
$$;

create or replace function public.atall_take_auth_request(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare d jsonb; e timestamptz;
begin
  delete from public.atall_auth_requests where req_id = p->>'req_id' returning data, expires_at into d, e;
  if d is null or e < now() then return null; end if;
  return d;
end $$;

-- ---------------------------------------------------------------------------
-- Authorization codes
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_code(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare r jsonb := p->'rec';
begin
  insert into public.atall_tokens (token_hash, kind, conn_id, agent_id, client_id, scope, exp, extra)
  values (p->>'hash', 'code', r->>'conn_id', r->>'agent_id', r->>'client_id', r->>'scope', (r->>'exp')::bigint,
          jsonb_build_object('redirect_uri', r->>'redirect_uri', 'code_challenge', r->>'code_challenge', 'resource', r->>'resource'));
  return '{}'::jsonb;
end $$;

create or replace function public.atall_take_code(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare t public.atall_tokens;
begin
  delete from public.atall_tokens where token_hash = p->>'hash' and kind = 'code' returning * into t;
  if t.token_hash is null or t.exp < public.atall_now_s() then return null; end if;
  return public.atall_token_json(t) || t.extra;
end $$;

-- ---------------------------------------------------------------------------
-- Connections
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_connection(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_connections (conn_id, agent_id, client_id, client_name, kind, created_at, revoked, revoked_reason)
  values (p->>'conn_id', p->>'agent_id', p->>'client_id', p->>'client_name', p->>'kind',
          coalesce((p->>'created_at')::timestamptz, now()), coalesce((p->>'revoked')::boolean, false), p->>'revoked_reason')
  on conflict (conn_id) do update set
    agent_id = excluded.agent_id, client_id = excluded.client_id, client_name = excluded.client_name,
    kind = excluded.kind, revoked = excluded.revoked, revoked_reason = excluded.revoked_reason;
  return '{}'::jsonb;
end $$;

create or replace function public.atall_get_connection(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_conn_json(c) from public.atall_connections c where c.conn_id = p->>'conn_id'
$$;

-- Revoking also deletes the connection's live tokens and codes (the Worker left them to expire,
-- relying on the revoked flag; the flag is still checked on every call).
create or replace function public.atall_revoke_connection(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  update public.atall_connections set revoked = true, revoked_reason = p->>'reason'
   where conn_id = p->>'conn_id' and not revoked;
  delete from public.atall_tokens where conn_id = p->>'conn_id' and (kind <> 'refresh' or used_at is null);
  return '{}'::jsonb;
end $$;

create or replace function public.atall_list_connections(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(j order by created_at, conn_id), '[]'::jsonb)
  from (select public.atall_conn_json(c) as j, c.created_at, c.conn_id
          from public.atall_connections c order by c.created_at desc, c.conn_id desc limit 200) s
$$;

-- ---------------------------------------------------------------------------
-- Access and refresh tokens
-- ---------------------------------------------------------------------------

create or replace function public.atall_put_token(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare r jsonb := p->'rec';
begin
  if p->>'kind' not in ('access', 'refresh') then raise exception 'bad token kind'; end if;
  insert into public.atall_tokens (token_hash, kind, conn_id, agent_id, client_id, scope, exp)
  values (p->>'hash', p->>'kind', r->>'conn_id', r->>'agent_id', r->>'client_id', r->>'scope', (r->>'exp')::bigint);
  return '{}'::jsonb;
end $$;

create or replace function public.atall_get_access_token(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_token_json(t) from public.atall_tokens t
   where t.token_hash = p->>'hash' and t.kind = 'access' and t.exp >= public.atall_now_s()
$$;

create or replace function public.atall_delete_access_token(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  delete from public.atall_tokens where token_hash = p->>'hash' and kind = 'access';
  return '{}'::jsonb;
end $$;

-- Rotation with reuse detection, in one statement: the first presenter flips used_at; any later
-- presenter finds used_at already set and learns which connection to revoke.
create or replace function public.atall_take_refresh_token(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare t public.atall_tokens; used_conn text;
begin
  update public.atall_tokens set used_at = now()
   where token_hash = p->>'hash' and kind = 'refresh' and used_at is null
  returning * into t;
  if t.token_hash is null then
    select conn_id into used_conn from public.atall_tokens
     where token_hash = p->>'hash' and kind = 'refresh' and used_at is not null;
    if used_conn is not null then return jsonb_build_object('reused_conn', used_conn); end if;
    return '{}'::jsonb;
  end if;
  -- keep the used row until its refresh lifetime ends (for reuse detection): exp already says so
  if t.exp < public.atall_now_s() then return '{}'::jsonb; end if;
  return jsonb_build_object('rec', public.atall_token_json(t));
end $$;

create or replace function public.atall_delete_refresh_token(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare t public.atall_tokens;
begin
  delete from public.atall_tokens where token_hash = p->>'hash' and kind = 'refresh' and used_at is null returning * into t;
  if t.token_hash is null then return null; end if;
  return public.atall_token_json(t);
end $$;

-- ---------------------------------------------------------------------------
-- Rate limits (fixed windows)
-- ---------------------------------------------------------------------------

create or replace function public.atall_rl_count(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select to_jsonb(coalesce((select n from public.atall_rate_limits
     where key = p->>'key' and window_start = public.atall_now_s() / (p->>'window_s')::bigint), 0))
$$;

create or replace function public.atall_rl_hit(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare w bigint := (p->>'window_s')::bigint; cnt int;
begin
  insert into public.atall_rate_limits (key, window_start, n, expires_at)
  values (p->>'key', public.atall_now_s() / w, 1, now() + make_interval(secs => w * 2))
  on conflict (key, window_start) do update set n = public.atall_rate_limits.n + 1
  returning n into cnt;
  return to_jsonb(cnt);
end $$;

-- ---------------------------------------------------------------------------
-- Tasks
-- ---------------------------------------------------------------------------

-- Raises unique_violation (23505) if the ref is taken; the caller retries with a new ref.
create or replace function public.atall_create_task(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare t public.atall_tasks;
begin
  insert into public.atall_tasks (task_id, ref, title, goal, constraints, context, done_when, do_not_share, "to", deadline, created_at, created_by)
  values (p->>'task_id', p->>'ref', p->>'title', p->>'goal', coalesce(p->'constraints', '[]'), coalesce(p->'context', '[]'),
          coalesce(p->>'done_when', ''), coalesce(p->'do_not_share', '[]'), public.atall_text_array(p->'to'),
          p->>'deadline', coalesce((p->>'created_at')::timestamptz, now()), p->>'created_by')
  returning * into t;
  return public.atall_task_json(t);
end $$;

create or replace function public.atall_get_task(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_task_json(t) from public.atall_tasks t
   where (p ? 'task_id' and t.task_id = p->>'task_id') or (p ? 'ref' and t.ref = p->>'ref')
   limit 1
$$;

create or replace function public.atall_list_tasks(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(j order by seq), '[]'::jsonb)
  from (select public.atall_task_json(t) as j, t.seq from public.atall_tasks t order by t.seq desc limit 200) s
$$;

create or replace function public.atall_ensure_seed(p jsonb default '{}'::jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_tasks (task_id, ref, title, goal, done_when, "to", created_by)
  values ('tsk_c0check', 'c0ck', 'Atall C0 connector check',
          'Post one short result with atall_post_result: say which assistant you are, and give one claim with a source URL for something you looked up today.',
          'One result posted with status done and at least one claim.', array['@all'], 'seed')
  on conflict do nothing;
  return '{}'::jsonb;
end $$;

create or replace function public.atall_reset(p jsonb default '{}'::jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  delete from public.atall_results where true;
  delete from public.atall_tasks where true;
  delete from public.atall_pings where true;
  delete from public.atall_presence where true;
  return '{}'::jsonb;
end $$;

-- ---------------------------------------------------------------------------
-- Results
-- ---------------------------------------------------------------------------

create or replace function public.atall_get_result(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select public.atall_result_json(r) from public.atall_results r where r.task_id = p->>'task_id' and r.agent_id = p->>'agent_id'
$$;

create or replace function public.atall_list_results(p jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(public.atall_result_json(r) order by r.agent_id), '[]'::jsonb)
  from public.atall_results r where r.task_id = p->>'task_id'
$$;

-- One transaction: lock the task, upsert this agent's result, set its per-agent state.
create or replace function public.atall_post_result(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
declare r jsonb := p->'result'; prev boolean; res public.atall_results;
begin
  perform 1 from public.atall_tasks where task_id = r->>'task_id' for update;
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
     set per_agent = per_agent || jsonb_build_object(r->>'agent_id', jsonb_build_object('state', p->>'state', 'updated_at', r->>'recorded_at'))
   where task_id = r->>'task_id';
  return public.atall_result_json(res);
end $$;

-- ---------------------------------------------------------------------------
-- Pings and presence
-- ---------------------------------------------------------------------------

create or replace function public.atall_add_ping(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_pings (ping_id, agent_id, conn_id, client_name, note, at)
  values (p->>'ping_id', p->>'agent_id', p->>'conn_id', p->>'client_name', coalesce(p->>'note', ''), (p->>'at')::timestamptz);
  delete from public.atall_pings where seq <= (select seq from public.atall_pings order by seq desc offset 50 limit 1);
  return '{}'::jsonb;
end $$;

create or replace function public.atall_list_pings(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(jsonb_build_object('ping_id', ping_id, 'agent_id', agent_id, 'conn_id', conn_id,
           'client_name', client_name, 'note', note, 'at', public.atall_iso(at)) order by seq), '[]'::jsonb)
  from (select * from public.atall_pings order by seq desc limit 50) s
$$;

create or replace function public.atall_touch_presence(p jsonb) returns jsonb
language plpgsql set search_path = public, pg_temp as $$
begin
  insert into public.atall_presence as cur (agent_id, conn_id, client_name, last_seen, last_tool)
  values (p->>'agent_id', p->>'conn_id', p->>'client_name', (p->>'last_seen')::timestamptz, p->>'last_tool')
  on conflict (agent_id) do update set
    conn_id = excluded.conn_id, client_name = excluded.client_name, last_seen = excluded.last_seen, last_tool = excluded.last_tool
  where not (cur.last_tool = excluded.last_tool and cur.conn_id = excluded.conn_id
             and excluded.last_seen - cur.last_seen < interval '60 seconds');
  return '{}'::jsonb;
end $$;

create or replace function public.atall_list_presence(p jsonb default '{}'::jsonb) returns jsonb
language sql stable set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(jsonb_build_object('agent_id', agent_id, 'conn_id', conn_id, 'client_name', client_name,
           'last_seen', public.atall_iso(last_seen), 'last_tool', last_tool) order by agent_id), '[]'::jsonb)
  from public.atall_presence
$$;

-- ---------------------------------------------------------------------------
-- Lock down: RLS on, no policies, no anon/authenticated access; service_role only.
-- ---------------------------------------------------------------------------

do $$
declare r record;
begin
  for r in select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind = 'r' and c.relname like 'atall\_%' loop
    execute format('alter table public.%I enable row level security', r.relname);
    if exists (select 1 from pg_roles where rolname = 'anon') then
      execute format('revoke all on table public.%I from anon', r.relname);
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
      execute format('revoke all on table public.%I from authenticated', r.relname);
    end if;
    if exists (select 1 from pg_roles where rolname = 'service_role') then
      execute format('grant select, insert, update, delete on table public.%I to service_role', r.relname);
    end if;
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

-- Built-in C0 check task (#c0ck, addressed to @all). /admin/reset clears it; the next inbox or
-- admin read puts it back unless ATALL_SEED_TASK=false.
select public.atall_ensure_seed();
