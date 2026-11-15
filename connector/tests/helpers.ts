// Test harness: drives the function's handler in-process, the way the Supabase gateway does
// (https://<ref>.supabase.co/functions/v1/atall/x arrives at the function as /atall/x).
// Store: MemoryStore by default; set ATALL_TEST_PG=postgres://… to run the same suite against the
// real SQL functions in a local Postgres (migration applied by tests/pg.ts).

import { handleRequest } from "../supabase/functions/atall/src/app.ts";
import type { Ctx, Env } from "../supabase/functions/atall/src/env.ts";
import type { Store } from "../supabase/functions/atall/src/store.ts";
import { AtallAdmin, AtallClient } from "../scripts/lib/client.mjs";
import { MemoryStore } from "./memory_store.ts";
import { generateRelayKey } from "../supabase/functions/atall/src/crypto.ts";
import { Phone } from "../scripts/lib/phone.ts";
import { pgStoreForTests, PG_URL, PGLITE } from "./pg.ts";

export const ORIGIN = "https://atall.test";
export const BASE = `${ORIGIN}/functions/v1/atall`;
export const OWNER = "test-owner-code";
export const ADMIN = "test-admin-token-0123456789";
export const STORE_KIND = PG_URL ? "postgres" : PGLITE ? "pglite" : "memory";

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

let current: Env | null = null;

/** One relay key for the whole run (as in production: a function secret). */
export const RELAY_KEY = await generateRelayKey();

export async function freshStore(): Promise<Store> {
  return PG_URL || PGLITE ? await pgStoreForTests() : new MemoryStore();
}

/** Call from beforeEach: every test gets an empty store (vitest-pool-workers isolated storage did this). */
export async function freshEnv(overrides: Partial<Env> = {}): Promise<Env> {
  current = {
    store: await freshStore(),
    ATALL_PUBLIC_URL: BASE,
    ATALL_OWNER_CODE: OWNER,
    ATALL_ADMIN_TOKEN: ADMIN,
    ATALL_DEV_PAGE: "true",
    ATALL_SEED_TASK: "true",
    ATALL_ALLOWED_ORIGINS: "",
    ATALL_RELAY_SIGNING_KEY: RELAY_KEY.pkcs8B64,
    ...overrides,
  };
  return current;
}

export function testEnv(): Env {
  if (!current) throw new Error("call freshEnv() in beforeEach");
  return current;
}

/** Emulates the Supabase gateway: strips /functions/v1, keeps /atall/… */
export function gatewayRewrite(req: Request): Request {
  const u = new URL(req.url);
  if (u.pathname.startsWith("/functions/v1/")) u.pathname = u.pathname.slice("/functions/v1".length);
  return new Request(u.toString(), req);
}

/** A fetch that drives the function's handler in-process, with optional outbound fetch stub. */
export function workerFetch(outbound?: FetchLike): FetchLike {
  return async (input, init) => {
    const req = gatewayRewrite(new Request(input as RequestInfo, init));
    const pending: Promise<unknown>[] = [];
    const ctx: Ctx = { waitUntil: (p) => void pending.push(p) };
    const res = await handleRequest(req, testEnv(), ctx, { fetch: (outbound ?? (async () => new Response("no network in tests", { status: 599 }))) as typeof fetch });
    await Promise.allSettled(pending);
    return res;
  };
}

export function newClient(opts: { name?: string; redirectUri?: string; outbound?: FetchLike } = {}) {
  return new AtallClient({ base: BASE, fetch: workerFetch(opts.outbound), clientName: opts.name ?? "ChatGPT", redirectUri: opts.redirectUri });
}

export async function connected(agentId: string, name = "ChatGPT") {
  const c = newClient({ name });
  await c.connect({ ownerCode: OWNER, agentId: ["kdot", "grok", "duet", "claude", "hark", "muse"].includes(agentId) ? agentId : "other", agentOther: agentId });
  return c;
}

export function admin() {
  return new AtallAdmin({ base: BASE, token: ADMIN, fetch: workerFetch() });
}

export function structured(res: { body: any }) {
  return res.body.result.structuredContent;
}

/** A phone driving the in-process handler. Pass pair=false to get an unpaired one. */
export async function phone(pair = true, name = "Pixel test") {
  const p = await new Phone(BASE, workerFetch()).init();
  if (pair) {
    const r = await p.pair(OWNER, name);
    if (r.status !== 201) throw new Error(`pairing failed: ${r.status} ${JSON.stringify(r.body)}`);
  }
  return p;
}
