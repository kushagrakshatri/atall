package app.atall.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64

internal val LenientJson = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false; encodeDefaults = true }

/** Token-endpoint response, validated. `toString` never prints tokens. */
class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresIn: Long,
    /** null when the server omitted `scope` (a refresh may omit an unchanged grant). */
    val scopes: List<String>?,
    val earliestRefreshAt: Long?,
) {
    override fun toString() = "TokenResponse(expiresIn=$expiresIn, scopes=$scopes, hasRefresh=${refreshToken != null}, hasId=${idToken != null})"
}

/**
 * The OAuth side: discovery, authorization-code exchange (PKCE, public client, no secret),
 * refresh (rotating, single-use refresh tokens), revocation, JWKS. Blocking; call off the main thread.
 */
class OAuthClient(private val config: SiwcConfig, private val http: OkHttpClient) {
    @Volatile private var endpoints: Endpoints? = null
    @Volatile private var jwksCache: List<JsonObject>? = null

    /** OpenID configuration, issuer-checked; endpoints on another origin are ignored. Falls back to OpenAI's documented paths. */
    fun endpoints(): Endpoints {
        endpoints?.let { return it }
        val iss = config.issuer.trimEnd('/')
        val fallback = Endpoints.fallback(iss)
        val body = try { getJson("$iss/.well-known/openid-configuration") } catch (_: TransientFailure) { return fallback }
        val obj = body as? JsonObject
        if (obj == null || obj.str("issuer")?.trimEnd('/') != iss) return fallback
        val origin = config.issuerUrl
        fun pick(key: String, fb: String): String {
            val v = obj.str(key)?.toHttpUrlOrNull() ?: return fb
            return if (v.scheme == origin.scheme && v.host == origin.host && v.port == origin.port) v.toString() else fb
        }
        return Endpoints(
            authorization = pick("authorization_endpoint", fallback.authorization),
            token = pick("token_endpoint", fallback.token),
            jwks = pick("jwks_uri", fallback.jwks),
            revocation = pick("revocation_endpoint", fallback.revocation),
        ).also { endpoints = it }
    }

    fun exchangeCode(clientId: String, code: String, verifier: String, redirectUri: String): TokenResponse =
        tokenRequest(mapOf(
            "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
            "code_verifier" to verifier, "redirect_uri" to redirectUri, "resource" to config.resource,
        ), requireRefresh = true)

    /** Omits `scope` so the grant is kept. The caller must persist the replacement refresh token before using it. */
    fun refresh(clientId: String, refreshToken: String): TokenResponse =
        tokenRequest(mapOf(
            "grant_type" to "refresh_token", "client_id" to clientId,
            "refresh_token" to refreshToken, "resource" to config.resource,
        ), requireRefresh = false)

    /** Returns the HTTP status (200 = revoked, including an already-invalid token); 0 on network failure. */
    fun revoke(clientId: String, refreshToken: String): Int {
        val form = FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
        val req = Request.Builder().url(endpoints().revocation).post(form).header("Accept", "application/json").header("User-Agent", config.userAgent).build()
        return try { http.newCall(req).execute().use { it.code } } catch (_: IOException) { 0 }
    }

    fun jwks(refresh: Boolean = false): List<JsonObject> {
        if (!refresh) jwksCache?.let { return it }
        val keys = (getJson(endpoints().jwks) as? JsonObject)?.get("keys") as? JsonArray
            ?: throw AuthFailure("jwks_unavailable")
        return keys.mapNotNull { it as? JsonObject }.also { jwksCache = it }
    }

