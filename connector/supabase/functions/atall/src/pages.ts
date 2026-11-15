import { esc } from "./util.ts";
import { KNOWN_AGENTS, type AuthRequest } from "./store.ts";

const STYLE = `
body{font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f5f2;color:#1c1b19}
main{max-width:520px;margin:6vh auto;padding:28px;background:#fff;border-radius:14px;box-shadow:0 1px 3px #0002}
h1{font-size:22px;margin:0 0 6px}p{margin:8px 0}.muted{color:#666;font-size:14px}
label{display:block;margin:16px 0 6px;font-weight:600}
input,select{width:100%;box-sizing:border-box;padding:10px;border:1px solid #ccc;border-radius:8px;font:inherit}
.row{display:flex;gap:10px;margin-top:22px}button{flex:1;padding:12px;border-radius:8px;border:0;font:inherit;font-weight:600;cursor:pointer}
.ok{background:#1c1b19;color:#fff}.no{background:#e9e7e2}
.warn{background:#fff4d6;border:1px solid #f0d58a;padding:10px;border-radius:8px;font-size:14px}
.err{background:#fde3e1;border:1px solid #f2a39b;padding:10px;border-radius:8px}
code{background:#f0eee9;padding:1px 5px;border-radius:4px;word-break:break-all}`;

export function page(title: string, body: string, headExtra = ""): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${headExtra}<title>${esc(title)}</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`;
}

export function errorPage(message: string): string {
  return page("Atall: sign-in problem", `<h1>Atall can't continue this sign-in</h1><p class="err">${esc(message)}</p><p class="muted">Close this tab and start the connection again from the agent.</p>`);
}

export function consentPage(reqId: string, r: AuthRequest, opts: { error?: string; action?: string } = {}): string {
  const redirectHost = (() => {
    try {
      return new URL(r.redirect_uri).host || r.redirect_uri;
    } catch {
      return r.redirect_uri;
    }
  })();
  const options = Object.entries(KNOWN_AGENTS)
    .map(([id, name]) => `<option value="${esc(id)}"${id === r.suggested_agent ? " selected" : ""}>${esc(name)} (${esc(id)})</option>`)
    .join("");
  return page(
    "Connect to Atall",
    `<h1>Connect an agent to Atall</h1>
<p><b>${esc(r.client_name)}</b> wants to read your Atall inbox and post results back.</p>
<p class="muted">It will be sent back to <code>${esc(redirectHost)}</code>.</p>
${r.pin ? `<p class="warn">This client was pre-registered without a return address. Approving pins it to <code>${esc(r.redirect_uri)}</code>.</p>` : ""}
${opts.error ? `<p class="err">${esc(opts.error)}</p>` : ""}
<form method="post" action="${esc(opts.action ?? "authorize")}" autocomplete="off">
<input type="hidden" name="req" value="${esc(reqId)}">
<label for="agent">Which agent is this?</label>
<select id="agent" name="agent_id">${options}<option value="other"${r.suggested_agent === "other" ? " selected" : ""}>Other…</option></select>
<label for="other">If other, its id (lowercase, e.g. <code>hermes</code>)</label>
<input id="other" name="agent_other" pattern="[a-z][a-z0-9_-]{1,31}" placeholder="optional">
<label for="code">Atall owner code</label>
<input id="code" name="owner_code" type="password" required autofocus>
<p class="muted">The connection gets its own token, so everything it posts is labelled with this agent. You can revoke it from the Atall admin page.</p>
<div class="row"><button class="no" name="decision" value="deny" formnovalidate>Deny</button><button class="ok" name="decision" value="approve">Allow</button></div>
</form>`,
  );
}

export function suggestAgent(clientName: string, redirectUri: string): string {
  const s = `${clientName} ${redirectUri}`.toLowerCase();
  if (/chatgpt|openai/.test(s)) return "kdot";
  if (/claude|anthropic/.test(s)) return "duet";
  if (/grok|x\.ai|cursor/.test(s)) return "grok";
  if (/hark/.test(s)) return "hark";
  if (/meta|muse/.test(s)) return "muse";
  return "other";
}

