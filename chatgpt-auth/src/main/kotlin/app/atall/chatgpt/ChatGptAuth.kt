package app.atall.chatgpt

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Supplies a bearer token for model calls. [force] = refresh even if not yet due (after a 401). */
fun interface TokenSource {
    fun accessToken(force: Boolean): String
}

/** Non-secret status for the UI. */
data class AuthStatus(
    /** signed_out | signed_in | ended */
    val state: String,
    val email: String,
    val clientId: String?,
    val planUsage: Boolean,
    val endedReason: String,
    val accounts: List<Pair<String, String>>, // clientId to email
    /** The account holder's name from the ID token (may be blank). Used for "Kush's thread"-style labels. */
    val name: String = "",
)

data class SignOutResult(val revoked: Boolean)

/**
 * Sign in with ChatGPT, app side, end to end. Thread-safe; keep ONE instance per process (the app is the
 * only token owner, so a process-wide lock is enough to serialize rotating refreshes).
 *
 * Flow:
 *  1. `val lb = LoopbackServer(appName)` (binds 127.0.0.1:<free port> before anything opens)
 *  2. `val attempt = auth.beginSignIn(SignInMode.DEFAULT, lb.redirectUri)`; `lb.start(attempt)`
 *  3. open `auth.authorizeUrl(attempt)` in a Custom Tab
 *  4. `when (val cb = lb.await()) { is Callback.Success -> auth.completeSignIn(attempt, cb) ... }`
 *  5. model calls take tokens from `auth` (it is a [TokenSource]).
 */
