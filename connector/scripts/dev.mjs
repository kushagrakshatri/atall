#!/usr/bin/env node
// Owner CLI for the C0 queue (same admin API the /dev page uses).
//
//   export ATALL_URL=https://<ref>.supabase.co/functions/v1/atall ATALL_ADMIN_TOKEN=…
//   node scripts/dev.mjs task "Title" "Goal text" [--to kdot,grok]
//   node scripts/dev.mjs state            # tasks, results, pings, presence, connections
//   node scripts/dev.mjs client "Grok Bot" [https://redirect/uri] [--confidential]
//   node scripts/dev.mjs token muse       # header bearer token for an agent
//   node scripts/dev.mjs revoke cn_…
//   node scripts/dev.mjs reset            # clears tasks, results, pings, presence

import { AtallAdmin } from "./lib/client.mjs";

const base = (process.env.ATALL_URL ?? "http://127.0.0.1:54321/functions/v1/atall").replace(/\/+$/, "");
const token = process.env.ATALL_ADMIN_TOKEN;
if (!token) {
  console.error("Set ATALL_ADMIN_TOKEN (and ATALL_URL=https://<ref>.supabase.co/functions/v1/atall).");
  process.exit(2);
}
const admin = new AtallAdmin({ base, token });
const [cmd, ...rest] = process.argv.slice(2);
const flag = (name) => {
  const i = rest.indexOf(name);
  if (i < 0) return undefined;
  const v = rest[i + 1];
  rest.splice(i, 2);
  return v;
};
const has = (name) => {
  const i = rest.indexOf(name);
  if (i < 0) return false;
  rest.splice(i, 1);
  return true;
};

let out;
switch (cmd) {
  case "task": {
    const to = flag("--to");
    const [title, goal] = rest;
    out = await admin.createTask({ title, goal, to: to ?? "@all" });
    break;
  }
  case "state": {
    out = await admin.state();
    if (out.status === 200) {
      const s = out.body;
      for (const t of s.tasks) {
        console.log(`#${t.ref} ${t.title} [${t.state}] to ${t.to.join(",")}`);
        for (const r of t.results) {
          console.log(`   ${r.agent_id} (${r.status}, ${r.recorded_at}): ${r.summary}`);
          for (const c of r.claims) console.log(`     - ${c.text}${c.sources?.length ? `  [${c.sources.map((x) => x.url).join(" ")}]` : ""}`);
        }
      }
      console.log("\nWrite-test pings:");
      for (const p of s.pings) console.log(`   ${p.at} ${p.agent_id} via ${p.client_name}: ${p.note}`);
      console.log("\nPresence:");
      for (const p of s.presence) console.log(`   ${p.agent_id} last ${p.last_tool} at ${p.last_seen} (${p.client_name})`);
      console.log("\nConnections:");
      for (const c of s.connections) console.log(`   ${c.conn_id} ${c.agent_id} ${c.client_name} ${c.kind}${c.revoked ? " REVOKED" : ""}`);
      process.exit(0);
    }
    break;
  }
  case "client": {
    const confidential = has("--confidential");
    const [name, redirect] = rest;
    out = await admin.createClient({ client_name: name, redirect_uris: redirect ? [redirect] : [], confidential });
    break;
  }
  case "token":
    out = await admin.createHeaderToken(rest[0], rest[1]);
    break;
  case "revoke":
    out = await admin.revoke(rest[0]);
    break;
  case "reset":
    out = await admin.reset();
    break;
  default:
    console.error("commands: task | state | client | token | revoke | reset");
    process.exit(2);
}
console.log(JSON.stringify(out.body, null, 2));
process.exit(out.status < 300 ? 0 : 1);
