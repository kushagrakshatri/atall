// Postgres data layer. Every operation is one SQL function from
// supabase/migrations/*_atall_c0.sql, called as `public.atall_<op>(p jsonb) returns jsonb`.
// In production the call goes through PostgREST (POST {SUPABASE_URL}/rest/v1/rpc/atall_<op>)
// with the service-role key; tests can plug in a direct Postgres executor instead.

import {
  type AgentState,
  type AgentStepInput,
  type AgentStepResult,
  type AnswerResult,
  type Approval,
  type ApprovalKeyResult,
  type DecideResult,
  type Device,
  type EventSub,
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
  REFRESH_TTL_S,
  ACCESS_TTL_S,
  type Result,
  type Store,
  type Task,
  type TaskInput,
  type TokenRecord,
} from "./store.ts";
import { newId, newRef, nowIso, sha256b64url } from "./util.ts";

/** Calls `public.<fn>(p => <json>)` and returns the decoded jsonb result (null for SQL NULL). */
export type Rpc = (fn: string, p: Record<string, unknown>) => Promise<unknown>;

export class RpcError extends Error {
  constructor(message: string, readonly code?: string, readonly status?: number) {
    super(message);
  }
}

/** PostgREST transport. Works with the legacy service_role JWT and the newer sb_secret_ keys. */
export function postgrestRpc(supabaseUrl: string, serviceKey: string, f: typeof fetch = fetch): Rpc {
  const base = `${supabaseUrl.replace(/\/+$/, "")}/rest/v1/rpc/`;
  const headers: Record<string, string> = {
    apikey: serviceKey,
    "Content-Type": "application/json",
    Accept: "application/json",
  };
  // A JWT goes in Authorization too; an sb_secret_ key is accepted via apikey alone.
  if (serviceKey.startsWith("eyJ")) headers.Authorization = `Bearer ${serviceKey}`;
  return async (fn, p) => {
    const res = await f(base + fn, { method: "POST", headers, body: JSON.stringify({ p }) });
    const text = await res.text();
    if (!res.ok) {
      let code: string | undefined;
      let message = `PostgREST ${fn} → HTTP ${res.status}`;
      try {
        const e = JSON.parse(text);
        code = e.code;
        if (e.message) message += `: ${e.message}`;
      } catch { /* not JSON */ }
      throw new RpcError(message, code, res.status);
    }
    return text ? JSON.parse(text) : null;
  };
}

const UNIQUE_VIOLATION = "23505";

export class PgStore implements Store {
  constructor(private rpc: Rpc) {}

  private async call<T>(fn: string, p: Record<string, unknown> = {}): Promise<T> {
    return (await this.rpc(`atall_${fn}`, p)) as T;
  }

  // ---- OAuth clients -------------------------------------------------------
  getClient(id: string) {
    return this.call<Client | null>("get_client", { client_id: id });
  }
  async putClient(c: Client) {
    await this.call("put_client", c as unknown as Record<string, unknown>);
  }
  pinClientRedirect(clientId: string, redirectUri: string) {
    return this.call<boolean>("pin_client_redirect", { client_id: clientId, redirect_uri: redirectUri });
  }
  getCimd(clientId: string) {
    return this.call<Client | null>("get_cimd", { client_id: clientId });
  }
  async putCimd(c: Client, ttlS: number) {
    await this.call("put_cimd", { client: c, ttl_s: ttlS });
  }

  // ---- consent requests ----------------------------------------------------
  async putAuthRequest(r: AuthRequest): Promise<string> {
    const id = newId("ar");
    await this.call("put_auth_request", { req_id: id, data: r });
    return id;
  }
  peekAuthRequest(id: string) {
    return this.call<AuthRequest | null>("peek_auth_request", { req_id: id });
  }
  takeAuthRequest(id: string) {
    return this.call<AuthRequest | null>("take_auth_request", { req_id: id });
  }

