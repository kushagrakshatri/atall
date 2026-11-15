# C1 contract: phone ⇄ relay (Supabase Edge Function "atall")

Base URL: https://ggmvzyyuksewkgbokrhe.supabase.co/functions/v1/atall
(From the Hark sandbox, call the same routes on https://ggmvzyyuksewkgbokrhe.functions.supabase.co/atall instead, because the gateway injects a Hark token on the supabase.co host.)

## Pairing (one time per phone)
POST /phone/pair  JSON {owner_code, device_name, public_key_spki_b64}
- The phone generates a P-256 key in Android Keystore (non-exportable) and sends its SPKI DER as base64.
- Returns 201 {device_id, relay_public_key_spki_b64, relay_key_alg:"ES256"}. Rate-limited the same way as the owner-code consent.
- The phone stores device_id and the relay public key.

## Signed phone requests (every /phone/* route except /phone/pair)
Headers: X-Atall-Device: <device_id>, X-Atall-Ts: <unix seconds>, X-Atall-Sig: <base64url ECDSA P-256 SHA-256 sig, DER or raw r||s — server accepts both>
Signed string: `${METHOD}\n${PATH}\n${TS}\n${base64url(sha256(body bytes))}` where PATH is the path after /atall (e.g. /phone/tasks) plus the query string if any.
Reject if |now-ts|>120 s, unknown/revoked device, bad sig, or a replayed (device, ts, sig) → 401 {error}.

## Routes
- POST /phone/tasks  body = task card {ref (4-6 [a-z0-9]), title, goal, to: ["all"] | [agent_id...], context?, constraints?, done_criteria?, dont_share?, deadline? (ISO), output?: "claims"}
  The relay stores the card WITH the phone's signature (phone_sig, signed string) so agents/auditors can see it was phone-signed. Returns 201 {task_id, ref}. A task posted any other way (no valid device signature) is refused. The /admin/tasks route stays for tests only.
- GET /phone/events?after=<cursor>  → {events:[{cursor, type: result|progress|question|claim|approval_request|presence|manifest, task_id?, agent_id, conn_id, client_name, at, payload, relay_sig}], next_cursor}
  relay_sig = base64url ES256 over the canonical JSON (keys sorted, no whitespace) of the event minus relay_sig. The relay private key lives in the function secret ATALL_RELAY_SIGNING_KEY (PKCS8 b64).
- POST /phone/ack {cursor}: events ≤ cursor are deleted after 24 h (TTL). Tasks expire at deadline+24 h, or 7 d if there is no deadline.
- POST /phone/answers {task_id, question_id, text}: answers an atall_ask question. It shows up in the agent's atall_inbox.
- GET /phone/agents → [{agent_id, conn_id, client_name, kind, created_at, last_seen, last_tool}]
- POST /phone/connections/{conn_id}/revoke: tokens are cut immediately, so the next tool call gets 401.

## MCP tools (all 8, names stable)
atall_inbox, atall_get_task, atall_claim, atall_progress (≤1 per 30 s per task), atall_post_result (summary, claims[{text, confidence, sources[{url,title}]}], artifacts, status done|partial), atall_ask, atall_request_approval (C1: records AUTH_REQUIRED + emits an approval_request event; tokens arrive in C3), atall_manifest. Keep atall_ping_write for tests.
Task states: SUBMITTED → WORKING (claim) → INPUT_REQUIRED (ask) / AUTH_REQUIRED (approval) → DONE/PARTIAL; per-agent state for @all tasks.
