// Minimal OAuth 2.1 authorization server for the Atall connector.
// - Authorization code + PKCE (S256 only), refresh tokens rotated with reuse detection
// - Client registration: Dynamic Client Registration (RFC 7591), Client ID Metadata
//   Documents (CIMD, client_id is an https URL), and static clients made by the owner
// - RFC 8414 / RFC 9728 metadata, RFC 9207 `iss` on the authorization response
// - One connection (and token family) per agent sign-in; the agent id is chosen by
//   Kush on the consent page and is the identity every tool call runs as.

import { type Deps, type Env, issuer } from "./env.ts";
import { consentPage, errorPage, suggestAgent } from "./pages.ts";
import {
  ACCESS_TTL_S,
  AGENT_ID_RE,
  type AuthMethod,
  type AuthRequest,
  CIMD_CACHE_TTL_S,
  type Client,
  CODE_TTL_S,
  HEADER_TOKEN_TTL_S,
  KNOWN_AGENTS,
  REFRESH_TTL_S,
  type Store,
  type TokenRecord,
} from "./store.ts";
import { b64url, clientIp, html, isLoopbackUrl, isPrivateHost, json, newId, nowIso, randomToken, safeEqual, sha256b64url } from "./util.ts";

export const SCOPE = "atall.agent";
const SUPPORTED_SCOPES = [SCOPE, "offline_access"];

// Issuer = the function's public base URL, path included (https://<ref>.supabase.co/functions/v1/atall).
export function resourceId(env: Env): string {
  return `${issuer(env)}/mcp`;
}

export function protectedResourceMetadata(env: Env) {
  return json({
    resource: resourceId(env),
    authorization_servers: [issuer(env)],
    scopes_supported: [SCOPE],
    bearer_methods_supported: ["header"],
    resource_name: "Atall",
  });
}

