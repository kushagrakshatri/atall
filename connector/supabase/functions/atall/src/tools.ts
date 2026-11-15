// The Atall tool set (C1: 8 task tools + the C0 write test). Names are stable (ChatGPT freezes a snapshot of
// them), and annotations are honest: readOnlyHint is true only on tools that change nothing in Atall beyond the
// presence heartbeat every authenticated call records.

import type { Env } from "./env.ts";
import type { Caller } from "./oauth.ts";
import { ANSWERED_STATES, type AgentStepResult, type Approval, type Claim, isAddressedTo, overallState, type Question, type Result, type Store, type Task } from "./store.ts";
import { b64url, newId, nowIso } from "./util.ts";
import { canonicalJson, sha256 } from "./crypto.ts";

export const INSTRUCTIONS =
  "Atall is Kush's private task inbox for his AI agents. When you see Atall work (or a short nudge like 'Atall #k3f9'), call atall_inbox, " +
  "read the card with atall_get_task, call atall_claim when you start, then post your answer with atall_post_result: a short summary plus claims, " +
  "each with a confidence and sources. Use atall_progress for an occasional status line and atall_ask when you need Kush to decide something; " +
  "his answer shows up in atall_inbox. Ask rather than guess. Never spend money, book, or change an account for an Atall task: " +
  "call atall_request_approval instead and wait: Kush approves on his phone with his fingerprint, and the decision (with a single-use token) " +
  "shows up in atall_inbox and atall_get_task. Act only on an approved decision, once, before the token expires and within its max_amount.";

export const PROGRESS_THROTTLE_S = 30;

const taskIdProp = { type: "string", description: "Task id (tsk_…) or its ref such as c0ck." } as const;

const taskSummarySchema = {
  type: "object",
  properties: {
    task_id: { type: "string" },
    ref: { type: "string" },
    title: { type: "string" },
    deadline: { type: ["string", "null"] },
    state: { type: "string" },
    my_state: { type: "string" },
  },
  required: ["task_id", "ref", "title", "state", "my_state"],
} as const;

