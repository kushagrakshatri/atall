// Supabase Edge Function entry point: https://<ref>.supabase.co/functions/v1/atall/...
// Deployed with verify_jwt = false (supabase/config.toml), because the function runs its own
// OAuth 2.1 server; the Supabase gateway must not demand a Supabase JWT on /mcp, /token, etc.

import { createHandler, envFrom, storeFromEnv } from "./src/server.ts";

const get = (name: string) => Deno.env.get(name);

Deno.serve(createHandler(envFrom(get, storeFromEnv(get))));
