// Builds the Env from Supabase's injected environment and returns the Deno.serve handler.
// Kept separate from index.ts so tests can exercise exactly what production runs.

import { handleRequest } from "./app.ts";
import type { Ctx, Env } from "./env.ts";
import type { Store } from "./store.ts";
import { PgStore, postgrestRpc } from "./store_pg.ts";
import { json } from "./util.ts";

type GetEnv = (name: string) => string | undefined;

/**
 * The function's public base URL (the OAuth issuer).
 *  1. ATALL_PUBLIC_URL if set (a custom domain or a root proxy, or local dev).
 *  2. SUPABASE_URL + /functions/v1/atall on the hosted platform (SUPABASE_URL is https://<ref>.supabase.co).
 *  3. Local `supabase functions serve` injects SUPABASE_URL=http://kong:8000 (internal); the public URL
 *     there is http://127.0.0.1:54321/functions/v1/atall.
 */
export function resolvePublicUrl(get: GetEnv): string {
  const explicit = get("ATALL_PUBLIC_URL")?.trim();
  if (explicit) return explicit.replace(/\/+$/, "");
  const sb = get("SUPABASE_URL")?.trim();
  if (sb) {
    const u = new URL(sb);
    if (u.hostname.includes(".")) return `${u.origin}/functions/v1/atall`;
  }
  return "http://127.0.0.1:54321/functions/v1/atall";
}

export function envFrom(get: GetEnv, store: Store): Env {
  return {
    store,
    ATALL_PUBLIC_URL: resolvePublicUrl(get),
    ATALL_OWNER_CODE: get("ATALL_OWNER_CODE") ?? "",
    ATALL_ADMIN_TOKEN: get("ATALL_ADMIN_TOKEN") ?? "",
    ATALL_DEV_PAGE: get("ATALL_DEV_PAGE") ?? "true",
    ATALL_SEED_TASK: get("ATALL_SEED_TASK") ?? "true",
    ATALL_ALLOWED_ORIGINS: get("ATALL_ALLOWED_ORIGINS") ?? "",
    ATALL_CONSENT_URL: get("ATALL_CONSENT_URL") || undefined,
    ATALL_RELAY_SIGNING_KEY: get("ATALL_RELAY_SIGNING_KEY") || undefined,
  };
}

export function storeFromEnv(get: GetEnv): Store {
  const url = get("SUPABASE_URL");
  const key = get("SUPABASE_SERVICE_ROLE_KEY") ?? get("SUPABASE_SECRET_KEY");
  if (!url || !key) throw new Error("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set (Supabase injects both)");
  return new PgStore(postgrestRpc(url, key));
}

/** Supabase's EdgeRuntime.waitUntil keeps the isolate alive for background work (presence writes). */
export function edgeCtx(): Ctx {
  // deno-lint-ignore no-explicit-any
  const rt = (globalThis as any).EdgeRuntime;
  return {
    waitUntil(p) {
      if (rt?.waitUntil) rt.waitUntil(p);
      else p.catch(() => undefined);
    },
  };
}

export function createHandler(env: Env, ctxFactory: () => Ctx = edgeCtx): (req: Request) => Promise<Response> {
  return async (req) => {
    try {
      return await handleRequest(req, env, ctxFactory());
    } catch (e) {
      console.error("atall: unhandled error", e instanceof Error ? e.message : e);
      return json({ error: "internal error" }, 500);
    }
  };
}
