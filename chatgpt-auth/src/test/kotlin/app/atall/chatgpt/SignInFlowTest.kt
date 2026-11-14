package app.atall.chatgpt

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SignInFlowTest {
    private val mock = MockOpenAI()
    private val store = InMemoryCredentialStore()
    private var clock = System.currentTimeMillis() / 1000
    private val auth = ChatGptAuth(mock.config(), store, now = { clock }, sleep = {})
    private val browser = OkHttpClient.Builder().followRedirects(false).build()

    @After fun tearDown() = mock.close()

    /** The whole browser round trip: loopback, authorize page, callback, exchange. */
    private fun signIn(mode: SignInMode = SignInMode.DEFAULT, deny: Boolean = false): AuthStatus {
        LoopbackServer("Atall").use { lb ->
            val attempt = auth.beginSignIn(mode, lb.redirectUri)
            lb.start(attempt)
            val callbackUrl = mock.approve(auth.authorizeUrl(attempt), deny)
            browser.newCall(Request.Builder().url(callbackUrl).build()).execute().close()
            return when (val cb = lb.await(5000)) {
                is Callback.Success -> auth.completeSignIn(attempt, cb)
                is Callback.Failed -> throw AuthFailure(cb.error)
                else -> error("unexpected $cb")
            }
        }
    }

    @Test fun firstSignInRegistersVerifiesAndStores() {
        assertEquals("signed_out", auth.status().state)
        val st = signIn()
        assertEquals("signed_in", st.state)
        assertEquals("kush@example.com", st.email)
        assertEquals("oaiapp_mock_1", st.clientId)
        assertTrue(st.planUsage)
        val v = store.load()
        assertTrue(HostId.isValid(v.hostId))
        val acc = v.activeAccount()!!
        assertEquals("user-sub-123", acc.subject)
        assertTrue(acc.scopes.contains("chatgpt.tokens.use.direct"))
        assertEquals(clock + 3600, acc.expiresAt)
        assertFalse(acc.toString().contains(acc.accessToken!!))
        assertEquals(acc.accessToken, auth.accessToken(false))
        assertEquals(1, mock.registrations.get())
    }

    @Test fun returningSignInReusesRegistrationAndHostId() {
        signIn()
        val host = store.load().hostId
        auth.signOut()
        assertEquals("signed_out", auth.status().state)
        assertEquals("oaiapp_mock_1", store.load().active) // client id kept
        val st = signIn()
        assertEquals("signed_in", st.state)
        assertEquals(1, mock.registrations.get()) // no second registration
        assertEquals(host, store.load().hostId)
    }

    @Test fun newAccountModeRegistersAgain() {
        signIn()
        signIn(SignInMode.NEW_ACCOUNT)
        assertEquals(2, mock.registrations.get())
    }

    @Test fun declinedConsentIsCancelled() {
        try { signIn(deny = true); fail() } catch (e: AuthFailure) { assertEquals("access_denied", e.code) }
        assertEquals("signed_out", auth.status().state)
    }

    @Test fun planScopeNotGrantedKeepsSignInButBlocksInference() {
        mock.grantPlanScope = false
        val st = signIn()
        assertEquals("signed_in", st.state)
        assertFalse(st.planUsage)
        try { auth.accessToken(false); fail() } catch (_: PlanUsageNotAllowed) {}
        // Re-consent goes to the same registration with prompt=consent
        mock.grantPlanScope = true
        LoopbackServer("Atall").use { lb ->
            val a = auth.beginSignIn(SignInMode.RECONSENT, lb.redirectUri)
            assertTrue(auth.authorizeUrl(a).contains("prompt=consent"))
            assertEquals("oaiapp_mock_1", a.clientId)
        }
    }

    @Test fun nonceMismatchRejected() {
        mock.badNonce = true
        try { signIn(); fail() } catch (e: AuthFailure) { assertEquals("invalid_id_token", e.code) }
        assertNull(store.load().active) // never activated
        assertEquals("oaiapp_mock_1", store.load().accounts.keys.single()) // registration kept before the code was spent
    }

    @Test fun idTokenChecks() {
        val oauth = OAuthClient(mock.config(), OkHttpClient())
        val v = IdTokenVerifier(mock.config(), oauth) { clock }
        val good = mock.idToken("oaiapp_mock_1", "n1")
        assertEquals("user-sub-123", v.verify(good, "oaiapp_mock_1", "n1").subject)
        fun bad(t: String, client: String = "oaiapp_mock_1", nonce: String? = "n1") =
            try { v.verify(t, client, nonce); fail("accepted bad token") } catch (_: AuthFailure) {}
        bad(good, client = "oaiapp_other")
        bad(good, nonce = "n2")
        bad(mock.idToken("oaiapp_mock_1", "n1", iss = "https://evil.example"))
        bad(mock.idToken("oaiapp_mock_1", "n1", exp = clock - 3600))
        bad(good.substring(0, good.length - 4) + "AAAA") // signature
        val parts = good.split('.')
        bad(parts[0] + "." + parts[1].reversed() + "." + parts[2]) // payload tampered
    }

    @Test fun discoveryIgnoresOtherOriginEndpoints() {
        val ep = OAuthClient(mock.config(), OkHttpClient()).endpoints()
        assertEquals("${mock.base}/api/accounts/oauth/revoke", ep.revocation)
        assertEquals("${mock.base}/api/accounts/oauth/token", ep.token)
    }

    @Test fun refreshesInsideSkewAndRotates() {
        signIn()
        val first = store.load().activeAccount()!!
        clock += 3600 - 200 // within 300 s of expiry
        val t2 = auth.accessToken(false)
        assertTrue(t2 != first.accessToken)
        val acc = store.load().activeAccount()!!
        assertEquals(t2, acc.accessToken)
        assertTrue(acc.refreshToken != first.refreshToken) // rotated, persisted
        assertEquals(acc.scopes, first.scopes) // refresh may omit scope; grant kept
        assertEquals(1, mock.refreshCalls.get())
        assertEquals(t2, auth.accessToken(false)) // no second refresh
        assertEquals(1, mock.refreshCalls.get())
    }

    @Test fun concurrentCallersRefreshOnce() {
        signIn()
        clock += 3500
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (1..8).map { pool.submit<String> { go.await(); auth.accessToken(false) } }
        go.countDown()
        val tokens = results.map { it.get(10, TimeUnit.SECONDS) }.toSet()
        pool.shutdown()
        assertEquals(1, tokens.size)
        assertEquals(1, mock.refreshCalls.get())
    }

    @Test fun reusedRefreshTokenEndsSignIn() {
        signIn()
        val stale = store.load().activeAccount()!!
        clock += 3500
        auth.accessToken(false) // rotates rt
        // Simulate a stale copy (e.g. restored backup) replaying the consumed refresh token.
        store.save(store.load().with(stale))
        try { auth.accessToken(force = true); fail() } catch (e: SignInRequired) { assertEquals("signin_ended", e.code) }
        val st = auth.status()
        assertEquals("ended", st.state)
        assertEquals("refresh_token_reused", st.endedReason)
        assertNull(store.load().activeAccount()!!.refreshToken)
    }

    @Test fun revokedRefreshEndsSignInButOutageKeepsIt() {
        signIn()
        clock += 3500
        mock.refreshMode = "unavailable"
        try { auth.accessToken(false); fail() } catch (_: TransientFailure) {}
        assertEquals("signed_in", auth.status().state)
        assertEquals("signed_in", auth.check().state)
        mock.refreshMode = "revoked"
        assertEquals("ended", auth.check().state)
        assertEquals("invalid_grant", auth.status().endedReason)
    }

    @Test fun signOutRevokesAtIssuerOrigin() {
        signIn()
        val rt = store.load().activeAccount()!!.refreshToken!!
        val r = auth.signOut()
        assertTrue(r.revoked)
        val req = mock.revokeRequests.single()
        assertEquals(rt, req["token"]); assertEquals("refresh_token", req["token_type_hint"]); assertEquals("oaiapp_mock_1", req["client_id"])
        val acc = store.load().activeAccount()!!
        assertNull(acc.accessToken); assertNull(acc.refreshToken)
        assertEquals("kush@example.com", acc.email)
        try { auth.accessToken(false); fail() } catch (e: SignInRequired) { assertEquals("signed_out", e.code) }
    }

    @Test fun signOutUnconfirmedRetriesThenClears() {
        signIn()
        mock.revokeStatus = 503
        assertFalse(auth.signOut(retries = 3).revoked)
        assertEquals(3, mock.revokeRequests.size)
        assertEquals("signed_out", auth.status().state)
    }

    @Test fun encryptedFileStoreRoundTrip() {
        val dir = Files.createTempDirectory("vault").toFile()
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val fileStore = FileCredentialStore(java.io.File(dir, "chatgpt.bin"), AesGcmCipher({ key }))
        val a2 = ChatGptAuth(mock.config(), fileStore, now = { clock })
        LoopbackServer("Atall").use { lb ->
            val attempt = a2.beginSignIn(SignInMode.DEFAULT, lb.redirectUri)
            lb.start(attempt)
            browser.newCall(Request.Builder().url(mock.approve(a2.authorizeUrl(attempt))).build()).execute().close()
            a2.completeSignIn(attempt, lb.await(5000) as Callback.Success)
        }
        val file = java.io.File(dir, "chatgpt.bin")
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        val at = fileStore.load().activeAccount()!!.accessToken!!
        assertFalse("token must not be on disk in clear", raw.contains(at) || raw.contains("kush@example.com"))
        assertEquals("rw-------", java.nio.file.Files.getPosixFilePermissions(file.toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
        // A fresh instance (app restart) resumes signed in.
        assertEquals("signed_in", ChatGptAuth(mock.config(), FileCredentialStore(file, AesGcmCipher({ key })), now = { clock }).status().state)
        // Wrong key fails closed.
        val other = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(runCatching { FileCredentialStore(file, AesGcmCipher({ other })).load() }.isFailure)
    }
}
