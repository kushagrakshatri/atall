// Atall connector, milestone C0: a remote MCP server on a Supabase Edge Function.
// Same routes as the Cloudflare Worker, mounted under the function's base path
// (https://<ref>.supabase.co/functions/v1/atall/...). See ../../../../README.md.

import { devPage, handleAdmin } from "./admin.ts";
import { consentOrigin, type Ctx, type Deps, type Env, issuer } from "./env.ts";
import { corsHeaders, handleMcp, originAllowed } from "./mcp.ts";
import { authorizationServerMetadata, authorizeGet, authorizePost, authorizeRequestInfo, protectedResourceMetadata, register, revoke, token } from "./oauth.ts";
import { handlePhone } from "./phone.ts";
import { json } from "./util.ts";

export const FUNCTION_NAME = "atall";

const OPEN_CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, MCP-Protocol-Version",
  "Access-Control-Max-Age": "600",
};

function withHeaders(res: Response, headers: Record<string, string>): Response {
  if (!Object.keys(headers).length) return res;
  const r = new Response(res.body, res);
  for (const [k, v] of Object.entries(headers)) r.headers.set(k, v);
  return r;
}

/** CORS for the statically hosted consent/dev pages (ATALL_CONSENT_URL's origin) only. */
function uiCors(req: Request, env: Env): Record<string, string> {
  const o = req.headers.get("Origin");
  const allowed = consentOrigin(env);
  if (!o || !allowed || o !== allowed) return {};
  return {
    "Access-Control-Allow-Origin": o,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

/**
 * Maps the incoming pathname to the route inside the function. Supabase hands the function
 * `/atall/<route>` (the /functions/v1 prefix is stripped by the gateway); local `functions serve`
 * and tests may pass the full `/functions/v1/atall/<route>`; a root proxy on a custom domain may
 * already have rewritten it. All three are accepted.
 */
export function routePath(pathname: string, env: Env): string {
  const prefixes = [new URL(issuer(env)).pathname.replace(/\/+$/, ""), `/functions/v1/${FUNCTION_NAME}`, `/${FUNCTION_NAME}`];
  let p = pathname;
  for (const prefix of prefixes) {
    if (prefix && (p === prefix || p.startsWith(prefix + "/"))) {
      p = p.slice(prefix.length);
      break;
    }
  }
  return p.replace(/\/+$/, "") || "/";
}

export async function handleRequest(req: Request, env: Env, ctx: Ctx, deps: Deps = { fetch: globalThis.fetch.bind(globalThis) }): Promise<Response> {
  const url = new URL(req.url);
  const p = routePath(url.pathname, env);
  const base = issuer(env);

  // MCP endpoint
  if (p === "/mcp") {
    if (req.method === "OPTIONS") return new Response(null, { status: originAllowed(req, env) ? 204 : 403, headers: corsHeaders(req, env) });
    return withHeaders(await handleMcp(req, env, deps, ctx), corsHeaders(req, env));
  }

  // Discovery documents: public, CORS-open (browser-based MCP clients read these).
  // Served under the function path, because nothing outside /functions/v1/atall reaches us.
  if (req.method === "OPTIONS" && (p.startsWith("/.well-known/") || p === "/register" || p === "/token" || p === "/revoke"))
    return new Response(null, { status: 204, headers: OPEN_CORS });
  if (p === "/.well-known/oauth-protected-resource" || p === "/.well-known/oauth-protected-resource/mcp") return withHeaders(protectedResourceMetadata(env), OPEN_CORS);
  if (
    p === "/.well-known/oauth-authorization-server" ||
    p === "/.well-known/oauth-authorization-server/mcp" ||
    p === "/.well-known/openid-configuration" ||
    p === "/.well-known/openid-configuration/mcp" ||
    p === "/mcp/.well-known/openid-configuration"
  )
    return withHeaders(authorizationServerMetadata(env), OPEN_CORS);

  if (p === "/register" && req.method === "POST") return withHeaders(await register(req, env), OPEN_CORS);
  if (p === "/token" && req.method === "POST") return withHeaders(await token(req, env, deps), OPEN_CORS);
  if (p === "/revoke" && req.method === "POST") return withHeaders(await revoke(req, env, deps), OPEN_CORS);

  if ((p === "/authorize" || p === "/authorize/request" || p.startsWith("/admin/")) && req.method === "OPTIONS")
    return new Response(null, { status: 204, headers: uiCors(req, env) });
  if (p === "/authorize" && req.method === "GET") return authorizeGet(req, env, deps);
  if (p === "/authorize" && req.method === "POST") return withHeaders(await authorizePost(req, env), uiCors(req, env));
  if (p === "/authorize/request" && req.method === "GET") return withHeaders(await authorizeRequestInfo(req, env), uiCors(req, env));

  // C1 phone API (native app; no CORS). Every route but /phone/pair is device-signed.
  if (p.startsWith("/phone/")) return handlePhone(req, env, p);

  if (p.startsWith("/admin/")) return withHeaders(await handleAdmin(req, env, p), uiCors(req, env));
  if (p === "/dev" && req.method === "GET") return env.ATALL_DEV_PAGE === "false" ? json({ error: "not found" }, 404) : devPage(base);

  if (p === "/" && req.method === "GET")
    return json({ name: "Atall connector", milestone: "C1", runtime: "supabase-edge", mcp_endpoint: `${base}/mcp`, protected_resource_metadata: `${base}/.well-known/oauth-protected-resource/mcp` });

  return json({ error: "not found" }, 404);
}