export function authorizationServerMetadata(env: Env) {
  const o = issuer(env);
  return json({
    issuer: o,
    authorization_endpoint: `${o}/authorize`,
    token_endpoint: `${o}/token`,
    registration_endpoint: `${o}/register`,
    revocation_endpoint: `${o}/revoke`,
    response_types_supported: ["code"],
    response_modes_supported: ["query"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none", "client_secret_post", "client_secret_basic"],
    revocation_endpoint_auth_methods_supported: ["none", "client_secret_post", "client_secret_basic"],
    scopes_supported: SUPPORTED_SCOPES,
    client_id_metadata_document_supported: true,
    authorization_response_iss_parameter_supported: true,
  });
}

function oauthError(error: string, description: string, status = 400, headers: HeadersInit = {}) {
  return json({ error, error_description: description }, status, headers);
}

const BAD_SCHEMES = new Set(["javascript:", "data:", "file:", "vbscript:", "blob:", "about:"]);

/** https anywhere, http only on loopback, or a native-app custom scheme. No fragments. */
export function validRedirectUri(s: unknown): s is string {
  if (typeof s !== "string" || s.length > 2000) return false;
  let u: URL;
  try {
    u = new URL(s);
  } catch {
    return false;
  }
  if (u.hash) return false;
  if (BAD_SCHEMES.has(u.protocol)) return false;
  if (u.protocol === "https:") return true;
  if (u.protocol === "http:") return isLoopbackUrl(u);
  return /^[a-z][a-z0-9+.-]*:$/.test(u.protocol); // e.g. com.example.app:/cb
}

function redirectMatches(registered: string[], requested: string): boolean {
  if (registered.includes(requested)) return true;
  // RFC 8252 §7.3: loopback redirects may vary the port.
  let r: URL;
  try {
    r = new URL(requested);
  } catch {
    return false;
  }
  if (!isLoopbackUrl(r)) return false;
  return registered.some((reg) => {
    try {
      const u = new URL(reg);
      return isLoopbackUrl(u) && u.hostname === r.hostname && u.pathname === r.pathname && u.search === r.search;
    } catch {
      return false;
    }
  });
}

// ---------------------------------------------------------------------------
// Client resolution (Postgres for DCR/static, fetched + cached for CIMD)
// ---------------------------------------------------------------------------

async function fetchCimd(clientId: string, env: Env, deps: Deps): Promise<Client | { error: string }> {
  let u: URL;
  try {
    u = new URL(clientId);
  } catch {
    return { error: "client_id is not a valid URL" };
  }
  if (u.protocol !== "https:" || !u.pathname || u.pathname === "/" || u.hash || u.username || u.password) return { error: "CIMD client_id must be an https URL with a path" };
  if (isPrivateHost(u.hostname)) return { error: "CIMD client_id host is not allowed" };

  const cached = await env.store.getCimd(clientId);
  if (cached) return cached;

  let res: Response;
  try {
    res = await deps.fetch(clientId, { redirect: "manual", headers: { Accept: "application/json" }, signal: AbortSignal.timeout(5000) });
  } catch {
    return { error: "could not fetch the client metadata document" };
  }
  if (res.status !== 200) return { error: `client metadata document returned HTTP ${res.status}` };
  const text = await res.text();
  if (text.length > 5120) return { error: "client metadata document is too large" };
  let doc: Record<string, unknown>;
  try {
    doc = JSON.parse(text);
  } catch {
    return { error: "client metadata document is not JSON" };
  }
  if (doc.client_id !== clientId) return { error: "client metadata document client_id does not match its URL" };
  const redirects = doc.redirect_uris;
  if (!Array.isArray(redirects) || !redirects.length || !redirects.every(validRedirectUri)) return { error: "client metadata document has invalid redirect_uris" };
  // The singular field is a preference. ChatGPT prefers private_key_jwt but also lists "none"
  // in token_endpoint_auth_methods_supported; we only advertise "none", so treat that as public + PKCE.
  const method = (doc.token_endpoint_auth_method as string | undefined) ?? "none";
  const supported = Array.isArray(doc.token_endpoint_auth_methods_supported) ? (doc.token_endpoint_auth_methods_supported as unknown[]) : [];
  if (method !== "none" && !supported.includes("none")) return { error: "only public CIMD clients (token_endpoint_auth_method none) are supported" };
  const client: Client = {
    client_id: clientId,
    client_name: typeof doc.client_name === "string" ? doc.client_name.slice(0, 100) : u.hostname,
    // ChatGPT's per-connector document lists /connector/oauth/{id}, but its connector platform
    // has been seen redirecting to /connector_platform_oauth_redirect; allow that one too for chatgpt.com.
    redirect_uris: u.hostname === "chatgpt.com" && !(redirects as string[]).includes("https://chatgpt.com/connector_platform_oauth_redirect")
      ? [...(redirects as string[]), "https://chatgpt.com/connector_platform_oauth_redirect"]
      : (redirects as string[]),
    token_endpoint_auth_method: "none",
    kind: "cimd",
    created_at: nowIso(),
  };
  await env.store.putCimd(client, CIMD_CACHE_TTL_S);
  return client;
}

export async function resolveClient(clientId: string, env: Env, deps: Deps): Promise<Client | { error: string }> {
  if (!clientId || clientId.length > 2000) return { error: "missing client_id" };
  if (clientId.startsWith("https://")) return fetchCimd(clientId, env, deps);
  const c = await env.store.getClient(clientId);
  return c ?? { error: "unknown client_id" };
}

// ---------------------------------------------------------------------------
// Dynamic Client Registration
// ---------------------------------------------------------------------------

export async function register(req: Request, env: Env): Promise<Response> {
  const store = env.store;
  if (!(await store.hit(`reg:${clientIp(req)}`, 30, 3600))) return oauthError("too_many_requests", "registration rate limit", 429);
  let body: Record<string, unknown>;
  try {
    const text = await req.text();
    if (text.length > 10_000) return oauthError("invalid_client_metadata", "registration body too large");
    body = JSON.parse(text);
  } catch {
    return oauthError("invalid_client_metadata", "body must be JSON");
  }
  const redirects = body.redirect_uris;
  if (!Array.isArray(redirects) || !redirects.length || redirects.length > 10 || !redirects.every(validRedirectUri))
    return oauthError("invalid_redirect_uri", "redirect_uris must be https, loopback http, or a native app scheme");
  const method = ((body.token_endpoint_auth_method as string) ?? "none") as AuthMethod;
  if (!["none", "client_secret_post", "client_secret_basic"].includes(method)) return oauthError("invalid_client_metadata", "unsupported token_endpoint_auth_method");
  const grants = (body.grant_types as string[] | undefined) ?? ["authorization_code", "refresh_token"];
  if (!Array.isArray(grants) || grants.some((g) => !["authorization_code", "refresh_token"].includes(g))) return oauthError("invalid_client_metadata", "unsupported grant_types");

  const client: Client = {
    client_id: newId("cl"),
    client_name: typeof body.client_name === "string" && body.client_name.trim() ? body.client_name.trim().slice(0, 100) : "Unnamed client",
    redirect_uris: redirects as string[],
    token_endpoint_auth_method: method,
    kind: "dcr",
    created_at: nowIso(),
  };
  let secret: string | undefined;
  if (method !== "none") {
    secret = randomToken(32);
    client.client_secret_hash = await sha256b64url(secret);
  }
  await store.putClient(client);
  return json(
    {
      client_id: client.client_id,
      client_id_issued_at: Math.floor(Date.now() / 1000),
      ...(secret ? { client_secret: secret, client_secret_expires_at: 0 } : {}),
      client_name: client.client_name,
      redirect_uris: client.redirect_uris,
      token_endpoint_auth_method: method,
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      scope: SUPPORTED_SCOPES.join(" "),
    },
    201,
  );
}

/** Owner-created client (for agents that take a fixed Client ID, e.g. Grok Bot). */
export async function createStaticClient(env: Env, input: { client_name?: string; redirect_uris?: string[]; confidential?: boolean }) {
  const redirects = input.redirect_uris ?? [];
  if (!redirects.every(validRedirectUri)) throw new Error("invalid redirect_uris");
  const client: Client = {
    client_id: newId("cl"),
    client_name: (input.client_name ?? "Static client").slice(0, 100),
    redirect_uris: redirects,
    token_endpoint_auth_method: input.confidential ? "client_secret_post" : "none",
    kind: "static",
    pin_redirect_on_first_use: redirects.length === 0,
    created_at: nowIso(),
  };
  let secret: string | undefined;
  if (input.confidential) {
    secret = randomToken(32);
    client.client_secret_hash = await sha256b64url(secret);
  }
  await env.store.putClient(client);
  return { client_id: client.client_id, client_secret: secret, redirect_uris: redirects, pin_redirect_on_first_use: client.pin_redirect_on_first_use, token_endpoint_auth_method: client.token_endpoint_auth_method };
}

// ---------------------------------------------------------------------------
// /authorize
// ---------------------------------------------------------------------------

function normalizeScope(raw: string | null): string {
  const asked = (raw ?? "").split(/\s+/).filter((s) => SUPPORTED_SCOPES.includes(s));
  return Array.from(new Set([SCOPE, ...asked])).join(" ");
}

function normalizeResource(env: Env, raw: string | null): string | null {
  const ours = resourceId(env);
  if (!raw) return ours;
  const r = raw.replace(/\/+$/, "");
  if (r === ours || r === issuer(env)) return ours;
  return null;
}

function redirectWith(redirectUri: string, params: Record<string, string | undefined>): Response {
  const u = new URL(redirectUri);
  for (const [k, v] of Object.entries(params)) if (v !== undefined) u.searchParams.set(k, v);
  return new Response(null, { status: 302, headers: { Location: u.toString(), "Cache-Control": "no-store" } });
}

/** In external-consent mode, problems on GET /authorize go to the static page instead of an HTML error
 *  (which *.supabase.co would serve as text/plain). */
function authorizeError(env: Env, message: string): Response {
  if (env.ATALL_CONSENT_URL) {
    const u = new URL(env.ATALL_CONSENT_URL);
    u.searchParams.set("error", message);
    return new Response(null, { status: 302, headers: { Location: u.toString(), "Cache-Control": "no-store" } });
  }
  return html(errorPage(message), 400);
}

export async function authorizeGet(req: Request, env: Env, deps: Deps): Promise<Response> {
  const q = new URL(req.url).searchParams;
  const resolved = await resolveClient(q.get("client_id") ?? "", env, deps);
  if ("error" in resolved) return authorizeError(env, resolved.error);
  const client = resolved;

  let redirectUri = q.get("redirect_uri") ?? "";
  let pin = false;
  if (!redirectUri && client.redirect_uris.length === 1) redirectUri = client.redirect_uris[0];
  if (!validRedirectUri(redirectUri)) return authorizeError(env, "redirect_uri is missing or invalid");
  if (!redirectMatches(client.redirect_uris, redirectUri)) {
    if (client.kind === "static" && client.pin_redirect_on_first_use && client.redirect_uris.length === 0) pin = true;
    else return authorizeError(env, `redirect_uri ${redirectUri} is not registered for this client`);
  }

  const state = q.get("state") ?? undefined;
  const iss = issuer(env);
  if (q.get("response_type") !== "code") return redirectWith(redirectUri, { error: "unsupported_response_type", state, iss });
  const challenge = q.get("code_challenge") ?? "";
  if (q.get("code_challenge_method") !== "S256" || !/^[A-Za-z0-9_-]{43}$/.test(challenge))
    return redirectWith(redirectUri, { error: "invalid_request", error_description: "PKCE with S256 is required", state, iss });
  const resource = normalizeResource(env, q.get("resource"));
  if (!resource) return redirectWith(redirectUri, { error: "invalid_target", error_description: "unknown resource", state, iss });

  const areq: AuthRequest = {
    client_id: client.client_id,
    client_name: client.client_name,
    redirect_uri: redirectUri,
    state,
    code_challenge: challenge,
    scope: normalizeScope(q.get("scope")),
    resource,
    suggested_agent: suggestAgent(client.client_name, redirectUri),
    pin,
    exp: Math.floor(Date.now() / 1000) + CODE_TTL_S,
  };
  const reqId = await env.store.putAuthRequest(areq);
  if (env.ATALL_CONSENT_URL) {
    const u = new URL(env.ATALL_CONSENT_URL);
    u.searchParams.set("req", reqId);
    return new Response(null, { status: 302, headers: { Location: u.toString(), "Cache-Control": "no-store" } });
  }
  return html(consentPage(reqId, areq, { action: `${iss}/authorize` }));
}

/** GET /authorize/request?req=… : what the static consent page shows (no secrets; does not consume the request). */
export async function authorizeRequestInfo(req: Request, env: Env): Promise<Response> {
  const id = new URL(req.url).searchParams.get("req") ?? "";
  const r = id ? await env.store.peekAuthRequest(id) : null;
  if (!r) return json({ error: "This sign-in request expired or was already used." }, 404);
  let redirectHost = r.redirect_uri;
  try {
    redirectHost = new URL(r.redirect_uri).host || r.redirect_uri;
  } catch { /* keep raw */ }
  return json({
    req: id,
    client_name: r.client_name,
    redirect_host: redirectHost,
    pin: r.pin,
    ...(r.pin ? { redirect_uri: r.redirect_uri } : {}),
    suggested_agent: r.suggested_agent,
    agents: KNOWN_AGENTS,
  });
}

function wantsJson(req: Request): boolean {
  return (req.headers.get("Accept") ?? "").includes("application/json");
}

export async function authorizePost(req: Request, env: Env): Promise<Response> {
  const asJson = wantsJson(req);
  const form = await req.formData();
  const reqId = String(form.get("req") ?? "");
  const store = env.store;
  const pending = reqId ? await store.takeAuthRequest(reqId) : null;
  const fatal = (message: string, status = 400) => (asJson ? json({ error: message }, status) : html(errorPage(message), status));
  if (!pending) return fatal("This sign-in request expired or was already used.");
  const iss = issuer(env);
  const go = (params: Record<string, string | undefined>) => {
    const res = redirectWith(pending.redirect_uri, params);
    return asJson ? json({ redirect_to: res.headers.get("Location") }) : res;
  };

  if (form.get("decision") !== "approve") return go({ error: "access_denied", state: pending.state, iss });

  // Re-arm the request so a typo doesn't force the agent to start over.
  const rearm = async (error: string, status = 400) => {
    const newReq = await store.putAuthRequest(pending);
    return asJson ? json({ error, req: newReq }, status) : html(consentPage(newReq, pending, { error, action: `${iss}/authorize` }), status);
  };

  // Only wrong owner codes count: 5 per IP and 20 overall per 10 minutes.
  const ip = clientIp(req);
  if ((await store.count(`consentfail:${ip}`, 600)) >= 5 || (await store.count("consentfail:all", 600)) >= 20) {
    return fatal("Too many wrong owner codes. Wait 10 minutes and start again.", 429);
  }
  const code = String(form.get("owner_code") ?? "");
  if (!env.ATALL_OWNER_CODE || !(await safeEqual(code, env.ATALL_OWNER_CODE))) {
    await store.hit(`consentfail:${ip}`, 5, 600);
    await store.hit("consentfail:all", 20, 600);
    return rearm("That owner code is not right.", 401);
  }

  let agentId = String(form.get("agent_id") ?? "");
  if (agentId === "other") agentId = String(form.get("agent_other") ?? "").trim().toLowerCase();
  if (!AGENT_ID_RE.test(agentId) || agentId === "other") return rearm("Pick an agent, or type a lowercase id for Other.");

  if (pending.pin && !(await store.pinClientRedirect(pending.client_id, pending.redirect_uri))) {
    return fatal("This client changed during sign-in. Start again.");
  }

  const connId = newId("cn");
  await store.putConnection({ conn_id: connId, agent_id: agentId, client_id: pending.client_id, client_name: pending.client_name, kind: "oauth", created_at: nowIso() });
  const authCode = randomToken(32);
  await store.putCode(authCode, {
    client_id: pending.client_id,
    redirect_uri: pending.redirect_uri,
    code_challenge: pending.code_challenge,
    scope: pending.scope,
    resource: pending.resource,
    agent_id: agentId,
    conn_id: connId,
    exp: Math.floor(Date.now() / 1000) + CODE_TTL_S,
  });
  return go({ code: authCode, state: pending.state, iss });
}

// ---------------------------------------------------------------------------
// /token
// ---------------------------------------------------------------------------

async function readForm(req: Request): Promise<URLSearchParams> {
  const ct = req.headers.get("Content-Type") ?? "";
  if (ct.includes("application/x-www-form-urlencoded") || ct.includes("multipart/form-data")) {
    const fd = await req.formData();
    const out = new URLSearchParams();
    for (const [k, v] of fd.entries()) if (typeof v === "string") out.append(k, v);
    return out;
  }
  const text = await req.text();
  if (ct.includes("application/json")) {
    try {
      const o = JSON.parse(text) as Record<string, string>;
      return new URLSearchParams(Object.entries(o).map(([k, v]) => [k, String(v)]));
    } catch {
      return new URLSearchParams();
    }
  }
  return new URLSearchParams(text);
}

/** Authenticates the client per its registered method. Returns the client or an error response. */
async function authenticateClient(req: Request, p: URLSearchParams, env: Env, deps: Deps): Promise<Client | Response> {
  let id = p.get("client_id") ?? "";
  let secret = p.get("client_secret") ?? undefined;
  const basic = req.headers.get("Authorization");
  if (basic?.startsWith("Basic ")) {
    try {
      const [u, s] = atob(basic.slice(6)).split(":");
      id = decodeURIComponent(u);
      secret = decodeURIComponent(s ?? "");
    } catch {
      return oauthError("invalid_client", "bad Basic header", 401);
    }
  }
  const resolved = await resolveClient(id, env, deps);
  if ("error" in resolved) return oauthError("invalid_client", resolved.error, 401);
  if (resolved.token_endpoint_auth_method !== "none") {
    if (!secret || !resolved.client_secret_hash || !(await safeEqual(await sha256b64url(secret), resolved.client_secret_hash)))
      return oauthError("invalid_client", "client authentication failed", 401);
  }
  return resolved;
}

const LONG_ACCESS_AGENTS = new Set(["muse"]);
const LONG_ACCESS_TTL_S = 30 * 24 * 3600;

async function issueTokens(store: Store, rec: Omit<TokenRecord, "exp">) {
  const access = `atl_at_${randomToken(32)}`;
  const refresh = `atl_rt_${randomToken(32)}`;
  const now = Math.floor(Date.now() / 1000);
  // Meta Muse's custom-connector OAuth never refreshes (it doesn't request offline access), so a 1 h
  // token would drop it after an hour. Give agents in LONG_ACCESS_AGENTS a 30-day access token instead.
  const ttl = LONG_ACCESS_AGENTS.has(rec.agent_id) ? LONG_ACCESS_TTL_S : ACCESS_TTL_S;
  await store.putAccessToken(access, { ...rec, exp: now + ttl }, ttl);
  await store.putRefreshToken(refresh, { ...rec, exp: now + REFRESH_TTL_S });
  return json({ access_token: access, token_type: "Bearer", expires_in: ttl, refresh_token: refresh, scope: rec.scope });
}

export async function token(req: Request, env: Env, deps: Deps): Promise<Response> {
  const p = await readForm(req);
  const client = await authenticateClient(req, p, env, deps);
  if (client instanceof Response) return client;
  const store = env.store;
  const grant = p.get("grant_type");

  if (grant === "authorization_code") {
    const code = await store.takeCode(p.get("code") ?? "");
    if (!code || code.client_id !== client.client_id) return oauthError("invalid_grant", "code is invalid, expired, or already used");
    if ((p.get("redirect_uri") ?? code.redirect_uri) !== code.redirect_uri) return oauthError("invalid_grant", "redirect_uri mismatch");
    const verifier = p.get("code_verifier") ?? "";
    if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) return oauthError("invalid_grant", "code_verifier missing or malformed");
    const digest = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
    if (!(await safeEqual(digest, code.code_challenge))) return oauthError("invalid_grant", "PKCE verification failed");
    const resource = p.get("resource");
    if (resource && normalizeResource(env, resource) !== code.resource) return oauthError("invalid_target", "resource mismatch");
    return issueTokens(store, { conn_id: code.conn_id, agent_id: code.agent_id, client_id: client.client_id, scope: code.scope });
  }

  if (grant === "refresh_token") {
    const { rec, reusedConn } = await store.takeRefreshToken(p.get("refresh_token") ?? "");
    if (reusedConn) {
      await store.revokeConnection(reusedConn, "refresh token reused");
      return oauthError("invalid_grant", "refresh token was already used; this connection has been revoked");
    }
    if (!rec || rec.client_id !== client.client_id) return oauthError("invalid_grant", "refresh token is invalid or expired");
    const conn = await store.getConnection(rec.conn_id);
    if (!conn || conn.revoked) return oauthError("invalid_grant", "this connection was revoked");
    return issueTokens(store, { conn_id: rec.conn_id, agent_id: rec.agent_id, client_id: rec.client_id, scope: rec.scope });
  }

  return oauthError("unsupported_grant_type", "use authorization_code or refresh_token");
}

