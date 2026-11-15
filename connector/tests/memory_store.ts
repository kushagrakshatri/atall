// In-memory implementation of the Store interface with the same semantics as the SQL functions
// (single-use codes/requests, refresh reuse detection, fixed-window rate limits, presence throttle,
// last-50 pings, last-200 tasks/connections). Used by the Deno test suite.

import {
  ANSWERED_STATES,
  type AgentState,
  type AgentStepInput,
  type AgentStepResult,
  type AnswerResult,
  type Approval,
  type ApprovalKeyResult,
  type DecideResult,
  type Device,
  type EventSub,
  type EventType,
  type Manifest,
  type Question,
  RefTakenError,
  type RelayEvent,
  taskExpiry,
  type AuthCode,
  type AuthRequest,
  type Client,
  type Connection,
  normalizeRef,
  type Ping,
  type Presence,
  type Result,
  SEED_TASK,
  type Store,
  type Task,
  type TaskInput,
  type TokenRecord,
} from "../supabase/functions/atall/src/store.ts";
import { newId, newRef, nowIso, sha256b64url } from "../supabase/functions/atall/src/util.ts";

const clone = <T>(v: T): T => (v === null || v === undefined ? v : structuredClone(v));
const nowS = () => Math.floor(Date.now() / 1000);

interface TokenRow extends TokenRecord {
  kind: "code" | "access" | "refresh";
  used?: boolean;
  extra?: Record<string, string>;
}

export class MemoryStore implements Store {
  clients = new Map<string, Client>();
  cimd = new Map<string, { client: Client; exp: number }>();
  authReqs = new Map<string, AuthRequest>();
  conns = new Map<string, Connection>();
  connOrder: string[] = [];
  tokens = new Map<string, TokenRow>();
  rl = new Map<string, number>();
  tasks = new Map<string, Task>();
  taskOrder: string[] = [];
  results = new Map<string, Result>();
  pings: Ping[] = [];
  presence = new Map<string, Presence>();
  devices = new Map<string, Device>();
  nonces = new Map<string, number>(); // key → expiry (epoch s)
  events: (RelayEvent & { acked_at?: number; expires_at: number })[] = [];
  seq = 0;
  questions = new Map<string, Question & { answered_by?: string }>();
  approvals = new Map<string, Approval>();
  jtis = new Set<string>();
  subs = new Map<string, EventSub>();
  manifests = new Map<string, Manifest>();
  /** Tests move this to simulate time passing for TTLs (ms added to Date.now()). */
  clockSkewMs = 0;
  private now() {
    return Date.now() + this.clockSkewMs;
  }

  async getClient(id: string) {
    return clone(this.clients.get(id) ?? null);
  }
  async putClient(c: Client) {
    this.clients.set(c.client_id, clone(c));
  }
  async pinClientRedirect(clientId: string, redirectUri: string) {
    const c = this.clients.get(clientId);
    if (!c || c.kind !== "static" || !c.pin_redirect_on_first_use || c.redirect_uris.length) return false;
    this.clients.set(clientId, { ...c, redirect_uris: [redirectUri], pin_redirect_on_first_use: false });
    return true;
  }
  async getCimd(clientId: string) {
    const e = this.cimd.get(clientId);
    return e && e.exp > nowS() ? clone(e.client) : null;
  }
  async putCimd(c: Client, ttlS: number) {
    this.cimd.set(c.client_id, { client: clone(c), exp: nowS() + ttlS });
  }

  async putAuthRequest(r: AuthRequest) {
    const id = newId("ar");
    this.authReqs.set(id, clone(r));
    return id;
  }
  async peekAuthRequest(id: string) {
    const r = this.authReqs.get(id);
    return r && r.exp >= nowS() ? clone(r) : null;
  }
  async takeAuthRequest(id: string) {
    const r = this.authReqs.get(id) ?? null;
    this.authReqs.delete(id);
    return r && r.exp >= nowS() ? r : null;
  }

