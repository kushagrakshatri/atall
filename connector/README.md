# Atall relay on Supabase (Edge Function + Postgres): C0 connector + C1 phone relay

This is a port of `../connector` (Cloudflare Worker + KV) to a **Supabase Edge Function** backed by **Postgres**. The behaviour is the same: dual-era MCP at `/mcp`, the OAuth 2.1 server (DCR, CIMD, static clients, PKCE S256, RFC 9207 `iss`, RFC 8414/9728 metadata, refresh rotation with reuse detection, RFC 7009 revoke), the four C0 tools with honest `readOnlyHint`, the consent page with the owner code, the admin API, and `/dev`. See `../connector/README.md` for the product side: what C0 is for, the one-minute write test, and the per-agent setup steps. Everything there applies here once you swap in the URL below.

- In the public repo (`kushagrakshatri/atall`) this directory is `connector/`. The Cloudflare Worker it was ported from (`../connector` below) is not in the repo.
- **Hosted project:** `ggmvzyyuksewkgbokrhe` (us-west-1)
- **Connector URL (once deployed):** `https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall/mcp`
- **Status:** deployed. C0 (MCP + OAuth) since function v3/v4; **C1 (phone relay) is function v6** (Nov 15 2026), migration `20261115190000_atall_c1` applied. The Worker in `../connector` is untouched.
- **C1 contract:** `C1-CONTRACT.md` (copy of the workspace's `new-product/c1-contract.md`). See "C1 phone relay" below for what was built and where it goes beyond the contract.

## C1 phone relay

Routes (all under the function base; from the Hark sandbox use `https://ggmvzyyuksewkgbokrhe.functions.supabase.co/atall/...`, because the gateway injects a Hark bearer on `*.supabase.co`):

| Route | Auth | What |
|---|---|---|
| `POST /phone/pair` `{owner_code, device_name, public_key_spki_b64}` | owner code (shares the consent page's 5/IP + 20/all per 10 min wrong-code limits) | 201 `{device_id, relay_public_key_spki_b64, relay_key_alg:"ES256"}`; 503 if the relay key isn't set |
| `POST /phone/tasks` | device signature | 201 `{task_id, ref}`; 400 bad card, 409 live ref taken, 401 unsigned/forged |
| `GET /phone/events?after=&limit=` | device signature | `{events:[…, relay_sig], next_cursor}` (limit default 100, max 500) |
| `POST /phone/ack` `{cursor}` | device signature | `{ok, cursor, acked, deleted_after_s:86400}` |
| `POST /phone/answers` `{task_id, question_id, text}` | device signature | 200; 404 unknown question; 409 already answered |
| `GET /phone/agents` | device signature | live (non-revoked) connections + presence |
| `POST /phone/connections/{conn_id}/revoke` | device signature | tokens deleted at once; next MCP call is 401 |
| `POST /phone/devices/{device_id}/revoke` | device signature | **not in the contract**: un-pair a device (itself or another) |
| `GET /admin/devices`, `POST /admin/devices/{id}/revoke` | admin token | same, for the owner console; `/admin/state` also lists `devices` |

- **Signed requests.** Headers `X-Atall-Device`, `X-Atall-Ts`, `X-Atall-Sig`; signed string `METHOD\nPATH\nTS\nbase64url(sha256(body))`, PATH = the route after `/atall` plus the raw query string (`/phone/events?after=12`). Signature DER or raw r‖s, base64url or base64. Refused (401 `{error}`): missing headers, |now−ts| > 120 s, unknown/revoked device, bad signature, replay.
- **Replay guard.** One row per accepted `(device, ts, signature)` in `atall_phone_nonces` (kept 10 min). The signature is first normalized to raw low-s form, so a DER↔raw re-encoding or the (r, n−s) malleation of a seen signature is also a replay. If the signer is deterministic (RFC 6979, e.g. Deno WebCrypto), the same request twice in one second is byte-identical and the second is refused; Android Keystore ECDSA is randomized, but a client should not retry inside the same second with the same ts anyway.
- **Phone-signed tasks.** The card is stored with `phone_sig = {device_id, device_name, device_public_key_spki_b64, alg, sig, signed_string, body}` (the exact body bytes as UTF-8). `atall_get_task` shows `verified: true`, a `provenance` line, and `phone_signature`, so an agent or auditor can check `sha256(body)` against the last line of `signed_string` and the signature with the device key. `to: ["all"]` is stored as `@all`. `context` / `constraints` / `done_criteria` / `dont_share` take a string or a list (stored as lists; `done_criteria` is also joined into the C0 `done_when`). `/admin/tasks` still works (tests, dev console) and shows `verified: false`.
- **Events and countersignatures.** Event rows are written in the same SQL transaction as the state change (`atall_post_result`, `atall_agent_step`, `atall_put_manifest`, `atall_touch_presence`). The function signs at read time: `relay_sig` = base64url **raw r‖s** ES256 over the canonical JSON (keys sorted at every level by UTF-16 code unit, no whitespace, JSON.stringify values) of the event without `relay_sig`. `cursor` is a JSON number; `task_id` is omitted (not null) on presence/manifest events.
  - `result` payload: `{result_id, ref, status, state, summary, claims, artifacts, replaced_previous, recorded_at}`
  - `progress`: `{ref, state:"WORKING", message, percent?}`; `atall_claim` emits one with `claimed: true`
  - `question`: `{ref, state:"INPUT_REQUIRED", question_id, text, choices}`
  - `approval_request`: `{ref, state:"AUTH_REQUIRED", approval_id, action, reason?, amount?, currency?, url?}`
  - `manifest`: the agent's manifest object; `presence`: `{status:"active", last_tool, idle_s}` when an agent is first seen or returns after 10+ min idle
  - `claim` is accepted by the schema but **not emitted** in C1: claims ride inside the `result` payload.
- **TTLs.** Acked events are deleted 24 h after the ack; unacked events after 7 d; tasks at `deadline + 24 h`, or 7 d after creation without a deadline (the `#c0ck` seed and C0 rows never expire); a task's results, events and questions go with it. Cleanup (`atall_purge_c1`) runs on every `GET /phone/events`; an expired task stops showing immediately and frees its ref.
- **Per-agent task states.** `SUBMITTED → WORKING` (claim/progress) `→ INPUT_REQUIRED` (ask; back to WORKING when the phone answers the last open question) / `AUTH_REQUIRED` (request_approval) `→ DONE / PARTIAL` (post_result; `declined` → `REJECTED`). Old rows keep C0's `COMPLETED`, still treated as answered. Overall state: named tasks are DONE/PARTIAL once every named agent answered; otherwise INPUT_REQUIRED / AUTH_REQUIRED if any agent is, else WORKING.
- **MCP tools (9 listed):** `atall_inbox` (now also returns `answers`), `atall_get_task` (C1 fields, `questions`), `atall_claim`, `atall_progress` (≤1 per 30 s per task per agent, enforced in SQL), `atall_post_result`, `atall_ask`, `atall_request_approval` (records AUTH_REQUIRED + event; `granted: false`), `atall_manifest` (no args = read back), plus `atall_ping_write`. Server version `0.2.0-c1`. Posting a result without claiming still works (C0 behaviour).
- **Relay key.** Function secret `ATALL_RELAY_SIGNING_KEY` (P-256 PKCS8, base64), also kept in `.atall-secrets.local` (mode 600, git-ignored). Rotating it breaks every pairing.
- **Live smoke:** `deno run -A tools/phone-sim.ts pair | task [ref] | events [--wait] | agents | negative | revoke` (state in `tools/.phone-sim.state.json`, mode 600, git-ignored; owner code read from `.atall-secrets.local`, never printed).

---

## Layout

```
connector-supabase/
  supabase/config.toml                      [functions.atall] verify_jwt = false (the function does its own OAuth)
  supabase/migrations/20261115000000_atall_c0.sql
                                            tables, SQL functions (one per data operation), RLS + grants, #c0ck seed
  supabase/migrations/20261115190000_atall_c1.sql
                                            C1: devices, phone nonces, events, questions, manifests; task columns;
                                            agent_step / answer / events / ack / purge functions; same lockdown
  supabase/functions/atall/index.ts         Deno.serve entry point
  supabase/functions/atall/deno.json        (empty import map; no third-party imports in the function)
  supabase/functions/atall/src/
    app.ts        router (was index.ts): base-path aware, well-known docs under the function path
    server.ts     env from Supabase's injected vars, PgStore wiring, EdgeRuntime.waitUntil
    env.ts        Env (store + secrets + public URL), Ctx, issuer()
    store.ts      Store interface + record types (the KV class became this interface)
    store_pg.ts   PgStore: each method is one PostgREST RPC to an atall_* SQL function
    mcp.ts oauth.ts tools.ts admin.ts pages.ts util.ts   ported from the Worker (tools.ts: C1 tools added)
    phone.ts      C1 phone API: pairing, signed-request check + replay guard, tasks, events, ack, answers, agents, revoke
    crypto.ts     ES256 verify (DER or raw), low-s normalization, canonical JSON, relay key load + countersign
  supabase/.env.example                     for local `supabase functions serve`
  tests/
    oauth.test.ts mcp.test.ts tools.test.ts the Worker's 42 tests, ported to `deno test`
    supabase.test.ts                        12 tests: base path, external consent page, env, PostgREST transport
    sql.test.ts                             12 SQL-layer tests (RLS/grants, concurrency); need ATALL_TEST_PG
    phone.test.ts                           24 C1 tests (pairing, forged/unsigned/replayed refused, revoke → 401, countersigs, TTLs)
    memory_store.ts                         in-memory Store with the same semantics as the SQL
    pg.ts helpers.ts                        harness (Postgres executor; gateway path emulation)
  scripts/
    deploy.sh            migrations (Management API) + secrets (Management API) + function (CLI --use-api)
    build-static.ts      dist/consent.html + dist/dev.html for a static host (see "HTML on supabase.co")
    local-stack.ts       Docker-free mini stack: PostgREST proxy + the function on one port, for smoke runs
    smoke.mjs dev.mjs lib/client.mjs lib/client.d.mts   ported; base URL now carries a path
    lib/phone.ts         a minimal signing phone (tests + tools/phone-sim.ts)
  tools/phone-sim.ts     live C1 smoke: pair, signed task, events + countersig check, negative checks, un-pair
  deno.json deno.lock .gitignore
```

## Data model (KV → Postgres)

| KV keys (Worker) | Postgres | Atomic now |
|---|---|---|
| `client:*` | `atall_clients` | Static-client redirect pinning is a conditional `UPDATE`, so the first approval wins. |
| `cimd:*` | `atall_cimd_cache` (1 h) | |
| `authreq:*` | `atall_auth_requests` (10 min) | `DELETE … RETURNING`, single use |
| `code:*`, `at:*`, `rt:*`, `rtused:*` | `atall_tokens` (kind = code/access/refresh, SHA-256 hash as the key) | Codes use `DELETE … RETURNING`. Refresh is one `UPDATE … SET used_at WHERE used_at IS NULL`: the first presenter rotates, every later one sees the reuse. |
| `conn:*`, `conns:index` | `atall_connections` | |
| `rl:*` | `atall_rate_limits` | `INSERT … ON CONFLICT DO UPDATE SET n = n + 1 RETURNING n` |
| `task:*`, `taskref:*`, `tasks:index`, `seeded` | `atall_tasks` (unique `ref`, `seq` for order) | A ref collision retries with a new ref (KV silently overwrote it). |
| `result:*` | `atall_results` (PK task_id, agent_id) | `atall_post_result` locks the task row, upserts the result, and merges `per_agent` in one transaction. KV did this as two racy writes. |
| `pings` | `atall_pings` (last 50 kept) | Append is an insert, so no read-modify-write. |
| `presence:*` | `atall_presence` | The 60 s write throttle is a conditional upsert. |

- **Security:**
  - RLS is enabled on all 10 tables with **no policies**.
  - `anon` and `authenticated` have no table privileges and no `EXECUTE` on any `atall_*` function.
  - Only `service_role` can use them. The function calls `POST {SUPABASE_URL}/rest/v1/rpc/atall_<op>` with `SUPABASE_SERVICE_ROLE_KEY`. Both are injected by Supabase. A newer `sb_secret_…` key also works: it is sent as `apikey` only.
- **Expiry:** KV TTLs used to handle this. Now `atall_purge_expired()` runs on every new consent request and deletes expired requests, codes, tokens, rate-limit windows and CIMD cache rows. Every read also checks `exp`.

## Base path and well-known documents

Supabase serves the function at `https://<ref>.supabase.co/functions/v1/atall/…`. The gateway hands it `/atall/…`. So:

- The **issuer** is `https://<ref>.supabase.co/functions/v1/atall`. The resource is `…/atall/mcp`, and every endpoint (`/authorize`, `/token`, `/register`, `/revoke`) hangs off it.
  - `server.ts` builds this from `SUPABASE_URL`.
  - Set `ATALL_PUBLIC_URL` to override it: for a custom domain, a root proxy, or local dev.
- **Served under the function path** (all the same documents the Worker served):
  - `…/atall/.well-known/oauth-protected-resource[/mcp]`
  - `…/atall/.well-known/oauth-authorization-server[/mcp]`
  - `…/atall/.well-known/openid-configuration[/mcp]`
  - `…/atall/mcp/.well-known/openid-configuration`
- **Root `/.well-known/…` can't be served on Supabase.** The project root belongs to Supabase's gateway, not to the function. This holds even with a custom domain, because functions always live under `/functions/v1/`.
- **What each kind of client does:**
  - **Spec-following MCP clients (2025-06-18 and later, including 2026-07-28) work.**
    - They read the `resource_metadata` URL from the 401's `WWW-Authenticate` header. It points under the function path.
    - For a path-bearing issuer they try, in order:
      1. RFC 8414 path-insertion (`/.well-known/oauth-authorization-server/functions/v1/atall`): root, 404
      2. OIDC path-insertion: root, 404
      3. OIDC path-append (`…/atall/.well-known/openid-configuration`): **answers**
    - `scripts/lib/client.mjs` now follows exactly this order. The tests assert `[404, 404, 200]`.
  - **Clients that need the root fail:**
    - clients that only implement the 2025-03-26 auth spec (they drop the path and use `https://<ref>.supabase.co/.well-known/oauth-authorization-server` and `/authorize` at the root)
    - clients that only do RFC 9728 path-insertion for the PRM without reading `WWW-Authenticate`
  - Claude.ai has had trouble with path-based issuers in the past. **Unverified for Nov 2026**: check it during the C0 test.
- **If any agent needs the root:**
  1. Put a tiny reverse proxy on a domain you control. For example, a Cloudflare Worker route on `relay.yourdomain.com/*` → `https://<ref>.supabase.co/functions/v1/atall/*`.
  2. Set the secret `ATALL_PUBLIC_URL=https://relay.yourdomain.com`.
  3. The function then advertises a root issuer and accepts the rewritten paths (`routePath` strips `/functions/v1/atall` or `/atall` either way; a test covers it).
  4. Decide this before connecting agents: tokens are bound to the issuer.

## HTML on supabase.co (consent page and /dev)

Supabase rewrites `text/html` responses from Edge Functions to `text/plain` on `*.supabase.co`. It also adds `CSP: sandbox`. Real HTML needs the paid Custom Domain add-on ([docs](https://supabase.com/docs/guides/functions/limits): "Serving of HTML content is only supported with custom domains"). The Worker's consent page and `/dev` are HTML, so the port supports two modes:

- **Inline (default; local dev, or with a custom domain):** the same server-rendered consent page and `/dev` as the Worker. The form posts to `${issuer}/authorize`.
- **External consent page (`ATALL_CONSENT_URL` set; use this on plain `*.supabase.co`):**
  1. `GET /authorize` stores the request and 302s to `ATALL_CONSENT_URL?req=…`. Errors go there as `?error=…`, never as an HTML page.
  2. The static page reads `GET …/atall/authorize/request?req=…`. This JSON has the client name, redirect host and suggested agent, and no secrets.
  3. It posts the form to `…/atall/authorize` with `Accept: application/json`.
  4. The function answers `{redirect_to}`, or `{error, req}` with a re-armed request id on a wrong owner code.
  5. CORS on `/authorize*` and `/admin/*` is granted only to that page's origin.
  6. Owner-code rate limits, single use and `iss` are unchanged.
- **Build the pages:** `ATALL_URL=https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall deno task build-static` writes `dist/consent.html`, `dist/dev.html` and `dist/_headers`.
  - The API base is **baked in at build time**, never read from the URL, so a crafted link can't send the owner code elsewhere.
  - `connect-src` is locked to the Supabase origin.
  - Host them on anything that serves real HTML (Cloudflare Pages on your existing account, GitHub Pages, …).
  - Then set `ATALL_CONSENT_URL=https://<host>/consent.html` and redeploy secrets.
- **Without either fix**, agents' sign-in pages show HTML source and Kush can't approve. `smoke.mjs` prints a `WARN` when it sees `/dev` come back as non-HTML.
- `scripts/dev.mjs` (CLI) needs no HTML and works in any mode.

## Run the tests

```bash
cd new-product/connector-supabase
deno task test        # 42 ported + 12 Supabase + 24 C1 phone tests on the in-memory store; sql.test.ts is skipped
deno task test:pglite # the same 78 on PgStore over the real C0+C1 migrations in an in-process PGlite (Postgres in WASM)
# optional, with a local Postgres (migration applied automatically, tables truncated per test):
ATALL_TEST_PG=postgres://postgres@127.0.0.1:5432/atall_test deno task test   # same 54 on PgStore + 12 SQL tests
deno task check && deno task lint
```
Install Deno with `curl -fsSL https://deno.land/install.sh | sh` (or the release zip; the sandbox used `deno-aarch64-unknown-linux-gnu`).

## Run locally

- **With Docker + Supabase CLI:**
  1. `supabase start`
  2. `cp supabase/.env.example supabase/.env.local`
  3. `supabase functions serve atall --no-verify-jwt --env-file supabase/.env.local`
  4. Smoke with `ATALL_URL=http://127.0.0.1:54321/functions/v1/atall`.
- **Without Docker:**
  1. Run PostgREST (`PGRST_DB_SCHEMAS=public`, an `authenticator` role granted `anon`/`service_role`) against a Postgres with the migration applied.
  2. Run `POSTGREST_URL=… SUPABASE_SERVICE_ROLE_KEY=<service_role JWT> ATALL_OWNER_CODE=… ATALL_ADMIN_TOKEN=… PORT=54400 deno task local-stack`.
  3. Run `ATALL_URL=http://127.0.0.1:54400/functions/v1/atall … node scripts/smoke.mjs`.
  - If an HTTP proxy is set in your shell, export `NO_PROXY=127.0.0.1,localhost` first: Deno's fetch honours it.

## Deploy

```bash
cd new-product/connector-supabase
PROJECT_REF=ggmvzyyuksewkgbokrhe SUPABASE_ACCESS_TOKEN=… scripts/deploy.sh --check   # read-only preflight
PROJECT_REF=ggmvzyyuksewkgbokrhe SUPABASE_ACCESS_TOKEN=… \
  ATALL_CONSENT_URL=https://<static-host>/consent.html scripts/deploy.sh [--smoke]
```

- **Migrations:** applied through the Management API (`POST /v1/projects/{ref}/database/migrations`), so no DB password is needed. Afterwards the script sends `NOTIFY pgrst, 'reload schema'`.
  - A migration already listed in the history, by version or by name, is skipped.
  - `--cli` uses `supabase db push --remote=<ref> --include-all --yes` instead. CLI 3.x pushes with a scoped token, or reads `SUPABASE_DB_PASSWORD`.
- **Secrets:** sent through the Management API (`POST /v1/projects/{ref}/secrets`) as JSON on a temp file.
  - The values never appear on a command line or in the output.
  - `ATALL_OWNER_CODE` and `ATALL_ADMIN_TOKEN` come from the env. Otherwise they're generated once into `.atall-secrets.local` (mode 600, git-ignored).
  - Pass-through if set: `ATALL_CONSENT_URL`, `ATALL_ALLOWED_ORIGINS`, `ATALL_DEV_PAGE`, `ATALL_SEED_TASK`, `ATALL_PUBLIC_URL`.
- **Function (`--curl`, used for v6):** `POST /v1/projects/{ref}/functions/deploy?slug=atall` as multipart: a `metadata` part `{"entrypoint_path":"index.ts","name":"atall","verify_jwt":false,"import_map_path":"deno.json"}` and one `file` part per source file (`index.ts`, `deno.json`, `src/*.ts`, filename = path relative to the function dir).
- **Function (default):** `supabase functions deploy atall --remote=<ref> --no-verify-jwt --use-api --yes`. This uses `supabase` on PATH, else `npx -y supabase@latest` (3.1.0 tested for `--help`). There's no Docker, and the token is read from `SUPABASE_ACCESS_TOKEN`.
- **Auth:** the access token reaches curl as a config line on stdin (`curl -K -`), never argv.
- **Smoke-test the deployment:** `ATALL_URL=https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall ATALL_OWNER_CODE=… ATALL_ADMIN_TOKEN=… node scripts/smoke.mjs`.

## Differences from the Worker

1. **URLs carry `/functions/v1/atall`.** The issuer, resource, endpoints and metadata all live under it. Root `/.well-known` isn't servable (see above). The smoke client now discovers AS metadata in MCP-spec order, and posts the consent form to the discovered `authorization_endpoint` instead of `origin + /authorize`.
2. **HTML is rewritten on `*.supabase.co`.** Added the external consent mode (`ATALL_CONSENT_URL`, `GET /authorize/request`, JSON `POST /authorize`), `scripts/build-static.ts`, and CORS for that one origin. Inline mode is unchanged. `/dev` now calls the admin API with an absolute base URL.
3. **Atomic storage** replaces KV's eventual consistency (table above). Observable effects:
   - concurrent redemptions of a code or refresh token can't both succeed
   - concurrent results from different agents can't drop each other's `per_agent` state
   - ref collisions retry instead of overwriting
4. **Revoking a connection also deletes its live tokens and codes.** The Worker relied on the revoked flag alone. The flag is still checked on every call.
5. **The `#c0ck` seed is also inserted by the migration.** With `ATALL_SEED_TASK=false` the Worker never created it. Here it exists after migration, and `/admin/reset` removes it for good in that mode. With the default (`true`) it comes back on the next inbox/admin read after a reset, as before.
6. **Lists are ordered explicitly.** Results are ordered by agent id, and presence by agent id. KV used key order, which was the same in practice.
7. **Client IP for owner-code rate limits** comes from `X-Forwarded-For` (first hop), since there is no `CF-Connecting-IP`. If Supabase passes a client-supplied XFF through, the per-IP limit (5/10 min) can be dodged. The global limit (20/10 min) still holds. **Unverified** on the hosted gateway.
8. **Presence writes** use `EdgeRuntime.waitUntil` instead of `ctx.waitUntil`.
9. **Tests:** `deno test` with `@std/testing/bdd` + `@std/expect` instead of vitest in workerd. Each test gets a fresh store. Test files use `.ts` import specifiers (Deno).
