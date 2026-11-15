// SQL-layer tests: run only when ATALL_TEST_PG points at a Postgres you can write to.
// They check the security posture (RLS, grants) and that the operations KV couldn't do atomically
// hold up under real concurrency.

import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import postgres from "postgres";
import { SEED_TASK } from "../supabase/functions/atall/src/store.ts";
import { closePg, directRpc, migrate, migrationSql, pg, PG_URL, truncateAll } from "./pg.ts";
import { sha256b64url } from "../supabase/functions/atall/src/util.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false, ignore: !PG_URL };
const nowS = () => Math.floor(Date.now() / 1000);

/** N independent connections firing the same call at once. */
async function race<T>(n: number, fn: string, p: (i: number) => Record<string, unknown>): Promise<T[]> {
  const conns = Array.from({ length: n }, () => postgres(PG_URL, { max: 1, onnotice: () => {} }));
  try {
    await Promise.all(conns.map((c) => c`select 1`));
    return await Promise.all(conns.map(async (c, i) => (await c.unsafe(`select public.${fn}($1::jsonb) as r`, [p(i) as never]))[0].r as T));
  } finally {
    await Promise.all(conns.map((c) => c.end({ timeout: 1 })));
  }
}

async function seedConnection(connId = "cn_test") {
  await directRpc("atall_put_connection", { conn_id: connId, agent_id: "kdot", client_id: "cl_x", client_name: "ChatGPT", kind: "oauth", created_at: new Date().toISOString() });
}

