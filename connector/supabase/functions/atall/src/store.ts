// Data-layer interface and record types. Two implementations:
//  - PgStore (store_pg.ts): Postgres tables + SQL functions, called through PostgREST RPC with the
//    service-role key. Every single-use / read-modify-write step that was racy on KV is one atomic
//    SQL statement or one locked transaction there.
//  - MemoryStore (tests/memory_store.ts): same semantics in Maps, for the Deno test suite.

export const ACCESS_TTL_S = 3600; // 1 h
export const REFRESH_TTL_S = 30 * 24 * 3600; // 30 d, rotated on every use
export const CODE_TTL_S = 600; // 10 min
export const HEADER_TOKEN_TTL_S = 90 * 24 * 3600; // 90 d
export const CIMD_CACHE_TTL_S = 3600;

export const KNOWN_AGENTS: Record<string, string> = {
  kdot: "kDot (ChatGPT dot)",
  grok: "Grok Bot",
  duet: "Claude Duet",
  claude: "Claude (claude.ai)",
  hark: "Hark",
  muse: "Meta Muse",
};
export const AGENT_ID_RE = /^[a-z][a-z0-9_-]{1,31}$/;

export type AuthMethod = "none" | "client_secret_post" | "client_secret_basic";

export interface Client {
  client_id: string;
  client_name: string;
  redirect_uris: string[];
  token_endpoint_auth_method: AuthMethod;
  client_secret_hash?: string;
  kind: "dcr" | "static" | "cimd";
  /** Static clients only: accept the first redirect_uri Kush approves, then pin it. */
  pin_redirect_on_first_use?: boolean;
  created_at: string;
}

export interface AuthCode {
  client_id: string;
  redirect_uri: string;
  code_challenge: string;
  scope: string;
  resource: string;
  agent_id: string;
  conn_id: string;
  exp: number;
}

export interface Connection {
  conn_id: string;
  agent_id: string;
  client_id: string;
  client_name: string;
  kind: "oauth" | "header";
  created_at: string;
  revoked?: boolean;
  revoked_reason?: string;
}

export interface TokenRecord {
  conn_id: string;
  agent_id: string;
  client_id: string;
  scope: string;
  exp: number; // epoch seconds
}

export interface AuthRequest {
  client_id: string;
  client_name: string;
  redirect_uri: string;
  state?: string;
  code_challenge: string;
  scope: string;
  resource: string;
  suggested_agent: string;
  pin: boolean;
  exp: number;
}

/** Per-agent task states. C1: SUBMITTED → WORKING (claim) → INPUT_REQUIRED (ask) / AUTH_REQUIRED (approval) → DONE / PARTIAL
 *  (REJECTED when the agent declines). COMPLETED is the C0 name for an answered task and still appears on old rows. */
export type AgentState = "SUBMITTED" | "WORKING" | "INPUT_REQUIRED" | "AUTH_REQUIRED" | "DONE" | "PARTIAL" | "COMPLETED" | "FAILED" | "CANCELED" | "REJECTED";
/** States in which this agent has answered (the task leaves its inbox). */
export const ANSWERED_STATES: readonly string[] = ["DONE", "PARTIAL", "COMPLETED", "REJECTED"];

export interface AgentEntry {
  state: AgentState;
  updated_at: string;
  /** Last atall_progress call (ISO), for the 1-per-30-s throttle. */
  progress_at?: string;
}

/** What the phone signed when it posted a task (POST /phone/tasks). Kept with the card so agents and auditors can check it. */
export interface PhoneSignature {
  device_id: string;
  device_name: string;
  device_public_key_spki_b64: string;
  alg: "ES256";
  /** base64url signature exactly as the phone sent it (DER or raw r||s). */
  sig: string;
  /** `${METHOD}\n${PATH}\n${TS}\n${base64url(sha256(body))}` */
  signed_string: string;
  /** The request body (UTF-8) whose SHA-256 is in signed_string. */
  body: string;
}

