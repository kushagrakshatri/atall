// C3: approval tokens (fingerprint-signed on the phone, checked here) and MCP Events wake-ups for ChatGPT dots.

import { afterAll, beforeEach, describe, it } from "@std/testing/bdd";
import { expect } from "@std/expect";
import { BASE, connected, freshEnv, newClient, OWNER, structured, testEnv, workerFetch } from "./helpers.ts";
import { closePg } from "./pg.ts";
import { b64std, canonicalJson, sha256 } from "../supabase/functions/atall/src/crypto.ts";
import { b64url } from "../supabase/functions/atall/src/util.ts";
import { webhookSignature } from "../supabase/functions/atall/src/events.ts";
import { Phone } from "../scripts/lib/phone.ts";

const OPTS = { sanitizeOps: false, sanitizeResources: false };
const P256 = { name: "ECDSA", namedCurve: "P-256" } as const;

type Outbound = { url: string; headers: Record<string, string>; body: string };

async function pairedPhone(outbound?: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  const p = await new Phone(BASE, workerFetch(outbound)).init();
  const r = await p.pair(OWNER, "Pixel C3");
  if (r.status !== 201) throw new Error("pair failed");
  return p;
}

async function approvalKey() {
  const kp = (await crypto.subtle.generateKey(P256, false, ["sign", "verify"])) as CryptoKeyPair;
  const spki = b64std(await crypto.subtle.exportKey("spki", kp.publicKey));
  const sign = async (token: Record<string, unknown>) =>
    b64url(new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, kp.privateKey, new TextEncoder().encode(canonicalJson(token)))));
  return { spki, sign };
}

/** An agent asks for approval on a phone task; returns the phone's view of the request. */
async function requestApproval(p: Phone, agent = "grok", ref = "apr1", amount: number | undefined = 42) {
  const t = await p.postTask({ ref, title: "Book dinner", goal: "Book Sakoon for 4.", to: [agent] });
  expect(t.status).toBe(201);
  const c = await connected(agent);
  const args: Record<string, unknown> = { task_id: ref, action: "Book Sakoon 7:30 for 4", reason: "You asked me to book" };
  if (amount !== undefined) Object.assign(args, { amount, currency: "USD" });
  const r = await c.callTool("atall_request_approval", args);
  const out = structured(r);
  const ev = (await p.events(0)).body.events.find((e: any) => e.type === "approval_request");
  return { c, taskId: t.body.task_id as string, approvalId: out.approval_id as string, payload: ev.payload as Record<string, unknown> };
}

async function tokenFor(req: { taskId: string; approvalId: string; payload: Record<string, unknown> }, agent = "grok", over: Record<string, unknown> = {}) {
  const now = Date.now();
  return {
    v: 1,
    jti: "apv_" + b64url(crypto.getRandomValues(new Uint8Array(12))),
    approval_id: req.approvalId,
    task_id: req.taskId,
    agent_id: agent,
    action_hash: b64url(await sha256(canonicalJson(req.payload))),
    decision: "approve",
    max_amount: { value: 42, currency: "USD" },
    nbf: new Date(now).toISOString().replace(/\.\d+Z$/, "Z"),
    exp: new Date(now + 10 * 60_000).toISOString().replace(/\.\d+Z$/, "Z"),
    uses: 1,
    ...over,
  };
}

