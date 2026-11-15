// Owner-only admin API and the /dev page used to run the C0 test without the
// phone app: create a task, watch results, pings and presence, revoke connections.
// All of it requires `Authorization: Bearer <ATALL_ADMIN_TOKEN>`.

import type { Env } from "./env.ts";
import { createHeaderToken, createStaticClient } from "./oauth.ts";
import { page } from "./pages.ts";
import { AGENT_ID_RE, overallState } from "./store.ts";
import { html, json, safeEqual } from "./util.ts";

async function isAdmin(req: Request, env: Env): Promise<boolean> {
  const tok = env.ATALL_ADMIN_TOKEN ?? "";
  if (tok.length < 16) return false; // refuse to run with a missing or weak admin token
  const h = req.headers.get("Authorization") ?? "";
  return h.startsWith("Bearer ") && (await safeEqual(h.slice(7).trim(), tok));
}

function strList(v: unknown, max = 20): string[] {
  if (v === undefined || v === null || v === "") return [];
  const arr = Array.isArray(v) ? v : String(v).split(/\n|,/);
  return arr.map((s) => String(s).trim()).filter(Boolean).slice(0, max).map((s) => s.slice(0, 500));
}

export async function handleAdmin(req: Request, env: Env, path: string): Promise<Response> {
  if (!(await isAdmin(req, env))) return json({ error: "unauthorized" }, 401);
  const store = env.store;

  if (req.method === "GET" && path === "/admin/state") {
    await store.ensureSeed(env.ATALL_SEED_TASK !== "false");
    const tasks = await store.listTasks();
    const withResults = await Promise.all(tasks.reverse().map(async (t) => ({ ...t, state: overallState(t), results: await store.listResults(t.task_id) })));
    return json({
      tasks: withResults,
      pings: (await store.listPings()).reverse(),
      presence: await store.listPresence(),
      connections: (await store.listConnections()).reverse(),
      devices: (await store.listDevices()).reverse(),
    });
  }

  let body: Record<string, unknown> = {};
  if (req.method === "POST") {
    try {
      const t = await req.text();
      body = t ? JSON.parse(t) : {};
    } catch {
      return json({ error: "body must be JSON" }, 400);
    }
  }

  if (req.method === "POST" && path === "/admin/tasks") {
    const title = String(body.title ?? "").trim().slice(0, 200);
    const goal = String(body.goal ?? "").trim().slice(0, 4000);
    if (!title || !goal) return json({ error: "title and goal are required" }, 400);
    const to = strList(body.to).map((s) => s.toLowerCase());
    if (to.some((a) => a !== "@all" && !AGENT_ID_RE.test(a))) return json({ error: "to must be agent ids or @all" }, 400);
    const task = await store.createTask({
      title,
      goal,
      to: to.length ? to : ["@all"],
      constraints: strList(body.constraints),
      context: strList(body.context),
      do_not_share: strList(body.do_not_share),
      done_when: String(body.done_when ?? "").slice(0, 1000),
      deadline: body.deadline ? String(body.deadline).slice(0, 100) : null,
    });
    return json(task, 201);
  }

  if (req.method === "POST" && path === "/admin/clients") {
    try {
      return json(await createStaticClient(env, { client_name: body.client_name as string, redirect_uris: strList(body.redirect_uris, 10), confidential: !!body.confidential }), 201);
    } catch (e) {
      return json({ error: (e as Error).message }, 400);
    }
  }

  if (req.method === "POST" && path === "/admin/tokens") {
    try {
      return json(await createHeaderToken(env, String(body.agent_id ?? ""), body.label as string | undefined), 201);
    } catch (e) {
      return json({ error: (e as Error).message }, 400);
    }
  }

  const m = /^\/admin\/connections\/([A-Za-z0-9_]+)\/revoke$/.exec(path);
  if (req.method === "POST" && m) {
    await store.revokeConnection(m[1], "revoked by owner");
    return json({ ok: true, conn_id: m[1] });
  }

  if (req.method === "GET" && path === "/admin/devices") return json(await store.listDevices());
  const dm = /^\/admin\/devices\/([A-Za-z0-9_]+)\/revoke$/.exec(path);
  if (req.method === "POST" && dm) {
    return (await store.revokeDevice(dm[1], "revoked by owner")) ? json({ ok: true, device_id: dm[1] }) : json({ error: "no such device" }, 404);
  }

  if (req.method === "POST" && path === "/admin/reset") {
    await store.reset();
    return json({ ok: true });
  }

  return json({ error: "not found" }, 404);
}

export function devPage(apiBase: string): Response {
  return html(devPageHtml(apiBase));
}

/** The /dev console. apiBase is the function's public URL; scripts/build-static.ts also bakes it into
 *  a standalone dev.html for hosts where *.supabase.co would serve this page as text/plain. */