export interface Task {
  task_id: string;
  ref: string;
  title: string;
  goal: string;
  constraints: string[];
  context: string[];
  done_when: string;
  do_not_share: string[];
  to: string[];
  deadline: string | null;
  created_at: string;
  created_by: "owner_admin" | "seed" | "phone";
  per_agent: Record<string, AgentEntry>;
  /** C1 card fields (absent on C0 rows). */
  output?: string | null;
  done_criteria?: string[];
  /** deadline + 24 h, or created + 7 d; null for the built-in check task and C0 rows. */
  expires_at?: string | null;
  phone_sig?: PhoneSignature | null;
}

export interface Claim {
  text: string;
  kind?: "fact" | "estimate" | "opinion" | "plan";
  confidence?: number;
  sources?: { url: string; title?: string }[];
}

export interface Result {
  result_id: string;
  task_id: string;
  ref: string;
  agent_id: string;
  conn_id: string;
  client_name: string;
  status: "done" | "partial" | "declined";
  summary: string;
  claims: Claim[];
  artifacts: { name: string; url: string; mime?: string }[];
  recorded_at: string;
  replaced_previous: boolean;
}

export interface Ping {
  ping_id: string;
  agent_id: string;
  conn_id: string;
  client_name: string;
  note: string;
  at: string;
}

export interface Presence {
  agent_id: string;
  conn_id: string;
  client_name: string;
  last_seen: string;
  last_tool: string;
}

export type TaskInput = Partial<Task> & { title: string; goal: string };

export class RefTakenError extends Error {}

// ---- C1: phone devices, events, questions, manifests -----------------------

export interface Device {
  device_id: string;
  device_name: string;
  public_key_spki_b64: string;
  created_at: string;
  last_seen?: string | null;
  revoked?: boolean;
  revoked_reason?: string | null;
  /** C3: the phone's approval key (P-256 SPKI, base64). Pinned on first registration; it signs only after a fingerprint. */
  approval_key_spki_b64?: string | null;
}

/** C3: one atall_request_approval and Kush's decision on it. */
export interface Approval {
  approval_id: string;
  task_id: string;
  ref: string;
  agent_id: string;
  conn_id: string;
  client_name: string;
  /** The approval_request event payload exactly as emitted: the token's action_hash is sha256 of its canonical JSON. */
  payload: Record<string, unknown>;
  action_hash: string;
  requested_at: string;
  status: "pending" | "approved" | "denied";
  decided_at?: string | null;
  decided_by?: string | null;
  token?: Record<string, unknown> | null;
  token_sig?: string | null;
  jti?: string | null;
}

/** C3: an MCP Events subscription (ChatGPT dots: `task.assigned`). */
export interface EventSub {
  sub_id: string;
  agent_id: string;
  conn_id: string;
  name: string;
  arguments: Record<string, unknown>;
  url: string;
  secret: string;
  created_at: string;
  expires_at: string;
}

export type ApprovalKeyResult = "set" | "same" | "conflict" | "no_device";
export type DecideResult = { ok: true; approval: Approval } | { ok: false; reason: "not_found" | "already_decided" };

export type EventType = "result" | "progress" | "question" | "claim" | "approval_request" | "presence" | "manifest";

/** An event as stored (relay_sig is added when the phone reads it). */
export interface RelayEvent {
  cursor: number;
  type: EventType;
  task_id?: string;
  agent_id: string;
  conn_id: string;
  client_name: string;
  at: string;
  payload: Record<string, unknown>;
}

export interface Question {
  question_id: string;
  task_id: string;
  ref: string;
  agent_id: string;
  conn_id: string;
  text: string;
  choices: string[];
  asked_at: string;
  answer?: string | null;
  answered_at?: string | null;
}

export interface Manifest {
  agent_id: string;
  conn_id: string;
  client_name: string;
  manifest: Record<string, unknown>;
  updated_at: string;
}

export interface AgentStepInput {
  task_id: string;
  agent_id: string;
  conn_id: string;
  client_name: string;
  /** New per-agent state, if this step changes it. */
  state?: AgentState;
  /** Refuse when this agent already answered the task (DONE/PARTIAL/COMPLETED/REJECTED). */
  refuse_if_terminal?: boolean;
  /** atall_progress: at most one step per this many seconds per (task, agent). */
  throttle_s?: number;
  question?: { question_id: string; text: string; choices: string[] };
  event?: { type: EventType; payload: Record<string, unknown> };
}

