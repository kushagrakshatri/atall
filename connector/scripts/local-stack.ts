// A Docker-free stand-in for `supabase start` + `supabase functions serve`, for end-to-end runs:
// one port that serves /rest/v1/* (proxied to a PostgREST you run against a Postgres with the
// migration applied) and /functions/v1/atall/* (this function, wired exactly as index.ts wires it,
// with the gateway's /functions/v1 strip). scripts/smoke.mjs then runs against
// http://127.0.0.1:54321/functions/v1/atall.
//
//   POSTGREST_URL=http://127.0.0.1:3300 SUPABASE_SERVICE_ROLE_KEY=<service_role JWT for that PostgREST> \
//   ATALL_OWNER_CODE=… ATALL_ADMIN_TOKEN=… deno run -A scripts/local-stack.ts
//
// With the real Supabase CLI and Docker, use `supabase start && supabase functions serve atall
// --no-verify-jwt --env-file supabase/.env.local` instead.

import { createHandler, envFrom } from "../supabase/functions/atall/src/server.ts";
import { PgStore, postgrestRpc } from "../supabase/functions/atall/src/store_pg.ts";

const port = Number(Deno.env.get("PORT") ?? 54321);
const postgrest = (Deno.env.get("POSTGREST_URL") ?? "http://127.0.0.1:3300").replace(/\/+$/, "");
const self = `http://127.0.0.1:${port}`;
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!key) throw new Error("set SUPABASE_SERVICE_ROLE_KEY");

const get = (k: string) => (k === "SUPABASE_URL" ? self : Deno.env.get(k));
const handler = createHandler(envFrom(get, new PgStore(postgrestRpc(self, key))));

Deno.serve({ port, hostname: "127.0.0.1" }, async (req) => {
  const u = new URL(req.url);
  if (u.pathname.startsWith("/rest/v1/")) {
    const target = postgrest + u.pathname.slice("/rest/v1".length) + u.search;
    const headers = new Headers(req.headers);
    headers.delete("apikey");
    return fetch(target, { method: req.method, headers, body: req.body });
  }
  if (u.pathname.startsWith("/functions/v1/atall")) {
    u.pathname = u.pathname.slice("/functions/v1".length); // what the Supabase gateway does
    return handler(new Request(u.toString(), req));
  }
  return new Response("not found", { status: 404 });
});