class ChatGptAuth(
    val config: SiwcConfig,
    private val store: CredentialStore,
    http: OkHttpClient = defaultHttp(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : TokenSource {
    val oauth = OAuthClient(config, http)
    private val verifier = IdTokenVerifier(config, oauth, now)
    private val lock = ReentrantLock()

    /** The install's `ext_agent_host_id`, created and saved on first use. */
    fun hostId(): String = lock.withLock {
        val v = store.load()
        v.hostId?.takeIf { HostId.isValid(it) } ?: HostId.newUuidUrn().also { store.save(v.copy(hostId = it)) }
    }

    fun status(): AuthStatus {
        val v = store.load()
        val a = v.activeAccount()
        val state = when {
            a == null -> "signed_out"
            a.state == "signed_in" && a.hasTokens -> "signed_in"
            a.state == "ended" -> "ended"
            else -> "signed_out"
        }
        return AuthStatus(state, a?.email ?: "", a?.clientId, a?.planUsage == true && state == "signed_in", a?.endedReason ?: "",
            v.accounts.values.map { it.clientId to it.email }, a?.name ?: "")
    }

    /**
     * A fresh attempt (state, nonce, PKCE verifier) for [redirectUri] (the loopback's).
     * [useIdTokenHint]: send the retained ID token as `id_token_hint` to skip the account picker on reauth.
     * OpenAI allows it; it puts a (possibly expired) ID token in the browser URL/history, so it's off by default.
     */
    fun beginSignIn(mode: SignInMode, redirectUri: String, useIdTokenHint: Boolean = false): AuthAttempt {
        require(Regex("^http://127\\.0\\.0\\.1:\\d{1,5}" + Regex.escape(config.callbackPath) + "$").matches(redirectUri)) { "redirect must be http://127.0.0.1:<port>${config.callbackPath}" }
        val host = hostId()
        val active = store.load().activeAccount()
        val clientId = if (mode == SignInMode.NEW_ACCOUNT) null else active?.clientId?.takeIf { AuthorizeUrl.isIssuedClientId(it) }
        if (mode == SignInMode.RECONSENT && clientId == null) throw AuthFailure("no_registration")
        val reauth = clientId != null
        return AuthAttempt(
            clientId = clientId, hostId = host, redirectUri = redirectUri,
            loginHint = if (reauth) active?.email?.takeIf { it.contains('@') } else null,
            idTokenHint = if (reauth && useIdTokenHint) active?.idToken else null,
            reconsent = mode == SignInMode.RECONSENT,
        )
    }

    fun authorizeUrl(attempt: AuthAttempt): String = AuthorizeUrl.build(config, oauth.endpoints(), attempt)

    /**
     * Finish from the loopback's success verdict: keep the issued client id *before* spending the one-time code,
     * exchange with PKCE, verify the ID token (sig, iss, aud, exp, nonce), record whether plan use was granted,
     * then make the account active. A code rejected with `invalid_grant` means: start a fresh attempt.
     */
    fun completeSignIn(attempt: AuthAttempt, callback: Callback.Success): AuthStatus {
        val returned = callback.clientId
        if (attempt.clientId != null && returned != null && returned != attempt.clientId) throw AuthFailure("registration_incomplete")
        val clientId = returned ?: attempt.clientId
        if (!AuthorizeUrl.isIssuedClientId(clientId)) throw AuthFailure("registration_incomplete")
        clientId!!

        lock.withLock {
            val v = store.load()
            if (v.accounts[clientId] == null) store.save(v.with(Account(clientId = clientId, issuer = config.issuer)))
        }
        val tokens = try {
            oauth.exchangeCode(clientId, callback.code, attempt.verifier, attempt.redirectUri)
        } catch (e: AuthFailure) {
            throw if (e.code == "invalid_grant") AuthFailure("code_rejected", e.status) else e
        }
        val idToken = tokens.idToken ?: throw AuthFailure("invalid_id_token")
        val claims = verifier.verify(idToken, clientId, attempt.nonce)
        val scopes = tokens.scopes ?: throw AuthFailure("invalid_token_response")
        val t = now()
        return lock.withLock {
            val v = store.load()
            val prev = v.accounts[clientId] ?: Account(clientId = clientId)
            if (prev.subject.isNotEmpty() && prev.subject != claims.subject) throw AuthFailure("account_mismatch")
            val acc = prev.copy(
                issuer = config.issuer, subject = claims.subject,
                email = claims.email ?: prev.email, name = claims.name ?: prev.name,
                idToken = idToken, accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
                expiresAt = t + tokens.expiresIn, earliestRefreshAt = tokens.earliestRefreshAt ?: 0,
                scopes = scopes.sorted(), planUsage = SiwcConfig.PLAN_SCOPE in scopes,
                state = "signed_in", endedReason = "", savedAt = t,
            )
            store.save(v.with(acc).copy(active = clientId)) // active only now, after the identity checked out
            status()
        }
    }

    /**
     * The active account's access token, refreshed under the lock when within [minTtl] of expiry (or [force]).
     * Throws [SignInRequired] (signed_out / signin_ended), [PlanUsageNotAllowed], or [TransientFailure].
     */
    fun freshAccessToken(force: Boolean = false, minTtl: Long = config.refreshSkewSeconds, requirePlanUsage: Boolean = true): String {
        val stale = currentUsable().let { acc ->
            if (requirePlanUsage && !acc.planUsage) throw PlanUsageNotAllowed()
            if (!force && acc.expiresAt - now() > minTtl) return acc.accessToken!!
            acc.accessToken!!
        }
        return lock.withLock {
            val acc = currentUsable() // another thread may have rotated it while we waited
            val t = now()
            when {
                acc.accessToken != stale && acc.expiresAt - t > 60 -> acc.accessToken!!
                !force && acc.expiresAt - t > minTtl -> acc.accessToken!!
                !force && t < acc.earliestRefreshAt && acc.expiresAt - t > 30 -> acc.accessToken!!
                else -> refreshLocked(acc).accessToken!!
            }
        }
    }

    override fun accessToken(force: Boolean): String = freshAccessToken(force = force)

    /** App open / resume: refresh if due so an ended sign-in shows before the person types. Never throws for sign-in state. */
    fun check(): AuthStatus {
        try { freshAccessToken(requirePlanUsage = false) } catch (_: SignInRequired) {} catch (_: TransientFailure) {}
        return status()
    }

    private fun currentUsable(): Account {
        val a = store.load().activeAccount()
        if (a == null || !a.hasTokens) throw SignInRequired(if (a?.state == "ended") "signin_ended" else "signed_out")
        return a
    }

    private fun refreshLocked(acc: Account): Account {
        check(lock.isHeldByCurrentThread)
        val tr = try {
            oauth.refresh(acc.clientId, acc.refreshToken!!)
        } catch (e: AuthFailure) {
            if (e.code in TERMINAL_REFRESH_CODES || e.code == "invalid_client" || e.status == 401) {
                val reason = if (e.code in TERMINAL_REFRESH_CODES || e.code == "invalid_client") e.code else "refresh_rejected"
                val v = store.load()
                store.save(v.with(acc.clearedTokens("ended", reason)))
                throw SignInRequired("signin_ended")
            }
            throw TransientFailure(e.code, e.status)
        }
        var updated = acc.copy(
            accessToken = tr.accessToken,
            refreshToken = tr.refreshToken ?: acc.refreshToken,
            expiresAt = now() + tr.expiresIn,
            earliestRefreshAt = tr.earliestRefreshAt ?: 0,
            scopes = (tr.scopes ?: acc.scopes).sorted(),
            planUsage = SiwcConfig.PLAN_SCOPE in (tr.scopes ?: acc.scopes),
            state = "signed_in", endedReason = "", savedAt = now(),
        )
        tr.idToken?.let { id ->
            runCatching { verifier.verify(id, acc.clientId, null) }.getOrNull()
                ?.takeIf { it.subject == acc.subject }?.let { updated = updated.copy(idToken = id) }
        }
        store.save(store.load().with(updated)) // the replacement pair is on disk before anyone uses it
        return updated
    }

    /**
     * Revoke the refresh token at OpenAI ([retries] tries with backoff on network/5xx/429), then clear tokens.
     * Keeps the client id, email and host id so the next sign-in reuses the registration.
     */
    fun signOut(retries: Int = 3): SignOutResult = lock.withLock {
        val v = store.load()
        val acc = v.activeAccount() ?: return SignOutResult(true)
        val refresh = acc.refreshToken
        var revoked = refresh == null
        if (refresh != null) {
            for (attempt in 0 until retries) {
                val code = oauth.revoke(acc.clientId, refresh)
                if (code == 200) { revoked = true; break }
                if (code in 400..499 && code != 429) break // definite refusal
                if (attempt < retries - 1) sleep(500L * (1 shl (attempt * 2)))
            }
        }
        store.save(store.load().with(acc.clearedTokens("signed_out")))
        SignOutResult(revoked)
    }

    /** Make another saved registration active (its own tokens; never mixed). */
    fun switchTo(clientId: String) = lock.withLock {
        val v = store.load()
        require(v.accounts.containsKey(clientId)) { "unknown registration" }
        store.save(v.copy(active = clientId))
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES) // long streams; SSE keeps the socket busy
            .retryOnConnectionFailure(false)   // never replay a token-endpoint POST (single-use codes / refresh tokens)
            .followRedirects(false)
            .build()
    }
}