export async function revoke(req: Request, env: Env, deps: Deps): Promise<Response> {
  const p = await readForm(req);
  const client = await authenticateClient(req, p, env, deps);
  if (client instanceof Response) return client;
  const store = env.store;
  const t = p.get("token") ?? "";
  const rt = await store.deleteRefreshToken(t);
  if (rt && rt.client_id === client.client_id) await store.revokeConnection(rt.conn_id, "revoked by client");
  const at = await store.getAccessToken(t);
  if (at && at.client_id === client.client_id) await store.deleteAccessToken(t);
  return new Response(null, { status: 200 }); // RFC 7009: 200 even for unknown tokens
}

// ---------------------------------------------------------------------------
// Bearer verification for /mcp
// ---------------------------------------------------------------------------

export interface Caller {
  agent_id: string;
  conn_id: string;
  client_id: string;
  client_name: string;
}

export async function verifyBearer(req: Request, env: Env): Promise<Caller | { error: "missing" | "invalid" }> {
  const h = req.headers.get("Authorization") ?? "";
  if (!h.startsWith("Bearer ")) return { error: "missing" };
  const tok = h.slice(7).trim();
  const store = env.store;
  const rec = await store.getAccessToken(tok);
  if (!rec) return { error: "invalid" };
  const conn = await store.getConnection(rec.conn_id);
  if (!conn || conn.revoked) return { error: "invalid" };
  return { agent_id: rec.agent_id, conn_id: rec.conn_id, client_id: rec.client_id, client_name: conn.client_name };
}

