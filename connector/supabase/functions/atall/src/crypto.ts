// C1 crypto: phone request signatures (ES256 by the phone's Keystore key) and relay countersignatures
// (ES256 by the relay key in ATALL_RELAY_SIGNING_KEY). WebCrypto only; no third-party imports.

import { b64url } from "./util.ts";

const enc = new TextEncoder();
const P256 = { name: "ECDSA", namedCurve: "P-256" } as const;
const ES256 = { name: "ECDSA", hash: "SHA-256" } as const;
/** P-256 group order. */
const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;

/** Decodes standard or url-safe base64, with or without padding. Throws on garbage. */
export function b64decode(text: string): Uint8Array {
  const s = text.trim().replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
  if (!/^[A-Za-z0-9+/]*$/.test(s)) throw new Error("not base64");
  const bin = atob(s + "=".repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export function b64std(bytes: ArrayBuffer | Uint8Array): string {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const b of u8) s += String.fromCharCode(b);
  return btoa(s);
}

export async function sha256(bytes: Uint8Array | string): Promise<Uint8Array> {
  const data = typeof bytes === "string" ? enc.encode(bytes) : bytes;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource));
}

/**
 * Canonical JSON for countersignatures: object keys sorted (JS default sort = UTF-16 code units), no whitespace,
 * values as JSON.stringify writes them (RFC 8785 for the values this relay emits). `undefined` members are dropped.
 */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== "object") {
    if (typeof v === "number" && !Number.isFinite(v)) throw new Error("non-finite number in canonical JSON");
    return JSON.stringify(v) ?? "null";
  }
  if (Array.isArray(v)) return "[" + v.map((x) => (x === undefined ? "null" : canonicalJson(x))).join(",") + "]";
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o).filter((k) => o[k] !== undefined).sort();
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonicalJson(o[k])).join(",") + "}";
}

// ---------------------------------------------------------------------------
// ECDSA signature encodings
// ---------------------------------------------------------------------------

function bytesToBig(b: Uint8Array): bigint {
  let x = 0n;
  for (const v of b) x = (x << 8n) | BigInt(v);
  return x;
}
function bigTo32(x: bigint): Uint8Array {
  const out = new Uint8Array(32);
  for (let i = 31; i >= 0; i--) {
    out[i] = Number(x & 0xffn);
    x >>= 8n;
  }
  return out;
}

/** DER ECDSA-Sig-Value → raw r||s (64 bytes). Null if it isn't well-formed DER. */
export function derToRaw(der: Uint8Array): Uint8Array | null {
  try {
    let i = 0;
    if (der[i++] !== 0x30) return null;
    let len = der[i++];
    if (len & 0x80) {
      const n = len & 0x7f;
      if (n !== 1) return null;
      len = der[i++];
    }
    if (i + len !== der.length) return null;
    const int = () => {
      if (der[i++] !== 0x02) throw new Error("bad int");
      const l = der[i++];
      if (l < 1 || l > 33 || i + l > der.length) throw new Error("bad int length");
      let v = der.slice(i, i + l);
      i += l;
      while (v.length > 1 && v[0] === 0) v = v.slice(1);
      if (v.length > 32) throw new Error("int too long");
      const out = new Uint8Array(32);
      out.set(v, 32 - v.length);
      return out;
    };
    const r = int();
    const s = int();
    if (i !== der.length) return null;
    const raw = new Uint8Array(64);
    raw.set(r, 0);
    raw.set(s, 32);
    return raw;
  } catch {
    return null;
  }
}

/** Candidate raw (r||s) forms of a signature given as DER or raw. */
function rawCandidates(sig: Uint8Array): Uint8Array[] {
  const out: Uint8Array[] = [];
  if (sig.length === 64) out.push(sig);
  if (sig[0] === 0x30) {
    const r = derToRaw(sig);
    if (r) out.push(r);
  }
  return out;
}

/** Low-s raw form: the same signature however it was encoded or malleated ((r, s) and (r, n−s) are both valid). */
export function normalizeSig(raw: Uint8Array): Uint8Array {
  const s = bytesToBig(raw.slice(32));
  const lowS = s > N / 2n ? N - s : s;
  const out = new Uint8Array(64);
  out.set(raw.slice(0, 32), 0);
  out.set(bigTo32(lowS), 32);
  return out;
}

// ---------------------------------------------------------------------------
// Device keys (phone)
// ---------------------------------------------------------------------------

