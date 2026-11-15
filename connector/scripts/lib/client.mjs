// @ts-self-types="./client.d.mts"
// A tiny MCP + OAuth client that walks the same path ChatGPT, Claude and Grok do:
// 401 → protected-resource metadata → authorization-server metadata → dynamic client
// registration → /authorize with PKCE (S256) → consent form → code (+ RFC 9207 iss)
// → /token → MCP calls with the bearer token.
// Used by the Deno test suite (in-process) and by scripts/smoke.mjs (Node, over HTTP).
// Base URL may carry a path (https://<ref>.supabase.co/functions/v1/atall); every endpoint is taken
// from the discovered metadata, never rebuilt from the origin.

export const MODERN = "2026-07-28";

const b64url = (u8) => btoa(String.fromCharCode(...u8)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export async function pkcePair() {
  const raw = new Uint8Array(32);
  crypto.getRandomValues(raw);
  const verifier = b64url(raw);
  const challenge = b64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  return { verifier, challenge };
}

function headerEncode(v) {
  return /^[\x20-\x7e]*$/.test(v) && v.trim() === v && !/^=\?base64\?.*\?=$/.test(v) ? v : `=?base64?${btoa(String.fromCharCode(...new TextEncoder().encode(v)))}?=`;
}

export class AtallClient {
  /** @param {{ base: string, fetch?: typeof fetch, clientName?: string, redirectUri?: string }} opts */
  constructor(opts) {
    this.base = opts.base.replace(/\/+$/, "");
    this.fetch = opts.fetch ?? globalThis.fetch.bind(globalThis);
    this.clientName = opts.clientName ?? "Atall test client";
    this.redirectUri = opts.redirectUri ?? "http://127.0.0.1:9/callback";
    this.nextId = 1;
    this.tokens = null;
    this.client = null;
    this.as = null;
  }

  async getJson(url) {
    const r = await this.fetch(url, { headers: { Accept: "application/json" } });
    if (!r.ok) throw new Error(`GET ${url} → ${r.status}`);
    return r.json();
  }

  /** Step 1-3: unauthenticated call, follow WWW-Authenticate to the metadata documents. */
  async discover() {
    const r = await this.fetch(`${this.base}/mcp`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 0, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: this.clientName, version: "1" } } }),
    });
    const www = r.headers.get("WWW-Authenticate") ?? "";
    const m = /resource_metadata="([^"]+)"/.exec(www);
    if (r.status !== 401 || !m) throw new Error(`expected 401 with resource_metadata, got ${r.status} ${www}`);
    this.prm = await this.getJson(m[1]);
    const issuer = this.prm.authorization_servers[0];
    // MCP authorization spec order for an issuer with a path: RFC 8414 path-insertion, OIDC
    // path-insertion, then OIDC path-append. On Supabase only the path-append form is reachable
    // (the project root isn't ours), so the first two 404 and the third answers.
    const iu = new URL(issuer);
    const path = iu.pathname.replace(/\/+$/, "");
    const candidates = path
      ? [`${iu.origin}/.well-known/oauth-authorization-server${path}`, `${iu.origin}/.well-known/openid-configuration${path}`, `${iu.origin}${path}/.well-known/openid-configuration`]
      : [`${iu.origin}/.well-known/oauth-authorization-server`, `${iu.origin}/.well-known/openid-configuration`];
    this.asTried = [];
    this.as = null;
    for (const url of candidates) {
      const r = await this.fetch(url, { headers: { Accept: "application/json" } });
      this.asTried.push({ url, status: r.status });
      if (r.ok) {
        this.as = await r.json();
        this.asUrl = url;
        break;
      }
    }
    if (!this.as) throw new Error(`no authorization server metadata found: ${JSON.stringify(this.asTried)}`);
    if (this.as.issuer !== issuer) throw new Error("issuer mismatch in AS metadata");
    return { status: r.status, wwwAuthenticate: www, prm: this.prm, as: this.as, asUrl: this.asUrl, asTried: this.asTried };
  }

  /** Step 4: RFC 7591 dynamic client registration. */
  async register(extra = {}) {
    const r = await this.fetch(this.as.registration_endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ client_name: this.clientName, redirect_uris: [this.redirectUri], token_endpoint_auth_method: "none", grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], application_type: "native", ...extra }),
    });
    const body = await r.json();
    if (r.status !== 201) throw new Error(`register → ${r.status} ${JSON.stringify(body)}`);
    this.client = body;
    return body;
  }

  /** Step 5-6: /authorize with PKCE, fill the consent form, return the redirect params. */
  async authorize({ ownerCode, agentId, agentOther, decision = "approve", clientId, verifierOverride } = {}) {
    const { verifier, challenge } = await pkcePair();
    this.verifier = verifierOverride ?? verifier;
    const state = b64url(crypto.getRandomValues(new Uint8Array(12)));
    const u = new URL(this.as.authorization_endpoint);
    u.search = new URLSearchParams({
      response_type: "code",
      client_id: clientId ?? this.client.client_id,
      redirect_uri: this.redirectUri,
      code_challenge: challenge,
      code_challenge_method: "S256",
      state,
      scope: "atall.agent offline_access",
      resource: this.prm.resource,
    }).toString();
    const page = await this.fetch(u.toString(), { redirect: "manual" });
    const authorizeUrl = this.as.authorization_endpoint.split("?")[0];
    const form = (req) => new URLSearchParams({ req, agent_id: agentId ?? "other", agent_other: agentOther ?? "", owner_code: ownerCode ?? "", decision });
    let loc;
    if (page.status === 302 && page.headers.get("Location") && !page.headers.get("Location").startsWith(this.redirectUri)) {
      // External consent page mode (ATALL_CONSENT_URL): drive the JSON API the static page uses.
      const consent = new URL(page.headers.get("Location"), u);
      const req = consent.searchParams.get("req");
      if (!req) throw new Error(`authorize GET → 400 (consent page error: ${consent.searchParams.get("error")})`);
      this.consentUrl = consent.toString();
      const info = await this.fetch(`${authorizeUrl}/request?req=${encodeURIComponent(req)}`, { headers: { Accept: "application/json" } });
      this.consentInfo = await info.json();
      if (info.status !== 200) throw new Error(`authorize request info → ${info.status}`);
      const res = await this.fetch(authorizeUrl, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body: form(req).toString(),
      });
      const body = await res.json();
      if (!body.redirect_to) return { status: res.status, html: body.error ?? "", json: body };
      loc = new URL(body.redirect_to);
    } else {
      const htmlText = await page.text();
      if (page.status !== 200) throw new Error(`authorize GET → ${page.status}: ${htmlText.slice(0, 300)}`);
      const req = /name="req" value="([^"]+)"/.exec(htmlText)?.[1];
      if (!req) throw new Error("consent form not found");
      const res = await this.fetch(authorizeUrl, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: form(req).toString(),
        redirect: "manual",
      });
      if (res.status !== 302) return { status: res.status, html: await res.text() };
      loc = new URL(res.headers.get("Location"));
    }
    const params = Object.fromEntries(loc.searchParams);
    if (params.state !== state) throw new Error("state mismatch");
    if (params.iss !== undefined && params.iss !== this.as.issuer) throw new Error("iss mismatch (RFC 9207)");
    return { status: 302, location: loc.toString(), params };
  }

  async tokenRequest(params) {
    const r = await this.fetch(this.as.token_endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: new URLSearchParams(params).toString(),
    });
    return { status: r.status, body: await r.json() };
  }

  /** Step 7: code → tokens. */
  async exchange(code) {
    const res = await this.tokenRequest({
      grant_type: "authorization_code",
      code,
      redirect_uri: this.redirectUri,
      client_id: this.client.client_id,
      code_verifier: this.verifier,
      resource: this.prm.resource,
    });
    if (res.status === 200) this.tokens = res.body;
    return res;
  }

  async refresh(refreshToken = this.tokens?.refresh_token) {
    const res = await this.tokenRequest({ grant_type: "refresh_token", refresh_token: refreshToken, client_id: this.client.client_id });
    if (res.status === 200) this.tokens = res.body;
    return res;
  }

  /** One-shot convenience: discover → register → authorize → exchange. */
  async connect({ ownerCode, agentId, agentOther }) {
    await this.discover();
    await this.register();
    const a = await this.authorize({ ownerCode, agentId, agentOther });
    if (a.status !== 302 || !a.params.code) throw new Error(`authorize failed: ${a.status} ${JSON.stringify(a.params ?? a.html?.slice(0, 200))}`);
    const t = await this.exchange(a.params.code);
    if (t.status !== 200) throw new Error(`token exchange failed: ${JSON.stringify(t.body)}`);
    return t.body;
  }

  /** Raw MCP POST. modern=true sends 2026-07-28 headers and _meta; false sends 2025-06-18. */
  async rpc(method, params = {}, { modern = true, headers = {}, token, notification = false } = {}) {
    const h = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
    const tok = token === undefined ? this.tokens?.access_token : token;
    if (tok) h.Authorization = `Bearer ${tok}`;
    let p = params;
    if (modern) {
      h["MCP-Protocol-Version"] = MODERN;
      h["Mcp-Method"] = method;
      const name = method === "resources/read" ? params.uri : params.name;
      if (["tools/call", "resources/read", "prompts/get"].includes(method) && name !== undefined) h["Mcp-Name"] = headerEncode(String(name));
      p = {
        ...params,
        _meta: {
          "io.modelcontextprotocol/protocolVersion": MODERN,
          "io.modelcontextprotocol/clientInfo": { name: this.clientName, version: "1" },
          "io.modelcontextprotocol/clientCapabilities": {},
          ...(params._meta ?? {}),
        },
      };
    } else {
      h["MCP-Protocol-Version"] = "2025-06-18";
    }
    Object.assign(h, headers);
    for (const [k, v] of Object.entries(h)) if (v === null) delete h[k];
    const msg = notification ? { jsonrpc: "2.0", method, params: p } : { jsonrpc: "2.0", id: this.nextId++, method, params: p };
    const r = await this.fetch(`${this.base}/mcp`, { method: "POST", headers: h, body: JSON.stringify(msg) });
    const text = await r.text();
    return { status: r.status, headers: r.headers, body: text ? JSON.parse(text) : null };
  }

  async callTool(name, args = {}, opts = {}) {
    return this.rpc("tools/call", { name, arguments: args }, opts);
  }
}

/** Owner-side helper for the admin API. */
export class AtallAdmin {
  constructor({ base, token, fetch: f }) {
    this.base = base.replace(/\/+$/, "");
    this.token = token;
    this.fetch = f ?? globalThis.fetch.bind(globalThis);
  }
  async req(method, path, body) {
    const r = await this.fetch(`${this.base}${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: r.status, body: await r.json() };
  }
  state() {
    return this.req("GET", "/admin/state");
  }
  createTask(t) {
    return this.req("POST", "/admin/tasks", t);
  }
  createClient(c) {
    return this.req("POST", "/admin/clients", c);
  }
  createHeaderToken(agent_id, label) {
    return this.req("POST", "/admin/tokens", { agent_id, label });
  }
  revoke(connId) {
    return this.req("POST", `/admin/connections/${connId}/revoke`);
  }
  reset() {
    return this.req("POST", "/admin/reset");
  }
}
