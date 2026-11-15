// Phone simulator for the live C1 relay. Plays Kush's phone: pairs a device key, posts a signed task,
// reads events and checks every relay countersignature, then un-pairs.
//
//   deno run -A tools/phone-sim.ts pair            # pair a test device (owner code read from .atall-secrets.local, never printed)
//   deno run -A tools/phone-sim.ts task [ref]      # POST /phone/tasks: "C1 smoke test" to ["hark"] (default ref c1t1)
//   deno run -A tools/phone-sim.ts events [--wait] # GET /phone/events, verify relay_sig on each (--wait: poll up to 120 s for a result)
//   deno run -A tools/phone-sim.ts agents          # GET /phone/agents
//   deno run -A tools/phone-sim.ts revoke          # un-pair the test device, then prove it gets 401
//   deno run -A tools/phone-sim.ts negative        # unsigned / forged / replayed requests must all be refused
//
// Base URL: ATALL_PHONE_BASE, default https://ggmvzyyuksewkgbokrhe.functions.supabase.co/atall (from the Hark sandbox the
// gateway injects a token on *.supabase.co, so this host is used instead; the phone uses …supabase.co/functions/v1/atall).
// State (device id, device private key, relay public key) lives in tools/.phone-sim.state.json, mode 600, git-ignored.

import { Phone } from "../scripts/lib/phone.ts";
import { b64decode, b64std, verifyCountersig } from "../supabase/functions/atall/src/crypto.ts";

const BASE = (Deno.env.get("ATALL_PHONE_BASE") ?? "https://ggmvzyyuksewkgbokrhe.functions.supabase.co/atall").replace(/\/+$/, "");
const DIR = new URL(".", import.meta.url);
const STATE = new URL(".phone-sim.state.json", DIR);
const SECRETS = new URL("../.atall-secrets.local", DIR);
const P256 = { name: "ECDSA", namedCurve: "P-256" } as const;

interface State { device_id: string; pkcs8_b64: string; spki_b64: string; relay_spki_b64: string; task_id?: string; ref?: string }

function ownerCode(): string {
  const line = Deno.readTextFileSync(SECRETS).split("\n").find((l) => l.startsWith("ATALL_OWNER_CODE="));
  if (!line) throw new Error("ATALL_OWNER_CODE missing from .atall-secrets.local");
  return line.slice("ATALL_OWNER_CODE=".length).trim();
}

function save(s: State) {
  Deno.writeTextFileSync(STATE, JSON.stringify(s, null, 2), { mode: 0o600 });
  Deno.chmodSync(STATE, 0o600);
}
function load(): State {
  return JSON.parse(Deno.readTextFileSync(STATE));
}

async function phoneFromState(s: State): Promise<Phone> {
  const p = new Phone(BASE);
  const privateKey = await crypto.subtle.importKey("pkcs8", b64decode(s.pkcs8_b64) as BufferSource, P256, false, ["sign"]);
  const publicKey = await crypto.subtle.importKey("spki", b64decode(s.spki_b64) as BufferSource, P256, true, ["verify"]);
  p.keyPair = { privateKey, publicKey } as CryptoKeyPair;
  p.deviceId = s.device_id;
  p.relaySpkiB64 = s.relay_spki_b64;
  return p;
}

function show(label: string, r: { status: number; body: unknown }) {
  console.log(`${label}: HTTP ${r.status} ${JSON.stringify(r.body).slice(0, 400)}`);
}

async function verifyAll(p: Phone, events: Record<string, unknown>[]) {
  let ok = 0;
  for (const e of events) {
    const v = await verifyCountersig(p.relaySpkiB64, e);
    if (v) ok++;
    const pl = (e.payload ?? {}) as Record<string, unknown>;
    console.log(`  #${e.cursor} ${e.type} agent=${e.agent_id} task=${e.task_id ?? "-"} relay_sig ${v ? "VERIFIED" : "BAD"}${pl.summary ? ` summary=${JSON.stringify(pl.summary)}` : ""}${pl.message ? ` message=${JSON.stringify(pl.message)}` : ""}`);
  }
  console.log(`countersignatures: ${ok}/${events.length} verified`);
  return ok === events.length;
}