describe({ name: "C3 approvals", ...OPTS }, () => {
  beforeEach(async () => {
    await freshEnv();
  });
  afterAll(async () => {
    await closePg();
  });

  it("approves only with a token signed by the phone's pinned approval key; the agent then sees the decision", async () => {
    const p = await pairedPhone();
    const req = await requestApproval(p);
    const key = await approvalKey();
    const token = await tokenFor(req);

    // No approval key yet: refused.
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token, token_sig: await key.sign(token) })).status).toBe(412);
    expect((await p.request("POST", "/phone/approval-key", { public_key_spki_b64: key.spki })).body.status).toBe("set");
    expect((await p.request("POST", "/phone/approval-key", { public_key_spki_b64: key.spki })).body.status).toBe("same");
    const other = await approvalKey();
    expect((await p.request("POST", "/phone/approval-key", { public_key_spki_b64: other.spki })).status).toBe(409);

    // Signed by another key: refused.
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token, token_sig: await other.sign(token) })).status).toBe(401);
    // Unsigned: refused.
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token })).status).toBe(400);

    const ok = await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token, token_sig: await key.sign(token) });
    expect(ok.status).toBe(200);
    expect(ok.body.decision).toBe("approve");
    // Single use: a second decision (even with a fresh token) is refused.
    const again = await tokenFor(req);
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token: again, token_sig: await key.sign(again) })).status).toBe(409);

    const card = structured(await req.c.callTool("atall_get_task", { task_id: "apr1" }));
    expect(card.approvals[0].status).toBe("approved");
    expect(card.approvals[0].token.jti).toBe(token.jti);
    expect(card.approvals[0].token_sig).toBeTruthy();
    expect(card.my_state).toBe("WORKING");
    const inbox = await req.c.callTool("atall_inbox", {});
    expect(inbox.body.result.content[0].text).toContain("→ approved");
  });

  it("refuses tokens that don't match the request: action hash, amount, agent, expiry, lifetime", async () => {
    const p = await pairedPhone();
    const req = await requestApproval(p);
    const key = await approvalKey();
    await p.request("POST", "/phone/approval-key", { public_key_spki_b64: key.spki });
    const cases: Record<string, unknown>[] = [
      { action_hash: b64url(await sha256("something else")) },
      { max_amount: { value: 10, currency: "USD" } },
      { max_amount: { value: 42, currency: "EUR" } },
      { agent_id: "hark" },
      { exp: new Date(Date.now() - 1000).toISOString() },
      { exp: new Date(Date.now() + 60 * 60_000).toISOString() },
      { uses: 3 },
      { decision: "deny" },
    ];
    for (const over of cases) {
      const t = await tokenFor(req, "grok", over);
      const r = await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token: t, token_sig: await key.sign(t) });
      expect([r.status, JSON.stringify(over)]).toEqual([400, JSON.stringify(over)]);
    }
    expect((await testEnv().store.getApproval(req.approvalId))?.status).toBe("pending");
  });

  it("denies without a token, and a denied request can't be approved after", async () => {
    const p = await pairedPhone();
    const req = await requestApproval(p, "grok", "apr2", undefined);
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "deny" })).status).toBe(200);
    const key = await approvalKey();
    await p.request("POST", "/phone/approval-key", { public_key_spki_b64: key.spki });
    const t = await tokenFor(req, "grok", { max_amount: undefined });
    expect((await p.request("POST", "/phone/approvals", { approval_id: req.approvalId, decision: "approve", token: t, token_sig: await key.sign(t) })).status).toBe(409);
    const card = structured(await req.c.callTool("atall_get_task", { task_id: "apr2" }));
    expect(card.approvals[0].status).toBe("denied");
    expect(card.approvals[0].token).toBeUndefined();
    expect((await p.request("POST", "/phone/approvals", { approval_id: "apr_nope", decision: "deny" })).status).toBe(404);
  });
});

