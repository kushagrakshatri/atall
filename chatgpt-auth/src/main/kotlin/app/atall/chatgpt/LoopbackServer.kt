package app.atall.chatgpt

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot loopback listener for `http://127.0.0.1:<port>/auth/callback` (pure JVM; works on Android).
 *
 *  - Binds 127.0.0.1 on an OS-chosen free port in the constructor, i.e. *before* the browser opens.
 *  - Answers only `GET <callbackPath>` with this attempt's state ([CallbackJudge]); anything else gets an
 *    empty 404, a wrong state an empty 400, and neither consumes the attempt.
 *  - On the callback it 302s to [returnUrl] (an explicit-package `intent://` URL on Android), then keeps
 *    serving a small "back to the app" page at [donePath] for [lingerMs], then closes.
 *  - Nothing secret is served, logged or echoed. The code is useless without the PKCE verifier.
 */
class LoopbackServer(
    private val appName: String,
    private val callbackPath: String = SiwcConfig.CALLBACK_PATH,
    private val donePath: String = "/auth/done",
    /** Where the browser is sent after the callback; null = straight to the done page. */
    private val returnUrl: ((port: Int) -> String)? = null,
    private val lingerMs: Long = 60_000,
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int = server.localPort
    val redirectUri: String = AuthorizeUrl.loopbackRedirect(port, callbackPath)
    private val done = CountDownLatch(1)
    @Volatile private var result: Callback? = null
    @Volatile private var attempt: AuthAttempt? = null
    @Volatile private var closed = false

    fun start(a: AuthAttempt) {
        require(a.redirectUri == redirectUri) { "attempt was built for another redirect URI" }
        attempt = a
        Thread({ serve() }, "chatgpt-signin-loopback").apply { isDaemon = true }.start()
    }

    /** Blocks until the callback arrives, [cancel] is called or [timeoutMs] passes (then `Failed("expired")`). */
    fun await(timeoutMs: Long = 10 * 60_000L): Callback {
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) finish(Callback.Failed("expired"))
        return result ?: Callback.Failed("cancelled")
    }

    fun cancel() { finish(Callback.Failed("cancelled")); close() }

    private fun finish(r: Callback) {
        synchronized(this) { if (result == null) result = r }
        done.countDown()
    }

    override fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
    }

    private fun serve() {
        var lingerUntil = Long.MAX_VALUE
        try {
            server.soTimeout = 500
            while (!closed && System.currentTimeMillis() < lingerUntil) {
                val s = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                s.use { handle(it) }
                if (result != null && lingerUntil == Long.MAX_VALUE) lingerUntil = System.currentTimeMillis() + lingerMs
            }
        } catch (_: SocketException) {
        } finally { close() }
    }

    private fun doneUrl() = "http://127.0.0.1:$port$donePath"

    private fun handle(s: Socket) {
        s.soTimeout = 5000
        val head = readHead(s) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return respond(s, 400, emptyMap(), "")
        val hosts = lines.drop(1).filter { it.substringBefore(":").trim().equals("host", true) }.map { it.substringAfter(":").trim() }
        val host = if (hosts.size == 1) hosts[0] else null
        val a = attempt ?: return respond(s, 404, emptyMap(), "")
        val back = returnUrl?.invoke(port) ?: doneUrl()
        val r = result
        if (r != null) {
            if (parts[0] == "GET" && host == "127.0.0.1:$port" && parts[1].substringBefore('?') == donePath) {
                return respond(s, 200, mapOf("Content-Type" to "text/html; charset=utf-8"),
                    ReturnToApp.donePage(appName, back, r is Callback.Success))
            }
            return respond(s, 404, emptyMap(), "")
        }
        when (val verdict = CallbackJudge.judge(parts[0], parts[1], host, port, a, callbackPath)) {
            Callback.NotOurs -> respond(s, 404, emptyMap(), "")
            Callback.WrongState -> respond(s, 400, emptyMap(), "")
            else -> {
                finish(verdict)
                respond(s, 302, mapOf("Location" to back), "")
            }
        }
    }

    private fun readHead(s: Socket): String? = try {
        val input = s.getInputStream()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < 8192) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.toString("ISO-8859-1").contains("\r\n\r\n")) break
        }
        out.toString("ISO-8859-1").substringBefore("\r\n\r\n").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun respond(s: Socket, status: Int, headers: Map<String, String>, body: String) {
        try {
            val reason = mapOf(200 to "OK", 302 to "Found", 400 to "Bad Request", 404 to "Not Found")[status] ?: "Error"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val all = linkedMapOf(
                "Cache-Control" to "no-store",
                "Referrer-Policy" to "no-referrer",
                "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
                "X-Content-Type-Options" to "nosniff",
                "Content-Length" to bytes.size.toString(),
                "Connection" to "close",
            )
            all.putAll(headers)
            val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
            all.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("\r\n")
            s.getOutputStream().apply { write(sb.toString().toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        } catch (_: Exception) {}
    }
}