const cmd = Deno.args[0] ?? "help";

if (cmd === "pair") {
  const kp = (await crypto.subtle.generateKey(P256, true, ["sign", "verify"])) as CryptoKeyPair;
  const p = new Phone(BASE);
  p.keyPair = kp;
  const r = await p.pair(ownerCode(), "phone-sim (Hark sandbox)");
  show("pair", { status: r.status, body: { ...r.body, relay_public_key_spki_b64: r.body?.relay_public_key_spki_b64 ? "(received)" : undefined } });
  if (r.status !== 201) Deno.exit(1);
  save({ device_id: p.deviceId, pkcs8_b64: b64std(await crypto.subtle.exportKey("pkcs8", kp.privateKey)), spki_b64: await p.spkiB64(), relay_spki_b64: p.relaySpkiB64 });
  console.log(`relay public key (SPKI): ${p.relaySpkiB64}`);
} else if (cmd === "task") {
  const s = load();
  const p = await phoneFromState(s);
  const ref = Deno.args[1] ?? "c1t1";
  const r = await p.postTask({ ref, title: "C1 smoke test", goal: "Reply with one claim: today's date, with your confidence and source.", to: ["hark"], output: "claims" });
  show("POST /phone/tasks", r);
  if (r.status !== 201) Deno.exit(1);
  save({ ...s, task_id: r.body.task_id, ref });
} else if (cmd === "events") {
  const s = load();
  const p = await phoneFromState(s);
  const wait = Deno.args.includes("--wait");
  const deadline = Date.now() + 120_000;
  for (;;) {
    const r = await p.events(0);
    if (r.status !== 200) { show("GET /phone/events", r); Deno.exit(1); }
    const all = r.body.events as Record<string, unknown>[];
    const mine = s.task_id ? all.filter((e) => e.task_id === s.task_id) : all;
    const result = mine.find((e) => e.type === "result");
    if (!wait || result || Date.now() > deadline) {
      console.log(`GET /phone/events: ${all.length} event(s), next_cursor ${r.body.next_cursor}; ${mine.length} for ${s.ref ?? "all"}:`);
      const ok = await verifyAll(p, mine);
      if (result) console.log(`result event payload: ${JSON.stringify(result.payload)}`);
      const allOk = await Promise.all(all.map((e) => verifyCountersig(p.relaySpkiB64, e)));
      console.log(`all events in the feed: ${allOk.filter(Boolean).length}/${all.length} countersignatures verified`);
      if (!ok || (wait && !result)) Deno.exit(1);
      break;
    }
    await new Promise((res) => setTimeout(res, 5000));
  }
} else if (cmd === "agents") {
  const p = await phoneFromState(load());
  const r = await p.agents();
  console.log(`GET /phone/agents: HTTP ${r.status}`);
  for (const a of r.body ?? []) console.log(`  ${a.agent_id} ${a.conn_id} ${a.kind} "${a.client_name}" last_seen=${a.last_seen} last_tool=${a.last_tool}`);
} else if (cmd === "negative") {
  const p = await phoneFromState(load());
  const card = { ref: "c1neg", title: "should never exist", goal: "x", to: ["hark"] };
  show("unsigned task", await p.postTask(card, { unsigned: true }));
  const attacker = (await crypto.subtle.generateKey(P256, false, ["sign"])) as CryptoKeyPair;
  show("forged task (other key)", await p.postTask(card, { key: attacker.privateKey }));
  show("stale ts", await p.events(0, { ts: Math.floor(Date.now() / 1000) - 300 }));
  const ok = await p.events(0);
  console.log(`fresh events: HTTP ${ok.status}`);
  show("replayed signature", await p.request("GET", "/phone/events?after=0", undefined, { headers: p.lastHeaders }));
} else if (cmd === "revoke") {
  const s = load();
  const p = await phoneFromState(s);
  show("POST /phone/devices/{id}/revoke", await p.revokeDevice());
  show("after revoke, GET /phone/events", await p.events(0));
} else {
  console.log("usage: deno run -A tools/phone-sim.ts pair|task [ref]|events [--wait]|agents|negative|revoke");
}
