package app.atall.chatgpt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake auth.openai.com + api.openai.com/v1 on 127.0.0.1 (no real network, no real credentials).
 * Checks PKCE, redirect URI, client id and resource; issues RS256 ID tokens; rotates single-use refresh
 * tokens and returns refresh_token_reused on replay; scripts SSE Responses streams.
 */
class MockOpenAI : AutoCloseable {
    val server = MockWebServer()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val kid = "test-key-1"
    val base: String
    val issued = "oaiapp_mock_1"
    var subject = "user-sub-123"
    var email = "kush@example.com"
    var grantPlanScope = true
    var accessTtl = 3600L
    var refreshMode = "ok" // ok | revoked | unavailable
    var revokeStatus = 200
    var badNonce = false
    val registrations = AtomicInteger()
    val refreshCalls = AtomicInteger()
    val revokeRequests = CopyOnWriteArrayList<Map<String, String>>()
    val responsesRequests = CopyOnWriteArrayList<JsonObject>()
    val responsesAuth = CopyOnWriteArrayList<String>()
    val scripts = ConcurrentLinkedQueue<(JsonObject) -> MockResponse>()
    /** access tokens that are currently valid */
    val validAccess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var currentRefresh: String? = null
    private val usedRefresh = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gen = AtomicInteger()
    private data class Pending(val clientId: String, val challenge: String, val redirect: String, val nonce: String, val resource: String)
    private val codes = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@MockOpenAI) { handle(request) }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://127.0.0.1:${server.port}"
    }

    fun config(appName: String = "Atall") = SiwcConfig(appName = appName, issuer = base, apiBase = "$base/v1")

    override fun close() = server.shutdown()

    /** Plays OpenAI's authorize page: the person signs in and approves. Returns the callback URL the browser is sent to. */
    fun approve(authorizeUrl: String, deny: Boolean = false): String {
        val u = authorizeUrl.toHttpUrl()
        val q = { k: String -> u.queryParameter(k) }
        require(u.encodedPath == "/api/accounts/authorize")
        require(q("response_type") == "code" && q("code_challenge_method") == "S256" && q("resource") == "https://api.openai.com/v1")
        require(q("ext_agent_host_id")!!.startsWith("urn:uuid:"))
        val redirect = q("redirect_uri")!!
        val state = q("state")!!
        if (deny) return "$redirect?error=access_denied&state=${enc(state)}"
        val client = q("client_id")!!
        val clientId = if (client == "dynamic_agent_client") { registrations.incrementAndGet(); issued } else client
        val code = "code-" + gen.incrementAndGet()
        codes[code] = Pending(clientId, q("code_challenge")!!, redirect, q("nonce")!!, q("resource")!!)
        val scope = if (grantPlanScope) SiwcConfig.SCOPES else SiwcConfig.IDENTITY_SCOPES + " offline_access"
        val cid = if (client == "dynamic_agent_client") "&client_id=$clientId" else ""
        return "$redirect?code=$code&scope=${enc(scope)}&state=${enc(state)}$cid"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun idToken(clientId: String, nonce: String?, sub: String = subject, iss: String = base, exp: Long = now() + 3600, aud: String = clientId): String {
        val header = buildJsonObject { put("alg", "RS256"); put("kid", kid); put("typ", "JWT") }
        val claims = buildJsonObject {
            put("iss", iss); put("aud", aud); put("sub", sub); put("email", email); put("name", "Kush")
            put("iat", now()); put("exp", exp); nonce?.let { put("nonce", it) }
        }
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val input = b64.encodeToString(header.toString().toByteArray()) + "." + b64.encodeToString(claims.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(input.toByteArray()); sign() }
        return input + "." + b64.encodeToString(sig)
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun form(r: RecordedRequest): Map<String, String> = r.body.readUtf8().split("&").filter { it.isNotEmpty() }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun tokens(clientId: String, nonce: String?, includeId: Boolean = true): MockResponse {
        val n = gen.incrementAndGet()
        val at = "at-$n"; val rt = "rt-$n"
        validAccess.clear(); validAccess.add(at)
        currentRefresh = rt
        val scope = if (grantPlanScope) SiwcConfig.SCOPES else SiwcConfig.IDENTITY_SCOPES + " offline_access"
        val body = buildJsonObject {
            put("access_token", at); put("refresh_token", rt); put("token_type", "Bearer"); put("expires_in", accessTtl)
            put("scope", scope)
            if (includeId) put("id_token", idToken(clientId, if (badNonce) "wrong-nonce" else nonce))
        }
        return json(200, body.toString())
    }

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.requestUrl!!.encodedPath
        return when {
            path == "/.well-known/openid-configuration" -> json(200, buildJsonObject {
                put("issuer", base)
                put("authorization_endpoint", "$base/api/accounts/authorize")
                put("token_endpoint", "$base/api/accounts/oauth/token")
                put("jwks_uri", "$base/.well-known/jwks.json")
                put("revocation_endpoint", "https://evil.example/revoke") // other origin: must be ignored
            }.toString())
            path == "/.well-known/jwks.json" -> {
                val pub = keys.public as RSAPublicKey
                val b64 = Base64.getUrlEncoder().withoutPadding()
                fun unsigned(b: java.math.BigInteger) = b.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                json(200, buildJsonObject {
                    put("keys", buildJsonArray { add(buildJsonObject {
                        put("kty", "RSA"); put("kid", kid); put("use", "sig"); put("alg", "RS256")
                        put("n", b64.encodeToString(unsigned(pub.modulus))); put("e", b64.encodeToString(unsigned(pub.publicExponent)))
                    }) })
                }.toString())
            }
            path == "/api/accounts/oauth/token" -> token(form(r))
            path == "/api/accounts/oauth/revoke" -> { revokeRequests += form(r); MockResponse().setResponseCode(revokeStatus) }
            path == "/v1/models" -> if (!authed(r)) json(401, """{"detail":"unauthorized"}""") else json(200, """{"models":[
                {"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},
                {"slug":"internal-x","display_name":"Hidden","visibility":"hide"},
                {"slug":"gpt-6.1-mini","display_name":"GPT-6.1 mini","visibility":"list"}]}""")
            path == "/v1/responses" -> {
                responsesAuth += r.getHeader("Authorization") ?: ""
                val body = LenientJson.parseToJsonElement(r.body.readUtf8()).jsonObject
                responsesRequests += body
                if (!authed(r)) json(401, """{"error":{"message":"bad token","type":"invalid_request_error","code":"invalid_api_key"}}""")
                else (scripts.poll() ?: { _: JsonObject -> sse(textStream("ok")) })(body)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun authed(r: RecordedRequest) = r.getHeader("Authorization")?.removePrefix("Bearer ") in validAccess

    private fun token(f: Map<String, String>): MockResponse {
        if (f["resource"] != "https://api.openai.com/v1") return json(400, """{"error":"invalid_target"}""")
        return when (f["grant_type"]) {
            "authorization_code" -> {
                val p = codes.remove(f["code"]) ?: return json(400, """{"error":"invalid_grant"}""")
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(f["code_verifier"]!!.toByteArray()))
                if (challenge != p.challenge || f["redirect_uri"] != p.redirect || f["client_id"] != p.clientId) return json(400, """{"error":"invalid_grant"}""")
                tokens(p.clientId, p.nonce)
            }
            "refresh_token" -> {
                refreshCalls.incrementAndGet()
                val rt = f["refresh_token"]!!
                if (f.containsKey("scope")) return json(400, """{"error":"invalid_request"}""")
                when {
                    refreshMode == "unavailable" -> json(503, """{"error":"temporarily_unavailable"}""")
                    refreshMode == "revoked" -> json(400, """{"error":"invalid_grant"}""")
                    rt in usedRefresh -> { currentRefresh = null; validAccess.clear(); json(400, """{"error":{"code":"refresh_token_reused","message":"reused"}}""") }
                    rt != currentRefresh -> json(400, """{"error":"invalid_grant"}""")
                    else -> { usedRefresh += rt; tokens(f["client_id"]!!, null, includeId = false) }
                }
            }
            else -> json(400, """{"error":"unsupported_grant_type"}""")
        }
    }

    companion object {
        fun sse(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

        fun ev(json: String) = "event: ${LenientJson.parseToJsonElement(json).jsonObject.str("type")}\ndata: $json\n\n"

        fun message(text: String) = """{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":${kotlinx.serialization.json.JsonPrimitive(text)},"annotations":[]}]}"""

        fun textStream(text: String): String = buildString {
            append(ev("""{"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}"""))
            append(": keep-alive comment\n\n")
            text.chunked(3).forEach { append(ev("""{"type":"response.output_text.delta","item_id":"msg_1","delta":${kotlinx.serialization.json.JsonPrimitive(it)}}""")) }
            append(ev("""{"type":"response.output_item.done","item":${message(text)}}"""))
            append(ev("""{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[${message(text)}],"usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}"""))
        }

        fun functionCallStream(vararg calls: Triple<String, String, String>): String = buildString {
            append(ev("""{"type":"response.created","response":{"id":"resp_2"}}"""))
            val items = calls.map { (id, name, args) ->
                """{"type":"function_call","id":"fc_$id","call_id":"$id","name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)},"status":"completed"}"""
            }
            items.forEach { append(ev("""{"type":"response.output_item.done","item":$it}""")) }
            append(ev("""{"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[${items.joinToString(",")}]}}"""))
        }
    }
}