  async putCode(code: string, rec: AuthCode) {
    const { redirect_uri, code_challenge, resource, ...base } = rec;
    this.tokens.set(await sha256b64url(code), { ...base, kind: "code", extra: { redirect_uri, code_challenge, resource } });
  }
  async takeCode(code: string) {
    const h = await sha256b64url(code);
    const t = this.tokens.get(h);
    if (!t || t.kind !== "code") return null;
    this.tokens.delete(h);
    if (t.exp < nowS()) return null;
    const { kind: _k, extra, used: _u, ...rec } = t;
    return { ...rec, ...extra } as AuthCode;
  }

  async putConnection(c: Connection) {
    if (!this.conns.has(c.conn_id)) this.connOrder.push(c.conn_id);
    this.conns.set(c.conn_id, clone(c));
  }
  async getConnection(id: string) {
    return clone(this.conns.get(id) ?? null);
  }
  async revokeConnection(id: string, reason: string) {
    const c = this.conns.get(id);
    if (c && !c.revoked) this.conns.set(id, { ...c, revoked: true, revoked_reason: reason });
    for (const [h, t] of this.tokens) if (t.conn_id === id && (t.kind !== "refresh" || !t.used)) this.tokens.delete(h);
  }
  async listConnections() {
    return this.connOrder.slice(-200).map((id) => clone(this.conns.get(id)!));
  }

  private rec(t: TokenRow): TokenRecord {
    return { conn_id: t.conn_id, agent_id: t.agent_id, client_id: t.client_id, scope: t.scope, exp: t.exp };
  }
  async putAccessToken(token: string, rec: TokenRecord) {
    this.tokens.set(await sha256b64url(token), { ...rec, kind: "access" });
  }
  async getAccessToken(token: string) {
    const t = this.tokens.get(await sha256b64url(token));
    return t && t.kind === "access" && t.exp >= nowS() ? this.rec(t) : null;
  }
  async deleteAccessToken(token: string) {
    const h = await sha256b64url(token);
    if (this.tokens.get(h)?.kind === "access") this.tokens.delete(h);
  }
  async putRefreshToken(token: string, rec: TokenRecord) {
    this.tokens.set(await sha256b64url(token), { ...rec, kind: "refresh" });
  }
  async takeRefreshToken(token: string) {
    const t = this.tokens.get(await sha256b64url(token));
    if (!t || t.kind !== "refresh") return { rec: null };
    if (t.used) return { rec: null, reusedConn: t.conn_id };
    t.used = true;
    if (t.exp < nowS()) return { rec: null };
    return { rec: this.rec(t) };
  }
  async deleteRefreshToken(token: string) {
    const h = await sha256b64url(token);
    const t = this.tokens.get(h);
    if (!t || t.kind !== "refresh" || t.used) return null;
    this.tokens.delete(h);
    return this.rec(t);
  }

  async count(key: string, windowS: number) {
    return this.rl.get(`${key}:${Math.floor(nowS() / windowS)}`) ?? 0;
  }
  async hit(key: string, limit: number, windowS: number) {
    const k = `${key}:${Math.floor(nowS() / windowS)}`;
    const n = (this.rl.get(k) ?? 0) + 1;
    this.rl.set(k, n);
    return n <= limit;
  }

