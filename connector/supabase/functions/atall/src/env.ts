import type { Store } from "./store.ts";

export interface Env {
  /** Data layer: Postgres (via PostgREST RPC) in production, in-memory in tests. Replaces the Worker's ATALL_KV. */
  store: Store;
  /** Public base URL of this function, no trailing slash, e.g. https://<ref>.supabase.co/functions/v1/atall.
   *  It is the OAuth issuer; /mcp, /authorize, /token, ... and the well-known docs all hang off it. */
  ATALL_PUBLIC_URL: string;
  /** Secret Kush types on the consent page to approve a connection (C0 stand-in for phone approval). */
  ATALL_OWNER_CODE: string;
  /** Bearer token for /admin/* and the /dev page. */
  ATALL_ADMIN_TOKEN: string;
  /** "true" serves the /dev page (still needs the admin token). Default true for C0. */
  ATALL_DEV_PAGE?: string;
  /** Comma-separated extra browser Origins allowed to call /mcp (requests without Origin are always allowed). */
  ATALL_ALLOWED_ORIGINS?: string;
  /** "false" skips (re-)seeding the hard-coded C0 check task. */
  ATALL_SEED_TASK?: string;
  /** Optional: full URL of a statically hosted consent page (static/consent.html built by scripts/build-static.ts).
   *  When set, GET /authorize redirects there instead of returning HTML, because *.supabase.co rewrites
   *  text/html responses from Edge Functions to text/plain. Its origin also gets CORS on /authorize and /admin. */
  ATALL_CONSENT_URL?: string;
  /** C1: the relay's ES256 private key (PKCS8 DER, base64). Countersigns phone events; its public key is handed out at pairing. */
  ATALL_RELAY_SIGNING_KEY?: string;
}

/** Replaces Cloudflare's ExecutionContext: EdgeRuntime.waitUntil in production, a collector in tests. */
export interface Ctx {
  waitUntil(p: Promise<unknown>): void;
}

/** Optional injectables so tests can stub outbound fetches (CIMD). */
export interface Deps {
  fetch: typeof fetch;
}

export function issuer(env: Env): string {
  return env.ATALL_PUBLIC_URL.replace(/\/+$/, "");
}

export function consentOrigin(env: Env): string | null {
  if (!env.ATALL_CONSENT_URL) return null;
  try {
    return new URL(env.ATALL_CONSENT_URL).origin;
  } catch {
    return null;
  }
}
