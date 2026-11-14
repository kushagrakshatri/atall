# Sign in with ChatGPT for the new app: design notes

Module: `new-product/chatgpt-auth/` · package `app.atall.chatgpt` · pure Kotlin/JVM (OkHttp 4.12, kotlinx.serialization 1.7.3, java.security only; no Android imports)
Status 2026-11-13: library + 39 JVM tests green against a local mock OpenAI. **Not yet run against the real auth.openai.com / api.openai.com** (on purpose: no sign-in as the user from the sandbox).

---

## 0. TL;DR

- **Use OpenAI's sanctioned route: Sign in with ChatGPT (SIWC), "ChatGPT plan usage for open-source, locally hosted apps".** This is what Hither already ships and proved in CI (`hermes-android@feature/on-device`: `ChatGptAuth.kt`, `ChatGptLoopback.kt`, `HitherChatGptModule.kt`, `ondevice/hither/hither_chatgpt*.py`). This module is that flow rewritten in plain Kotlin with no Python, plus the model client and a tool-calling loop.
- **Do not copy the Codex CLI route** (Codex's own OAuth client `app_EMoamEEZ73f0CkXaXp7hrann` → `chatgpt.com/backend-api/codex` + `chatgpt-account-id`). It works in Codex/Hermes, but it borrows another product's client, and OpenAI's SIWC docs say plainly: *"do not point it at ChatGPT's `backend-api` endpoints."* Documented in §8 for reference only; the library refuses to send the token anywhere but the configured API base.
- **Login on Android:** Chrome Custom Tab → `https://auth.openai.com/api/accounts/authorize` → 302 to `http://127.0.0.1:<port>/auth/callback` served by a one-shot `ServerSocket` in the app → 302 to an explicit-package `intent://` URL back into the app. App Links and custom schemes **cannot** be the redirect: SIWC requires an http loopback redirect on `127.0.0.1`.
- **Tokens:** the app is the only owner. Vault = one JSON file encrypted with AES-256-GCM using a non-exportable **Android Keystore** key, in `noBackupFilesDir`, excluded from backup/device transfer. **Not** EncryptedSharedPreferences (androidx.security-crypto is deprecated).
- **Refresh:** access token 1 h, refresh token 30 days rolling, **single-use and rotating**. Refresh 300 s before expiry under one process-wide lock; persist the new pair before use; listed codes end the sign-in.
- **Models:** `GET /v1/models` with the user's token (`visibility == "list"`). Hither defaulted to `gpt-6.1-sol` (OpenAI's own docs example).
- **Limits:** usage counts against the person's plan. Per-app weekly cap (10–100 %) set in ChatGPT → Settings → Usage. On Plus the 5-hour limit is shared across all apps; Pro has no 5-hour limit. 429 `subscription_sharing_usage_limit_exceeded` = stop and link to Manage usage.
- **ToS gate:** the self-serve route is for **open-source, locally hosted** apps. A paid or closed-source app must go through OpenAI's commercial interest form instead.

---

## 1. What the existing app does (studied)

| Piece | File (hermes-android, branch `feature/on-device`) | Carried over? |
|---|---|---|
| PKCE/state/nonce, authorize URL, callback judging, `intent://` return, done page | `apps/mobile/android/app/src/ondevice/java/app/hither/mobile/ChatGptAuth.kt` | Yes, nearly verbatim → `Authorize.kt` |
| One-shot loopback listener on 127.0.0.1:0 | `…/ChatGptLoopback.kt` | Yes → `LoopbackServer.kt` (generalised: app name, paths, linger) |
| Custom Tab launch, per-install host id, hand-off to Python over stdin | `…/HitherChatGptModule.kt` | Android glue only (§3); the Python hop is gone |
| Code exchange, JWKS RS256 ID-token check, vault, refresh, revoke, background-use rule | `apps/mobile/ondevice/hither/hither_chatgpt.py` | Rewritten in Kotlin → `OAuthClient.kt`, `ChatGptAuth.kt`, `Credentials.kt` |
| Responses shaping (store/stream, banned fields, system→developer, `additional_tools`), SSE collection, SIWC error copy | `apps/mobile/ondevice/hither/hither_chatgpt_provider.py` | Rewritten → `Responses.kt`; error copy stays in the UI layer |
| Mock OpenAI for CI | `apps/mobile/ondevice/signin/mock-openai.mjs` | Re-done in Kotlin for JVM tests → `src/test/.../MockOpenAI.kt` |
| `LocalHermesCredentials.kt` | per-install password for the local Hermes dashboard | **Not related** to ChatGPT; nothing to carry over |
| Full analysis + decisions | `on-device-spike/chatgpt-signin.md` (§0 "as built", 99/99 CI checks, run 40635946559) | Basis for these notes |

