// MCP Streamable HTTP endpoint, dual-era:
//  - Modern (2026-07-28): stateless, per-request `_meta`, `server/discover`,
//    required Mcp-Method / Mcp-Name headers validated against the body.
//  - Legacy (2025-03-26 … 2025-11-25): `initialize` handshake, answered
//    statelessly (no Mcp-Session-Id is minted), so ChatGPT, Claude and Grok
//    clients that still open with `initialize` work on the same /mcp URL.
// Every response is a single JSON object; this server never needs SSE.

import { type Ctx, type Deps, type Env, issuer } from "./env.ts";
import { type Caller, unauthorized, verifyBearer } from "./oauth.ts";
import { callTool, INSTRUCTIONS, TOOLS, UnknownToolError } from "./tools.ts";
import { json, nowIso } from "./util.ts";

export const MODERN = "2026-07-28";
export const LEGACY = ["2025-11-25", "2025-06-18", "2025-03-26"];
export const SUPPORTED = [MODERN, ...LEGACY];
export const SERVER_INFO = { name: "atall-connector", title: "Atall", version: "0.2.0-c1" };

const ERR = {
  PARSE: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL: -32603,
  HEADER_MISMATCH: -32020,
  UNSUPPORTED_VERSION: -32022,
};

type Id = string | number | null;

function rpcError(id: Id, code: number, message: string, status = 200, data?: unknown): Response {
  return json({ jsonrpc: "2.0", id, error: { code, message, ...(data !== undefined ? { data } : {}) } }, status);
}
function rpcResult(id: Id, result: unknown): Response {
  return json({ jsonrpc: "2.0", id, result });
}

function decodeHeaderValue(v: string): string {
  const m = /^=\?base64\?(.*)\?=$/.exec(v);
  if (!m) return v;
  try {
    const bin = atob(m[1]);
    return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  } catch {
    return "\u0000invalid";
  }
}

const DEFAULT_ORIGINS = ["https://chatgpt.com", "https://claude.ai", "https://grok.com"];

