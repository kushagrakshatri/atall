import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import { BASE, admin, connected, newClient, workerFetch, freshEnv } from "./helpers.ts";
import { closePg } from "./pg.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false };

describe({ name: "mcp", ...OPTS }, () => {
beforeEach(async () => {
  await freshEnv();
});
afterAll(async () => {
  await closePg();
});

const WRITE_TOOLS = ["atall_claim", "atall_progress", "atall_post_result", "atall_ask", "atall_request_approval", "atall_manifest", "atall_ping_write"];
const READ_TOOLS = ["atall_inbox", "atall_get_task"];
/** tools/list order (stable; ChatGPT snapshots it). The 8 C1 task tools plus the C0 write test. */
const ALL_TOOLS = ["atall_inbox", "atall_get_task", "atall_claim", "atall_progress", "atall_post_result", "atall_ask", "atall_request_approval", "atall_manifest", "atall_ping_write"];

describe("Legacy (2025-era) handshake, as ChatGPT/Claude/Grok clients open today", () => {
  it("initialize → notifications/initialized → tools/list, with no session id", async () => {
    const c = await connected("kdot");
    const init = await c.rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "ChatGPT", version: "1" } }, { modern: false });
    expect(init.status).toBe(200);
    expect(init.body.result.protocolVersion).toBe("2025-06-18");
    expect(init.body.result.capabilities.tools).toBeDefined();
    expect(init.body.result.serverInfo.name).toBe("atall-connector");
    expect(init.body.result.instructions).toContain("atall_inbox");
    expect(init.headers.get("Mcp-Session-Id")).toBeNull();

    const note = await c.rpc("notifications/initialized", {}, { modern: false, notification: true });
    expect(note.status).toBe(202);

    const ping = await c.rpc("ping", {}, { modern: false });
    expect(ping.body.result).toEqual({});

    const list = await c.rpc("tools/list", {}, { modern: false });
    expect(list.body.result.tools.map((t: any) => t.name)).toEqual(ALL_TOOLS);
  });

  it("negotiates down to a supported legacy version", async () => {
    const c = await connected("grok", "Grok");
    const init = await c.rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "x", version: "1" } }, { modern: false });
    expect(init.body.result.protocolVersion).toBe("2025-11-25");
  });

  it("legacy tools/call works without the 2026 headers", async () => {
    const c = await connected("kdot");
    const r = await c.callTool("atall_inbox", {}, { modern: false });
    expect(r.status).toBe(200);
    expect(r.body.result.structuredContent.agent_id).toBe("kdot");
  });
});

