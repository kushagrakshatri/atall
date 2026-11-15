// C3: MCP Events (https://developers.openai.com/plugins/build/mcp-events.md). ChatGPT dots subscribe to
// `task.assigned` through events/subscribe; when the phone posts a task card (or asks for a nudge), the relay POSTs
// one signed event (Standard Webhooks) to each matching subscription. The event carries no content: only the task id
// and ref. The dot then reads the card with atall_get_task, as with any other wake-up.

import type { Caller } from "./oauth.ts";
import type { Deps, Env } from "./env.ts";
import { b64decode, b64std, canonicalJson, sha256 } from "./crypto.ts";
import type { EventSub, Task } from "./store.ts";
import { b64url, newId, nowIso } from "./util.ts";

export const TASK_ASSIGNED = "task.assigned";
/** Subscriptions last 7 days unless ChatGPT asks for less; it refreshes before refreshBefore. */
export const SUB_TTL_MS = 7 * 24 * 3600_000;
const MIN_SUB_TTL_MS = 3600_000;

export const EVENT_DEFINITIONS = [
  {
    name: TASK_ASSIGNED,
    description: "Kush's phone assigned you a new Atall task (or nudged you about one you haven't picked up). Read it with atall_get_task, then do it.",
    delivery: ["webhook"],
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    payloadSchema: {
      type: "object",
      properties: {
        task_id: { type: "string" },
        ref: { type: "string" },
        agent_id: { type: "string" },
        attempt: { type: "integer" },
      },
      required: ["task_id", "ref", "agent_id"],
      additionalProperties: false,
    },
  },
];

export class EventsError extends Error {
  constructor(readonly code: number, message: string, readonly data?: unknown) {
    super(message);
  }
}