export function unauthorized(env: Env, kind: "missing" | "invalid"): Response {
  const meta = `${issuer(env)}/.well-known/oauth-protected-resource/mcp`;
  const err = kind === "invalid" ? `, error="invalid_token", error_description="token is invalid, expired, or revoked"` : "";
  return json(
    { jsonrpc: "2.0", id: null, error: { code: -32001, message: "Authentication required: sign in to Atall with OAuth" } },
    401,
    { "WWW-Authenticate": `Bearer realm="atall", resource_metadata="${meta}", scope="${SCOPE}"${err}` },
  );
}

/** Owner-minted bearer token for agents that use a custom header instead of OAuth. */
export async function createHeaderToken(env: Env, agentId: string, label?: string) {
  if (!AGENT_ID_RE.test(agentId)) throw new Error("invalid agent_id");
  const store = env.store;
  const connId = newId("cn");
  await store.putConnection({ conn_id: connId, agent_id: agentId, client_id: "header", client_name: label?.slice(0, 100) || `${agentId} (header token)`, kind: "header", created_at: nowIso() });
  const tok = `atl_hdr_${randomToken(32)}`;
  await store.putAccessToken(tok, { conn_id: connId, agent_id: agentId, client_id: "header", scope: SCOPE, exp: Math.floor(Date.now() / 1000) + HEADER_TOKEN_TTL_S }, HEADER_TOKEN_TTL_S);
  return { token: tok, conn_id: connId, agent_id: agentId, expires_in: HEADER_TOKEN_TTL_S };
}