  async createTask(input: TaskInput, createdBy: Task["created_by"] = "owner_admin", fixedId?: string, fixedRef?: string) {
    let ref = fixedRef ?? newRef();
    // An expired task that hasn't been purged yet must not hold its ref (same as the SQL).
    for (const t of [...this.tasks.values()]) if (t.ref === ref && this.expired(t)) this.dropTask(t.task_id);
    const refTaken = (r: string) => [...this.tasks.values()].some((t) => t.ref === r);
    if (refTaken(ref)) {
      if (fixedRef) throw new RefTakenError(`ref ${fixedRef} is taken`);
      while (refTaken(ref)) ref = newRef();
    }
    const task: Task = {
      task_id: fixedId ?? newId("tsk"),
      ref,
      title: input.title,
      goal: input.goal,
      constraints: input.constraints ?? [],
      context: input.context ?? [],
      done_when: input.done_when ?? "",
      do_not_share: input.do_not_share ?? [],
      to: input.to && input.to.length ? input.to : ["@all"],
      deadline: input.deadline ?? null,
      created_at: nowIso(),
      created_by: createdBy,
      per_agent: {},
    };
    if (input.output) task.output = input.output;
    if (input.done_criteria?.length) task.done_criteria = input.done_criteria;
    const exp = input.expires_at !== undefined ? input.expires_at : createdBy === "seed" ? null : taskExpiry(input.deadline);
    if (exp) task.expires_at = exp;
    if (input.phone_sig) task.phone_sig = input.phone_sig;
    if (this.tasks.has(task.task_id)) throw new Error("duplicate task_id");
    this.tasks.set(task.task_id, task);
    this.taskOrder.push(task.task_id);
    return clone(task);
  }
  private expired(t: Task) {
    return !!t.expires_at && Date.parse(t.expires_at) <= this.now();
  }
  private dropTask(id: string) {
    this.tasks.delete(id);
    this.taskOrder = this.taskOrder.filter((x) => x !== id);
    for (const k of [...this.results.keys()]) if (k.startsWith(`${id}:`)) this.results.delete(k);
    this.events = this.events.filter((e) => e.task_id !== id);
    for (const [k, q] of this.questions) if (q.task_id === id) this.questions.delete(k);
  }
  async getTask(id: string) {
    const t = this.tasks.get(id);
    return clone(t && !this.expired(t) ? t : null);
  }
  async getTaskByIdOrRef(idOrRef: string) {
    const { id, ref } = normalizeRef(idOrRef);
    if (id) return this.getTask(id);
    return clone([...this.tasks.values()].find((t) => t.ref === ref && !this.expired(t)) ?? null);
  }
  async listTasks() {
    return this.taskOrder.map((id) => this.tasks.get(id)!).filter((t) => !this.expired(t)).slice(-200).map(clone);
  }
  async ensureSeed(enabled: boolean) {
    if (!enabled || this.tasks.has(SEED_TASK.task_id)) return;
    await this.createTask({ title: SEED_TASK.title, goal: SEED_TASK.goal, done_when: SEED_TASK.done_when, to: [...SEED_TASK.to] }, "seed", SEED_TASK.task_id, SEED_TASK.ref);
  }
  async reset() {
    this.events = [];
    this.questions.clear();
    this.tasks.clear();
    this.taskOrder = [];
    this.results.clear();
    this.pings = [];
    this.presence.clear();
  }

  async getResult(taskId: string, agentId: string) {
    return clone(this.results.get(`${taskId}:${agentId}`) ?? null);
  }
  async listResults(taskId: string) {
    return [...this.results.values()].filter((r) => r.task_id === taskId).sort((a, b) => a.agent_id.localeCompare(b.agent_id)).map(clone);
  }
  async postResult(r: Omit<Result, "replaced_previous">, state: AgentState) {
    const t = this.tasks.get(r.task_id);
    if (!t) throw new Error(`unknown task ${r.task_id}`);
    const key = `${r.task_id}:${r.agent_id}`;
    const stored: Result = { ...clone(r), replaced_previous: this.results.has(key) };
    this.results.set(key, stored);
    t.per_agent[r.agent_id] = { ...t.per_agent[r.agent_id], state, updated_at: r.recorded_at };
    this.emit("result", t.task_id, r.agent_id, r.conn_id, r.client_name, {
      result_id: stored.result_id, ref: stored.ref, status: stored.status, state, summary: stored.summary, claims: stored.claims,
      artifacts: stored.artifacts, replaced_previous: stored.replaced_previous, recorded_at: stored.recorded_at,
    });
    return clone(stored);
  }

  async addPing(p: Ping) {
    this.pings.push(clone(p));
    this.pings = this.pings.slice(-50);
  }
  async listPings() {
    return this.pings.map(clone);
  }
  async touchPresence(p: Presence) {
    const prev = this.presence.get(p.agent_id);
    const gap = prev ? Date.parse(p.last_seen) - Date.parse(prev.last_seen) : null;
    if (gap === null || gap >= 600_000) {
      this.emit("presence", undefined, p.agent_id, p.conn_id, p.client_name, { status: "active", last_tool: p.last_tool, idle_s: gap === null ? null : Math.floor(gap / 1000) });
    }
    if (prev && prev.last_tool === p.last_tool && prev.conn_id === p.conn_id && Date.parse(p.last_seen) - Date.parse(prev.last_seen) < 60_000) return;
    this.presence.set(p.agent_id, clone(p));
  }
  async listPresence() {
    return [...this.presence.values()].sort((a, b) => a.agent_id.localeCompare(b.agent_id)).map(clone);
  }