/** Standard Webhooks signature: "v1," + base64(HMAC-SHA256(secret bytes, `${id}.${ts}.${body}`)). */
export async function webhookSignature(secret: string, id: string, ts: number, body: string): Promise<string> {
  const keyBytes = b64decode(secret.replace(/^whsec_/, ""));
  const key = await crypto.subtle.importKey("raw", keyBytes as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${id}.${ts}.${body}`));
  return "v1," + b64std(mac);
}

function secretOk(secret: unknown): secret is string {
  if (typeof secret !== "string" || !secret.startsWith("whsec_")) return false;
  try {
    const n = b64decode(secret.slice(6)).length;
    return n >= 24 && n <= 64;
  } catch {
    return false;
  }
}

/** HTTPS to a public host only: no loopback, private, link-local or IP-literal-internal targets, no credentials. */
export function callbackUrlOk(raw: unknown): raw is string {
  if (typeof raw !== "string" || raw.length > 2048) return false;
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return false;
  }
  if (u.protocol !== "https:" || u.username || u.password) return false;
  const h = u.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (!h || h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return false;
  if (/^\d+\.\d+\.\d+\.\d+$/.test(h)) {
    const [a, b] = h.split(".").map(Number);
    if (a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224) return false;
  }
  if (h.includes(":")) {
    if (h === "::1" || h === "::" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80") || h.startsWith("::ffff:")) return false;
  }
  return true;
}

async function post(deps: Deps, url: string, secret: string, subId: string, id: string, body: string): Promise<Response | null> {
  const ts = Math.floor(Date.now() / 1000);
  try {
    return await deps.fetch(url, {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.timeout(10_000),
      headers: {
        "Content-Type": "application/json",
        "webhook-id": id,
        "webhook-timestamp": String(ts),
        "webhook-signature": await webhookSignature(secret, id, ts, body),
        "X-MCP-Subscription-Id": subId,
      },
      body,
    });
  } catch {
    return null;
  }
}

async function subId(caller: Caller, url: string, name: string, args: Record<string, unknown>): Promise<string> {
  return "sub_" + b64url(await sha256(`${caller.agent_id}\n${url}\n${name}\n${canonicalJson(args)}`)).slice(0, 26);
}

export function eventsList() {
  return { events: EVENT_DEFINITIONS };
}

/** events/subscribe: validate, verify the callback with a signed challenge, store. */
export async function eventsSubscribe(params: Record<string, unknown>, caller: Caller, env: Env, deps: Deps) {
  const name = params.name;
  if (name !== TASK_ASSIGNED) throw new EventsError(-32602, `Unknown event: ${String(name)}`);
  const args = (params.arguments ?? {}) as Record<string, unknown>;
  if (typeof args !== "object" || Array.isArray(args) || Object.keys(args).length) throw new EventsError(-32602, "task.assigned takes no arguments");
  const delivery = (params.delivery ?? {}) as Record<string, unknown>;
  if (delivery.mode !== "webhook") throw new EventsError(-32602, "Only webhook delivery is supported");
  if (!callbackUrlOk(delivery.url)) throw new EventsError(-32602, "delivery.url must be a public https URL");
  if (!secretOk(delivery.secret)) throw new EventsError(-32602, "delivery.secret must be a whsec_ key of 24-64 bytes");
  const url = delivery.url as string;
  const secret = delivery.secret as string;
  const id = await subId(caller, url, TASK_ASSIGNED, args);

  // Verify the callback before any application data goes to it.
  const challenge = b64url(crypto.getRandomValues(new Uint8Array(24)));
  const res = await post(deps, url, secret, id, "msg_verification_" + newId("v").slice(2), JSON.stringify({ type: "verification", challenge }));
  if (!res) throw new EventsError(-32015, "Callback verification failed", { reason: "timeout" });
  let echoed = "";
  try {
    echoed = res.ok ? String((await res.json())?.challenge ?? "") : "";
  } catch { /* not JSON */ }
  if (!res.ok || echoed !== challenge) throw new EventsError(-32015, "Callback verification failed", { reason: "challenge_failed" });

  let ttl = SUB_TTL_MS;
  if (typeof params.ttlMs === "number" && Number.isFinite(params.ttlMs)) ttl = Math.min(SUB_TTL_MS, Math.max(MIN_SUB_TTL_MS, params.ttlMs));
  const expires = new Date(Date.now() + ttl).toISOString();
  const sub: EventSub = { sub_id: id, agent_id: caller.agent_id, conn_id: caller.conn_id, name: TASK_ASSIGNED, arguments: args, url, secret, created_at: nowIso(), expires_at: expires };
  await env.store.putEventSub(sub);
  return { id, refreshBefore: expires, cursor: null, truncated: false };
}

export async function eventsUnsubscribe(params: Record<string, unknown>, caller: Caller, env: Env) {
  const delivery = (params.delivery ?? {}) as Record<string, unknown>;
  if (typeof params.name === "string" && typeof delivery.url === "string") {
    await env.store.deleteEventSub(await subId(caller, delivery.url, params.name, (params.arguments ?? {}) as Record<string, unknown>));
  }
  return {};
}

/**
 * Delivers `task.assigned` for [task] to the subscriptions of [agentIds]. The event id is stable per (task, agent,
 * attempt) so ChatGPT de-duplicates retries. Returns how many subscriptions accepted it (2xx). A 410 drops the sub.
 */
export async function deliverTaskAssigned(env: Env, deps: Deps, task: Pick<Task, "task_id" | "ref">, agentIds: string[], attempt = 1): Promise<number> {
  if (!agentIds.length) return 0;
  const subs = await env.store.listEventSubs(agentIds);
  let ok = 0;
  await Promise.all(subs.map(async (s) => {
    const eventId = `evt_${task.task_id}_${s.agent_id}_${attempt}`;
    const body = JSON.stringify({
      eventId,
      name: TASK_ASSIGNED,
      timestamp: nowIso(),
      data: { task_id: task.task_id, ref: task.ref, agent_id: s.agent_id, attempt },
      cursor: null,
    });
    for (let i = 0; i < 2; i++) {
      const res = await post(deps, s.url, s.secret, s.sub_id, eventId, body);
      if (res?.ok) { ok++; return; }
      if (res && (res.status === 410 || res.status === 413)) {
        if (res.status === 410) await env.store.deleteEventSub(s.sub_id);
        return;
      }
    }
  }));
  return ok;
}

/** Agents with a live subscription among [candidates] (for the /phone/wake answer and the agents list). */
export async function subscribedAgents(env: Env, candidates: string[]): Promise<Set<string>> {
  return new Set((await env.store.listEventSubs(candidates)).map((s) => s.agent_id));
}