export type AgentStepResult =
  | { ok: true; ref: string; state: string; prev_state: string; cursor?: number }
  | { ok: false; reason: "unknown_task" | "terminal" | "throttled"; state?: string; retry_after_s?: number };

export type AnswerResult = { ok: true; agent_id: string; ref: string } | { ok: false; reason: "not_found" | "already_answered" };

/** The built-in C0 check task. The SQL migration seeds the same row (atall_ensure_seed). */
export const SEED_TASK = {
  task_id: "tsk_c0check",
  ref: "c0ck",
  title: "Atall C0 connector check",
  goal: "Post one short result with atall_post_result: say which assistant you are, and give one claim with a source URL for something you looked up today.",
  done_when: "One result posted with status done and at least one claim.",
  to: ["@all"],
} as const;

export interface Store {
  // OAuth clients
  getClient(id: string): Promise<Client | null>;
  putClient(c: Client): Promise<void>;
  /** Atomically pins a static client's first redirect_uri; false if it was already pinned or isn't pinnable. */
  pinClientRedirect(clientId: string, redirectUri: string): Promise<boolean>;
  // CIMD document cache (keyed by the client_id URL)
  getCimd(clientId: string): Promise<Client | null>;
  putCimd(c: Client, ttlS: number): Promise<void>;
  // consent-page round trip
  putAuthRequest(r: AuthRequest): Promise<string>;
  peekAuthRequest(id: string): Promise<AuthRequest | null>;
  takeAuthRequest(id: string): Promise<AuthRequest | null>;
  // authorization codes (by hash, single use)
  putCode(code: string, rec: AuthCode): Promise<void>;
  takeCode(code: string): Promise<AuthCode | null>;
  // connections
  putConnection(c: Connection): Promise<void>;
  getConnection(id: string): Promise<Connection | null>;
  revokeConnection(id: string, reason: string): Promise<void>;
  listConnections(): Promise<Connection[]>;
  // tokens (by hash)
  putAccessToken(token: string, rec: TokenRecord, ttl?: number): Promise<void>;
  getAccessToken(token: string): Promise<TokenRecord | null>;
  deleteAccessToken(token: string): Promise<void>;
  putRefreshToken(token: string, rec: TokenRecord): Promise<void>;
  /** Returns the record and marks the token used. A second use returns {reusedConn}. */
  takeRefreshToken(token: string): Promise<{ rec: TokenRecord | null; reusedConn?: string }>;
  deleteRefreshToken(token: string): Promise<TokenRecord | null>;
  // rate limiting (fixed windows)
  count(key: string, windowS: number): Promise<number>;
  hit(key: string, limit: number, windowS: number): Promise<boolean>;
  // tasks
  createTask(input: TaskInput, createdBy?: Task["created_by"], fixedId?: string, fixedRef?: string): Promise<Task>;
  getTask(id: string): Promise<Task | null>;
  getTaskByIdOrRef(idOrRef: string): Promise<Task | null>;
  /** Oldest first (insertion order), at most the last 200. */
  listTasks(): Promise<Task[]>;
  ensureSeed(enabled: boolean): Promise<void>;
  /** Clears tasks, results, pings and presence (not clients, tokens or connections). */
  reset(): Promise<void>;
  // results
  getResult(taskId: string, agentId: string): Promise<Result | null>;
  listResults(taskId: string): Promise<Result[]>;
  /** Atomically upserts this agent's result and sets its per-agent task state. Returns the stored result
   *  with replaced_previous computed inside the same transaction. */
  postResult(r: Omit<Result, "replaced_previous">, state: AgentState): Promise<Result>;
  // pings
  addPing(p: Ping): Promise<void>;
  /** Oldest first, last 50. */
  listPings(): Promise<Ping[]>;
  // presence
  /** Also emits a presence event when the agent is new or was idle for 10+ minutes. */
  touchPresence(p: Presence): Promise<void>;
  listPresence(): Promise<Presence[]>;