  // ---- C1 ----
  private emit(type: EventType, taskId: string | undefined, agentId: string, connId: string, clientName: string, payload: Record<string, unknown>) {
    const e: RelayEvent & { expires_at: number } = {
      cursor: ++this.seq, type, agent_id: agentId, conn_id: connId, client_name: clientName, at: new Date(this.now()).toISOString(),
      payload: clone(payload), expires_at: this.now() + 7 * 24 * 3600_000,
    };
    if (taskId) e.task_id = taskId;
    this.events.push(e);
    return e.cursor;
  }
  async putDevice(d: Device) {
    this.devices.set(d.device_id, clone(d));
  }
  async getDevice(id: string) {
    return clone(this.devices.get(id) ?? null);
  }
  async listDevices() {
    return [...this.devices.values()].map(clone);
  }
  async revokeDevice(id: string, reason: string) {
    const d = this.devices.get(id);
    if (!d) return false;
    if (!d.revoked) this.devices.set(id, { ...d, revoked: true, revoked_reason: reason });
    return true;
  }
  async markPhoneNonce(deviceId: string, ts: number, sigHash: string) {
    const now = nowS();
    for (const [k, exp] of this.nonces) if (exp < now) this.nonces.delete(k);
    const k = `${deviceId}|${ts}|${sigHash}`;
    if (this.nonces.has(k)) return false;
    this.nonces.set(k, now + 600);
    const d = this.devices.get(deviceId);
    if (d) d.last_seen = nowIso();
    return true;
  }
  async agentStep(s: AgentStepInput): Promise<AgentStepResult> {
    const t = this.tasks.get(s.task_id);
    if (!t || this.expired(t)) return { ok: false, reason: "unknown_task" };
    const cur = t.per_agent[s.agent_id];
    const curState = cur?.state ?? "SUBMITTED";
    if (s.refuse_if_terminal && ANSWERED_STATES.includes(curState)) return { ok: false, reason: "terminal", state: curState };
    const now = this.now();
    if (s.throttle_s !== undefined && cur?.progress_at && now - Date.parse(cur.progress_at) < s.throttle_s * 1000) {
      return { ok: false, reason: "throttled", state: curState, retry_after_s: Math.max(1, Math.ceil(s.throttle_s - (now - Date.parse(cur.progress_at)) / 1000)) };
    }
    const iso = new Date(now).toISOString();
    const entry = { ...(cur ?? {}) } as Task["per_agent"][string];
    if (s.state) Object.assign(entry, { state: s.state, updated_at: iso });
    if (s.throttle_s !== undefined) entry.progress_at = iso;
    if (entry.state || entry.progress_at) t.per_agent[s.agent_id] = entry;
    if (s.question) {
      this.questions.set(s.question.question_id, {
        question_id: s.question.question_id, task_id: t.task_id, ref: t.ref, agent_id: s.agent_id, conn_id: s.conn_id,
        text: s.question.text, choices: [...s.question.choices], asked_at: iso, answer: null, answered_at: null,
      });
    }
    const cursor = s.event ? this.emit(s.event.type, t.task_id, s.agent_id, s.conn_id, s.client_name, s.event.payload) : undefined;
    return { ok: true, ref: t.ref, state: entry.state ?? "SUBMITTED", prev_state: curState, ...(cursor !== undefined ? { cursor } : {}) };
  }
  async answerQuestion(taskId: string, questionId: string, text: string, deviceId: string): Promise<AnswerResult> {
    const q = this.questions.get(questionId);
    if (!q || q.task_id !== taskId) return { ok: false, reason: "not_found" };
    if (q.answer != null) return { ok: false, reason: "already_answered" };
    Object.assign(q, { answer: text, answered_at: nowIso(), answered_by: deviceId });
    const t = this.tasks.get(taskId)!;
    const open = [...this.questions.values()].some((x) => x.task_id === taskId && x.agent_id === q.agent_id && x.answer == null);
    if (t.per_agent[q.agent_id]?.state === "INPUT_REQUIRED" && !open) Object.assign(t.per_agent[q.agent_id], { state: "WORKING", updated_at: nowIso() });
    return { ok: true, agent_id: q.agent_id, ref: t.ref };
  }
  async listQuestions(agentId: string, taskId?: string) {
    return [...this.questions.values()]
      .filter((q) => q.agent_id === agentId && (!taskId || q.task_id === taskId))
      .filter((q) => { const t = this.tasks.get(q.task_id); return t && !this.expired(t); })
      .map(({ answered_by: _a, ...q }) => clone(q));
  }
  async putManifest(m: Manifest) {
    this.manifests.set(m.agent_id, clone(m));
    return this.emit("manifest", undefined, m.agent_id, m.conn_id, m.client_name, m.manifest);
  }
  async getManifest(agentId: string) {
    return clone(this.manifests.get(agentId) ?? null);
  }
  private purge() {
    const now = this.now();
    for (const t of [...this.tasks.values()]) if (this.expired(t)) this.dropTask(t.task_id);
    this.events = this.events.filter((e) => e.expires_at > now);
  }
  async listEvents(after: number, limit: number) {
    this.purge();
    return this.events.filter((e) => e.cursor > after).slice(0, Math.min(Math.max(limit, 1), 500)).map(({ acked_at: _a, expires_at: _e, ...e }) => clone(e));
  }
  async ackEvents(cursor: number) {
    const now = this.now();
    let n = 0;
    for (const e of this.events) {
      if (e.cursor <= cursor && e.acked_at === undefined) {
        e.acked_at = now;
        e.expires_at = Math.min(e.expires_at, now + 24 * 3600_000);
        n++;
      }
    }
    return n;
  }

