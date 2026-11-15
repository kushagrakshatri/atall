// C1 phone API: pairing, ES256-signed phone requests with a replay guard, phone-signed tasks,
// countersigned events, acks, answers, the agents list and revocation. See C1-CONTRACT.md at the connector root.

import type { Ctx, Deps, Env } from "./env.ts";
import { canonicalJson, countersign, importDeviceKey, loadRelayKey, phoneSignedString, type RelayKey, sha256, verifyEs256 } from "./crypto.ts";
import { deliverTaskAssigned } from "./events.ts";
import { AGENT_ID_RE, type Device, isAddressedTo, RefTakenError, taskExpiry } from "./store.ts";
import { b64url, clientIp, json, newId, nowIso, safeEqual } from "./util.ts";

export const MAX_SKEW_S = 120;
const MAX_BODY = 64_000;
const REF_RE = /^[a-z0-9]{4,6}$/;

const err = (error: string, status: number) => json({ error }, status);

async function relayKey(env: Env): Promise<RelayKey | null> {
  if (!env.ATALL_RELAY_SIGNING_KEY) return null;
  try {
    return await loadRelayKey(env.ATALL_RELAY_SIGNING_KEY);
  } catch (e) {
    console.error("atall: ATALL_RELAY_SIGNING_KEY is not a usable P-256 PKCS8 key", e instanceof Error ? e.message : e);
    return null;
  }
}