    private fun tokenRequest(form: Map<String, String>, requireRefresh: Boolean): TokenResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(endpoints().token).post(body)
            .header("Accept", "application/json").header("User-Agent", config.userAgent).build()
        val (status, json) = try {
            http.newCall(req).execute().use { r -> r.code to parseOrNull(r.body?.string()) }
        } catch (e: IOException) { throw TransientFailure("offline", cause = e) }
        if (status == 200 && json is JsonObject) return parseToken(json, requireRefresh)
        val code = errorCode(json)
        when {
            status == 429 -> throw TransientFailure("busy", status)
            status >= 500 -> throw TransientFailure("unavailable", status)
            else -> throw AuthFailure(code.ifEmpty { "http_$status" }, status)
        }
    }

    private fun parseToken(o: JsonObject, requireRefresh: Boolean): TokenResponse {
        val access = o.str("access_token")
        val type = o.str("token_type")
        val expiresIn = (o["expires_in"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        if (access.isNullOrEmpty() || !type.equals("bearer", true) || expiresIn == null || expiresIn <= 0) throw AuthFailure("invalid_token_response")
        val scopes = o.str("scope")?.split(' ')?.filter { it.isNotEmpty() }
        val refresh = o.str("refresh_token")?.takeIf { it.isNotEmpty() }
        if (requireRefresh && scopes?.contains("offline_access") != false && refresh == null) throw AuthFailure("invalid_token_response")
        val earliest = (o["earliest_refresh_at"] as? JsonPrimitive)?.let { p ->
            p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
            ?: p.contentOrNull?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
        }
        return TokenResponse(access, refresh, o.str("id_token")?.takeIf { it.isNotEmpty() }, expiresIn, scopes, earliest)
    }

    private fun getJson(url: String): JsonElement? {
        val req = Request.Builder().url(url).get().header("Accept", "application/json").header("User-Agent", config.userAgent).build()
        return try {
            http.newCall(req).execute().use { r -> if (r.code != 200) throw TransientFailure("http_${r.code}", r.code) else parseOrNull(r.body?.string()) }
        } catch (e: IOException) { throw TransientFailure("offline", cause = e) }
    }

    companion object {
        internal fun parseOrNull(s: String?): JsonElement? = if (s.isNullOrBlank()) null else runCatching { LenientJson.parseToJsonElement(s) }.getOrNull()

        internal fun errorCode(body: JsonElement?): String {
            val o = body as? JsonObject ?: return ""
            return when (val err = o["error"]) {
                is JsonObject -> err.str("code") ?: err.str("type") ?: ""
                is JsonPrimitive -> err.contentOrNull ?: ""
                else -> o.str("code") ?: ""
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Decodes a JWT payload *without* verifying it (display only: email, plan, exp). */
object Jwt {
    fun b64d(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })

    fun parts(token: String): Triple<JsonObject, JsonObject, ByteArray>? {
        val p = token.split('.')
        if (p.size != 3) return null
        return try {
            Triple(
                LenientJson.parseToJsonElement(String(b64d(p[0]), Charsets.UTF_8)).jsonObject,
                LenientJson.parseToJsonElement(String(b64d(p[1]), Charsets.UTF_8)).jsonObject,
                b64d(p[2]),
            )
        } catch (_: Exception) { null }
    }

    fun unverifiedClaims(token: String): JsonObject? = parts(token)?.second
}

/** Verified ID-token identity. */
data class IdClaims(val subject: String, val email: String?, val name: String?, val raw: JsonObject)

/**
 * RS256 ID-token check against the issuer's JWKS (java.security only, so it runs on Android too):
 * signature, `iss`, `aud` contains the issued client id (and `azp` when present / multi-audience),
 * `exp`, `iat`, and the attempt's `nonce` (constant time).
 */
class IdTokenVerifier(private val config: SiwcConfig, private val oauth: OAuthClient, private val now: () -> Long) {
    fun verify(idToken: String, clientId: String, nonce: String?): IdClaims {
        val (header, claims, sig) = Jwt.parts(idToken) ?: throw AuthFailure("invalid_id_token")
        if (header.str("alg") != "RS256") throw AuthFailure("invalid_id_token")
        val kid = header.str("kid")
        val signingInput = idToken.substringBeforeLast('.').toByteArray(Charsets.US_ASCII)
        fun candidates(keys: List<JsonObject>) = keys.filter { k ->
            k.str("kty") == "RSA" && (k.str("use") ?: "sig") == "sig" && (kid == null || k.str("kid") == kid)
        }
        var keys = candidates(oauth.jwks())
        if (keys.isEmpty()) keys = candidates(oauth.jwks(refresh = true)) // key rotation
        if (keys.none { verifyRs256(it, signingInput, sig) }) throw AuthFailure("invalid_id_token")

        val t = now()
        val iss = claims.str("iss")?.trimEnd('/')
        val auds = when (val a = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(a.contentOrNull)
            is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        val azp = claims.str("azp")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull
        val iat = (claims["iat"] as? JsonPrimitive)?.longOrNull
        val sub = claims.str("sub")
        if (iss != config.issuer.trimEnd('/') || clientId !in auds) throw AuthFailure("invalid_id_token")
        if ((azp != null && azp != clientId) || (auds.size > 1 && azp != clientId)) throw AuthFailure("invalid_id_token")
        if (exp == null || exp + config.clockLeewaySeconds < t) throw AuthFailure("id_token_expired")
        if (iat != null && iat - config.clockLeewaySeconds > t) throw AuthFailure("invalid_id_token")
        if (sub.isNullOrEmpty()) throw AuthFailure("invalid_id_token")
        if (nonce != null && !MessageDigest.isEqual((claims.str("nonce") ?: "").toByteArray(), nonce.toByteArray())) throw AuthFailure("invalid_id_token")
        return IdClaims(sub, claims.str("email"), claims.str("name"), claims)
    }

    private fun verifyRs256(jwk: JsonObject, input: ByteArray, sig: ByteArray): Boolean = try {
        val n = BigInteger(1, Jwt.b64d(jwk.str("n") ?: return false))
        val e = BigInteger(1, Jwt.b64d(jwk.str("e") ?: return false))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey
        if (key.modulus.bitLength() < 2048) false
        else Signature.getInstance("SHA256withRSA").run { initVerify(key); update(input); verify(sig) }
    } catch (_: Exception) { false }
}
