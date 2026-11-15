import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import { pkcePair } from "../scripts/lib/client.mjs";
import { ADMIN, BASE, OWNER, ORIGIN, admin, connected, newClient, workerFetch, freshEnv, gatewayRewrite, testEnv } from "./helpers.ts";
import { createHandler, envFrom } from "../supabase/functions/atall/src/server.ts";
import { closePg } from "./pg.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false };

describe({ name: "oauth", ...OPTS }, () => {
beforeEach(async () => {
  await freshEnv();
});
afterAll(async () => {
  await closePg();
});

describe("OAuth discovery", () => {
  it("serves RFC 9728 protected-resource metadata and RFC 8414 AS metadata", async () => {
    const f = workerFetch();
    const prm = await (await f(`${BASE}/.well-known/oauth-protected-resource/mcp`)).json();
    expect(prm.resource).toBe(`${BASE}/mcp`);
    expect(prm.authorization_servers).toEqual([BASE]);
    const as = await (await f(`${BASE}/.well-known/oauth-authorization-server`)).json();
    expect(as.issuer).toBe(BASE);
    expect(as.code_challenge_methods_supported).toEqual(["S256"]);
    expect(as.scopes_supported).toContain("offline_access");
    expect(as.registration_endpoint).toBe(`${BASE}/register`);
    expect(as.client_id_metadata_document_supported).toBe(true);
    expect(as.authorization_response_iss_parameter_supported).toBe(true);
    const oidc = await (await f(`${BASE}/.well-known/openid-configuration`)).json();
    expect(oidc.token_endpoint).toBe(`${BASE}/token`);
  });

  it("answers an unauthenticated MCP call with 401 + WWW-Authenticate pointing at the metadata", async () => {
    const c = newClient();
    const d = await c.discover();
    expect(d.status).toBe(401);
    expect(d.wwwAuthenticate).toContain(`resource_metadata="${BASE}/.well-known/oauth-protected-resource/mcp"`);
    expect(d.wwwAuthenticate).toContain('scope="atall.agent"');
  });
});

describe("Dynamic client registration + PKCE", () => {
  it("runs the full flow: register → consent → code with iss → tokens", async () => {
    const c = newClient({ name: "ChatGPT" });
    await c.discover();
    const reg = await c.register();
    expect(reg.client_id).toMatch(/^cl_/);
    expect(reg.token_endpoint_auth_method).toBe("none");
    const a = await c.authorize({ ownerCode: OWNER, agentId: "kdot" });
    expect(a.status).toBe(302);
    expect(a.params.code).toBeTruthy();
    expect(a.params.iss).toBe(BASE); // RFC 9207
    const t = await c.exchange(a.params.code);
    expect(t.status).toBe(200);
    expect(t.body.token_type).toBe("Bearer");
    expect(t.body.access_token).toMatch(/^atl_at_/);
    expect(t.body.refresh_token).toMatch(/^atl_rt_/);
    expect(t.body.scope).toBe("atall.agent offline_access");
  });

  it("suggests the agent from the client name on the consent page", async () => {
    const c = newClient({ name: "Claude" });
    await c.discover();
    await c.register();
    const { challenge } = await pkcePair();
    const u = `${BASE}/authorize?response_type=code&client_id=${c.client.client_id}&redirect_uri=${encodeURIComponent(c.redirectUri)}&code_challenge=${challenge}&code_challenge_method=S256&state=x`;
    const html = await (await workerFetch()(u)).text();
    expect(html).toContain('value="duet" selected');
    expect(html).not.toContain(OWNER);
  });

  it("rejects a wrong owner code and re-shows the form without issuing a code", async () => {
    const c = newClient();
    await c.discover();
    await c.register();
    const a = await c.authorize({ ownerCode: "wrong", agentId: "kdot" });
    expect(a.status).toBe(401);
    expect(a.html).toContain("owner code is not right");
    expect(a.html).toContain('name="req"');
  });

  it("returns access_denied (with iss) when Kush presses Deny", async () => {
    const c = newClient();
    await c.discover();
    await c.register();
    const a = await c.authorize({ decision: "deny" });
    expect(a.status).toBe(302);
    expect(a.params.error).toBe("access_denied");
    expect(a.params.iss).toBe(BASE);
  });

  it("refuses a wrong PKCE verifier and a reused code", async () => {
    const c = newClient();
    await c.discover();
    await c.register();
    const a = await c.authorize({ ownerCode: OWNER, agentId: "grok", verifierOverride: "x".repeat(43) });
    const bad = await c.exchange(a.params.code);
    expect(bad.status).toBe(400);
    expect(bad.body.error).toBe("invalid_grant");
    // the code was consumed by the failed attempt; a retry with the right verifier can't reuse it
    const reuse = await c.exchange(a.params.code);
    expect(reuse.body.error).toBe("invalid_grant");

    const a2 = await c.authorize({ ownerCode: OWNER, agentId: "grok" });
    expect((await c.exchange(a2.params.code)).status).toBe(200);
    expect((await c.exchange(a2.params.code)).body.error).toBe("invalid_grant");
  });

  it("requires S256 and a registered redirect_uri", async () => {
    const c = newClient();
    await c.discover();
    await c.register();
    const f = workerFetch();
    const plain = await f(`${BASE}/authorize?response_type=code&client_id=${c.client.client_id}&redirect_uri=${encodeURIComponent(c.redirectUri)}&code_challenge=abc&code_challenge_method=plain&state=s`, { redirect: "manual" });
    expect(plain.status).toBe(302);
    expect(new URL(plain.headers.get("Location")!).searchParams.get("error")).toBe("invalid_request");
    const { challenge } = await pkcePair();
    const other = await f(`${BASE}/authorize?response_type=code&client_id=${c.client.client_id}&redirect_uri=${encodeURIComponent("https://evil.example/cb")}&code_challenge=${challenge}&code_challenge_method=S256`, { redirect: "manual" });
    expect(other.status).toBe(400); // error page, never a redirect to an unregistered URI
    expect(await other.text()).toContain("not registered");
  });

  it("rejects dangerous redirect URIs at registration", async () => {
    const f = workerFetch();
    for (const uri of ["javascript:alert(1)", "http://example.com/cb", "https://ok.example/cb#frag"]) {
      const r = await f(`${BASE}/register`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ redirect_uris: [uri] }) });
      expect(r.status, uri).toBe(400);
    }
  });

  it("supports confidential DCR clients (client_secret_basic)", async () => {
    const c = newClient();
    await c.discover();
    const reg = await c.register({ token_endpoint_auth_method: "client_secret_basic" });
    expect(reg.client_secret).toBeTruthy();
    const a = await c.authorize({ ownerCode: OWNER, agentId: "duet" });
    const f = workerFetch();
    const body = new URLSearchParams({ grant_type: "authorization_code", code: a.params.code, redirect_uri: c.redirectUri, code_verifier: c.verifier });
    const noSecret = await f(`${BASE}/token`, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ ...Object.fromEntries(body), client_id: reg.client_id }) });
    expect(noSecret.status).toBe(401);
    const a2 = await c.authorize({ ownerCode: OWNER, agentId: "duet" });
    body.set("code", a2.params.code);
    body.set("code_verifier", c.verifier);
    const ok = await f(`${BASE}/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Authorization: `Basic ${btoa(`${reg.client_id}:${reg.client_secret}`)}` },
      body,
    });
    expect(ok.status).toBe(200);
  });
});

describe("Refresh tokens", () => {
  it("rotates refresh tokens and revokes the connection on reuse", async () => {
    const c = await connected("kdot");
    const first = c.tokens.refresh_token;
    const r1 = await c.refresh();
    expect(r1.status).toBe(200);
    expect(r1.body.refresh_token).not.toBe(first);
    expect((await c.rpc("tools/list")).status).toBe(200);

    const reuse = await c.refresh(first);
    expect(reuse.status).toBe(400);
    expect(reuse.body.error_description).toContain("revoked");
    // the whole connection is now dead, including the fresh access token
    expect((await c.rpc("tools/list")).status).toBe(401);
    expect((await c.refresh()).body.error).toBe("invalid_grant");
  });

  it("revokes a connection through the RFC 7009 endpoint", async () => {
    const c = await connected("grok", "Grok");
    const r = await workerFetch()(`${BASE}/revoke`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token: c.tokens.refresh_token, client_id: c.client.client_id }),
    });
    expect(r.status).toBe(200);
    expect((await c.rpc("tools/list")).status).toBe(401);
  });
});

describe("Client ID Metadata Documents (CIMD)", () => {
  const CID = "https://agent.example/oauth/client.json";
  const doc = { client_id: CID, client_name: "Example Agent", redirect_uris: ["https://agent.example/cb"], token_endpoint_auth_method: "none" };

  it("fetches the document and completes the flow with the URL as client_id", async () => {
    let fetched = 0;
    const c = newClient({
      redirectUri: "https://agent.example/cb",
      outbound: async (url, init) => {
        fetched++;
        expect(String(url)).toBe(CID);
        expect(init?.redirect).toBe("manual");
        return new Response(JSON.stringify(doc), { headers: { "Content-Type": "application/json" } });
      },
    });
    await c.discover();
    c.client = { client_id: CID };
    const a = await c.authorize({ ownerCode: OWNER, agentId: "muse", clientId: CID });
    expect(a.status).toBe(302);
    const t = await c.exchange(a.params.code);
    expect(t.status).toBe(200);
    expect(fetched).toBe(1); // second lookup (token endpoint) is served from cache
  });

  it("rejects a document whose client_id doesn't match, and private hosts", async () => {
    const bad = newClient({ outbound: async () => new Response(JSON.stringify({ ...doc, client_id: "https://other.example/x" })) });
    await bad.discover();
    const { challenge } = await pkcePair();
    const r = await workerFetch(async () => new Response(JSON.stringify({ ...doc, client_id: "https://other.example/x" })))(
      `${BASE}/authorize?response_type=code&client_id=${encodeURIComponent("https://agent2.example/c.json")}&redirect_uri=${encodeURIComponent("https://agent.example/cb")}&code_challenge=${challenge}&code_challenge_method=S256`,
    );
    expect(r.status).toBe(400);
    expect(await r.text()).toContain("does not match");
    const p = await workerFetch()(
      `${BASE}/authorize?response_type=code&client_id=${encodeURIComponent("https://127.0.0.1/c.json")}&redirect_uri=${encodeURIComponent("https://agent.example/cb")}&code_challenge=${challenge}&code_challenge_method=S256`,
    );
    expect(p.status).toBe(400);
    expect(await p.text()).toContain("not allowed");
  });
});

describe("Static clients and header tokens (for agents that take a fixed Client ID or headers)", () => {
  it("pins a static client's redirect_uri on the first approved sign-in", async () => {
    const created = await admin().createClient({ client_name: "Grok Bot" });
    expect(created.status).toBe(201);
    expect(created.body.pin_redirect_on_first_use).toBe(true);
    const c = newClient({ redirectUri: "https://grok.example/oauth/callback" });
    await c.discover();
    c.client = { client_id: created.body.client_id };
    const a = await c.authorize({ ownerCode: OWNER, agentId: "grok", clientId: created.body.client_id });
    expect(a.status).toBe(302);
    expect((await c.exchange(a.params.code)).status).toBe(200);
    // now pinned: a different redirect is refused
    const c2 = newClient({ redirectUri: "https://evil.example/cb" });
    await c2.discover();
    await expect(c2.authorize({ ownerCode: OWNER, agentId: "grok", clientId: created.body.client_id })).rejects.toThrow(/400/);
  });

  it("mints an owner header token that authenticates MCP calls as its agent", async () => {
    const t = await admin().createHeaderToken("muse", "Meta Muse header");
    expect(t.status).toBe(201);
    const c = newClient();
    const r = await c.callTool("atall_ping_write", { note: "header" }, { token: t.body.token });
    expect(r.status).toBe(200);
    expect(r.body.result.structuredContent.from_agent).toBe("muse");
  });
});

describe("Admin API", () => {
  it("requires the admin token", async () => {
    const f = workerFetch();
    expect((await f(`${BASE}/admin/state`)).status).toBe(401);
    expect((await f(`${BASE}/admin/state`, { headers: { Authorization: "Bearer nope" } })).status).toBe(401);
    expect((await f(`${BASE}/admin/state`, { headers: { Authorization: `Bearer ${ADMIN}` } })).status).toBe(200);
  });

  it("serves the dev page without embedding any secret", async () => {
    const html = await (await workerFetch()(`${BASE}/dev`)).text();
    expect(html).toContain("Atall connector");
    expect(html).not.toContain(ADMIN);
    expect(html).not.toContain(OWNER);
  });

  it("is reachable through the deployed default export", async () => {
    // Same wiring as supabase/functions/atall/index.ts: env from Supabase's injected variables.
    const injected: Record<string, string> = { SUPABASE_URL: ORIGIN, ATALL_OWNER_CODE: OWNER, ATALL_ADMIN_TOKEN: ADMIN };
    const handler = createHandler(envFrom((k) => injected[k], testEnv().store), () => ({ waitUntil: () => {} }));
    const r: Response = await handler(gatewayRewrite(new Request(`${BASE}/`)));
    const body = await r.json();
    expect(body.mcp_endpoint).toBe(`${BASE}/mcp`);
  });
});
});