  // ---- codes ----------------------------------------------------------------
  async putCode(code: string, rec: AuthCode) {
    await this.call("put_code", { hash: await sha256b64url(code), rec });
  }
  async takeCode(code: string) {
    return this.call<AuthCode | null>("take_code", { hash: await sha256b64url(code) });
  }

  // ---- connections ------------------------------------------------------------
  async putConnection(c: Connection) {
    await this.call("put_connection", c as unknown as Record<string, unknown>);
  }
  getConnection(id: string) {
    return this.call<Connection | null>("get_connection", { conn_id: id });
  }
  async revokeConnection(id: string, reason: string) {
    await this.call("revoke_connection", { conn_id: id, reason });
  }
  listConnections() {
    return this.call<Connection[]>("list_connections");
  }

  // ---- tokens -----------------------------------------------------------------
  async putAccessToken(token: string, rec: TokenRecord, _ttl = ACCESS_TTL_S) {
    // rec.exp is authoritative (the Worker used ttl only for KV expiry)
    await this.call("put_token", { hash: await sha256b64url(token), kind: "access", rec });
  }
  async getAccessToken(token: string) {
    return this.call<TokenRecord | null>("get_access_token", { hash: await sha256b64url(token) });
  }
  async deleteAccessToken(token: string) {
    await this.call("delete_access_token", { hash: await sha256b64url(token) });
  }
  async putRefreshToken(token: string, rec: TokenRecord) {
    await this.call("put_token", { hash: await sha256b64url(token), kind: "refresh", rec: { ...rec, exp: rec.exp ?? Math.floor(Date.now() / 1000) + REFRESH_TTL_S } });
  }
  async takeRefreshToken(token: string) {
    const r = await this.call<{ rec?: TokenRecord; reused_conn?: string }>("take_refresh_token", { hash: await sha256b64url(token) });
    return { rec: r?.rec ?? null, reusedConn: r?.reused_conn ?? undefined };
  }
  async deleteRefreshToken(token: string) {
    return this.call<TokenRecord | null>("delete_refresh_token", { hash: await sha256b64url(token) });
  }

  // ---- rate limiting ------------------------------------------------------------
  async count(key: string, windowS: number) {
    return Number(await this.call<number>("rl_count", { key, window_s: windowS }));
  }
  async hit(key: string, limit: number, windowS: number) {
    return Number(await this.call<number>("rl_hit", { key, window_s: windowS })) <= limit;
  }

  // ---- tasks --------------------------------------------------------------------
  async createTask(input: TaskInput, createdBy: Task["created_by"] = "owner_admin", fixedId?: string, fixedRef?: string): Promise<Task> {
    for (let attempt = 0; ; attempt++) {
      const row = {
        task_id: fixedId ?? newId("tsk"),
        ref: fixedRef ?? newRef(),
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
        output: input.output ?? null,
        done_criteria: input.done_criteria ?? [],
        expires_at: input.expires_at !== undefined ? input.expires_at : createdBy === "seed" ? null : taskExpiry(input.deadline),
        phone_sig: input.phone_sig ?? null,
      };
      try {
        return await this.call<Task>("create_task", row);
      } catch (e) {
        // 4-char refs can collide; KV silently overwrote the ref index, here we draw a new one.
        if (e instanceof RpcError && e.code === UNIQUE_VIOLATION && !fixedRef && attempt < 5) continue;
        if (e instanceof RpcError && e.code === UNIQUE_VIOLATION && fixedRef) throw new RefTakenError(`ref ${fixedRef} is taken`);
        throw e;
      }
    }
  }
  getTask(id: string) {
    return this.call<Task | null>("get_task", { task_id: id });
  }
  getTaskByIdOrRef(idOrRef: string) {
    const { id, ref } = normalizeRef(idOrRef);
    return this.call<Task | null>("get_task", id ? { task_id: id } : { ref });
  }
  listTasks() {
    return this.call<Task[]>("list_tasks");
  }
  async ensureSeed(enabled: boolean) {
    if (enabled) await this.call("ensure_seed");
  }
  async reset() {
    await this.call("reset");
  }