function parseJson(bytes: Uint8Array): Record<string, unknown> | null {
  if (!bytes.length) return {};
  try {
    const v = JSON.parse(new TextDecoder().decode(bytes));
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

/**
 * Checks the device signature on a phone request. Order matters: the signature is verified before the
 * replay guard records anything, so garbage can't fill the nonce table.
 */
export async function verifyPhoneRequest(req: Request, env: Env, signedPath: string, body: Uint8Array): Promise<Device | Response> {
  const deviceId = req.headers.get("X-Atall-Device")?.trim() ?? "";
  const tsRaw = req.headers.get("X-Atall-Ts")?.trim() ?? "";
  const sig = req.headers.get("X-Atall-Sig")?.trim() ?? "";
  if (!deviceId || !tsRaw || !sig) return err("unsigned request: X-Atall-Device, X-Atall-Ts and X-Atall-Sig are required", 401);
  if (!/^\d{1,12}$/.test(tsRaw)) return err("bad X-Atall-Ts", 401);
  const ts = Number(tsRaw);
  if (Math.abs(Math.floor(Date.now() / 1000) - ts) > MAX_SKEW_S) return err("stale or future timestamp (more than 120 s off)", 401);
  if (!/^dev_[a-z0-9]{10,40}$/.test(deviceId)) return err("unknown or revoked device", 401);
  const device = await env.store.getDevice(deviceId);
  if (!device || device.revoked) return err("unknown or revoked device", 401);
  let key: CryptoKey;
  try {
    key = await importDeviceKey(device.public_key_spki_b64);
  } catch {
    return err("unknown or revoked device", 401);
  }
  const signed = await phoneSignedString(req.method, signedPath, tsRaw, body);
  const norm = await verifyEs256(key, sig, signed);
  if (!norm) return err("bad signature", 401);
  const fresh = await env.store.markPhoneNonce(deviceId, ts, b64url(await crypto.subtle.digest("SHA-256", norm as BufferSource)));
  if (!fresh) return err("replayed request", 401);
  return device;
}

function strField(v: unknown, field: string, max: number, required: boolean): string | null | Response {
  if (v === undefined || v === null || v === "") return required ? err(`${field} is required`, 400) : null;
  if (typeof v !== "string") return err(`${field} must be a string`, 400);
  const s = v.trim();
  if (required && !s) return err(`${field} is required`, 400);
  if (s.length > max) return err(`${field} is longer than ${max} characters`, 400);
  return s;
}

/** A string or a list of strings → list (≤20 items of ≤1000 chars). */
function listField(v: unknown, field: string): string[] | Response {
  if (v === undefined || v === null || v === "") return [];
  const arr = typeof v === "string" ? [v] : v;
  if (!Array.isArray(arr) || arr.length > 20 || arr.some((x) => typeof x !== "string")) return err(`${field} must be a string or a list of at most 20 strings`, 400);
  if (arr.some((x) => x.length > 1000)) return err(`${field} items must be at most 1000 characters`, 400);
  return arr.map((x) => x.trim()).filter(Boolean);
}

async function pair(req: Request, env: Env, body: Uint8Array): Promise<Response> {
  const key = await relayKey(env);
  if (!key) return err("pairing is not configured on this relay (no relay signing key)", 503);
  const b = parseJson(body);
  if (!b) return err("body must be a JSON object", 400);
  const store = env.store;
  // Same limits as the owner-code consent page, sharing its counters: 5 wrong codes per IP and 20 overall per 10 min.
  const ip = clientIp(req);
  if ((await store.count(`consentfail:${ip}`, 600)) >= 5 || (await store.count("consentfail:all", 600)) >= 20) {
    return err("Too many wrong owner codes. Wait 10 minutes and try again.", 429);
  }
  const code = typeof b.owner_code === "string" ? b.owner_code : "";
  if (!env.ATALL_OWNER_CODE || !(await safeEqual(code, env.ATALL_OWNER_CODE))) {
    await store.hit(`consentfail:${ip}`, 5, 600);
    await store.hit("consentfail:all", 20, 600);
    return err("That owner code is not right.", 401);
  }
  const name = strField(b.device_name, "device_name", 100, true);
  if (name instanceof Response) return name;
  if (typeof b.public_key_spki_b64 !== "string" || b.public_key_spki_b64.length > 400) return err("public_key_spki_b64 is required", 400);
  try {
    await importDeviceKey(b.public_key_spki_b64);
  } catch {
    return err("public_key_spki_b64 must be a P-256 SubjectPublicKeyInfo (DER, base64)", 400);
  }
  const device: Device = { device_id: newId("dev"), device_name: name!, public_key_spki_b64: b.public_key_spki_b64.trim(), created_at: nowIso() };
  await store.putDevice(device);
  return json({ device_id: device.device_id, relay_public_key_spki_b64: key.publicSpkiB64, relay_key_alg: "ES256" }, 201);
}

async function postTask(env: Env, device: Device, body: Uint8Array, signed: { sig: string; signed_string: string }, ctx?: Ctx, deps?: Deps): Promise<Response> {
  const b = parseJson(body);
  if (!b) return err("body must be a JSON object", 400);
  const known = ["ref", "title", "goal", "to", "context", "constraints", "done_criteria", "dont_share", "deadline", "output"];
  const extra = Object.keys(b).filter((k) => !known.includes(k));
  if (extra.length) return err(`unknown field(s): ${extra.join(", ")}`, 400);
  if (typeof b.ref !== "string" || !REF_RE.test(b.ref)) return err("ref must be 4-6 characters of [a-z0-9]", 400);
  const title = strField(b.title, "title", 200, true);
  if (title instanceof Response) return title;
  const goal = strField(b.goal, "goal", 4000, true);
  if (goal instanceof Response) return goal;
  if (!Array.isArray(b.to) || !b.to.length || b.to.length > 20 || b.to.some((a) => typeof a !== "string")) return err('to must be ["all"] or a list of agent ids', 400);
  const to = [...new Set((b.to as string[]).map((a) => a.trim().toLowerCase()).map((a) => (a === "all" || a === "@all" ? "@all" : a)))];
  if (to.some((a) => a !== "@all" && !AGENT_ID_RE.test(a))) return err('to must be ["all"] or a list of agent ids', 400);
  if (to.includes("@all") && to.length > 1) return err('to must be ["all"] on its own, or agent ids', 400);
  const context = listField(b.context, "context");
  if (context instanceof Response) return context;
  const constraints = listField(b.constraints, "constraints");
  if (constraints instanceof Response) return constraints;
  const doneCriteria = listField(b.done_criteria, "done_criteria");
  if (doneCriteria instanceof Response) return doneCriteria;
  const dontShare = listField(b.dont_share, "dont_share");
  if (dontShare instanceof Response) return dontShare;
  let deadline: string | null = null;
  if (b.deadline !== undefined && b.deadline !== null) {
    if (typeof b.deadline !== "string" || b.deadline.length > 64 || !Number.isFinite(Date.parse(b.deadline))) return err("deadline must be an ISO 8601 date-time", 400);
    deadline = b.deadline;
  }
  if (b.output !== undefined && b.output !== null && b.output !== "claims") return err('output must be "claims" if given', 400);
  try {
    const t = await env.store.createTask({
      title: title!,
      goal: goal!,
      to,
      context,
      constraints,
      done_when: doneCriteria.join("\n"),
      done_criteria: doneCriteria,
      do_not_share: dontShare,
      deadline,
      output: (b.output as string | undefined) ?? null,
      expires_at: taskExpiry(deadline),
      phone_sig: {
        device_id: device.device_id,
        device_name: device.device_name,
        device_public_key_spki_b64: device.public_key_spki_b64,
        alg: "ES256",
        sig: signed.sig,
        signed_string: signed.signed_string,
        body: new TextDecoder().decode(body),
      },
    }, "phone", undefined, b.ref);
    // C3: wake agents that subscribed to MCP Events (kDot's dot). No content in the event, only the id and ref.
    if (ctx && deps) {
      const agentsTo = to.includes("@all") ? (await env.store.listConnections()).filter((c) => !c.revoked).map((c) => c.agent_id) : to;
      ctx.waitUntil(deliverTaskAssigned(env, deps, t, [...new Set(agentsTo)], 1).catch(() => 0));
    }
    return json({ task_id: t.task_id, ref: t.ref }, 201);
  } catch (e) {
    if (e instanceof RefTakenError) return err(`ref ${b.ref} is already used by a live task`, 409);
    throw e;
  }
}

async function events(env: Env, url: URL): Promise<Response> {
  const key = await relayKey(env);
  if (!key) return err("relay signing key is not configured", 503);
  const afterRaw = url.searchParams.get("after") ?? "0";
  if (!/^\d{1,15}$/.test(afterRaw || "0")) return err("after must be a cursor (integer)", 400);
  const limitRaw = url.searchParams.get("limit") ?? "100";
  if (!/^\d{1,4}$/.test(limitRaw)) return err("limit must be an integer", 400);
  const after = Number(afterRaw || "0");
  const limit = Math.min(Math.max(Number(limitRaw), 1), 500);
  const list = await env.store.listEvents(after, limit);
  const signed = await Promise.all(list.map((e) => countersign(key, e as unknown as Record<string, unknown>)));
  return json({ events: signed, next_cursor: list.length ? list[list.length - 1].cursor : after });
}

async function agents(env: Env): Promise<Response> {
  const [conns, presence] = await Promise.all([env.store.listConnections(), env.store.listPresence()]);
  // C3: which agents can be woken by an MCP event (kDot's dot after it subscribes to task.assigned).
  const subscribed = new Set((await env.store.listEventSubs(conns.filter((c) => !c.revoked).map((c) => c.agent_id))).map((s) => s.agent_id));
  const byAgent = new Map(presence.map((p) => [p.agent_id, p]));
  const out = conns
    .filter((c) => !c.revoked)
    .map((c) => {
      const p = byAgent.get(c.agent_id);
      const mine = p && p.conn_id === c.conn_id;
      return { agent_id: c.agent_id, conn_id: c.conn_id, client_name: c.client_name, kind: c.kind, created_at: c.created_at, last_seen: mine ? p.last_seen : null, last_tool: mine ? p.last_tool : null, events: subscribed.has(c.agent_id) };
    });
  return json(out);
}

/** Routes /phone/*. `p` is the route inside the function (no /atall prefix). */
export async function handlePhone(req: Request, env: Env, p: string, ctx?: Ctx, deps?: Deps): Promise<Response> {
  const url = new URL(req.url);
  const buf = new Uint8Array(await req.arrayBuffer());
  if (buf.length > MAX_BODY) return err("request too large", 413);

  if (p === "/phone/pair") {
    if (req.method !== "POST") return err("method not allowed", 405);
    return pair(req, env, buf);
  }

  // Every other /phone/ route is device-signed. PATH = the route after /atall plus the raw query string.
  const signedPath = p + url.search;
  const device = await verifyPhoneRequest(req, env, signedPath, buf);
  if (device instanceof Response) return device;
  const store = env.store;

  if (p === "/phone/tasks" && req.method === "POST") {
    const sig = req.headers.get("X-Atall-Sig")!.trim();
    const signed_string = await phoneSignedString(req.method, signedPath, req.headers.get("X-Atall-Ts")!.trim(), buf);
    return postTask(env, device, buf, { sig, signed_string }, ctx, deps);
  }
  if (p === "/phone/events" && req.method === "GET") return events(env, url);
  if (p === "/phone/agents" && req.method === "GET") return agents(env);

  if (p === "/phone/ack" && req.method === "POST") {
    const b = parseJson(buf);
    const c = b?.cursor;
    const cursor = typeof c === "number" ? c : typeof c === "string" && /^\d{1,15}$/.test(c) ? Number(c) : NaN;
    if (!Number.isSafeInteger(cursor) || cursor < 0) return err("cursor must be an event cursor", 400);
    const acked = await store.ackEvents(cursor);
    return json({ ok: true, cursor, acked, deleted_after_s: 24 * 3600 });
  }

  if (p === "/phone/answers" && req.method === "POST") {
    const b = parseJson(buf);
    if (!b) return err("body must be a JSON object", 400);
    const taskId = strField(b.task_id, "task_id", 64, true);
    if (taskId instanceof Response) return taskId;
    const qid = strField(b.question_id, "question_id", 64, true);
    if (qid instanceof Response) return qid;
    const text = strField(b.text, "text", 4000, true);
    if (text instanceof Response) return text;
    const r = await store.answerQuestion(taskId!, qid!, text!, device.device_id);
    if (!r.ok) return r.reason === "not_found" ? err("no such question on that task", 404) : err("that question was already answered", 409);
    return json({ ok: true, task_id: taskId, question_id: qid, agent_id: r.agent_id, ref: r.ref });
  }

  // C3: nudge an agent through its MCP Events subscription. Answers how many subscriptions took the event.
  if (p === "/phone/wake" && req.method === "POST") {
    const b = parseJson(buf);
    if (!b) return err("body must be a JSON object", 400);
    const taskId = strField(b.task_id, "task_id", 64, true);
    if (taskId instanceof Response) return taskId;
    const agentId = strField(b.agent_id, "agent_id", 32, true);
    if (agentId instanceof Response) return agentId;
    const t = await store.getTask(taskId!);
    if (!t || (t.expires_at && Date.parse(t.expires_at) < Date.now())) return err("no such task", 404);
    if (!isAddressedTo(t, agentId!)) return err("that task isn't addressed to this agent", 400);
    const attempt = Math.floor(Date.now() / 1000);
    const delivered = deps ? await deliverTaskAssigned(env, deps, t, [agentId!], attempt) : 0;
    return json({ ok: true, task_id: t.task_id, agent_id: agentId, delivered });
  }

  // C3: the phone's approval key (signs only after a fingerprint). Pinned on first use per device.
  if (p === "/phone/approval-key" && req.method === "POST") {
    const b = parseJson(buf);
    const spki = typeof b?.public_key_spki_b64 === "string" ? b.public_key_spki_b64.trim() : "";
    if (!spki || spki.length > 400) return err("public_key_spki_b64 is required", 400);
    try {
      await importDeviceKey(spki);
    } catch {
      return err("public_key_spki_b64 must be a P-256 SubjectPublicKeyInfo (DER, base64)", 400);
    }
    const r = await store.setApprovalKey(device.device_id, spki);
    if (r === "conflict") return err("this phone already has a different approval key; pair again to replace it", 409);
    if (r === "no_device") return err("unknown or revoked device", 401);
    return json({ ok: true, status: r });
  }

  // C3: Kush's decision on an approval request. "approve" needs a token signed with the approval key.
  if (p === "/phone/approvals" && req.method === "POST") return decideApproval(env, device, buf);

  let m = /^\/phone\/connections\/([A-Za-z0-9_]{1,64})\/revoke$/.exec(p);
  if (m && req.method === "POST") {
    const conn = await store.getConnection(m[1]);
    if (!conn) return err("no such connection", 404);
    await store.revokeConnection(m[1], `revoked from phone ${device.device_id}`);
    return json({ ok: true, conn_id: m[1], agent_id: conn.agent_id });
  }

  // Not in the C1 contract: un-pair a device (itself or another of Kush's devices).
  m = /^\/phone\/devices\/([A-Za-z0-9_]{1,64})\/revoke$/.exec(p);
  if (m && req.method === "POST") {
    const ok = await store.revokeDevice(m[1], `revoked from phone ${device.device_id}`);
    return ok ? json({ ok: true, device_id: m[1] }) : err("no such device", 404);
  }

  return err("not found", 404);
}

export const APPROVAL_MAX_TTL_S = 15 * 60;

/**
 * Checks and records a decision. An approval needs: the phone's pinned approval key; a token whose ES256 signature
 * (over its canonical JSON) verifies with that key; the token bound to this approval, task, agent and action hash; a
 * single use; not yet expired, valid for at most 15 minutes; and a max_amount that covers what the agent asked for.
 */
async function decideApproval(env: Env, device: Device, buf: Uint8Array): Promise<Response> {
  const b = parseJson(buf);
  if (!b) return err("body must be a JSON object", 400);
  const id = strField(b.approval_id, "approval_id", 64, true);
  if (id instanceof Response) return id;
  const decision = b.decision;
  if (decision !== "approve" && decision !== "deny") return err("decision must be approve or deny", 400);
  const a = await env.store.getApproval(id!);
  if (!a) return err("no such approval request", 404);
  if (a.status !== "pending") return err("that request was already decided", 409);
  if (decision === "deny") {
    const r = await env.store.decideApproval(a.approval_id, { status: "denied", device_id: device.device_id });
    if (!r.ok) return r.reason === "not_found" ? err("no such approval request", 404) : err("that request was already decided", 409);
    return json({ ok: true, approval_id: a.approval_id, decision: "deny" });
  }
  if (!device.approval_key_spki_b64) return err("register an approval key first (POST /phone/approval-key)", 412);
  const token = b.token;
  if (!token || typeof token !== "object" || Array.isArray(token)) return err("token is required to approve", 400);
  const sig = typeof b.token_sig === "string" ? b.token_sig.trim() : "";
  if (!sig) return err("token_sig is required to approve", 400);
  let key: CryptoKey;
  try {
    key = await importDeviceKey(device.approval_key_spki_b64);
  } catch {
    return err("this phone's approval key is unusable; pair again", 409);
  }
  const tk = token as Record<string, unknown>;
  if (!(await verifyEs256(key, sig, canonicalJson(tk)))) return err("bad token signature", 401);
  const actionHash = b64url(await sha256(canonicalJson(a.payload)));
  const nbf = Date.parse(String(tk.nbf ?? ""));
  const exp = Date.parse(String(tk.exp ?? ""));
  const now = Date.now();
  const problems: string[] = [];
  if (tk.approval_id !== a.approval_id) problems.push("approval_id");
  if (tk.task_id !== a.task_id) problems.push("task_id");
  if (tk.agent_id !== a.agent_id) problems.push("agent_id");
  if (tk.action_hash !== actionHash || a.action_hash !== actionHash) problems.push("action_hash");
  if (tk.decision !== "approve") problems.push("decision");
  if (tk.uses !== 1) problems.push("uses");
  if (typeof tk.jti !== "string" || !/^apv_[A-Za-z0-9_-]{8,64}$/.test(tk.jti)) problems.push("jti");
  if (!Number.isFinite(nbf) || !Number.isFinite(exp) || nbf > now + MAX_SKEW_S * 1000 || exp <= now || exp - nbf > APPROVAL_MAX_TTL_S * 1000) problems.push("time");
  const asked = typeof a.payload.amount === "number" ? a.payload.amount : null;
  if (asked !== null) {
    const max = (tk.max_amount ?? {}) as Record<string, unknown>;
    if (typeof max.value !== "number" || max.value < asked) problems.push("max_amount");
    if (typeof a.payload.currency === "string" && max.currency !== a.payload.currency) problems.push("currency");
  }
  if (problems.length) return err(`token doesn't match this request (${problems.join(", ")})`, 400);
  const r = await env.store.decideApproval(a.approval_id, { status: "approved", device_id: device.device_id, token: tk, token_sig: sig, jti: tk.jti as string });
  if (!r.ok) return r.reason === "not_found" ? err("no such approval request", 404) : err("that request was already decided", 409);
  return json({ ok: true, approval_id: a.approval_id, decision: "approve", expires_at: tk.exp });
}