  // ---- C3 ----
  async setApprovalKey(deviceId: string, spkiB64: string): Promise<ApprovalKeyResult> {
    const d = this.devices.get(deviceId);
    if (!d || d.revoked) return "no_device";
    if (!d.approval_key_spki_b64) {
      d.approval_key_spki_b64 = spkiB64;
      return "set";
    }
    return d.approval_key_spki_b64 === spkiB64 ? "same" : "conflict";
  }
  async putApproval(a: Approval) {
    this.approvals.set(a.approval_id, clone(a));
  }
  async getApproval(id: string) {
    const a = this.approvals.get(id);
    if (!a) return null;
    const t = this.tasks.get(a.task_id);
    return t && !this.expired(t) ? clone(a) : null;
  }
  async decideApproval(id: string, d: { status: "approved" | "denied"; device_id: string; token?: Record<string, unknown> | null; token_sig?: string | null; jti?: string | null }): Promise<DecideResult> {
    const a = this.approvals.get(id);
    if (!a) return { ok: false, reason: "not_found" };
    if (a.status !== "pending" || (d.jti && this.jtis.has(d.jti))) return { ok: false, reason: "already_decided" };
    if (d.jti) this.jtis.add(d.jti);
    Object.assign(a, { status: d.status, decided_at: nowIso(), decided_by: d.device_id, token: d.token ?? null, token_sig: d.token_sig ?? null, jti: d.jti ?? null });
    const t = this.tasks.get(a.task_id);
    const e = t?.per_agent[a.agent_id];
    if (e?.state === "AUTH_REQUIRED") {
      const open = [...this.approvals.values()].some((x) => x.task_id === a.task_id && x.agent_id === a.agent_id && x.status === "pending");
      if (!open) Object.assign(e, { state: "WORKING", updated_at: nowIso() });
    }
    return { ok: true, approval: clone(a) };
  }
  async listApprovals(agentId: string, taskId?: string) {
    return [...this.approvals.values()].filter((a) => a.agent_id === agentId && (!taskId || a.task_id === taskId)).map(clone);
  }
  async putEventSub(sub: EventSub) {
    this.subs.set(sub.sub_id, clone(sub));
  }
  async listEventSubs(agentIds: string[]) {
    const now = this.now();
    return [...this.subs.values()].filter((x) => agentIds.includes(x.agent_id) && Date.parse(x.expires_at) > now).map(clone);
  }
  async deleteEventSub(subId: string) {
    return this.subs.delete(subId);
  }
}