  // ---- C1 ----
  putDevice(d: Device): Promise<void>;
  getDevice(id: string): Promise<Device | null>;
  listDevices(): Promise<Device[]>;
  revokeDevice(id: string, reason: string): Promise<boolean>;
  /** Records a phone request signature (normalized) for the replay guard. False if it was seen before. */
  markPhoneNonce(deviceId: string, ts: number, sigHash: string): Promise<boolean>;
  /** One locked step on a task for one agent: state change, optional question, optional event. */
  agentStep(s: AgentStepInput): Promise<AgentStepResult>;
  answerQuestion(taskId: string, questionId: string, text: string, deviceId: string): Promise<AnswerResult>;
  /** Questions this agent asked (optionally on one task), oldest first. */
  listQuestions(agentId: string, taskId?: string): Promise<Question[]>;
  putManifest(m: Manifest): Promise<number>;
  getManifest(agentId: string): Promise<Manifest | null>;
  /** Events after the cursor, oldest first. Also purges expired tasks, events and nonces. */
  listEvents(after: number, limit: number): Promise<RelayEvent[]>;
  /** Marks events <= cursor acknowledged; they are deleted 24 h later. Returns how many were newly acked. */
  ackEvents(cursor: number): Promise<number>;

  // ---- C3 ----
  /** Pins the device's approval key: "set" the first time, "same" for the same key again, "conflict" for another key. */
  setApprovalKey(deviceId: string, spkiB64: string): Promise<ApprovalKeyResult>;
  putApproval(a: Approval): Promise<void>;
  getApproval(id: string): Promise<Approval | null>;
  /** Atomically records the decision once; a second decision gets already_decided (and a reused jti too). */
  decideApproval(id: string, d: { status: "approved" | "denied"; device_id: string; token?: Record<string, unknown> | null; token_sig?: string | null; jti?: string | null }): Promise<DecideResult>;
  listApprovals(agentId: string, taskId?: string): Promise<Approval[]>;
  /** Insert or refresh (same sub_id). */
  putEventSub(s: EventSub): Promise<void>;
  /** Live (unexpired) subscriptions for these agents. */
  listEventSubs(agentIds: string[]): Promise<EventSub[]>;
  deleteEventSub(subId: string): Promise<boolean>;
}

export const TASK_TTL_NO_DEADLINE_S = 7 * 24 * 3600;
export const TASK_TTL_AFTER_DEADLINE_S = 24 * 3600;

/** Tasks expire at deadline + 24 h, or 7 d after creation if there is no deadline. */
export function taskExpiry(deadline: string | null | undefined, now = Date.now()): string {
  const d = deadline ? Date.parse(deadline) : NaN;
  return new Date(Number.isFinite(d) ? d + TASK_TTL_AFTER_DEADLINE_S * 1000 : now + TASK_TTL_NO_DEADLINE_S * 1000).toISOString();
}

export function normalizeRef(idOrRef: string): { id?: string; ref?: string } {
  const s = idOrRef.trim().replace(/^#/, "");
  return s.startsWith("tsk_") ? { id: s } : { ref: s.toLowerCase() };
}

export function overallState(t: Task): AgentState {
  const entries = Object.values(t.per_agent);
  if (!entries.length) return "SUBMITTED";
  const named = t.to.filter((a) => a !== "@all");
  if (!t.to.includes("@all") && named.length && named.every((a) => ANSWERED_STATES.includes(t.per_agent[a]?.state ?? ""))) {
    const states = named.map((a) => t.per_agent[a].state);
    if (states.every((s) => s === "COMPLETED")) return "COMPLETED"; // C0 rows
    return states.some((s) => s === "PARTIAL") ? "PARTIAL" : "DONE";
  }
  const states = entries.map((e) => e.state);
  if (states.includes("INPUT_REQUIRED")) return "INPUT_REQUIRED";
  if (states.includes("AUTH_REQUIRED")) return "AUTH_REQUIRED";
  return "WORKING";
}

export function isAddressedTo(t: Task, agentId: string): boolean {
  return t.to.includes("@all") || t.to.includes(agentId);
}
