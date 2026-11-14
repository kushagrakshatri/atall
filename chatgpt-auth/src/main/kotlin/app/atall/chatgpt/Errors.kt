package app.atall.chatgpt

/**
 * Every failure carries a stable machine [code] (the UI maps codes to words) and never a token.
 * Messages are safe to log.
 */
sealed class ChatGptException(val code: String, message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** No usable sign-in: never signed in, signed out, or the sign-in ended (revoked / refresh rejected). */
class SignInRequired(code: String) : ChatGptException(code, "ChatGPT sign-in required ($code)")

/** Network trouble or a temporary OpenAI failure. Credentials are kept; retry with bounded backoff. */
class TransientFailure(code: String, status: Int? = null, val retryAfterSeconds: Long? = null, cause: Throwable? = null) :
    ChatGptException(code, "Temporary ChatGPT failure ($code${status?.let { ", HTTP $it" } ?: ""})", status, cause)

/** A definite OAuth failure that is not "sign in again" by itself (bad callback, registration incomplete, invalid ID token…). */
class AuthFailure(code: String, status: Int? = null) : ChatGptException(code, "ChatGPT sign-in failed ($code)", status)

/** The sign-in is valid but the person didn't allow use of their ChatGPT plan (no `chatgpt.tokens.use.direct`). */
class PlanUsageNotAllowed : ChatGptException("plan_usage_not_allowed", "ChatGPT plan use isn't enabled for this sign-in")

/** A request to a destination other than the configured API base; refused locally, token not sent. */
class BlockedDestination(url: String) : ChatGptException("blocked_destination", "Refusing to send the ChatGPT token to ${url.substringBefore('?')}")

/** What the app should do about an API error (SIWC "Errors and recovery"). */
enum class Recovery { SIGN_IN_AGAIN, USAGE_LIMIT, NOT_ELIGIBLE, RETRY_LATER, FIX_REQUEST, POLICY_OR_REGION, UNKNOWN }

/** An error from the Responses / models route, before or during a stream. */
class ApiError(
    code: String,
    status: Int?,
    val param: String? = null,
    val requestId: String? = null,
    val recovery: Recovery = recoveryFor(code, status),
    upstreamMessage: String? = null,
) : ChatGptException(code, "ChatGPT request failed: $code${status?.let { " (HTTP $it)" } ?: ""}${upstreamMessage?.let { ": ${it.take(300)}" } ?: ""}", status) {
    companion object {
        fun recoveryFor(code: String, status: Int?): Recovery = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> Recovery.USAGE_LIMIT
            "subscription_sharing_user_not_eligible" -> Recovery.NOT_ELIGIBLE
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> Recovery.RETRY_LATER
            "subscription_sharing_unsupported_capability" -> Recovery.FIX_REQUEST
            "subscription_sharing_route_not_supported" -> Recovery.FIX_REQUEST
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" -> Recovery.SIGN_IN_AGAIN
            else -> when (status) {
                401 -> Recovery.SIGN_IN_AGAIN
                403 -> Recovery.POLICY_OR_REGION
                429, 500, 502, 503, 504 -> Recovery.RETRY_LATER
                400, 404, 422 -> Recovery.FIX_REQUEST
                else -> Recovery.UNKNOWN
            }
        }
    }
}

/** Refresh error codes that make the refresh token unusable (SIWC "Refresh errors"). */
internal val TERMINAL_REFRESH_CODES = setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused",
)