describe({ name: "C3 MCP Events (task.assigned)", ...OPTS }, () => {
  beforeEach(async () => {
    await freshEnv();
  });

  /** A ChatGPT-like receiver: echoes verification challenges, records deliveries. */
  function receiver(opts: { echo?: boolean } = {}) {
    const got: Outbound[] = [];
    const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input instanceof Request ? input.url : input);
      const headers = Object.fromEntries(new Headers(init?.headers).entries());
      const body = String(init?.body ?? "");
      got.push({ url, headers, body });
      const msg = JSON.parse(body);
      if (msg.type === "verification") return new Response(JSON.stringify({ challenge: opts.echo === false ? "nope" : msg.challenge }), { status: 200 });
      return new Response(null, { status: 202 });
    };
    return { got, fetch };
  }

  const SECRET = "whsec_" + btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))));
  const CALLBACK = "https://chatgpt.example/mcp-events/cb_123";

  async function kdot(fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
    const c = newClient({ name: "ChatGPT", outbound: fetch });
    await c.connect({ ownerCode: OWNER, agentId: "kdot" });
    return c;
  }

  it("advertises events, verifies the callback, and wakes the dot with a signed, content-free event", async () => {
    const rx = receiver();
    const c = await kdot(rx.fetch);
    const init = await c.rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "ChatGPT", version: "1" } }, { modern: false });
    expect(init.body.result.capabilities.events).toEqual({});
    const list = await c.rpc("events/list", {});
    expect(list.body.result.events.map((e: any) => e.name)).toEqual(["task.assigned"]);

    const sub = await c.rpc("events/subscribe", { name: "task.assigned", arguments: {}, delivery: { mode: "webhook", url: CALLBACK, secret: SECRET }, cursor: null });
    expect(sub.body.result.id).toMatch(/^sub_/);
    expect(sub.body.result.cursor).toBeNull();
    expect(rx.got.length).toBe(1);
    expect(JSON.parse(rx.got[0].body).type).toBe("verification");
    // Idempotent: same identity, same id.
    const sub2 = await c.rpc("events/subscribe", { name: "task.assigned", arguments: {}, delivery: { mode: "webhook", url: CALLBACK, secret: SECRET }, cursor: null });
    expect(sub2.body.result.id).toBe(sub.body.result.id);
    expect((await testEnv().store.listEventSubs(["kdot"])).length).toBe(1);

    const p = await pairedPhone(rx.fetch);
    rx.got.length = 0;
    const t = await p.postTask({ ref: "evt1", title: "Secret dinner plans", goal: "Do not leak this goal", to: ["kdot"] });
    expect(t.status).toBe(201);
    expect(rx.got.length).toBe(1);
    const d = rx.got[0];
    expect(d.url).toBe(CALLBACK);
    const ev = JSON.parse(d.body);
    expect(ev.name).toBe("task.assigned");
    expect(ev.data).toEqual({ task_id: t.body.task_id, ref: "evt1", agent_id: "kdot", attempt: 1 });
    expect(d.body).not.toContain("Secret dinner");
    expect(d.body).not.toContain("leak");
    expect(d.headers["webhook-id"]).toBe(ev.eventId);
    expect(d.headers["x-mcp-subscription-id"]).toBe(sub.body.result.id);
    expect(d.headers["webhook-signature"]).toBe(await webhookSignature(SECRET, ev.eventId, Number(d.headers["webhook-timestamp"]), d.body));

    // A nudge from the phone goes out again with a new event id; a task for someone else doesn't reach kdot.
    rx.got.length = 0;
    const w = await p.request("POST", "/phone/wake", { task_id: t.body.task_id, agent_id: "kdot" });
    expect(w.body.delivered).toBe(1);
    expect(JSON.parse(rx.got[0].body).eventId).not.toBe(ev.eventId);
    rx.got.length = 0;
    await p.postTask({ ref: "evt2", title: "For Grok", goal: "x", to: ["grok"] });
    expect(rx.got.length).toBe(0);
    expect((await p.request("POST", "/phone/wake", { task_id: t.body.task_id, agent_id: "grok" })).status).toBe(400);
    // A wake for an agent with no subscription delivers nothing (the phone falls back to its next path).
    const g = await p.postTask({ ref: "evt3", title: "For Grok", goal: "x", to: ["grok"] });
    expect((await p.request("POST", "/phone/wake", { task_id: g.body.task_id, agent_id: "grok" })).body.delivered).toBe(0);

    await c.rpc("events/unsubscribe", { name: "task.assigned", arguments: {}, delivery: { mode: "webhook", url: CALLBACK } });
    expect((await testEnv().store.listEventSubs(["kdot"])).length).toBe(0);
  });

  it("refuses private callback URLs, weak secrets, unknown events, and a callback that doesn't echo", async () => {
    const c = await kdot(receiver({ echo: false }).fetch);
    const sub = (delivery: Record<string, unknown>, name = "task.assigned") => c.rpc("events/subscribe", { name, arguments: {}, delivery: { mode: "webhook", ...delivery } });
    for (const url of ["http://chatgpt.example/x", "https://localhost/x", "https://10.0.0.5/x", "https://192.168.1.2/x", "https://[::1]/x"]) {
      expect((await sub({ url, secret: SECRET })).body.error.code).toBe(-32602);
    }
    expect((await sub({ url: CALLBACK, secret: "whsec_c2hvcnQ=" })).body.error.code).toBe(-32602);
    expect((await sub({ url: CALLBACK, secret: SECRET }, "task.deleted")).body.error.code).toBe(-32602);
    const bad = await sub({ url: CALLBACK, secret: SECRET });
    expect(bad.body.error.code).toBe(-32015);
    expect(bad.body.error.data.reason).toBe("challenge_failed");
    expect((await testEnv().store.listEventSubs(["kdot"])).length).toBe(0);
  });
});

describe({ name: "C3 agents list", ...OPTS }, () => {
  beforeEach(async () => {
    await freshEnv();
  });
  it("marks agents with a live MCP Events subscription", async () => {
    await connected("grok");
    await connected("kdot");
    await testEnv().store.putEventSub({
      sub_id: "sub_test", agent_id: "kdot", conn_id: "cn_x", name: "task.assigned", arguments: {}, url: "https://chatgpt.example/cb",
      secret: "whsec_" + btoa("x".repeat(32)), created_at: new Date().toISOString(), expires_at: new Date(Date.now() + 3600_000).toISOString(),
    });
    const p = await pairedPhone();
    const list = (await p.agents()).body as any[];
    expect(list.find((a) => a.agent_id === "kdot").events).toBe(true);
    expect(list.find((a) => a.agent_id === "grok").events).toBe(false);
  });
});