/** Imports a P-256 SubjectPublicKeyInfo (base64 or base64url). Throws if it isn't one. */
export async function importDeviceKey(spkiB64: string): Promise<CryptoKey> {
  const der = b64decode(spkiB64);
  if (der.length < 59 || der.length > 200) throw new Error("public key is not a P-256 SPKI");
  return await crypto.subtle.importKey("spki", der as BufferSource, P256, true, ["verify"]);
}

/**
 * Verifies an ES256 signature (DER or raw r||s, base64url or base64) over `data`.
 * Returns the normalized raw signature (for the replay guard) or null.
 */
export async function verifyEs256(key: CryptoKey, sigB64: string, data: string | Uint8Array): Promise<Uint8Array | null> {
  let sig: Uint8Array;
  try {
    sig = b64decode(sigB64);
  } catch {
    return null;
  }
  const bytes = typeof data === "string" ? enc.encode(data) : data;
  for (const raw of rawCandidates(sig)) {
    try {
      if (await crypto.subtle.verify(ES256, key, raw as BufferSource, bytes as BufferSource)) return normalizeSig(raw);
    } catch { /* try the next encoding */ }
  }
  return null;
}

/** `${METHOD}\n${PATH}\n${TS}\n${base64url(sha256(body))}` */
export async function phoneSignedString(method: string, pathAndQuery: string, ts: string | number, body: Uint8Array): Promise<string> {
  return `${method.toUpperCase()}\n${pathAndQuery}\n${ts}\n${b64url(await sha256(body))}`;
}

// ---------------------------------------------------------------------------
// Relay key (countersignatures)
// ---------------------------------------------------------------------------

export interface RelayKey {
  privateKey: CryptoKey;
  publicKey: CryptoKey;
  /** SubjectPublicKeyInfo DER, standard base64 (what /phone/pair returns). */
  publicSpkiB64: string;
}

const relayKeyCache = new Map<string, Promise<RelayKey>>();

/** Loads the relay key from PKCS8 (base64). The public key is derived from the private JWK. Cached per value. */
export function loadRelayKey(pkcs8B64: string): Promise<RelayKey> {
  let p = relayKeyCache.get(pkcs8B64);
  if (!p) {
    p = (async () => {
      const privateKey = await crypto.subtle.importKey("pkcs8", b64decode(pkcs8B64) as BufferSource, P256, true, ["sign"]);
      const jwk = await crypto.subtle.exportKey("jwk", privateKey);
      const publicKey = await crypto.subtle.importKey("jwk", { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, ext: true }, P256, true, ["verify"]);
      const publicSpkiB64 = b64std(await crypto.subtle.exportKey("spki", publicKey));
      return { privateKey, publicKey, publicSpkiB64 };
    })();
    p.catch(() => relayKeyCache.delete(pkcs8B64));
    relayKeyCache.set(pkcs8B64, p);
  }
  return p;
}

/** Generates a fresh relay key; returns PKCS8 and SPKI as standard base64. */
export async function generateRelayKey(): Promise<{ pkcs8B64: string; spkiB64: string }> {
  const kp = (await crypto.subtle.generateKey(P256, true, ["sign", "verify"])) as CryptoKeyPair;
  return { pkcs8B64: b64std(await crypto.subtle.exportKey("pkcs8", kp.privateKey)), spkiB64: b64std(await crypto.subtle.exportKey("spki", kp.publicKey)) };
}

/** relay_sig: base64url raw r||s ES256 over the canonical JSON of the event without relay_sig. */
export async function countersign<T extends Record<string, unknown>>(key: RelayKey, event: T): Promise<T & { relay_sig: string }> {
  const { relay_sig: _drop, ...rest } = event as T & { relay_sig?: string };
  const sig = await crypto.subtle.sign(ES256, key.privateKey, enc.encode(canonicalJson(rest)));
  return { ...(rest as T), relay_sig: b64url(sig) };
}

/** Verifies a relay_sig with the relay public key (SPKI base64). Used by tests and tools/phone-sim.ts. */
export async function verifyCountersig(relaySpkiB64: string, event: Record<string, unknown>): Promise<boolean> {
  const sig = event.relay_sig;
  if (typeof sig !== "string") return false;
  const { relay_sig: _drop, ...rest } = event;
  const key = await crypto.subtle.importKey("spki", b64decode(relaySpkiB64) as BufferSource, P256, false, ["verify"]);
  return (await verifyEs256(key, sig, canonicalJson(rest))) !== null;
}
