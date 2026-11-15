#!/usr/bin/env node
// End-to-end smoke test over real HTTP, against a local stack or the deployed Supabase function.
// ATALL_URL is the function base, path included: https://<ref>.supabase.co/functions/v1/atall
// It signs in as the agent "smoke" (DCR + PKCE + consent with the owner code), runs
// the legacy handshake and the 2026-07-28 calls, reads a task, posts a result, posts
// a write-test ping, checks the admin view, then revokes its own connection.
//
//   ATALL_URL=https://<ref>.supabase.co/functions/v1/atall ATALL_OWNER_CODE=… ATALL_ADMIN_TOKEN=… node scripts/smoke.mjs

import { AtallAdmin, AtallClient } from "./lib/client.mjs";

const base = (process.env.ATALL_URL ?? "http://127.0.0.1:54321/functions/v1/atall").replace(/\/+$/, "");
const ownerCode = process.env.ATALL_OWNER_CODE;
const adminToken = process.env.ATALL_ADMIN_TOKEN;
if (!ownerCode || !adminToken) {
  console.error("Set ATALL_OWNER_CODE and ATALL_ADMIN_TOKEN (and ATALL_URL=https://<ref>.supabase.co/functions/v1/atall for the deployed function).");
  process.exit(2);
}

let failures = 0;
const check = (label, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${detail ? `  (${detail})` : ""}`);
  if (!ok) failures++;
};

const admin = new AtallAdmin({ base, token: adminToken });
const c = new AtallClient({ base, clientName: "Atall smoke test" });

const d = await c.discover();
check("401 + resource_metadata on an unauthenticated call", d.status === 401 && d.wwwAuthenticate.includes("resource_metadata"));
check("authorization server metadata (S256, DCR, offline_access)", d.as.code_challenge_methods_supported?.includes("S256") && !!d.as.registration_endpoint && d.as.scopes_supported?.includes("offline_access"), `found at ${d.asUrl}`);
check("issuer and resource carry the function base path", d.as.issuer === base && d.prm.resource === `${base}/mcp`, d.as.issuer);
// Not a failure, but worth knowing: *.supabase.co serves text/html from functions as text/plain.
const devRes = await fetch(`${base}/dev`);
const devType = devRes.headers.get("content-type") ?? "";
await devRes.body?.cancel();
if (devRes.status === 200 && !devType.includes("text/html"))
  console.log(`WARN  /dev came back as "${devType}", so browsers will show HTML source. Set ATALL_CONSENT_URL and host the static pages (see README).`);
await c.register();
check("dynamic client registration", !!c.client?.client_id, c.client?.client_id);
const a = await c.authorize({ ownerCode, agentId: "other", agentOther: "smoke" });
check("consent with owner code → code + iss", a.status === 302 && !!a.params?.code && a.params?.iss === d.as.issuer, c.consentUrl ? `via external consent page ${new URL(c.consentUrl).origin}` : "inline consent page");
const t = await c.exchange(a.params.code);
check("token exchange with PKCE", t.status === 200 && !!t.body.refresh_token);

const init = await c.rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "smoke", version: "1" } }, { modern: false });
check("legacy initialize", init.status === 200 && init.body.result?.protocolVersion === "2025-06-18");
const disc = await c.rpc("server/discover", {}, { token: null });
check("2026-07-28 server/discover", disc.status === 200 && disc.body.result?.supportedVersions?.includes("2026-07-28"));
const list = await c.rpc("tools/list");
const names = list.body.result?.tools?.map((x) => x.name) ?? [];
check("tools/list", ["atall_inbox", "atall_get_task", "atall_post_result", "atall_ping_write"].every((n) => names.includes(n)), names.join(", "));

const task = await admin.createTask({ title: "Smoke test task", goal: "Post any result.", to: "smoke" });
check("admin creates a task for 'smoke'", task.status === 201, task.body.ref);
const inbox = await c.callTool("atall_inbox");
check("atall_inbox shows it", !!inbox.body.result?.structuredContent?.tasks?.find((x) => x.ref === task.body.ref));
const card = await c.callTool("atall_get_task", { task_id: task.body.ref });
check("atall_get_task", card.body.result?.structuredContent?.task_id === task.body.task_id);
const post = await c.callTool("atall_post_result", { task_id: task.body.task_id, status: "done", summary: "Smoke test result.", claims: [{ text: "This came from scripts/smoke.mjs.", confidence: 1 }] });
check("atall_post_result", post.status === 200 && !post.body.result?.isError, post.body.result?.content?.[0]?.text);
const ping = await c.callTool("atall_ping_write", { note: "smoke" });
check("atall_ping_write", ping.status === 200 && ping.body.result?.structuredContent?.from_agent === "smoke");

const st = await admin.state();
const seen = st.body.tasks?.find((x) => x.task_id === task.body.task_id);
check("admin state shows the result from 'smoke'", seen?.results?.[0]?.agent_id === "smoke" && ["DONE", "COMPLETED"].includes(seen?.state));
const r = await c.refresh();
check("refresh token rotation", r.status === 200);

const conn = st.body.connections?.find((x) => x.agent_id === "smoke" && !x.revoked);
if (conn) await admin.revoke(conn.conn_id);
const after = await c.rpc("tools/list");
check("revoked connection is refused", after.status === 401);

console.log(failures ? `\n${failures} check(s) failed` : "\nAll smoke checks passed");
process.exit(failures ? 1 : 0);
