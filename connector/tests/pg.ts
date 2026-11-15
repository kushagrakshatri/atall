// Optional Postgres backing for the test suite. Set ATALL_TEST_PG to a database URL; the migration in
// supabase/migrations is applied once (with stand-in anon/authenticated/service_role roles), and every
// test starts from truncated tables. The RPC executor calls the same SQL functions PostgREST would.

import postgres from "postgres";
import { PgStore, RpcError, type Rpc } from "../supabase/functions/atall/src/store_pg.ts";

export const PG_URL = Deno.env.get("ATALL_TEST_PG") ?? "";
/** ATALL_TEST_PGLITE=1: run the store-level suite on the real SQL migrations in an in-process PGlite (Postgres in WASM). */
export const PGLITE = !PG_URL && Deno.env.get("ATALL_TEST_PGLITE") === "1";

// deno-lint-ignore no-explicit-any
let lite: any = null;
async function pglite() {
  if (!lite) {
    const { PGlite } = await import("@electric-sql/pglite");
    lite = new PGlite();
    await lite.exec(ROLES_SQL);
    await lite.exec(migrationSql());
  }
  return lite;
}

const ALL_TABLES = `public.atall_events, public.atall_questions, public.atall_manifests, public.atall_devices, public.atall_phone_nonces,
    public.atall_results, public.atall_tasks, public.atall_pings, public.atall_presence,
    public.atall_tokens, public.atall_connections, public.atall_clients, public.atall_cimd_cache,
    public.atall_auth_requests, public.atall_rate_limits`;

const liteRpc: Rpc = async (fn, p) => {
  if (!/^atall_[a-z_]+$/.test(fn)) throw new Error(`bad function ${fn}`);
  const db = await pglite();
  try {
    const r = await db.query(`select public.${fn}($1::jsonb) as r`, [JSON.stringify(p)]);
    return r.rows[0]?.r ?? null;
  } catch (e) {
    const err = e as { message?: string; code?: string };
    throw new RpcError(err.message ?? String(e), err.code);
  }
};

/** Raw SQL against the PGlite instance (tests only). */
export async function liteQuery<T = Record<string, unknown>>(sqlText: string, params: unknown[] = []): Promise<T[]> {
  return (await (await pglite()).query(sqlText, params)).rows as T[];
}

let sql: ReturnType<typeof postgres> | null = null;
let migrated = false;

export const ROLES_SQL = `do $$ begin
  if not exists (select 1 from pg_roles where rolname='anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname='authenticated') then create role authenticated nologin; end if;
  if not exists (select 1 from pg_roles where rolname='service_role') then create role service_role nologin bypassrls; end if;
end $$;`;

export function migrationSql(): string {
  const dir = new URL("../supabase/migrations/", import.meta.url);
  const files = [...Deno.readDirSync(dir)].map((e) => e.name).filter((n) => n.endsWith(".sql")).sort();
  return files.map((f) => Deno.readTextFileSync(new URL(f, dir))).join("\n;\n");
}

export function pg() {
  if (!sql) sql = postgres(PG_URL, { max: 4, onnotice: () => {} });
  return sql;
}

export async function closePg() {
  if (lite) {
    await lite.close();
    lite = null;
  }
  if (sql) await sql.end({ timeout: 1 });
  sql = null;
  migrated = false;
}

export async function migrate() {
  if (migrated) return;
  const db = pg();
  await db.unsafe(ROLES_SQL);
  await db.unsafe(migrationSql());
  migrated = true;
}

export const directRpc: Rpc = async (fn, p) => {
  if (!/^atall_[a-z_]+$/.test(fn)) throw new Error(`bad function ${fn}`);
  try {
    // postgres.js serialises the value for the jsonb parameter itself (passing a string would double-encode).
    const rows = await pg().unsafe(`select public.${fn}($1::jsonb) as r`, [p as never]);
    return rows[0]?.r ?? null;
  } catch (e) {
    const err = e as { message?: string; code?: string };
    throw new RpcError(err.message ?? String(e), err.code);
  }
};

export async function truncateAll() {
  await pg().unsafe(`truncate ${ALL_TABLES} restart identity cascade`);
}

export async function pgStoreForTests(): Promise<PgStore> {
  if (PGLITE) {
    await (await pglite()).exec(`truncate ${ALL_TABLES} restart identity cascade`);
    return new PgStore(liteRpc);
  }
  await migrate();
  await truncateAll();
  return new PgStore(directRpc);
}
