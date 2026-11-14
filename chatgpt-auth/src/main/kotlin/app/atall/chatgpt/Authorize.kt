package app.atall.chatgpt

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Per-attempt secrets and PKCE (RFC 7636, S256). */
object Pkce {
    private val random = SecureRandom()

    /** base64url (no padding) of [bytes] random bytes. 48 bytes -> a 64-char verifier (RFC 7636 allows 43..128). */
    fun randomValue(bytes: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    fun challenge(verifier: String): String {
        require(verifier.length in 43..128 && verifier.all { it.isLetterOrDigit() || it in "-._~" }) { "invalid PKCE verifier" }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

/** The per-install `ext_agent_host_id`: chosen once, before the first sign-in, kept for the install's life. */
object HostId {
    private val uuidUrn = Regex("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val thumbprint = Regex("^urn:ietf:params:oauth:jwk-thumbprint:[A-Za-z0-9_:-]{1,200}$")
    private val didKey = Regex("^did:key:[A-Za-z0-9]{1,200}$")

    fun newUuidUrn(): String = "urn:uuid:" + UUID.randomUUID().toString()
    fun isValid(value: String?): Boolean =
        value != null && (uuidUrn.matches(value) || thumbprint.matches(value) || didKey.matches(value))
}

enum class SignInMode {
    /** The saved registration for the active account, or a first registration when there is none. */
    DEFAULT,
    /** Add another ChatGPT account / workspace: a fresh dynamic registration. */
    NEW_ACCOUNT,
    /** The saved registration again with `prompt=consent`, after the person chose to allow plan use. */
    RECONSENT,
}

/** One sign-in attempt. Lives only in memory; [verifier] never leaves the process. */
class AuthAttempt internal constructor(
    /** null = first registration (`dynamic_agent_client`). */
    val clientId: String?,
    val hostId: String,
    val redirectUri: String,
    val loginHint: String?,
    /** Optional; puts an ID token in the browser URL (allowed by OpenAI, but redact the URL from logs). */
    internal val idTokenHint: String?,
    val reconsent: Boolean,
    val state: String = Pkce.randomValue(32),
    val nonce: String = Pkce.randomValue(32),
    internal val verifier: String = Pkce.randomValue(48),
) {
    override fun toString() = "AuthAttempt(clientId=$clientId, redirectUri=$redirectUri, reconsent=$reconsent)"
}

object AuthorizeUrl {
    private val clientIdPattern = Regex("^[A-Za-z0-9_-]{1,200}$")

    fun isIssuedClientId(value: String?): Boolean =
        value != null && value != SiwcConfig.DYNAMIC_CLIENT && clientIdPattern.matches(value)

    /** `http://127.0.0.1:<port>/auth/callback`. Only the port may vary; never `localhost`. */
    fun loopbackRedirect(port: Int, path: String = SiwcConfig.CALLBACK_PATH): String {
        require(port in 1..65535)
        return "http://127.0.0.1:$port$path"
    }

    fun build(config: SiwcConfig, endpoints: Endpoints, a: AuthAttempt): String {
        val p = linkedMapOf(
            "client_id" to (a.clientId ?: SiwcConfig.DYNAMIC_CLIENT),
            "response_type" to "code",
            "redirect_uri" to a.redirectUri,
            "scope" to config.scopes,
            "resource" to config.resource,
            "state" to a.state,
            "nonce" to a.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to Pkce.challenge(a.verifier),
            "ext_agent_host_id" to a.hostId,
        )
        if (a.clientId == null) p["agent_name_hint"] = config.appName // first registration only
        if (a.clientId != null && !a.idTokenHint.isNullOrBlank()) p["id_token_hint"] = a.idTokenHint
        if (!a.loginHint.isNullOrBlank()) p["login_hint"] = a.loginHint
        if (a.reconsent) p["prompt"] = "consent"
        return endpoints.authorization + "?" + p.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** For logs: drops the query (it may carry an id_token_hint). */
    fun redacted(url: String): String = url.substringBefore('?') + "?…"

    internal fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}

/** The verdict on one request reaching the loopback listener. */
sealed class Callback {
    /** Not the callback (other path, method or Host): empty 404, the attempt stays open. */
    object NotOurs : Callback()
    /** Callback path but wrong/missing state: empty 400, the attempt stays open (not consumed). */
    object WrongState : Callback()
    /** OpenAI returned an error (`access_denied` when the person declined), or the callback was incomplete. */
    data class Failed(val error: String) : Callback()
    data class Success(val code: String, val clientId: String?) : Callback() {
        override fun toString() = "Success(code=<redacted>, clientId=$clientId)"
    }
}

object CallbackJudge {
    private val errorPattern = Regex("^[a-z_]{1,64}$")

    fun parseQuery(query: String?): Map<String, List<String>> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (part in query.split("&")) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            try {
                out.getOrPut(URLDecoder.decode(k, "UTF-8")) { mutableListOf() }.add(URLDecoder.decode(v, "UTF-8"))
            } catch (_: IllegalArgumentException) {
                out.getOrPut("__malformed") { mutableListOf() }.add("1")
            }
        }
        return out
    }

    /**
     * Exactly `GET <callbackPath>`, `Host: 127.0.0.1:<port>`, one `state` equal to ours (constant time).
     * A new registration must bring an issued client id; a reauthorization may omit it, but a different one is rejected.
     */
    fun judge(method: String, target: String, host: String?, port: Int, attempt: AuthAttempt, callbackPath: String = SiwcConfig.CALLBACK_PATH): Callback {
        if (method != "GET" || host != "127.0.0.1:$port") return Callback.NotOurs
        if (target.substringBefore('?') != callbackPath) return Callback.NotOurs
        val q = parseQuery(target.substringAfter('?', ""))
        val states = q["state"].orEmpty()
        if (states.size != 1 || !MessageDigest.isEqual(states[0].toByteArray(), attempt.state.toByteArray())) return Callback.WrongState
        q["error"]?.firstOrNull()?.let { return Callback.Failed(if (errorPattern.matches(it)) it else "authorize_failed") }
        val codes = q["code"].orEmpty()
        val returned = q["client_id"].orEmpty()
        if (codes.size != 1 || codes[0].isEmpty() || codes[0].length > 4096 || returned.size > 1) return Callback.Failed("registration_incomplete")
        val clientId = returned.firstOrNull()
        if (attempt.clientId == null && !AuthorizeUrl.isIssuedClientId(clientId)) return Callback.Failed("registration_incomplete")
        if (attempt.clientId != null && clientId != null && clientId != attempt.clientId) return Callback.Failed("registration_incomplete")
        return Callback.Success(codes[0], clientId)
    }
}

/** Return-to-app helpers for the loopback's 302 and fallback page. */
object ReturnToApp {
    /** `intent://` URL with an explicit package and the app's own scheme, so no other app can catch it. */
    fun intentUrl(packageName: String, scheme: String, fallbackUrl: String): String =
        "intent://signin/done#Intent;scheme=$scheme;package=$packageName;S.browser_fallback_url=${AuthorizeUrl.enc(fallbackUrl)};end"

    fun donePage(appName: String, intentUrl: String, ok: Boolean): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val title = if (ok) "You're signed in" else "Sign-in didn't finish"
        val body = if (ok) "Go back to ${esc(appName)} to keep going." else "Go back to ${esc(appName)} to try again."
        return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(appName)}</title><style>body{font:17px/1.45 system-ui,sans-serif;margin:0;padding:22vh 28px;color:#14171a;background:#f6f7f8}
h1{font-size:26px;font-weight:600;margin:0 0 10px}p{margin:0 0 28px;color:#4a5560}
a{display:block;text-align:center;padding:16px;border-radius:999px;background:#14171a;color:#fff;text-decoration:none;font-weight:600}
@media(prefers-color-scheme:dark){body{background:#101418;color:#eef1f4}p{color:#a9b4bf}a{background:#eef1f4;color:#101418}}</style>
<h1>$title</h1><p>$body</p><a href="${esc(intentUrl)}">Back to ${esc(appName)}</a></html>"""
    }
}
