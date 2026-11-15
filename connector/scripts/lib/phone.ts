// A minimal phone for the C1 relay: pairs a P-256 device key, signs requests the way the Android app does,
// and checks relay countersignatures. Used by tests/phone.test.ts and tools/phone-sim.ts.

import { b64decode, b64std, derToRaw, phoneSignedString, verifyCountersig } from "../../supabase/functions/atall/src/crypto.ts";
import { b64url } from "../../supabase/functions/atall/src/util.ts";

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

const P256 = { name: "ECDSA", namedCurve: "P-256" } as const;

/** raw r||s → DER (what Android Keystore produces). */
export function rawToDer(raw: Uint8Array): Uint8Array {
  const int = (b: Uint8Array) => {
    let i = 0;
    while (i < b.length - 1 && b[i] === 0) i++;
    let v = b.slice(i);
    if (v[0] & 0x80) v = Uint8Array.of(0, ...v);
    return Uint8Array.of(0x02, v.length, ...v);
  };
  const r = int(raw.slice(0, 32));
  const s = int(raw.slice(32));
  return Uint8Array.of(0x30, r.length + s.length, ...r, ...s);
}

export interface SignOpts {
  /** Override the timestamp (seconds). */
  ts?: number;
  /** "der" (Android default) or "raw" (r||s). */
  encoding?: "der" | "raw";
  /** Skip signing entirely (unsigned request). */
  unsigned?: boolean;
  /** Use these exact headers instead of signing (replay tests). */
  headers?: Record<string, string>;
  /** Sign with a different key (forgery tests). */
  key?: CryptoKey;
  /** Sign this path instead of the one requested (tests). */
  signPath?: string;
}

export interface PhoneResponse<T = any> {
  status: number;
  body: T;
  headers: Record<string, string>;
}

export class Phone {
  deviceId = "";
  relaySpkiB64 = "";
  keyPair!: CryptoKeyPair;
  /** Headers of the last signed request (to replay it). */
  lastHeaders: Record<string, string> = {};
  /** Last ts used per (method, path, body): ECDSA here is deterministic (RFC 6979), so the same request signed twice in
   *  one second is byte-identical and the relay refuses it as a replay. The helper moves such a repeat to the next second. */
  private lastTs = new Map<string, number>();

  constructor(readonly base: string, readonly fetchFn: FetchLike = fetch) {}

  async init() {
    this.keyPair = (await crypto.subtle.generateKey(P256, false, ["sign", "verify"])) as CryptoKeyPair;
    return this;
  }

  async spkiB64() {
    return b64std(await crypto.subtle.exportKey("spki", this.keyPair.publicKey));
  }

  async pair(ownerCode: string, deviceName = "Test phone"): Promise<PhoneResponse> {
    const res = await this.fetchFn(`${this.base}/phone/pair`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ owner_code: ownerCode, device_name: deviceName, public_key_spki_b64: await this.spkiB64() }),
    });
    const out = await read(res);
    if (res.status === 201) {
      this.deviceId = out.body.device_id;
      this.relaySpkiB64 = out.body.relay_public_key_spki_b64;
    }
    return out;
  }

  async sign(method: string, pathAndQuery: string, body: Uint8Array, opts: SignOpts = {}): Promise<Record<string, string>> {
    let t = opts.ts ?? Math.floor(Date.now() / 1000);
    const k = await phoneSignedString(method, opts.signPath ?? pathAndQuery, "", body);
    if (opts.ts === undefined && (this.lastTs.get(k) ?? -1) >= t) t = this.lastTs.get(k)! + 1;
    this.lastTs.set(k, Math.max(t, this.lastTs.get(k) ?? 0));
    const ts = String(t);
    const signed = await phoneSignedString(method, opts.signPath ?? pathAndQuery, ts, body);
    const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, opts.key ?? this.keyPair.privateKey, new TextEncoder().encode(signed)));
    const sig = (opts.encoding ?? "der") === "der" ? rawToDer(raw) : raw;
    return { "X-Atall-Device": this.deviceId, "X-Atall-Ts": ts, "X-Atall-Sig": b64url(sig) };
  }

  /** A signed request. pathAndQuery is the route after /atall, e.g. "/phone/events?after=0". */
  async request(method: string, pathAndQuery: string, json?: unknown, opts: SignOpts = {}): Promise<PhoneResponse> {
    const bodyText = json === undefined ? "" : JSON.stringify(json);
    const body = new TextEncoder().encode(bodyText);
    const headers: Record<string, string> = json === undefined ? {} : { "Content-Type": "application/json" };
    if (opts.headers) Object.assign(headers, opts.headers);
    else if (!opts.unsigned) Object.assign(headers, await this.sign(method, pathAndQuery, body, opts));
    this.lastHeaders = headers;
    const res = await this.fetchFn(`${this.base}${pathAndQuery}`, { method, headers, ...(json === undefined ? {} : { body: bodyText }) });
    return read(res);
  }

  postTask(card: Record<string, unknown>, opts?: SignOpts) {
    return this.request("POST", "/phone/tasks", card, opts);
  }
  events(after = 0, opts?: SignOpts) {
    return this.request("GET", `/phone/events?after=${after}`, undefined, opts);
  }
  ack(cursor: number) {
    return this.request("POST", "/phone/ack", { cursor });
  }
  answer(taskId: string, questionId: string, text: string) {
    return this.request("POST", "/phone/answers", { task_id: taskId, question_id: questionId, text });
  }
  agents() {
    return this.request("GET", "/phone/agents");
  }
  revokeConnection(connId: string) {
    return this.request("POST", `/phone/connections/${connId}/revoke`);
  }
  revokeDevice(deviceId = this.deviceId) {
    return this.request("POST", `/phone/devices/${deviceId}/revoke`);
  }

  /** Checks every event's relay_sig against the relay key from pairing. */
  async verifyEvents(events: Record<string, unknown>[]): Promise<boolean[]> {
    return Promise.all(events.map((e) => verifyCountersig(this.relaySpkiB64, e)));
  }
}

/** Verifies a task card's phone_signature the way an agent or auditor would. */
export async function verifyPhoneSignature(sig: { device_public_key_spki_b64: string; sig: string; signed_string: string; body: string }): Promise<boolean> {
  const key = await crypto.subtle.importKey("spki", b64decode(sig.device_public_key_spki_b64) as BufferSource, P256, false, ["verify"]);
  const bodyHash = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(sig.body)));
  if (sig.signed_string.split("\n")[3] !== bodyHash) return false;
  let raw = b64decode(sig.sig);
  if (raw.length !== 64) raw = derToRaw(raw) ?? raw;
  return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw as BufferSource, new TextEncoder().encode(sig.signed_string) as BufferSource);
}

async function read(res: Response): Promise<PhoneResponse> {
  const text = await res.text();
  let body: unknown = text;
  try {
    body = text ? JSON.parse(text) : null;
  } catch { /* keep text */ }
  return { status: res.status, body, headers: Object.fromEntries(res.headers.entries()) };
}