export function devPageHtml(apiBase: string, headExtra = ""): string {
  // No secrets in the page: it asks for the admin token and keeps it in sessionStorage.
  // Agent-supplied text is rendered with textContent only, and links only if http(s).
  const body = `<style>main{max-width:860px}table{border-collapse:collapse;width:100%;font-size:14px}td,th{border-bottom:1px solid #eee;padding:6px;text-align:left;vertical-align:top}
.card{border:1px solid #e4e1da;border-radius:10px;padding:12px;margin:10px 0}.res{background:#f7f6f3;border-radius:8px;padding:8px;margin:6px 0}textarea{width:100%;box-sizing:border-box;min-height:60px;padding:8px;border:1px solid #ccc;border-radius:8px;font:inherit}
.pill{display:inline-block;padding:1px 8px;border-radius:99px;background:#eee;font-size:12px;margin-left:6px}button.small{padding:4px 10px;flex:none;border-radius:6px;border:1px solid #ccc;background:#fff;cursor:pointer}</style>
<h1>Atall connector · dev</h1>
<p class="muted">C0 test console. Create a task, then watch agents read it and post results.</p>
<div id="login"><label for="tok">Admin token</label><input id="tok" type="password"><div class="row"><button class="ok" id="save">Open</button></div></div>
<div id="app" hidden>
<h2>New task</h2>
<label>Title</label><input id="t-title" value="Atall write test">
<label>Goal</label><textarea id="t-goal">Call atall_post_result for this task with status done, a one-sentence summary saying which assistant you are, and one claim with a source URL.</textarea>
<label>To (agent ids, comma separated, or @all)</label><input id="t-to" value="@all">
<div class="row"><button class="ok" id="create">Create task</button><button class="no" id="reset">Clear tasks, results, pings</button></div>
<p id="msg" class="muted"></p>
<h2>Write-test pings</h2><table id="pings"></table>
<h2>Presence</h2><table id="presence"></table>
<h2>Tasks and results</h2><div id="tasks"></div>
<h2>Connections</h2><table id="conns"></table>
</div>
<script>
const API=${JSON.stringify(apiBase).replace(/</g, "\\u003c")};const $=s=>document.querySelector(s);let tok=sessionStorage.getItem("atall_admin")||"";
function el(tag,text,attrs){const e=document.createElement(tag);if(text!=null)e.textContent=String(text);if(attrs)for(const k in attrs)e.setAttribute(k,attrs[k]);return e}
function link(u,label){try{const x=new URL(u);if(x.protocol==="https:"||x.protocol==="http:"){const a=el("a",label||u,{href:x.href,target:"_blank",rel:"noopener noreferrer"});return a}}catch{}return el("span",u)}
function row(cells,th){const tr=el("tr");cells.forEach(c=>{const td=el(th?"th":"td");if(c instanceof Node)td.append(c);else td.textContent=c==null?"":String(c);tr.append(td)});return tr}
async function api(path,opts={}){const r=await fetch(API+path,{...opts,headers:{"Authorization":"Bearer "+tok,"Content-Type":"application/json"}});if(r.status===401){sessionStorage.removeItem("atall_admin");tok="";show();throw new Error("unauthorized")}return r.json()}
function show(){$("#login").hidden=!!tok;$("#app").hidden=!tok}
async function refresh(){if(!tok)return;const s=await api("/admin/state");
const p=$("#pings");p.replaceChildren(row(["When","Agent","Connection","Note"],true));s.pings.forEach(x=>p.append(row([new Date(x.at).toLocaleString(),x.agent_id,x.client_name,x.note])));
const pr=$("#presence");pr.replaceChildren(row(["Agent","Last seen","Last call","Client"],true));s.presence.forEach(x=>pr.append(row([x.agent_id,new Date(x.last_seen).toLocaleString(),x.last_tool,x.client_name])));
const ts=$("#tasks");ts.replaceChildren();s.tasks.forEach(t=>{const c=el("div",null,{class:"card"});const h=el("div");h.append(el("b","#"+t.ref+" "+t.title),el("span",t.state,{class:"pill"}),el("span","to "+t.to.join(", "),{class:"pill"}));c.append(h,el("div",t.goal,{class:"muted"}));
 if(!t.results.length)c.append(el("p","No results yet.",{class:"muted"}));
 t.results.forEach(r=>{const d=el("div",null,{class:"res"});d.append(el("b",r.agent_id+" · "+r.status),el("span",new Date(r.recorded_at).toLocaleString(),{class:"pill"}),el("p",r.summary));
  if(r.claims.length){const ul=el("ul");r.claims.forEach(cl=>{const li=el("li",cl.text+(cl.confidence!=null?" ("+cl.confidence+")":""));(cl.sources||[]).forEach(src=>{li.append(" ",link(src.url,src.title||"source"))});ul.append(li)});d.append(ul)}
  (r.artifacts||[]).forEach(a=>d.append(link(a.url,a.name)));c.append(d)});ts.append(c)});
const cn=$("#conns");cn.replaceChildren(row(["Agent","Client","Kind","Created","Status",""],true));s.connections.forEach(x=>{const b=el("button","Revoke",{class:"small"});b.onclick=async()=>{await api("/admin/connections/"+x.conn_id+"/revoke",{method:"POST"});refresh()};cn.append(row([x.agent_id,x.client_name,x.kind,new Date(x.created_at).toLocaleString(),x.revoked?"revoked":"active",x.revoked?"":b]))});}
$("#save").onclick=()=>{tok=$("#tok").value.trim();sessionStorage.setItem("atall_admin",tok);show();refresh().catch(()=>{})};
$("#create").onclick=async()=>{const t=await api("/admin/tasks",{method:"POST",body:JSON.stringify({title:$("#t-title").value,goal:$("#t-goal").value,to:$("#t-to").value})});$("#msg").textContent=t.error?t.error:"Created #"+t.ref+" ("+t.task_id+")";refresh()};
$("#reset").onclick=async()=>{if(confirm("Clear all tasks, results, pings and presence?")){await api("/admin/reset",{method:"POST"});refresh()}};
show();refresh().catch(()=>{});setInterval(()=>refresh().catch(()=>{}),4000);
</script>`;
  return page("Atall dev", body, headExtra);
}