/**
 * Standalone consent page for ATALL_CONSENT_URL mode (built by scripts/build-static.ts, hosted on any
 * static host that serves real text/html). The API base is baked in at build time, never read from the
 * URL, so a crafted link can't make this page send the owner code anywhere else.
 * Flow: GET /authorize → 302 here with ?req=… → GET {api}/authorize/request?req=… (JSON) →
 * POST {api}/authorize (form-encoded, Accept: application/json) → {redirect_to} → navigate.
 */
export function externalConsentPageHtml(apiBase: string): string {
  const api = new URL(apiBase);
  const csp = `default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src ${api.origin}; form-action 'none'; base-uri 'none'`;
  const body = `<h1>Connect an agent to Atall</h1>
<div id="box"><p class="muted">Loading…</p></div>
<form id="f" hidden autocomplete="off">
<p><b id="client"></b> wants to read your Atall inbox and post results back.</p>
<p class="muted">It will be sent back to <code id="host"></code>.</p>
<p class="warn" id="pin" hidden></p>
<p class="err" id="err" hidden></p>
<label for="agent">Which agent is this?</label>
<select id="agent" name="agent_id"></select>
<label for="other">If other, its id (lowercase, e.g. <code>hermes</code>)</label>
<input id="other" name="agent_other" pattern="[a-z][a-z0-9_-]{1,31}" placeholder="optional">
<label for="code">Atall owner code</label>
<input id="code" name="owner_code" type="password" required autofocus>
<p class="muted">The connection gets its own token, so everything it posts is labelled with this agent. You can revoke it from the Atall admin page.</p>
<div class="row"><button type="button" class="no" id="deny">Deny</button><button type="submit" class="ok">Allow</button></div>
</form>
<script>
if (window.top !== window.self) { document.body.textContent = ""; throw new Error("framed"); }
const API = ${JSON.stringify(apiBase.replace(/\/+$/, "")).replace(/</g, "\\u003c")};
const $ = (s) => document.querySelector(s);
const q = new URLSearchParams(location.search);
let req = q.get("req");
function fatal(msg) { const b = $("#box"); b.replaceChildren(); const p = document.createElement("p"); p.className = "err"; p.textContent = msg; const m = document.createElement("p"); m.className = "muted"; m.textContent = "Close this tab and start the connection again from the agent."; b.append(p, m); $("#f").hidden = true; }
async function load() {
  if (q.get("error")) return fatal(q.get("error"));
  if (!req) return fatal("This page needs a sign-in request.");
  const r = await fetch(API + "/authorize/request?req=" + encodeURIComponent(req), { headers: { Accept: "application/json" } });
  const d = await r.json();
  if (!r.ok) return fatal(d.error || "This sign-in request expired.");
  $("#client").textContent = d.client_name; $("#host").textContent = d.redirect_host;
  if (d.pin) { const p = $("#pin"); p.hidden = false; p.textContent = "This client was pre-registered without a return address. Approving pins it to " + d.redirect_uri + "."; }
  const sel = $("#agent");
  for (const [id, name] of Object.entries(d.agents)) { const o = document.createElement("option"); o.value = id; o.textContent = name + " (" + id + ")"; if (id === d.suggested_agent) o.selected = true; sel.append(o); }
  const o = document.createElement("option"); o.value = "other"; o.textContent = "Other…"; if (d.suggested_agent === "other") o.selected = true; sel.append(o);
  $("#box").replaceChildren(); $("#f").hidden = false; $("#code").focus();
}
async function send(decision) {
  const body = new URLSearchParams({ req, decision, agent_id: $("#agent").value, agent_other: $("#other").value, owner_code: $("#code").value });
  const r = await fetch(API + "/authorize", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" }, body });
  const d = await r.json();
  if (d.redirect_to) { location.assign(d.redirect_to); return; }
  if (d.req) { req = d.req; const e = $("#err"); e.hidden = false; e.textContent = d.error; $("#code").value = ""; return; }
  fatal(d.error || "Sign-in failed.");
}
$("#f").addEventListener("submit", (e) => { e.preventDefault(); send("approve").catch(() => fatal("Network error.")); });
$("#deny").addEventListener("click", () => send("deny").catch(() => fatal("Network error.")));
load().catch(() => fatal("Could not reach Atall."));
</script>`;
  return page("Connect to Atall", body, `<meta http-equiv="Content-Security-Policy" content="${esc(csp)}"><meta name="referrer" content="no-referrer">`);
}