export const TOOLS = [
  {
    name: "atall_inbox",
    title: "Check Atall inbox",
    description:
      "List the Atall tasks addressed to you (or to all agents) that you have not answered yet: id, ref, title, deadline and state, plus Kush's answers to questions you asked with atall_ask. Read-only.",
    inputSchema: {
      type: "object",
      properties: {
        include_done: { type: "boolean", description: "Also list tasks you already answered. Default false." },
      },
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: {
        agent_id: { type: "string" },
        tasks: { type: "array", items: taskSummarySchema },
        answers: {
          type: "array",
          description: "Kush's answers to questions you asked with atall_ask, on tasks you haven't finished.",
          items: {
            type: "object",
            properties: { question_id: { type: "string" }, task_id: { type: "string" }, ref: { type: "string" }, question: { type: "string" }, answer: { type: "string" }, answered_at: { type: "string" } },
            required: ["question_id", "task_id", "question", "answer"],
          },
        },
      },
      required: ["agent_id", "tasks"],
    },
    annotations: { title: "Check Atall inbox", readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "atall_get_task",
    title: "Read an Atall task",
    description:
      "Return one Atall task card in full: goal, constraints, context, what counts as done, what not to share, whether Kush's phone signed it, and your questions and his answers. Accepts the task id (tsk_…) or its ref. Read-only.",
    inputSchema: {
      type: "object",
      properties: {
        task_id: taskIdProp,
      },
      required: ["task_id"],
      additionalProperties: false,
    },
    annotations: { title: "Read an Atall task", readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "atall_claim",
    title: "Start an Atall task",
    description: "Write: tell Kush you are working on an Atall task (your state becomes WORKING). Call it when you start, before atall_post_result.",
    inputSchema: { type: "object", properties: { task_id: taskIdProp }, required: ["task_id"], additionalProperties: false },
    outputSchema: {
      type: "object",
      properties: { task_id: { type: "string" }, ref: { type: "string" }, state: { type: "string" }, previous_state: { type: "string" } },
      required: ["task_id", "ref", "state"],
    },
    annotations: { title: "Start an Atall task", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "atall_progress",
    title: "Post Atall progress",
    description: "Write: send Kush a short status line on a task you are working on (at most one per 30 seconds per task).",
    inputSchema: {
      type: "object",
      properties: {
        task_id: taskIdProp,
        message: { type: "string", maxLength: 500, description: "One short status line, e.g. 'Comparing 3 flights'." },
        percent: { type: "number", minimum: 0, maximum: 100, description: "Optional rough completion percentage." },
      },
      required: ["task_id", "message"],
      additionalProperties: false,
    },
    annotations: { title: "Post Atall progress", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "atall_post_result",
    title: "Post result to Atall",
    description:
      "Write: send your answer for an Atall task back to Kush. Records a summary and optional claims (each with confidence and source URLs) and marks the task answered by you. Posting again for the same task replaces your earlier result.",
    inputSchema: {
      type: "object",
      properties: {
        task_id: { type: "string", description: "Task id (tsk_…) or 4-character ref." },
        status: { type: "string", enum: ["done", "partial", "declined"], description: "done, partial, or declined (you won't do it)." },
        summary: { type: "string", description: "Your answer in a few sentences.", maxLength: 8000 },
        claims: {
          type: "array",
          maxItems: 50,
          description: "Atomic statements that make up your answer.",
          items: {
            type: "object",
            properties: {
              text: { type: "string", maxLength: 2000 },
              kind: { type: "string", enum: ["fact", "estimate", "opinion", "plan"] },
              confidence: { type: "number", minimum: 0, maximum: 1 },
              sources: {
                type: "array",
                maxItems: 10,
                items: {
                  type: "object",
                  properties: { url: { type: "string" }, title: { type: "string" } },
                  required: ["url"],
                },
              },
            },
            required: ["text"],
          },
        },
        artifacts: {
          type: "array",
          maxItems: 10,
          items: {
            type: "object",
            properties: { name: { type: "string" }, url: { type: "string" }, mime: { type: "string" } },
            required: ["name", "url"],
          },
        },
      },
      required: ["task_id", "status", "summary"],
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: {
        result_id: { type: "string" },
        task_id: { type: "string" },
        ref: { type: "string" },
        recorded_at: { type: "string" },
        from_agent: { type: "string" },
        replaced_previous: { type: "boolean" },
      },
      required: ["result_id", "task_id", "recorded_at", "from_agent"],
    },
    annotations: { title: "Post result to Atall", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "atall_ask",
    title: "Ask Kush a question",
    description:
      "Write: ask Kush a question about a task when you need a decision or a missing detail (your state becomes INPUT_REQUIRED). His answer appears in atall_inbox and atall_get_task.",
    inputSchema: {
      type: "object",
      properties: {
        task_id: taskIdProp,
        question: { type: "string", maxLength: 1000, description: "One clear question." },
        choices: { type: "array", maxItems: 8, items: { type: "string", maxLength: 200 }, description: "Optional short answers he can tap." },
      },
      required: ["task_id", "question"],
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: { question_id: { type: "string" }, task_id: { type: "string" }, ref: { type: "string" }, state: { type: "string" } },
      required: ["question_id", "task_id", "state"],
    },
    annotations: { title: "Ask Kush a question", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "atall_request_approval",
    title: "Request Kush's approval",
    description:
      "Write: ask Kush to approve an action you must not take on your own (spending money, booking, changing an account). Your state becomes AUTH_REQUIRED. Kush approves or denies on his phone with his fingerprint; check atall_get_task (approvals) or atall_inbox for the decision. Don't act until it says approved, and then only once, before its token expires, and within max_amount.",
    inputSchema: {
      type: "object",
      properties: {
        task_id: taskIdProp,
        action: { type: "string", maxLength: 500, description: "What you would do, e.g. 'Book the 7:25 AM SFO→SEA flight'." },
        reason: { type: "string", maxLength: 1000 },
        amount: { type: "number", minimum: 0, description: "Cost, if any." },
        currency: { type: "string", maxLength: 8, description: "e.g. USD" },
        url: { type: "string", description: "Where the action would happen." },
      },
      required: ["task_id", "action"],
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: { approval_id: { type: "string" }, task_id: { type: "string" }, ref: { type: "string" }, state: { type: "string" }, granted: { type: "boolean" } },
      required: ["approval_id", "task_id", "state", "granted"],
    },
    annotations: { title: "Request Kush's approval", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "atall_manifest",
    title: "Atall agent manifest",
    description:
      "Write: tell Atall what you are and can do (model, capabilities, whether you can browse or run code) so Kush's phone can route tasks to you. Call with no arguments to read back what you last sent.",
    inputSchema: {
      type: "object",
      properties: {
        display_name: { type: "string", maxLength: 100 },
        model: { type: "string", maxLength: 100 },
        capabilities: { type: "array", maxItems: 30, items: { type: "string", maxLength: 100 } },
        can_browse: { type: "boolean" },
        can_run_code: { type: "boolean" },
        notes: { type: "string", maxLength: 1000 },
      },
      additionalProperties: false,
    },
    annotations: { title: "Atall agent manifest", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "atall_ping_write",
    title: "Atall write test (heartbeat)",
    description:
      "Write test: records one harmless heartbeat entry in Atall saying you called it, with an optional short note. Changes nothing else. Use it when asked to test whether you can call Atall write tools.",
    inputSchema: {
      type: "object",
      properties: {
        note: { type: "string", maxLength: 200, description: "Optional short note, e.g. 'write test'." },
      },
      additionalProperties: false,
    },
    outputSchema: {
      type: "object",
      properties: { ping_id: { type: "string" }, recorded_at: { type: "string" }, from_agent: { type: "string" } },
      required: ["ping_id", "recorded_at", "from_agent"],
    },
    annotations: { title: "Atall write test (heartbeat)", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
] as const;

export type ToolName = (typeof TOOLS)[number]["name"];
export const TOOL_NAMES = TOOLS.map((t) => t.name) as readonly string[];

export interface CallToolResult {
  content: { type: "text"; text: string }[];
  structuredContent?: unknown;
  isError?: boolean;
}

class ToolInputError extends Error {}

function fail(msg: string): never {
  throw new ToolInputError(msg);
}

function str(v: unknown, field: string, max: number, required = true): string | undefined {
  if (v === undefined || v === null) {
    if (required) fail(`${field} is required`);
    return undefined;
  }
  if (typeof v !== "string") fail(`${field} must be a string`);
  if (required && !v.trim()) fail(`${field} must not be empty`);
  if (v.length > max) fail(`${field} is longer than ${max} characters`);
  return v;
}

function httpUrl(v: unknown, field: string): string {
  const s = str(v, field, 2000)!;
  let u: URL;
  try {
    u = new URL(s);
  } catch {
    fail(`${field} must be a URL`);
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") fail(`${field} must be an http(s) URL`);
  return s;
}

function noExtra(o: Record<string, unknown>, allowed: string[]) {
  const extra = Object.keys(o).filter((k) => !allowed.includes(k));
  if (extra.length) fail(`unknown argument(s): ${extra.join(", ")}`);
}

function ok(text: string, structured?: unknown): CallToolResult {
  return { content: [{ type: "text", text }], ...(structured !== undefined ? { structuredContent: structured } : {}) };
}

function myState(t: Task, agentId: string): string {
  return t.per_agent[agentId]?.state ?? "SUBMITTED";
}

function summarize(t: Task, agentId: string) {
  return { task_id: t.task_id, ref: t.ref, title: t.title, deadline: t.deadline, state: overallState(t), my_state: myState(t, agentId) };
}

function provenance(t: Task): string {
  if (t.phone_sig) return `signed on Kush's phone (${t.phone_sig.device_name}, device ${t.phone_sig.device_id}) and checked by the relay`;
  if (t.created_by === "seed") return "built-in C0 check task";
  return "created by Kush on the Atall admin page (test route; not phone-signed)";
}

function questionView(q: Question) {
  return { question_id: q.question_id, question: q.text, choices: q.choices, asked_at: q.asked_at, answer: q.answer ?? null, answered_at: q.answered_at ?? null };
}

/** What an agent sees about its approval requests: the decision and, when approved, the single-use token. */
function approvalView(a: Approval) {
  return {
    approval_id: a.approval_id,
    action: a.payload.action,
    ...(a.payload.amount !== undefined ? { amount: a.payload.amount } : {}),
    ...(a.payload.currency !== undefined ? { currency: a.payload.currency } : {}),
    status: a.status,
    ...(a.decided_at ? { decided_at: a.decided_at } : {}),
    ...(a.status === "approved" && a.token ? { token: a.token, token_sig: a.token_sig, expires_at: a.token.exp } : {}),
  };
}

function card(t: Task, agentId: string, questions: Question[], approvals: Approval[] = []) {
  return {
    ...(approvals.length ? { approvals: approvals.map(approvalView) } : {}),
    ...summarize(t, agentId),
    goal: t.goal,
    constraints: t.constraints,
    context: t.context,
    done_when: t.done_when,
    ...(t.done_criteria?.length ? { done_criteria: t.done_criteria } : {}),
    do_not_share: t.do_not_share,
    ...(t.output ? { output: t.output } : {}),
    to: t.to,
    created_at: t.created_at,
    ...(t.expires_at ? { expires_at: t.expires_at } : {}),
    provenance: provenance(t),
    verified: !!t.phone_sig,
    // What the phone signed, so an agent or an auditor can check it: verify `sig` (ES256, DER or raw r||s, base64url)
    // over `signed_string` with the device key, and that sha256(body) is the last line of signed_string.
    ...(t.phone_sig ? { phone_signature: t.phone_sig } : {}),
    questions: questions.map(questionView),
    how_to_answer:
      "Call atall_claim when you start, atall_progress for status, atall_ask if you need Kush, then atall_post_result with this task_id, a status, a summary, and claims with confidence and sources." +
      (t.output === "claims" ? " This task asks for claims: give each finding as a claim with a confidence and sources." : ""),
  };
}

function stepError(r: Extract<AgentStepResult, { ok: false }>, ref: string): never {
  if (r.reason === "terminal") fail(`You already answered #${ref} (${r.state}). Call atall_post_result again to replace your answer.`);
  if (r.reason === "throttled") fail(`Progress is limited to one update per ${PROGRESS_THROTTLE_S} s per task. Try again in ${r.retry_after_s ?? PROGRESS_THROTTLE_S} s.`);
  fail(`No Atall task ${ref} is addressed to you. Call atall_inbox to see your tasks.`);
}

async function findAddressedTask(store: Store, idOrRef: string, agentId: string): Promise<Task> {
  const t = await store.getTaskByIdOrRef(idOrRef);
  if (!t || !isAddressedTo(t, agentId)) fail(`No Atall task ${idOrRef} is addressed to you. Call atall_inbox to see your tasks.`);
  return t;
}

export async function callTool(name: string, rawArgs: unknown, caller: Caller, env: Env): Promise<CallToolResult> {
  const store = env.store;
  const args = (rawArgs ?? {}) as Record<string, unknown>;
  try {
    if (typeof args !== "object" || Array.isArray(args)) fail("arguments must be an object");
    switch (name) {
      case "atall_inbox": {
        noExtra(args, ["include_done"]);
        if (args.include_done !== undefined && typeof args.include_done !== "boolean") fail("include_done must be a boolean");
        await store.ensureSeed(env.ATALL_SEED_TASK !== "false");
        const tasks = (await store.listTasks())
          .filter((t) => isAddressedTo(t, caller.agent_id))
          .filter((t) => args.include_done || !ANSWERED_STATES.includes(myState(t, caller.agent_id)))
          .reverse()
          .map((t) => summarize(t, caller.agent_id));
        const open = new Set(tasks.filter((t) => !ANSWERED_STATES.includes(t.my_state)).map((t) => t.task_id));
        const answers = (await store.listQuestions(caller.agent_id))
          .filter((q) => q.answer != null && open.has(q.task_id))
          .map((q) => ({ question_id: q.question_id, task_id: q.task_id, ref: q.ref, question: q.text, answer: q.answer!, answered_at: q.answered_at ?? "" }));
        let text = tasks.length
          ? `${tasks.length} Atall task(s) for ${caller.agent_id}:\n` + tasks.map((t) => `- #${t.ref} ${t.title} (task_id ${t.task_id}, ${t.my_state}${t.deadline ? `, due ${t.deadline}` : ""})`).join("\n")
          : `No open Atall tasks for ${caller.agent_id}.`;
        if (answers.length) text += `\nKush answered:\n` + answers.map((a) => `- #${a.ref} "${a.question}" → ${a.answer} (question_id ${a.question_id})`).join("\n");
        const decisions = (await store.listApprovals(caller.agent_id))
          .filter((a) => a.status !== "pending" && open.has(a.task_id))
          .map((a) => ({ ...approvalView(a), task_id: a.task_id, ref: (a.payload.ref as string) ?? "" }));
        if (decisions.length) text += `\nKush decided:\n` + decisions.map((d) => `- #${d.ref} ${String(d.action)} → ${d.status}${d.status === "approved" ? ` (token valid until ${String(d.expires_at)}; read it with atall_get_task)` : ""} (approval_id ${d.approval_id})`).join("\n");
        return ok(text, { agent_id: caller.agent_id, tasks, ...(answers.length ? { answers } : {}), ...(decisions.length ? { approvals: decisions } : {}) });
      }
      case "atall_get_task": {
        noExtra(args, ["task_id"]);
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        const c = card(t, caller.agent_id, await store.listQuestions(caller.agent_id, t.task_id), await store.listApprovals(caller.agent_id, t.task_id));
        return ok(JSON.stringify(c, null, 2), c);
      }
      case "atall_claim": {
        noExtra(args, ["task_id"]);
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        const r = await store.agentStep({
          ...who(caller), task_id: t.task_id, state: "WORKING", refuse_if_terminal: true,
          event: { type: "progress", payload: { ref: t.ref, state: "WORKING", claimed: true, message: `${caller.agent_id} started working on #${t.ref}` } },
        });
        if (!r.ok) stepError(r, t.ref);
        return ok(`You're on #${t.ref} (WORKING). Post your answer with atall_post_result when you're done.`, { task_id: t.task_id, ref: t.ref, state: r.state, previous_state: r.prev_state });
      }
      case "atall_progress": {
        noExtra(args, ["task_id", "message", "percent"]);
        const message = str(args.message, "message", 500)!;
        if (args.percent !== undefined && (typeof args.percent !== "number" || !(args.percent >= 0 && args.percent <= 100))) fail("percent must be a number from 0 to 100");
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        const r = await store.agentStep({
          ...who(caller), task_id: t.task_id, state: "WORKING", refuse_if_terminal: true, throttle_s: PROGRESS_THROTTLE_S,
          event: { type: "progress", payload: { ref: t.ref, state: "WORKING", message, ...(args.percent !== undefined ? { percent: args.percent } : {}) } },
        });
        if (!r.ok) stepError(r, t.ref);
        return ok(`Progress on #${t.ref} sent to Kush.`, { task_id: t.task_id, ref: t.ref, state: r.state });
      }
      case "atall_ask": {
        noExtra(args, ["task_id", "question", "choices"]);
        const question = str(args.question, "question", 1000)!;
        let choices: string[] = [];
        if (args.choices !== undefined) {
          if (!Array.isArray(args.choices) || args.choices.length > 8) fail("choices must be an array of at most 8 strings");
          choices = args.choices.map((c: unknown, i: number) => str(c, `choices[${i}]`, 200)!);
        }
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        const questionId = newId("q");
        const r = await store.agentStep({
          ...who(caller), task_id: t.task_id, state: "INPUT_REQUIRED", refuse_if_terminal: true,
          question: { question_id: questionId, text: question, choices },
          event: { type: "question", payload: { ref: t.ref, state: "INPUT_REQUIRED", question_id: questionId, text: question, choices } },
        });
        if (!r.ok) stepError(r, t.ref);
        return ok(`Asked Kush on #${t.ref} (question_id ${questionId}). His answer will show up in atall_inbox and atall_get_task.`, { question_id: questionId, task_id: t.task_id, ref: t.ref, state: r.state });
      }
      case "atall_request_approval": {
        noExtra(args, ["task_id", "action", "reason", "amount", "currency", "url"]);
        const action = str(args.action, "action", 500)!;
        const reason = str(args.reason, "reason", 1000, false);
        if (args.amount !== undefined && (typeof args.amount !== "number" || !(args.amount >= 0) || !Number.isFinite(args.amount))) fail("amount must be a non-negative number");
        const currency = str(args.currency, "currency", 8, false);
        const url = args.url !== undefined ? httpUrl(args.url, "url") : undefined;
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        const approvalId = newId("apr");
        const payload: Record<string, unknown> = {
          ref: t.ref, state: "AUTH_REQUIRED", approval_id: approvalId, action,
          ...(reason ? { reason } : {}), ...(args.amount !== undefined ? { amount: args.amount } : {}), ...(currency ? { currency } : {}), ...(url ? { url } : {}),
        };
        const r = await store.agentStep({
          ...who(caller), task_id: t.task_id, state: "AUTH_REQUIRED", refuse_if_terminal: true,
          event: { type: "approval_request", payload },
        });
        if (!r.ok) stepError(r, t.ref);
        // C3: kept so the phone's fingerprint-signed token can be checked against exactly this request.
        await store.putApproval({
          approval_id: approvalId, task_id: t.task_id, ref: t.ref, agent_id: caller.agent_id, conn_id: caller.conn_id, client_name: caller.client_name,
          payload, action_hash: b64url(await sha256(canonicalJson(payload))), requested_at: nowIso(), status: "pending",
        });
        return ok(
          `Approval request ${approvalId} sent to Kush for #${t.ref}. Don't take the action yet: Kush approves on his phone with his fingerprint. Check atall_get_task or atall_inbox for the decision.`,
          { approval_id: approvalId, task_id: t.task_id, ref: t.ref, state: r.state, granted: false },
        );
      }
      case "atall_manifest": {
        noExtra(args, ["display_name", "model", "capabilities", "can_browse", "can_run_code", "notes"]);
        if (!Object.keys(args).length) {
          const m = await store.getManifest(caller.agent_id);
          return ok(m ? JSON.stringify(m.manifest, null, 2) : `No manifest yet for ${caller.agent_id}. Call atall_manifest with your details.`, m ?? { agent_id: caller.agent_id, manifest: null });
        }
        const manifest: Record<string, unknown> = {};
        for (const [k, max] of [["display_name", 100], ["model", 100], ["notes", 1000]] as const) {
          const v = str(args[k], k, max, false);
          if (v) manifest[k] = v;
        }
        if (args.capabilities !== undefined) {
          if (!Array.isArray(args.capabilities) || args.capabilities.length > 30) fail("capabilities must be an array of at most 30 strings");
          manifest.capabilities = args.capabilities.map((c: unknown, i: number) => str(c, `capabilities[${i}]`, 100)!);
        }
        for (const k of ["can_browse", "can_run_code"] as const) {
          if (args[k] !== undefined) {
            if (typeof args[k] !== "boolean") fail(`${k} must be a boolean`);
            manifest[k] = args[k];
          }
        }
        const updated_at = nowIso();
        await store.putManifest({ agent_id: caller.agent_id, conn_id: caller.conn_id, client_name: caller.client_name, manifest, updated_at });
        return ok(`Manifest saved for ${caller.agent_id}.`, { agent_id: caller.agent_id, conn_id: caller.conn_id, client_name: caller.client_name, manifest, updated_at });
      }
      case "atall_post_result": {
        noExtra(args, ["task_id", "status", "summary", "claims", "artifacts"]);
        const status = args.status;
        if (status !== "done" && status !== "partial" && status !== "declined") fail("status must be done, partial, or declined");
        const summary = str(args.summary, "summary", 8000)!;
        const claims: Claim[] = [];
        if (args.claims !== undefined) {
          if (!Array.isArray(args.claims) || args.claims.length > 50) fail("claims must be an array of at most 50 items");
          args.claims.forEach((c: unknown, i: number) => {
            if (!c || typeof c !== "object") fail(`claims[${i}] must be an object`);
            const o = c as Record<string, unknown>;
            const claim: Claim = { text: str(o.text, `claims[${i}].text`, 2000)! };
            if (o.kind !== undefined) {
              if (!["fact", "estimate", "opinion", "plan"].includes(o.kind as string)) fail(`claims[${i}].kind is not valid`);
              claim.kind = o.kind as Claim["kind"];
            }
            if (o.confidence !== undefined) {
              if (typeof o.confidence !== "number" || o.confidence < 0 || o.confidence > 1) fail(`claims[${i}].confidence must be between 0 and 1`);
              claim.confidence = o.confidence;
            }
            if (o.sources !== undefined) {
              if (!Array.isArray(o.sources) || o.sources.length > 10) fail(`claims[${i}].sources must be an array of at most 10`);
              claim.sources = o.sources.map((s: unknown, j: number) => {
                const so = (s ?? {}) as Record<string, unknown>;
                return { url: httpUrl(so.url, `claims[${i}].sources[${j}].url`), ...(so.title ? { title: str(so.title, "title", 300)! } : {}) };
              });
            }
            claims.push(claim);
          });
        }
        const artifacts: Result["artifacts"] = [];
        if (args.artifacts !== undefined) {
          if (!Array.isArray(args.artifacts) || args.artifacts.length > 10) fail("artifacts must be an array of at most 10");
          args.artifacts.forEach((a: unknown, i: number) => {
            const o = (a ?? {}) as Record<string, unknown>;
            artifacts.push({ name: str(o.name, `artifacts[${i}].name`, 200)!, url: httpUrl(o.url, `artifacts[${i}].url`), ...(o.mime ? { mime: str(o.mime, "mime", 100)! } : {}) });
          });
        }
        const t = await findAddressedTask(store, str(args.task_id, "task_id", 64)!, caller.agent_id);
        // One atomic SQL call: upsert the result and set this agent's task state (two racy KV writes in the Worker).
        const result = await store.postResult({
          result_id: newId("res"),
          task_id: t.task_id,
          ref: t.ref,
          agent_id: caller.agent_id,
          conn_id: caller.conn_id,
          client_name: caller.client_name,
          status,
          summary,
          claims,
          artifacts,
          recorded_at: nowIso(),
        }, status === "declined" ? "REJECTED" : status === "partial" ? "PARTIAL" : "DONE");
        const previous = result.replaced_previous;
        const receipt = { result_id: result.result_id, task_id: t.task_id, ref: t.ref, recorded_at: result.recorded_at, from_agent: caller.agent_id, replaced_previous: result.replaced_previous };
        return ok(`Result recorded for #${t.ref} from ${caller.agent_id} (${claims.length} claim(s))${previous ? ", replacing your earlier result" : ""}.`, receipt);
      }
      case "atall_ping_write": {
        noExtra(args, ["note"]);
        const note = str(args.note, "note", 200, false) ?? "";
        const ping = { ping_id: newId("png"), agent_id: caller.agent_id, conn_id: caller.conn_id, client_name: caller.client_name, note, at: nowIso() };
        await store.addPing(ping);
        return ok(`Write test recorded: Atall saw a heartbeat from ${caller.agent_id} at ${ping.at}.`, { ping_id: ping.ping_id, recorded_at: ping.at, from_agent: caller.agent_id });
      }
      default:
        throw new UnknownToolError(name);
    }
  } catch (e) {
    if (e instanceof ToolInputError) return { content: [{ type: "text", text: e.message }], isError: true };
    throw e;
  }
}

export class UnknownToolError extends Error {}

function who(caller: Caller) {
  return { agent_id: caller.agent_id, conn_id: caller.conn_id, client_name: caller.client_name };
}