describe("Modern (2026-07-28) stateless transport", () => {
  it("server/discover works without auth and advertises versions, capabilities and instructions", async () => {
    const c = newClient();
    const r = await c.rpc("server/discover", {}, { token: null });
    expect(r.status).toBe(200);
    const res = r.body.result;
    expect(res.resultType).toBe("complete");
    expect(res.supportedVersions).toContain("2026-07-28");
    expect(res.supportedVersions).toContain("2025-06-18");
    expect(res.capabilities.tools).toBeDefined();
    expect(res.instructions).toContain("atall_post_result");
    expect(res._meta["io.modelcontextprotocol/serverInfo"].name).toBe("atall-connector");
    expect(res.ttlMs).toBeGreaterThan(0);
  });

  it("tools/list is cacheable and every tool carries honest annotations", async () => {
    const c = await connected("kdot");
    const r = await c.rpc("tools/list");
    const res = r.body.result;
    expect(res.resultType).toBe("complete");
    expect(res.cacheScope).toBe("public");
    expect(res.ttlMs).toBeGreaterThan(0);
    const byName = Object.fromEntries(res.tools.map((t: any) => [t.name, t]));
    for (const n of READ_TOOLS) {
      expect(byName[n].annotations.readOnlyHint, n).toBe(true);
      expect(byName[n].description).toMatch(/Read-only\./);
    }
    for (const n of WRITE_TOOLS) {
      expect(byName[n].annotations.readOnlyHint, n).toBe(false);
      expect(byName[n].annotations.destructiveHint, n).toBe(false);
      expect(byName[n].description, n).toMatch(/^Write/);
    }
  });

  it("rejects a tools/call whose Mcp-Name header doesn't match the body (-32020)", async () => {
    const c = await connected("kdot");
    const r = await c.callTool("atall_inbox", {}, { headers: { "Mcp-Name": "atall_ping_write" } });
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe(-32020);
  });

  it("rejects missing Mcp-Method, missing Mcp-Name, and a header/_meta version mismatch", async () => {
    const c = await connected("kdot");
    const noMethod = await c.rpc("tools/list", {}, { headers: { "Mcp-Method": null } });
    expect(noMethod.status).toBe(400);
    expect(noMethod.body.error.code).toBe(-32020);
    const noName = await c.callTool("atall_inbox", {}, { headers: { "Mcp-Name": null } });
    expect(noName.body.error.code).toBe(-32020);
    const mismatch = await c.rpc("tools/list", { _meta: { "io.modelcontextprotocol/protocolVersion": "2025-11-25" } });
    expect(mismatch.status).toBe(400);
    expect(mismatch.body.error.code).toBe(-32020);
  });

  it("accepts a base64-sentinel Mcp-Name", async () => {
    const c = await connected("kdot");
    const r = await c.callTool("atall_inbox", {}, { headers: { "Mcp-Name": `=?base64?${btoa("atall_inbox")}?=` } });
    expect(r.status).toBe(200);
  });

  it("returns UnsupportedProtocolVersion (-32022) with the supported list", async () => {
    const c = await connected("kdot");
    const r = await c.rpc("tools/list", {}, { headers: { "MCP-Protocol-Version": "2099-01-01" } });
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe(-32022);
    expect(r.body.error.data.supported).toContain("2026-07-28");
    expect(r.body.error.data.requested).toBe("2099-01-01");
  });

  it("404s unknown methods and the removed ping in modern mode", async () => {
    const c = await connected("kdot");
    const u = await c.rpc("resources/list");
    expect(u.status).toBe(404);
    expect(u.body.error.code).toBe(-32601);
    const p = await c.rpc("ping");
    expect(p.status).toBe(404);
  });

  it("puts resultType and serverInfo on tool results", async () => {
    const c = await connected("kdot");
    const r = await c.callTool("atall_inbox");
    expect(r.body.result.resultType).toBe("complete");
    expect(r.body.result._meta["io.modelcontextprotocol/serverInfo"].version).toBe("0.3.0-c3");
  });
});

describe("Transport guards", () => {
  it("GET and DELETE on /mcp are 405 (no standalone SSE stream, no sessions)", async () => {
    const f = workerFetch();
    expect((await f(`${BASE}/mcp`)).status).toBe(405);
    expect((await f(`${BASE}/mcp`, { method: "DELETE" })).status).toBe(405);
  });

  it("refuses unknown browser Origins and allows ChatGPT's with CORS", async () => {
    const c = await connected("kdot");
    const bad = await c.rpc("tools/list", {}, { headers: { Origin: "https://evil.example" } });
    expect(bad.status).toBe(403);
    const good = await c.rpc("tools/list", {}, { headers: { Origin: "https://chatgpt.com" } });
    expect(good.status).toBe(200);
    expect(good.headers.get("Access-Control-Allow-Origin")).toBe("https://chatgpt.com");
  });

  it("rejects batches, malformed JSON and bad tokens", async () => {
    const f = workerFetch();
    const batch = await f(`${BASE}/mcp`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "[]" });
    expect(batch.status).toBe(400);
    const junk = await f(`${BASE}/mcp`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{" });
    expect((await junk.json()).error.code).toBe(-32700);
    const c = newClient();
    const r = await c.rpc("tools/list", {}, { token: "atl_at_forged" });
    expect(r.status).toBe(401);
    expect(r.headers.get("WWW-Authenticate")).toContain('error="invalid_token"');
  });

  it("revoking a connection from the admin page cuts it off on the next call", async () => {
    const c = await connected("hark", "Hark");
    const st = await admin().state();
    const conn = st.body.connections.find((x: any) => x.agent_id === "hark");
    await admin().revoke(conn.conn_id);
    expect((await c.rpc("tools/list")).status).toBe(401);
  });
});

describe("Presence", () => {
  it("records the last call per agent", async () => {
    const c = await connected("kdot");
    await c.callTool("atall_inbox");
    const st = await admin().state();
    const p = st.body.presence.find((x: any) => x.agent_id === "kdot");
    expect(p.last_tool).toBe("atall_inbox");
    expect(p.client_name).toBe("ChatGPT");
  });
});
});