  // ---- results ------------------------------------------------------------------
  getResult(taskId: string, agentId: string) {
    return this.call<Result | null>("get_result", { task_id: taskId, agent_id: agentId });
  }
  listResults(taskId: string) {
    return this.call<Result[]>("list_results", { task_id: taskId });
  }
  postResult(r: Omit<Result, "replaced_previous">, state: AgentState) {
    return this.call<Result>("post_result", { result: r, state });
  }

  // ---- pings / presence ------------------------------------------------------------
  async addPing(p: Ping) {
    await this.call("add_ping", p as unknown as Record<string, unknown>);
  }
  listPings() {
    return this.call<Ping[]>("list_pings");
  }
  async touchPresence(p: Presence) {
    await this.call("touch_presence", p as unknown as Record<string, unknown>);
  }
  listPresence() {
    return this.call<Presence[]>("list_presence");
  }

  // ---- C1: devices, replay guard, agent steps, questions, manifests, events ------------
  async putDevice(d: Device) {
    await this.call("put_device", d as unknown as Record<string, unknown>);
  }
  getDevice(id: string) {
    return this.call<Device | null>("get_device", { device_id: id });
  }
  listDevices() {
    return this.call<Device[]>("list_devices");
  }
  revokeDevice(id: string, reason: string) {
    return this.call<boolean>("revoke_device", { device_id: id, reason });
  }
  markPhoneNonce(deviceId: string, ts: number, sigHash: string) {
    return this.call<boolean>("phone_nonce", { device_id: deviceId, ts, sig_hash: sigHash });
  }
  agentStep(s: AgentStepInput) {
    return this.call<AgentStepResult>("agent_step", s as unknown as Record<string, unknown>);
  }
  answerQuestion(taskId: string, questionId: string, text: string, deviceId: string) {
    return this.call<AnswerResult>("answer_question", { task_id: taskId, question_id: questionId, text, device_id: deviceId });
  }
  listQuestions(agentId: string, taskId?: string) {
    return this.call<Question[]>("list_questions", taskId ? { agent_id: agentId, task_id: taskId } : { agent_id: agentId });
  }
  async putManifest(m: Manifest) {
    return Number(await this.call<number>("put_manifest", m as unknown as Record<string, unknown>));
  }
  getManifest(agentId: string) {
    return this.call<Manifest | null>("get_manifest", { agent_id: agentId });
  }
  listEvents(after: number, limit: number) {
    return this.call<RelayEvent[]>("list_events", { after, limit });
  }
  async ackEvents(cursor: number) {
    return Number(await this.call<number>("ack_events", { cursor }));
  }

  // ---- C3: approval tokens, MCP Events subscriptions ---------------------------------
  setApprovalKey(deviceId: string, spkiB64: string) {
    return this.call<ApprovalKeyResult>("set_approval_key", { device_id: deviceId, spki: spkiB64 });
  }
  async putApproval(a: Approval) {
    await this.call("put_approval", a as unknown as Record<string, unknown>);
  }
  getApproval(id: string) {
    return this.call<Approval | null>("get_approval", { approval_id: id });
  }
  decideApproval(id: string, d: { status: "approved" | "denied"; device_id: string; token?: Record<string, unknown> | null; token_sig?: string | null; jti?: string | null }) {
    return this.call<DecideResult>("decide_approval", { approval_id: id, ...d });
  }
  listApprovals(agentId: string, taskId?: string) {
    return this.call<Approval[]>("list_approvals", taskId ? { agent_id: agentId, task_id: taskId } : { agent_id: agentId });
  }
  async putEventSub(sub: EventSub) {
    await this.call("put_event_sub", sub as unknown as Record<string, unknown>);
  }
  listEventSubs(agentIds: string[]) {
    return this.call<EventSub[]>("list_event_subs", { agent_ids: agentIds });
  }
  deleteEventSub(subId: string) {
    return this.call<boolean>("delete_event_sub", { sub_id: subId });
  }
}
