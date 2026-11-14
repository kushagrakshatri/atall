package app.atall.chatgpt

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthorizeTest {
    private val config = SiwcConfig(appName = "Atall")
    private val ep = Endpoints.fallback(config.issuer)

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.randomValue(48)
        assertEquals(64, v.length)
        assertEquals(43, Pkce.challenge(v).length)
        assertTrue(runCatching { Pkce.challenge("short") }.isFailure)
    }

    @Test fun hostIdFormats() {
        val id = HostId.newUuidUrn()
        assertTrue(HostId.isValid(id))
        assertTrue(HostId.isValid("urn:ietf:params:oauth:jwk-thumbprint:sha-256:NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"))
        assertFalse(HostId.isValid("kush@example.com"))
        assertFalse(HostId.isValid(null))
    }

    @Test fun firstRegistrationUrl() {
        val a = AuthAttempt(null, HostId.newUuidUrn(), AuthorizeUrl.loopbackRedirect(51234), null, null, false)
        val u = AuthorizeUrl.build(config, ep, a).toHttpUrl()
        assertEquals("https://auth.openai.com/api/accounts/authorize", u.newBuilder().query(null).build().toString())
        assertEquals("dynamic_agent_client", u.queryParameter("client_id"))
        assertEquals("Atall", u.queryParameter("agent_name_hint"))
        assertEquals("http://127.0.0.1:51234/auth/callback", u.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", u.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", u.queryParameter("resource"))
        assertEquals("S256", u.queryParameter("code_challenge_method"))
        assertEquals(Pkce.challenge(a.verifier), u.queryParameter("code_challenge"))
        assertEquals(a.state, u.queryParameter("state")); assertEquals(a.nonce, u.queryParameter("nonce"))
        assertNull(u.queryParameter("login_hint")); assertNull(u.queryParameter("prompt")); assertNull(u.queryParameter("id_token_hint"))
        assertFalse("verifier must never be in the URL", u.toString().contains(a.verifier))
        // Encoded spaces as %20, not +
        assertTrue(AuthorizeUrl.build(config, ep, a).contains("scope=openid%20profile"))
    }

    @Test fun reauthAndReconsentUrl() {
        val a = AuthAttempt("oaiapp_abc", HostId.newUuidUrn(), AuthorizeUrl.loopbackRedirect(40000), "kush@example.com", "eyJ.idtoken.sig", true)
        val u = AuthorizeUrl.build(config, ep, a).toHttpUrl()
        assertEquals("oaiapp_abc", u.queryParameter("client_id"))
        assertNull(u.queryParameter("agent_name_hint"))
        assertEquals("kush@example.com", u.queryParameter("login_hint"))
        assertEquals("consent", u.queryParameter("prompt"))
        assertEquals("eyJ.idtoken.sig", u.queryParameter("id_token_hint"))
        assertEquals("https://auth.openai.com/api/accounts/authorize?…", AuthorizeUrl.redacted(u.toString()))
    }

    @Test fun judgeCallback() {
        val port = 41000
        val first = AuthAttempt(null, HostId.newUuidUrn(), AuthorizeUrl.loopbackRedirect(port), null, null, false)
        val host = "127.0.0.1:$port"
        val s = first.state
        fun j(target: String, h: String? = host, m: String = "GET", a: AuthAttempt = first) = CallbackJudge.judge(m, target, h, port, a)
        assertEquals(Callback.NotOurs, j("/auth/callback?state=$s&code=c&client_id=oaiapp_1", h = "localhost:$port"))
        assertEquals(Callback.NotOurs, j("/auth/callback?state=$s&code=c&client_id=oaiapp_1", m = "POST"))
        assertEquals(Callback.NotOurs, j("/callback?state=$s&code=c&client_id=oaiapp_1"))
        assertEquals(Callback.WrongState, j("/auth/callback?state=nope&code=c&client_id=oaiapp_1"))
        assertEquals(Callback.WrongState, j("/auth/callback?state=$s&state=$s&code=c&client_id=oaiapp_1"))
        assertEquals(Callback.WrongState, j("/auth/callback?code=c&client_id=oaiapp_1"))
        assertEquals(Callback.Failed("access_denied"), j("/auth/callback?state=$s&error=access_denied"))
        assertEquals(Callback.Failed("authorize_failed"), j("/auth/callback?state=$s&error=%3Cscript%3E"))
        assertEquals(Callback.Failed("registration_incomplete"), j("/auth/callback?state=$s&code=c"))
        assertEquals(Callback.Failed("registration_incomplete"), j("/auth/callback?state=$s&code=c&client_id=dynamic_agent_client"))
        assertEquals(Callback.Success("c", "oaiapp_1"), j("/auth/callback?state=$s&code=c&client_id=oaiapp_1&scope=openid"))
        val re = AuthAttempt("oaiapp_1", HostId.newUuidUrn(), AuthorizeUrl.loopbackRedirect(port), null, null, false)
        val rs = re.state
        assertEquals(Callback.Success("c2", null), j("/auth/callback?state=$rs&code=c2", a = re))
        assertEquals(Callback.Failed("registration_incomplete"), j("/auth/callback?state=$rs&code=c2&client_id=oaiapp_OTHER", a = re))
        assertFalse(Callback.Success("secret-code", null).toString().contains("secret-code"))
    }

    @Test fun loopbackRefusesForgeriesThenAcceptsAndRedirectsToApp() {
        val lb = LoopbackServer("Atall", returnUrl = { p -> ReturnToApp.intentUrl("app.atall", "atall-signin", "http://127.0.0.1:$p/auth/done") })
        val a = AuthAttempt(null, HostId.newUuidUrn(), lb.redirectUri, null, null, false)
        lb.start(a)
        val http = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
        fun get(path: String, host: String? = null): Pair<Int, String?> {
            val b = Request.Builder().url("http://127.0.0.1:${lb.port}$path")
            host?.let { b.header("Host", it) }
            return http.newCall(b.build()).execute().use { it.code to it.header("Location") }
        }
        assertEquals(404, get("/").first)
        assertEquals(404, get("/auth/callback?state=${a.state}&code=c&client_id=oaiapp_1", host = "evil.example").first)
        assertEquals(400, get("/auth/callback?state=forged&code=c&client_id=oaiapp_1").first)
        assertEquals(404, get("/auth/done").first) // nothing to show before the callback
        val (code, location) = get("/auth/callback?state=${a.state}&code=real&client_id=oaiapp_1")
        assertEquals(302, code)
        assertTrue(location!!.startsWith("intent://signin/done#Intent;scheme=atall-signin;package=app.atall;S.browser_fallback_url=http%3A%2F%2F127.0.0.1%3A"))
        assertEquals(Callback.Success("real", "oaiapp_1"), lb.await(1000))
        assertEquals(404, get("/auth/callback?state=${a.state}&code=again&client_id=oaiapp_1").first) // one shot
        val page = http.newCall(Request.Builder().url("http://127.0.0.1:${lb.port}/auth/done").build()).execute().use { it.body!!.string() }
        assertTrue(page.contains("You're signed in") && !page.contains("real"))
        lb.close()
    }

    @Test fun loopbackExpiresAndCancels() {
        LoopbackServer("Atall").use { lb ->
            lb.start(AuthAttempt(null, HostId.newUuidUrn(), lb.redirectUri, null, null, false))
            assertEquals(Callback.Failed("expired"), lb.await(200))
        }
        val lb2 = LoopbackServer("Atall")
        lb2.start(AuthAttempt(null, HostId.newUuidUrn(), lb2.redirectUri, null, null, false))
        lb2.cancel()
        assertEquals(Callback.Failed("cancelled"), lb2.await(1000))
    }

    @Test fun configRejectsInsecureRemoteBases() {
        assertTrue(runCatching { SiwcConfig(appName = "x", issuer = "http://auth.openai.com") }.isFailure)
        assertTrue(runCatching { SiwcConfig(appName = "x", apiBase = "http://10.0.0.2/v1") }.isFailure)
        SiwcConfig(appName = "x", issuer = "http://127.0.0.1:9700")
    }
}