export function originAllowed(req: Request, env: Env): boolean {
  const o = req.headers.get("Origin");
  if (!o) return true;
  const self = new URL(issuer(env));
  if (o === self.origin) return true;
  const extra = (env.ATALL_ALLOWED_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  if ([...DEFAULT_ORIGINS, ...extra].includes(o)) return true;
  // Local development: a loopback page may talk to a loopback server.
  const selfLoop = ["localhost", "127.0.0.1"].includes(self.hostname);
  try {
    const ou = new URL(o);
    return selfLoop && ["localhost", "127.0.0.1"].includes(ou.hostname);
  } catch {
    return false;
  }
}

export function corsHeaders(req: Request, env: Env): Record<string, string> {
  const o = req.headers.get("Origin");
  if (!o || !originAllowed(req, env)) return {};
  return {
    "Access-Control-Allow-Origin": o,
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, MCP-Protocol-Version, Mcp-Method, Mcp-Name, Mcp-Session-Id, Last-Event-ID",
    "Access-Control-Expose-Headers": "WWW-Authenticate, MCP-Protocol-Version",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

function toolList(modern: boolean) {
  const tools = TOOLS.map((t) => ({ ...t }));
  return modern ? { resultType: "complete", tools, ttlMs: 300_000, cacheScope: "public" } : { tools };
}

function withServerMeta<T extends object>(result: T, modern: boolean): T {
  if (!modern) return result;
  return { resultType: "complete", ...result, _meta: { "io.modelcontextprotocol/serverInfo": SERVER_INFO } } as T;
}

export function discoverResult() {
  return {
    resultType: "complete",
    supportedVersions: SUPPORTED,
    capabilities: { tools: { listChanged: false } },
    instructions: INSTRUCTIONS,
    ttlMs: 3_600_000,
    cacheScope: "public",
    _meta: { "io.modelcontextprotocol/serverInfo": SERVER_INFO },
  };
}

export async function handleMcp(req: Request, env: Env, _deps: Deps, ctx: Ctx): Promise<Response> {
  if (!originAllowed(req, env)) return rpcError(null, ERR.INVALID_REQUEST, "Origin not allowed", 403);
  if (req.method === "GET" || req.method === "DELETE") return new Response(null, { status: 405, headers: { Allow: "POST" } });
  if (req.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });

  const text = await req.text();
  if (text.length > 256_000) return rpcError(null, ERR.INVALID_REQUEST, "Request too large", 413);
  let msg: Record<string, unknown>;
  try {
    msg = JSON.parse(text);
  } catch {
    return rpcError(null, ERR.PARSE, "Parse error", 400);
  }
  if (Array.isArray(msg)) return rpcError(null, ERR.INVALID_REQUEST, "JSON-RPC batching is not supported", 400);
  if (!msg || typeof msg !== "object" || msg.jsonrpc !== "2.0" || typeof msg.method !== "string")
    return rpcError(null, ERR.INVALID_REQUEST, "Invalid JSON-RPC request", 400);
  if ("result" in msg || "error" in msg) return rpcError(null, ERR.INVALID_REQUEST, "Clients must not send JSON-RPC responses", 400);

  const method = msg.method as string;
  const isNotification = !("id" in msg);
  const id = (isNotification ? null : (msg.id as Id)) ?? null;
  const params = (msg.params ?? {}) as Record<string, unknown>;
  const meta = (params._meta ?? {}) as Record<string, unknown>;
  const headerVersion = req.headers.get("MCP-Protocol-Version");
  const metaVersion = meta["io.modelcontextprotocol/protocolVersion"] as string | undefined;

  // ---- era selection and header validation --------------------------------
  let modern = false;
  if (method !== "initialize") {
    if (headerVersion && !SUPPORTED.includes(headerVersion))
      return rpcError(id, ERR.UNSUPPORTED_VERSION, "Unsupported protocol version", 400, { supported: SUPPORTED, requested: headerVersion });
    if (metaVersion !== undefined && !SUPPORTED.includes(metaVersion))
      return rpcError(id, ERR.UNSUPPORTED_VERSION, "Unsupported protocol version", 400, { supported: SUPPORTED, requested: metaVersion });
    if (headerVersion === MODERN || metaVersion !== undefined) {
      modern = true;
      if (!headerVersion) return rpcError(id, ERR.HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version header is required", 400);
      if (metaVersion !== headerVersion)
        return rpcError(id, ERR.HEADER_MISMATCH, `Header mismatch: MCP-Protocol-Version '${headerVersion}' does not match _meta protocolVersion '${metaVersion ?? "(missing)"}'`, 400);
      const hMethod = req.headers.get("Mcp-Method");
      if (hMethod !== method) return rpcError(id, ERR.HEADER_MISMATCH, `Header mismatch: Mcp-Method '${hMethod ?? "(missing)"}' does not match body method '${method}'`, 400);
      if (["tools/call", "resources/read", "prompts/get"].includes(method)) {
        const raw = req.headers.get("Mcp-Name");
        const bodyName = (method === "resources/read" ? params.uri : params.name) as string | undefined;
        const hName = raw === null ? null : decodeHeaderValue(raw);
        if (hName === null || hName !== bodyName)
          return rpcError(id, ERR.HEADER_MISMATCH, `Header mismatch: Mcp-Name '${hName ?? "(missing)"}' does not match body value '${bodyName ?? "(missing)"}'`, 400);
      }
    }
  }

  if (isNotification) return new Response(null, { status: 202 });

  // ---- auth: everything except server/discover needs a token ---------------
  let caller: Caller | null = null;
  if (method !== "server/discover") {
    const v = await verifyBearer(req, env);
    if ("error" in v) return unauthorized(env, v.error);
    caller = v;
    const tool = method === "tools/call" ? String(params.name ?? "") : method;
    ctx.waitUntil(
      env.store
        .touchPresence({ agent_id: v.agent_id, conn_id: v.conn_id, client_name: v.client_name, last_seen: nowIso(), last_tool: tool })
        .catch(() => undefined),
    );
  }

  // ---- dispatch -------------------------------------------------------------
  try {
    switch (method) {
      case "server/discover":
        return rpcResult(id, discoverResult());
      case "initialize": {
        const asked = params.protocolVersion as string | undefined;
        const chosen = asked && LEGACY.includes(asked) ? asked : LEGACY[0];
        return rpcResult(id, { protocolVersion: chosen, capabilities: { tools: { listChanged: false } }, serverInfo: SERVER_INFO, instructions: INSTRUCTIONS });
      }
      case "ping":
        if (modern) return rpcError(id, ERR.METHOD_NOT_FOUND, "Method not found: ping was removed in 2026-07-28", 404);
        return rpcResult(id, {});
      case "tools/list":
        return rpcResult(id, toolList(modern));
      case "tools/call": {
        const name = params.name;
        if (typeof name !== "string") return rpcError(id, ERR.INVALID_PARAMS, "params.name is required");
        const result = await callTool(name, params.arguments, caller!, env);
        return rpcResult(id, withServerMeta(result, modern));
      }
      default:
        return rpcError(id, ERR.METHOD_NOT_FOUND, `Method not found: ${method}`, modern ? 404 : 200);
    }
  } catch (e) {
    if (e instanceof UnknownToolError) return rpcError(id, ERR.INVALID_PARAMS, `Unknown tool: ${e.message}`);
    console.error("mcp error", e);
    return rpcError(id, ERR.INTERNAL, "Internal error");
  }
}