Differences from Hither, by design:
- No Python peer, so no cross-process file lock and no stdin hand-off. One `ChatGptAuth` per process.
- The Keystore becomes usable (Hither couldn't: Python had to read the tokens).
- `id_token_hint` is supported but **off by default** (`beginSignIn(useIdTokenHint = true)`). OpenAI allows it and it skips the account picker, but it puts an ID token in the browser URL/history. Hither chose `login_hint` only. Same default here.

---

## 2. The protocol (as implemented)

Sources: developers.openai.com/siwc/token-sharing-open-source{, /sign-in, /profiles-and-sessions, /models-and-inference, /token-reference, /errors-and-recovery, /preview-limitations}, help.openai.com/en/articles/20001542 (all fetched 2026-11-13).

### 2.1 Identities
- **Client** = one OAuth registration per ChatGPT account + workspace. The first sign-in uses `client_id=dynamic_agent_client` and `agent_name_hint=<AppName>`, and the callback returns the issued `client_id` (`oaiapp_…`). Save it and reuse it for every later sign-in, refresh and revoke. Never save `dynamic_agent_client`.
- **Host** = this install. `ext_agent_host_id` = `urn:uuid:<v4>` (supported; RFC 9278 JWK-thumbprint URIs are "recommended" but optional and unverified by OpenAI). Choose it before the first sign-in and keep it for the install's life. It's an identifier, not a credential.

### 2.2 Authorize (system browser / Custom Tab)
`GET https://auth.openai.com/api/accounts/authorize?` with:
`client_id` (dynamic or issued) · `response_type=code` · `redirect_uri=http://127.0.0.1:<port>/auth/callback` · `scope=openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` · `resource=https://api.openai.com/v1` · `state` · `nonce` · `code_challenge_method=S256` · `code_challenge` · `ext_agent_host_id` · first registration only: `agent_name_hint` · reauth: `login_hint` (and optionally `id_token_hint`) · re-asking for plan use only: `prompt=consent` (`force_reconsent=true` once OpenAI confirms rollout for the integration).

Redirect rules: http, host exactly `127.0.0.1` (not `localhost`), path exactly `/auth/callback`. Only the port may vary between attempts, and the URI must be byte-identical in the authorize request and the code exchange.

### 2.3 Callback
- New registration: `?code&scope&state&client_id`. Reauth may omit `client_id`; a *different* one is rejected.
- `error=access_denied` = declined: validate state, then stop.
- The listener answers only `GET /auth/callback` with `Host: 127.0.0.1:<port>` and exactly one constant-time-equal `state`. Anything else gets an empty 404/400 and does **not** consume the attempt.

### 2.4 Code exchange
`POST https://auth.openai.com/api/accounts/oauth/token` (form) with `grant_type=authorization_code, client_id=<issued>, code, code_verifier, redirect_uri, resource`. No secret. On `invalid_grant`, discard the code and start a fresh attempt with the same issued client id (`AuthFailure("code_rejected")`).
- The response has `access_token, refresh_token, id_token, token_type, expires_in (3600), scope, earliest_refresh_at`.
- Endpoints come from `/.well-known/openid-configuration`, **same origin only**, with the documented fallbacks.

### 2.5 Validate
- ID token: RS256 against the JWKS (refetched once on unknown `kid`), `iss`, `aud ∋ issued client id` (and `azp` rules), `exp`/`iat` with 60 s leeway, and the attempt's `nonce`. Use `sub` as the identity. On a returning account, `sub` must match (`account_mismatch`).
- **Plan permission:** `chatgpt.tokens.use.direct` must be in the granted scopes. Without it, keep the sign-in, mark `planUsage=false` and refuse inference (`PlanUsageNotAllowed`). The UI then offers "Allow" → `SignInMode.RECONSENT`.

### 2.6 Refresh
`POST token_endpoint` with `grant_type=refresh_token, client_id=<issued>, refresh_token, resource` and **no `scope`** (keeps the grant).
- Refresh tokens rotate and are single-use. Store the replacement before using it.
- Serialize refreshes; replaying a consumed token returns `refresh_token_reused` and kills the session.
- Respect `earliest_refresh_at`.
- Terminal codes end the sign-in (state `ended`, tokens wiped, registration kept): `invalid_grant, invalid_refresh_token, token_expired, refresh_token_expired, refresh_token_invalidated, refresh_token_reused`, plus `invalid_client` or HTTP 401. 429/5xx/network → `TransientFailure` (keep the credentials).

### 2.7 Sign-out
`POST revocation_endpoint` with `token=<refresh>, token_type_hint=refresh_token, client_id`. An empty 200 is success, including for an already-invalid token.
- Retry with backoff on network/5xx/429 (3 tries).
- Then clear access, refresh and ID tokens. Keep the client id, email and host id so the registration is reused.
- If revocation wasn't confirmed, say so and link ChatGPT → Settings → Security and login → Login connections → Disconnect.
- OpenAI does **not** notify the app when the user disconnects it in ChatGPT; that shows up as a refresh or 401 failure.

### 2.8 Inference
`POST https://api.openai.com/v1/responses` with `Authorization: Bearer <access>`. Preview limits (enforced by `RequestShaper`):
- `store:false` and `stream:true` on every request, consumed as SSE. Success is **only** `response.completed`. A usage-limit error can arrive mid-stream as `response.failed`.
- No `previous_response_id` over HTTP: send the full context each turn.
- Explicit `role:"system"` message items are rejected; use `instructions` or developer messages.
- Stripped fields: `background, conversation, max_output_tokens, max_tool_calls, metadata, moderation, multi_agent, prompt, prompt_cache_retention, safety_identifier, temperature, top_logprobs, top_p, truncation, user` (plus `previous_response_id`, `service_tier`).
- Function/custom tools must be **grouped in namespaces or supplied through `additional_tools` input items**. Hosted tools are unsupported: image generation, file search, Code Interpreter, computer use, hosted MCP/connectors, `tool_search`, `programmatic_tool_calling`. Web search is subject to model and account policy.
- Inputs: text, images and files when the model accepts them. No audio/video input, Files upload or transcription.

---

## 3. How the Android app should do the login

### 3.1 Why loopback + Custom Tab (and not App Links / custom scheme / WebView)
- **Redirect constraint (hard):** SIWC only accepts `http://127.0.0.1:<port>/auth/callback`. An `https://` App Link or `myapp://` scheme is not a registrable redirect for the self-serve OSS client.
- **Custom Tab, not WebView:** Google and Apple sign-in inside OpenAI's page refuse embedded WebViews (`disallowed_useragent`). A Custom Tab also keeps OpenAI cookies out of any WebView the app uses, and the user sees the real URL bar.
- **Loopback works on Android** because the browser and the app share the device's loopback interface. Hither proved this on the emulator (CI phase C, 32/32), including 17 forged-callback attempts from another app's UID, all refused.

### 3.2 Sequence (UI thread ↔ `Dispatchers.IO`)
```kotlin
// one per process (Application / Hilt singleton)
val auth = ChatGptAuth(
    SiwcConfig(appName = "Atall"),
    FileCredentialStore(File(ctx.noBackupFilesDir, "chatgpt.vault"), AesGcmCipher({ keystoreKey() }, keystoreManagedIv = true)),
)

suspend fun signIn(activity: Activity, mode: SignInMode) = withContext(Dispatchers.IO) {
    LoopbackServer("Atall", returnUrl = { port ->
        ReturnToApp.intentUrl(activity.packageName, "atall-signin", "http://127.0.0.1:$port/auth/done")
    }).use { lb ->                                    // bound BEFORE the browser opens
        val attempt = auth.beginSignIn(mode, lb.redirectUri)
        lb.start(attempt)
        withContext(Dispatchers.Main) {
            CustomTabsIntent.Builder().setShowTitle(true).setUrlBarHidingEnabled(false).build()
                .launchUrl(activity, Uri.parse(auth.authorizeUrl(attempt)))   // never log this URL
        }
        when (val cb = lb.await(10 * 60_000L)) {      // runInterruptible + lb.cancel() on scope cancel
            is Callback.Success -> auth.completeSignIn(attempt, cb)
            is Callback.Failed -> throw AuthFailure(if (cb.error == "access_denied") "cancelled" else cb.error)
            else -> error("unreachable")
        }
    }
}
```
- **Manifest:** `<queries><intent><action android:name="android.support.customtabs.action.CustomTabsService"/></intent></queries>` (Android 11+ package visibility), and an activity with `VIEW/DEFAULT/BROWSABLE` + `<data android:scheme="atall-signin" android:host="signin"/>`. Use a sign-in-only scheme with an explicit `package=` in the `intent://` URL so no other app can catch it.
- **INTERNET** permission. No cleartext config is needed for 127.0.0.1: the app *serves* loopback, it doesn't fetch it. The browser is the client.
- **Coming back early:** if the user presses back in the tab, keep the attempt and listener alive and show "Finish signing in" (Reopen / Cancel). Reopen the same URL.
- **Fallbacks:** if there's no Custom Tabs provider, use `ACTION_VIEW`. If there's no browser at all, show "needs a web browser". If the `intent://` hop doesn't fire (some non-Chrome browsers), the done page has a "Back to <App>" button, and `onResume` re-reads status anyway.

### 3.3 Process-death risk during sign-in
The listener lives in the app process while the user is in another app (the browser), possibly for minutes (password manager, MFA, Google sign-in). Android can kill a backgrounded process, which loses the listener, the PKCE verifier and the attempt.
- Mitigation: hold the attempt in a **foreground service** for the duration (Android 14+: `foregroundServiceType="shortService"` is capped at about 3 minutes, so prefer `specialUse` with a justification, or just accept a rare restart).
- Never persist the verifier. On restart, start a fresh attempt. Hither didn't need a service in CI, but real-phone behaviour is unverified.

### 3.4 Chrome network-policy risks to verify on a real phone
- Chrome's **Local Network Access** rollout restricts public sites from reaching local addresses. Today it targets subresources and fetches, not a top-level 302 navigation from `auth.openai.com` to `127.0.0.1`, but verify on current Chrome stable.
- "Always use secure connections" exempts `127.0.0.1`/`localhost`. Verify too.
- Samsung Internet and Firefox as the default browser are untested (Hither listed this as open, §0.4).

---

## 4. Token storage
- **One owner, one copy:** with rotating single-use refresh tokens, two copies (say a backup restore, or a second process) means the second refresh replays a consumed token, and OpenAI revokes the family. `FileCredentialStore` writes atomically (temp + fsync + rename, 0600/0700).
- **Encryption at rest:** use an AES-256-GCM key in the **AndroidKeyStore**, generated once, non-exportable:
  ```kotlin
  KeyGenParameterSpec.Builder("chatgpt-vault", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
      .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
      .setKeySize(256).setRandomizedEncryptionRequired(true).build()
  ```
  Pass it to `AesGcmCipher({ key }, keystoreManagedIv = true)` (the Keystore picks the IV; it's stored in the blob). Don't require user authentication on the key: background refreshes must work while the screen is locked after first unlock. StrongBox is optional (slower; fine for one small blob).
- **Why not EncryptedSharedPreferences:** `androidx.security:security-crypto` was deprecated in 2025 and is unmaintained. The pattern above is what Google now points to, and it keeps the core library Android-free.
- **Backups:** put the file in `noBackupFilesDir`, and still add `<exclude domain="file" path="…"/>` (or the no_backup domain) to both `backup_rules.xml` and `data_extraction_rules.xml`. Device-to-device transfer ignores `allowBackup=false` on Android 12+.
- **If the Keystore key is lost** (it's wiped on some OEM resets, or after a "clear credentials"), `load()` throws. Treat that as signed out, delete the vault and keep the host id if possible (it's stored in the same file, so it gets re-minted; acceptable).
- **Never:** in logs, Compose state, crash reports (Sentry scrubbers), analytics, the clipboard, notification text, or URLs. Every library type's `toString` redacts tokens and codes. `AuthorizeUrl.redacted()` exists for logging the authorize URL.

---

## 5. Refresh in the app
- `ChatGptAuth` implements `TokenSource`. `ResponsesClient` calls `accessToken(force=false)` per request, and on a pre-stream 401 does exactly one `force=true` refresh and retry.
- Call `auth.check()` at app open and resume (at most every ~10 min). It refreshes if due, so an ended session shows **"Your ChatGPT sign-in ended"** before the user types.
- Long agent runs (`RunService`/WorkManager) use the same singleton. Refreshes are serialized by a `ReentrantLock`, so 8 concurrent callers make exactly one refresh (tested).
- 30-day rolling refresh lifetime: an app unused for 30+ days needs a new sign-in, which is the same registration, so the account picker and redirect take about 2 taps.

## 6. Models, rate limits, plans
- **Model list:** `ResponsesClient.listModels()` → `GET /v1/models`, keeping `visibility == "list"` in server order (`slug`, `display_name`). Refresh it on account switch. Make the model a setting with a sane default, and fall back if the saved slug disappears.
- **Who can use it:** anyone can *sign in*. For plan usage, "supported open-source tools remain available to all ChatGPT users", while commercial apps are limited to Go/Plus/Pro/Business (help article 20001542). Business also needs the admin to allow the app (Admin Console → External access).
- **Limits:**
  - The per-app weekly cap is a % of overall weekly usage (a cap, not a reserved pool).
  - The Plus 5-hour limit is shared across all apps.
  - Credits after plan limits are opt-in, and only with the app cap at 100 %.
  - Never infer a reset time from the error. Link to `https://chatgpt.com/settings/usage`.
  - `subscription_sharing_usage_unavailable`/`_user_unavailable` (503) → bounded backoff.
- **Errors** are mapped to `ApiError(code, status, param, requestId, recovery)` with `Recovery ∈ {SIGN_IN_AGAIN, USAGE_LIMIT, NOT_ELIGIBLE, RETRY_LATER, FIX_REQUEST, POLICY_OR_REGION}`. Direct-admission errors come as `{"detail": …}` with no code, so they're mapped by status. The UI owns the words (reuse Hither's `COPY` table in `hither_chatgpt_provider.py`).
- **UI guideline conflict to decide:** OpenAI asks apps to show **"Using ChatGPT plan"** near the composer, with *Manage usage*. Hither decided (2026-10-27) to show it only in Profile plus the one-time "You're using your ChatGPT plan / Got it" notice. For an open-source release, following OpenAI's guideline is the safer choice.

## 7. Tool calling for the coordinator
- `AgentTool` (name, description, JSON-Schema `parameters`, `strict`, `call(args, ctx): ToolResult`):
  - `ToolResult.Output(text)`: the result goes back to the model and the loop continues (`ask_agent` → `{"ask_id":…}`).
  - `ToolResult.Final(text)`: ends the run with that answer (`reply`).
  - `ToolResult.Suspend(reason)`: pauses with no output (`wait` while text agents answer). The coordinator persists `history + result.newItems`, then calls `runner.resume(history + newItems, mapOf(callId to output))`. This matches design.md §3.4 (persisted state machine that survives process death).
- `AgentRunner` is stateless; the caller owns the transcript. Replayed output items drop server `id`/`status` (store:false means they aren't persisted server-side). Reasoning items are dropped unless `includeEncryptedReasoning=true` (requests `include:["reasoning.encrypted_content"]`, as Codex does).
- Unknown tools, bad JSON arguments and tool exceptions go back to the model as error outputs (no crash). `maxModelCalls` (default 8) stops runaway loops.
- Tools are placed in one `additional_tools` developer input item (Hither's shape). `ToolPlacement.TOP_LEVEL` exists for an API-key route.

## 8. Reference only: the Codex CLI route (not used)
From `openai/codex` (`codex-rs/login/src/server.rs`, `auth/manager.rs`, `model-provider-info/src/lib.rs`, `http-client/src/openai_headers.rs`, fetched 2026-11-13):
- Client `app_EMoamEEZ73f0CkXaXp7hrann`; authorize `https://auth.openai.com/oauth/authorize` with scope `openid profile email offline_access api.connectors.read api.connectors.invoke`, plus `id_token_add_organizations=true`, `codex_cli_simplified_flow=true` and `originator`.
- Redirect `http://127.0.0.1:1455/auth/callback`, with fallback port **1457** (fixed allow-list; Hermes still uses `localhost:1455`).
- Token/refresh at `/oauth/token` (refresh sent as **JSON**, no `resource`); revoke at `/oauth/revoke`. Access token refreshed within 5 min of expiry; `refresh_token_reused/expired/invalidated` are permanent.
- Also an optional token-exchange for an `openai-api-key`.
- Model calls go to `https://chatgpt.com/backend-api/codex/responses` with header `chatgpt-account-id` (from the ID token's `https://api.openai.com/auth.chatgpt_account_id`).
- **Why not:** it identifies as Codex, isn't offered to third parties, the fixed ports can be squatted, it's device-code or loopback only, and SIWC docs explicitly say not to use `backend-api`. The policy risk is that OpenAI blocks non-Codex use without notice. `ResponsesClient` blocks any destination outside `apiBase`, so this route can't be enabled by accident.

## 9. ToS and risk
1. **Eligibility of the app:** the self-serve route is for open-source, locally hosted tools. If the new app is ever paid, closed-source or server-hosted, plan usage needs OpenAI's commercial programme (interest form, "select group of partners"). Decide the licence and public repo before release (Hither left this open).
2. **Preview API:** fields, models, error codes and `additional_tools` vs namespaces may change. All shaping is in `RequestShaper`, and tests pin the current behaviour.
3. **User's money:** every model call spends the user's plan. The coordinator must cap rounds and asks (design.md: ≤3 rounds, ≤4 asks) and stop on usage-limit errors with no silent retries. Consider Hither's "pause model calls 120 s after the app leaves the screen unless allowed" rule for background runs.
4. **Token theft surface:** code running as the app's UID, root, or a compromised dependency. The Keystore protects against offline extraction only. Keep dependencies minimal; R8 doesn't change this.
5. **Branding:** use "Continue with ChatGPT", the plan notice and Manage usage links per OpenAI's UI guidelines. Don't imply endorsement.
6. **Disconnects aren't pushed.** Handle it lazily through refresh/401.
7. **Process death mid-sign-in and browser variance** (§3.3–3.4) are UX risks, not security ones.

## 10. Open questions (need a real account / decision)
1. `additional_tools` input item vs **namespace** grouping: which one does production accept for function tools? (Hither verified only against its own mock.) This is a one-line switch in `RequestShaper`.
2. Is `include: ["reasoning.encrypted_content"]` accepted on the SIWC route? (Default off.)
3. Does `/v1/models` on SIWC return the `models[]` shape from the docs (handled) or `data[]` (also handled)? Which slugs show up per plan (Free/Go/Plus/Pro)?
4. `force_reconsent=true` rollout: when should it replace `prompt=consent`?
5. Real-phone checks: Chrome / Samsung Internet / Firefox redirect-back, Google/Apple/MFA inside the Custom Tab, process death while in the browser, Local Network Access prompts.
6. Should "Using ChatGPT plan" be shown near the composer (OpenAI guideline) or only in Profile (Hither decision)?
7. `ext_agent_host_id`: keep a `urn:uuid` or move to a Keystore-backed JWK-thumbprint (optional, "recommended")?
8. Licence and public repo timing, which gates use of the self-serve route at all.

## 11. Files
```
new-product/chatgpt-auth/
  NOTES.md                 this file
  README.md                how to build/test, API sketch
  build.sh, deps.txt       Gradle-free build: downloads 7 jars, kotlinc 2.1.21 + JDK 17, runs JUnit 4
  build.gradle.kts         drop-in `:chatgpt-auth` JVM module for the app's Gradle build (not run here)
  src/main/kotlin/app/atall/chatgpt/
    Config.kt              SiwcConfig, Endpoints (+ fallbacks), https/loopback-only bases
    Errors.kt              SignInRequired, TransientFailure, AuthFailure, PlanUsageNotAllowed, BlockedDestination, ApiError+Recovery
    Authorize.kt           Pkce, HostId, SignInMode, AuthAttempt, AuthorizeUrl, Callback/CallbackJudge, ReturnToApp
    LoopbackServer.kt      one-shot 127.0.0.1:0 listener
    OAuthClient.kt         discovery, code exchange, refresh, revoke, JWKS; Jwt; IdTokenVerifier (RS256)
    Credentials.kt         Account/Vault (kotlinx.serialization), CredentialStore, InMemory/File stores, AesGcmCipher
    ChatGptAuth.kt         the session manager: beginSignIn/authorizeUrl/completeSignIn/accessToken/check/signOut/switchTo
    Responses.kt           RequestShaper, SseParser, ResponsesClient (stream, models, 401 retry, destination guard), Items
    Agent.kt               AgentTool, Schema, ToolResult (Output/Final/Suspend), AgentRunner (run/resume)
  src/test/kotlin/app/atall/chatgpt/
    MockOpenAI.kt          fake auth.openai.com + /v1 on 127.0.0.1 (PKCE, RS256 ID tokens, rotation/reuse, SSE scripts)
    AuthorizeTest.kt (8)  SignInFlowTest.kt (15)  ResponsesTest.kt (10)  AgentLoopTest.kt (6)  = 39
```
