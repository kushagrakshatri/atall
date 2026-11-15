// Supabase-specific behaviour that the Worker didn't have: the function base path, the external
// consent page (because *.supabase.co serves text/html as text/plain), env resolution, and the
// PostgREST transport. Runs against whichever store tests/helpers.ts selects.

import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import { routePath } from "../supabase/functions/atall/src/app.ts";
import { externalConsentPageHtml } from "../supabase/functions/atall/src/pages.ts";
import { devPageHtml } from "../supabase/functions/atall/src/admin.ts";
import { resolvePublicUrl } from "../supabase/functions/atall/src/server.ts";
import { postgrestRpc, RpcError } from "../supabase/functions/atall/src/store_pg.ts";
import { ADMIN, BASE, freshEnv, newClient, ORIGIN, OWNER, testEnv, workerFetch } from "./helpers.ts";
import { closePg } from "./pg.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false };
const CONSENT = "https://atall-ui.example.dev/consent.html";

describe({ name: "supabase", ...OPTS }, () => {
  afterAll(async () => {
    await closePg();
  });

  describe("base path and discovery", () => {
    beforeEach(async () => {
      await freshEnv();
    });

    it("issuer, endpoints and resource all carry /functions/v1/atall", async () => {
      const c = newClient();
      const d = await c.discover();
      expect(d.prm.resource).toBe(`${BASE}/mcp`);
      expect(d.as.issuer).toBe(BASE);
      for (const k of ["authorization_endpoint", "token_endpoint", "registration_endpoint", "revocation_endpoint"]) expect(d.as[k].startsWith(`${BASE}/`), k).toBe(true);
      expect(d.wwwAuthenticate).toContain(`resource_metadata="${BASE}/.well-known/oauth-protected-resource/mcp"`);
    });

    it("root-level RFC 8414 / OIDC path-insertion URLs are not ours; the path-append OIDC URL answers", async () => {
      const c = newClient();
      const d = await c.discover();
      expect(d.asTried.map((x) => x.status)).toEqual([404, 404, 200]);
      expect(d.asUrl).toBe(`${BASE}/.well-known/openid-configuration`);
      const f = workerFetch();
      expect((await f(`${BASE}/.well-known/oauth-authorization-server`)).status).toBe(200);
      expect((await f(`${ORIGIN}/.well-known/oauth-protected-resource/functions/v1/atall/mcp`)).status).toBe(404);
    });

    it("routePath accepts /atall/x (gateway), /functions/v1/atall/x (local) and a root proxy", () => {
      const env = testEnv();
      expect(routePath("/atall/mcp", env)).toBe("/mcp");
      expect(routePath("/functions/v1/atall/.well-known/openid-configuration", env)).toBe("/.well-known/openid-configuration");
      expect(routePath("/atall", env)).toBe("/");
      expect(routePath("/atallx/mcp", env)).toBe("/atallx/mcp");
      const proxied = { ...env, ATALL_PUBLIC_URL: "https://relay.example.com" };
      expect(routePath("/mcp", proxied)).toBe("/mcp");
      expect(routePath("/atall/mcp", proxied)).toBe("/mcp");
    });

    it("works behind a root proxy when ATALL_PUBLIC_URL has no path (issuer at the root)", async () => {
      await freshEnv({ ATALL_PUBLIC_URL: "https://relay.example.com" });
      const f = workerFetch();
      const as = await (await f("https://relay.example.com/atall/.well-known/oauth-authorization-server")).json();
      expect(as.issuer).toBe("https://relay.example.com");
      expect(as.token_endpoint).toBe("https://relay.example.com/token");
    });

    it("resolves the public URL from Supabase's injected env", () => {
      const env = (o: Record<string, string>) => (k: string) => o[k];
      expect(resolvePublicUrl(env({ SUPABASE_URL: "https://ggmvzyyuksewkgbokrhe.supabase.co" }))).toBe("https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall");
      expect(resolvePublicUrl(env({ SUPABASE_URL: "http://kong:8000" }))).toBe("http://127.0.0.1:54321/functions/v1/atall");
      expect(resolvePublicUrl(env({ SUPABASE_URL: "https://x.supabase.co", ATALL_PUBLIC_URL: "https://relay.example.com/" }))).toBe("https://relay.example.com");
    });

    it("the inline consent form posts to the function path, and /dev calls the admin API under it", async () => {
      const c = newClient();
      await c.discover();
      await c.register();
      const r = await workerFetch()(
        `${BASE}/authorize?response_type=code&client_id=${c.client.client_id}&redirect_uri=${encodeURIComponent(c.redirectUri)}&code_challenge=${"a".repeat(43)}&code_challenge_method=S256`,
      );
      expect(await r.text()).toContain(`action="${BASE}/authorize"`);
      const dev = await (await workerFetch()(`${BASE}/dev`)).text();
      expect(dev).toContain(`const API="${BASE}"`);
      expect(dev).not.toContain(ADMIN);
    });
  });

  describe("external consent page (ATALL_CONSENT_URL)", () => {
    beforeEach(async () => {
      await freshEnv({ ATALL_CONSENT_URL: CONSENT });
    });

    it("GET /authorize redirects to the static page; the full flow completes over the JSON API", async () => {
      const c = newClient({ name: "ChatGPT" });
      await c.discover();
      await c.register();
      const a = await c.authorize({ ownerCode: OWNER, agentId: "kdot" });
      expect(c.consentUrl!.startsWith(`${CONSENT}?req=ar_`)).toBe(true);
      expect(c.consentInfo.client_name).toBe("ChatGPT");
      expect(c.consentInfo.suggested_agent).toBe("kdot");
      expect(JSON.stringify(c.consentInfo)).not.toContain(OWNER);
      expect(a.status).toBe(302);
      expect(a.params.iss).toBe(BASE);
      const t = await c.exchange(a.params.code);
      expect(t.status).toBe(200);
      expect((await c.callTool("atall_inbox")).body.result.structuredContent.agent_id).toBe("kdot");
    });

    it("a wrong owner code returns a re-armed request id; deny returns access_denied", async () => {
      const c = newClient();
      await c.discover();
      await c.register();
      const bad = await c.authorize({ ownerCode: "nope", agentId: "kdot" });
      expect(bad.status).toBe(401);
      expect(bad.json.error).toContain("owner code is not right");
      expect(bad.json.req).toMatch(/^ar_/);
      const f = workerFetch();
      const retry = await f(`${BASE}/authorize`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
        body: new URLSearchParams({ req: bad.json.req, decision: "approve", agent_id: "kdot", owner_code: OWNER }),
      });
      const body = await retry.json();
      expect(new URL(body.redirect_to).searchParams.get("code")).toBeTruthy();
      const deny = await c.authorize({ decision: "deny" });
      expect(deny.params.error).toBe("access_denied");
    });

    it("problems on GET /authorize go to the static page as ?error=, never as HTML", async () => {
      const r = await workerFetch()(`${BASE}/authorize?client_id=cl_unknown&response_type=code`, { redirect: "manual" });
      expect(r.status).toBe(302);
      const loc = new URL(r.headers.get("Location")!);
      expect(loc.origin + loc.pathname).toBe(CONSENT);
      expect(loc.searchParams.get("error")).toBe("unknown client_id");
    });

    it("CORS on /authorize and /admin is granted to the consent page's origin only", async () => {
      const f = workerFetch();
      const ok = await f(`${BASE}/authorize`, { method: "OPTIONS", headers: { Origin: "https://atall-ui.example.dev" } });
      expect(ok.headers.get("Access-Control-Allow-Origin")).toBe("https://atall-ui.example.dev");
      const evil = await f(`${BASE}/admin/state`, { method: "OPTIONS", headers: { Origin: "https://evil.example" } });
      expect(evil.headers.get("Access-Control-Allow-Origin")).toBeNull();
      const st = await f(`${BASE}/admin/state`, { headers: { Origin: "https://atall-ui.example.dev", Authorization: `Bearer ${ADMIN}` } });
      expect(st.status).toBe(200);
      expect(st.headers.get("Access-Control-Allow-Origin")).toBe("https://atall-ui.example.dev");
    });

    it("the static pages bake in the API base and lock connect-src to it", () => {
      const consent = externalConsentPageHtml(BASE);
      expect(consent).toContain(`const API = "${BASE}"`);
      expect(consent).toContain(`connect-src ${ORIGIN}`);
      expect(consent).not.toContain("location.search.get(\"api\")");
      const dev = devPageHtml(BASE);
      expect(dev).toContain(`const API="${BASE}"`);
    });
  });

  describe("PostgREST transport", () => {
    it("POSTs {p} to /rest/v1/rpc/<fn> with the service key and maps errors", async () => {
      const seen: { url: string; headers: Headers; body: string }[] = [];
      const fake = (async (url: string, init: RequestInit) => {
        seen.push({ url, headers: new Headers(init.headers), body: String(init.body) });
        if (url.endsWith("atall_create_task")) return new Response(JSON.stringify({ code: "23505", message: "duplicate key" }), { status: 409 });
        if (url.endsWith("atall_get_task")) return new Response("null", { status: 200 });
        return new Response(JSON.stringify({ ok: true }), { status: 200 });
      }) as unknown as typeof fetch;
      const jwt = postgrestRpc("https://ref.supabase.co/", "eyJhbGciOi.fake.jwt", fake);
      expect(await jwt("atall_list_tasks", { a: 1 })).toEqual({ ok: true });
      expect(seen[0].url).toBe("https://ref.supabase.co/rest/v1/rpc/atall_list_tasks");
      expect(seen[0].headers.get("apikey")).toBe("eyJhbGciOi.fake.jwt");
      expect(seen[0].headers.get("Authorization")).toBe("Bearer eyJhbGciOi.fake.jwt");
      expect(JSON.parse(seen[0].body)).toEqual({ p: { a: 1 } });
      expect(await jwt("atall_get_task", {})).toBeNull();
      const err = (await jwt("atall_create_task", {}).catch((e) => e)) as RpcError;
      expect(err).toBeInstanceOf(RpcError);
      expect(err.code).toBe("23505");
      const secret = postgrestRpc("https://ref.supabase.co", "sb_secret_fake", fake);
      await secret("atall_list_tasks", {});
      expect(seen.at(-1)!.headers.get("Authorization")).toBeNull();
      expect(seen.at(-1)!.headers.get("apikey")).toBe("sb_secret_fake");
    });
  });
});