describe({ name: "sql", ...OPTS }, () => {
  beforeEach(async () => {
    await migrate();
    await truncateAll();
  });
  afterAll(async () => {
    await closePg();
  });

  it("the migration is idempotent", async () => {
    await pg().unsafe(migrationSql());
    const [{ n }] = await pg()`select count(*)::int as n from public.atall_tasks where task_id = 'tsk_c0check'`;
    expect(n).toBe(1);
  });

  it("RLS is on for every atall table, with no policies", async () => {
    const tables = await pg()`select c.relname, c.relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relkind = 'r' and c.relname like 'atall\\_%' order by 1`;
    expect(tables.length).toBe(15); // 10 from C0 + devices, phone_nonces, events, questions, manifests (C1)
    for (const t of tables) expect(t.relrowsecurity, t.relname).toBe(true);
    const [{ n }] = await pg()`select count(*)::int as n from pg_policies where schemaname = 'public' and tablename like 'atall\\_%'`;
    expect(n).toBe(0);
  });

  it("anon and authenticated can't read tables or call functions; service_role can", async () => {
    for (const role of ["anon", "authenticated"]) {
      const [g] = await pg()`select has_table_privilege(${role}, 'public.atall_tokens', 'select') as t,
        has_function_privilege(${role}, 'public.atall_list_tasks(jsonb)', 'execute') as f,
        has_function_privilege(${role}, 'public.atall_take_refresh_token(jsonb)', 'execute') as f2`;
      expect(g, role).toEqual({ t: false, f: false, f2: false });
    }
    const [s] = await pg()`select has_table_privilege('service_role', 'public.atall_tokens', 'select') as t,
      has_function_privilege('service_role', 'public.atall_list_tasks(jsonb)', 'execute') as f`;
    expect(s).toEqual({ t: true, f: true });
    const err = await pg()
      .begin(async (tx) => {
        await tx`set local role anon`;
        await tx`select public.atall_list_tasks()`;
      })
      .then(() => null, (e) => e as Error);
    expect(String(err?.message)).toContain("permission denied");
    const ok = await pg().begin(async (tx) => {
      await tx`set local role service_role`;
      await tx`select public.atall_ensure_seed()`;
      return (await tx`select public.atall_list_tasks() as r`)[0].r;
    });
    expect(ok[0].ref).toBe("c0ck");
  });

  it("seeds the same #c0ck task as the TypeScript SEED_TASK", async () => {
    await directRpc("atall_ensure_seed", {});
    const t = (await directRpc("atall_get_task", { ref: "c0ck" })) as Record<string, unknown>;
    expect(t).toMatchObject({ task_id: SEED_TASK.task_id, title: SEED_TASK.title, goal: SEED_TASK.goal, done_when: SEED_TASK.done_when, to: ["@all"], created_by: "seed" });
    expect(String(t.created_at)).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/);
  });

  it("a refresh token presented 8 times at once rotates once; the other 7 see reuse", async () => {
    await seedConnection();
    const hash = await sha256b64url("atl_rt_race");
    await directRpc("atall_put_token", { hash, kind: "refresh", rec: { conn_id: "cn_test", agent_id: "kdot", client_id: "cl_x", scope: "atall.agent", exp: nowS() + 3600 } });
    const out = await race<{ rec?: unknown; reused_conn?: string }>(8, "atall_take_refresh_token", () => ({ hash }));
    expect(out.filter((r) => r.rec).length).toBe(1);
    expect(out.filter((r) => r.reused_conn === "cn_test").length).toBe(7);
  });

  it("an authorization code redeemed 8 times at once works once", async () => {
    await seedConnection();
    const hash = await sha256b64url("code_race");
    await directRpc("atall_put_code", {
      hash,
      rec: { conn_id: "cn_test", agent_id: "kdot", client_id: "cl_x", scope: "atall.agent", exp: nowS() + 600, redirect_uri: "https://x/cb", code_challenge: "c", resource: "r" },
    });
    const out = await race<unknown>(8, "atall_take_code", () => ({ hash }));
    expect(out.filter((r) => r !== null).length).toBe(1);
    expect(out.find((r) => r !== null)).toMatchObject({ conn_id: "cn_test", redirect_uri: "https://x/cb", code_challenge: "c" });
  });

  it("rate-limit hits are counted exactly under concurrency", async () => {
    const out = await race<number>(12, "atall_rl_hit", () => ({ key: "consentfail:1.2.3.4", window_s: 600 }));
    expect([...out].sort((a, b) => a - b)).toEqual(Array.from({ length: 12 }, (_, i) => i + 1));
    expect(await directRpc("atall_rl_count", { key: "consentfail:1.2.3.4", window_s: 600 })).toBe(12);
  });

  it("concurrent results from different agents both land in per_agent (KV would drop one)", async () => {
    await directRpc("atall_ensure_seed", {});
    const agents = ["kdot", "grok", "duet", "hark", "muse", "claude"];
    await race(agents.length, "atall_post_result", (i) => ({
      state: "COMPLETED",
      result: { result_id: `res_${i}`, task_id: "tsk_c0check", ref: "c0ck", agent_id: agents[i], conn_id: "cn", client_name: "x", status: "done", summary: "s", claims: [], artifacts: [], recorded_at: new Date().toISOString() },
    }));
    const t = (await directRpc("atall_get_task", { task_id: "tsk_c0check" })) as { per_agent: Record<string, { state: string }> };
    expect(Object.keys(t.per_agent).sort()).toEqual([...agents].sort());
    expect(((await directRpc("atall_list_results", { task_id: "tsk_c0check" })) as unknown[]).length).toBe(agents.length);
  });

  it("the same agent posting twice at once stores one result, marked replaced exactly once", async () => {
    await directRpc("atall_ensure_seed", {});
    const out = await race<{ replaced_previous: boolean }>(2, "atall_post_result", (i) => ({
      state: "COMPLETED",
      result: { result_id: `res_${i}`, task_id: "tsk_c0check", ref: "c0ck", agent_id: "kdot", conn_id: "cn", client_name: "x", status: "done", summary: `s${i}`, claims: [], artifacts: [], recorded_at: new Date().toISOString() },
    }));
    expect(out.map((r) => r.replaced_previous).sort()).toEqual([false, true]);
  });

  it("a static client's redirect can be pinned by only one concurrent approval", async () => {
    await directRpc("atall_put_client", { client_id: "cl_static", client_name: "Grok", redirect_uris: [], token_endpoint_auth_method: "none", kind: "static", pin_redirect_on_first_use: true });
    const out = await race<boolean>(6, "atall_pin_client_redirect", (i) => ({ client_id: "cl_static", redirect_uri: `https://grok.example/cb${i}` }));
    expect(out.filter(Boolean).length).toBe(1);
    const c = (await directRpc("atall_get_client", { client_id: "cl_static" })) as { redirect_uris: string[]; pin_redirect_on_first_use: boolean };
    expect(c.redirect_uris.length).toBe(1);
    expect(c.pin_redirect_on_first_use).toBe(false);
  });

  it("keeps only the last 50 pings and throttles presence writes in SQL", async () => {
    for (let i = 0; i < 55; i++) await directRpc("atall_add_ping", { ping_id: `png_${i}`, agent_id: "kdot", conn_id: "cn", client_name: "x", note: String(i), at: new Date().toISOString() });
    const pings = (await directRpc("atall_list_pings", {})) as { note: string }[];
    expect(pings.length).toBe(50);
    expect(pings[0].note).toBe("5");
    expect(pings[49].note).toBe("54");
    const t0 = new Date();
    await directRpc("atall_touch_presence", { agent_id: "kdot", conn_id: "cn", client_name: "x", last_seen: t0.toISOString(), last_tool: "atall_inbox" });
    await directRpc("atall_touch_presence", { agent_id: "kdot", conn_id: "cn", client_name: "x", last_seen: new Date(t0.getTime() + 5000).toISOString(), last_tool: "atall_inbox" });
    let p = ((await directRpc("atall_list_presence", {})) as { last_seen: string; last_tool: string }[])[0];
    expect(p.last_seen).toBe(t0.toISOString());
    await directRpc("atall_touch_presence", { agent_id: "kdot", conn_id: "cn", client_name: "x", last_seen: new Date(t0.getTime() + 6000).toISOString(), last_tool: "atall_get_task" });
    p = ((await directRpc("atall_list_presence", {})) as { last_seen: string; last_tool: string }[])[0];
    expect(p.last_tool).toBe("atall_get_task");
  });

  it("expired rows are purged, and revoking a connection deletes its live tokens", async () => {
    await seedConnection();
    await directRpc("atall_put_token", { hash: "old", kind: "access", rec: { conn_id: "cn_test", agent_id: "kdot", client_id: "cl_x", scope: "s", exp: nowS() - 10 } });
    await directRpc("atall_put_token", { hash: "live", kind: "access", rec: { conn_id: "cn_test", agent_id: "kdot", client_id: "cl_x", scope: "s", exp: nowS() + 3600 } });
    await directRpc("atall_purge_expired", {});
    const left = await pg()`select token_hash from public.atall_tokens order by 1`;
    expect(left.map((r) => r.token_hash)).toEqual(["live"]);
    await directRpc("atall_revoke_connection", { conn_id: "cn_test", reason: "test" });
    const after = await pg()`select count(*)::int as n from public.atall_tokens`;
    expect(after[0].n).toBe(0);
    expect(await directRpc("atall_get_connection", { conn_id: "cn_test" })).toMatchObject({ revoked: true, revoked_reason: "test" });
  });
});
