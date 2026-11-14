package app.atall.chatgpt

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Sign in with ChatGPT (SIWC), "ChatGPT plan usage for open-source, locally hosted apps".
 * https://developers.openai.com/siwc/token-sharing-open-source
 *
 * Every URL the library talks to comes from here, so tests point it at a mock server and production
 * uses the defaults. Only https, or plain http on a loopback address (tests), is accepted.
 */
data class SiwcConfig(
    /** The app's real name; sent once as `agent_name_hint` on first registration. Shown on OpenAI's consent page. */
    val appName: String,
    val issuer: String = ISSUER,
    /** The OAuth `resource` and the audience of the access token. */
    val resource: String = RESOURCE,
    /** Where Responses / models requests go. The bearer token is never sent anywhere else. */
    val apiBase: String = RESOURCE,
    val scopes: String = SCOPES,
    val callbackPath: String = CALLBACK_PATH,
    /** Refresh this long before the hour-long access token expires (Hither and Codex both use 5 minutes). */
    val refreshSkewSeconds: Long = 300,
    /** ID-token `exp`/`iat` clock leeway. */
    val clockLeewaySeconds: Long = 60,
    val userAgent: String = "$appName (Android)",
) {
    init {
        require(appName.isNotBlank()) { "appName is required" }
        listOf(issuer, resource, apiBase).forEach { require(isAllowedBase(it)) { "only https or loopback http: $it" } }
        require(callbackPath.startsWith("/")) { "callbackPath must start with /" }
    }

    val issuerUrl: HttpUrl get() = issuer.trimEnd('/').toHttpUrl()
    val apiBaseUrl: HttpUrl get() = apiBase.trimEnd('/').toHttpUrl()

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val IDENTITY_SCOPES = "openid profile email"
        const val PLAN_SCOPES = "offline_access resource.invoke chatgpt.tokens.use.direct"
        const val SCOPES = "$IDENTITY_SCOPES $PLAN_SCOPES"
        /** Granted only when the person allowed this app to use their ChatGPT plan. */
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        /** First-registration entry point. Never saved, never used for token exchange. */
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val CALLBACK_PATH = "/auth/callback"
        const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"

        fun isAllowedBase(value: String): Boolean {
            val url = try { value.trimEnd('/').toHttpUrl() } catch (_: IllegalArgumentException) { return false }
            if (url.isHttps) return true
            return url.host == "127.0.0.1" || url.host == "localhost" || url.host == "[::1]" || url.host == "::1"
        }
    }
}

/** OpenID endpoints; discovered from `/.well-known/openid-configuration` (same origin only) with documented fallbacks. */
data class Endpoints(
    val authorization: String,
    val token: String,
    val jwks: String,
    val revocation: String,
) {
    companion object {
        fun fallback(issuer: String): Endpoints {
            val iss = issuer.trimEnd('/')
            return Endpoints(
                authorization = "$iss/api/accounts/authorize",
                token = "$iss/api/accounts/oauth/token",
                jwks = "$iss/.well-known/jwks.json",
                revocation = "$iss/api/accounts/oauth/revoke",
            )
        }
    }
}
