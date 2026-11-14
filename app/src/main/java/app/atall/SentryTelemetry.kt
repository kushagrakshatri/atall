package app.atall

import android.content.Context
import app.atall.core.DebugLog
import app.atall.core.Redact
import app.atall.core.Telemetry
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Sentry for Atall: crashes, ANRs, each run's steps as breadcrumbs, and events for stuck runs, model errors and
 * finished runs, tagged with versionName and runId. A no-op until a DSN exists: the build-time one
 * ([BuildConfig.SENTRY_DSN], never committed) or the hidden Settings → "Debug: Sentry DSN" field.
 * Every string is redacted (phone numbers, tokens, secrets) in [SentryOptions.beforeSend] / beforeBreadcrumb;
 * message text rides along only while "Send message text with debug logs" is on.
 */
class SentryTelemetry(private val context: Context, private val sendText: () -> Boolean) : Telemetry {
    @Volatile var dsnInUse: String? = null; private set
    val enabled: Boolean get() = dsnInUse != null

    /** Starts (or restarts with a new DSN) Sentry. Blank or malformed: stays off. Returns whether it is on. */
    @Synchronized
    fun start(dsn: String?): Boolean {
        val d = dsn?.trim().orEmpty()
        if (!looksLikeDsn(d)) return enabled
        if (d == dsnInUse) return true
        return runCatching {
            SentryAndroid.init(context) { o ->
                o.dsn = d
                o.release = "app.atall@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                o.environment = if (BuildConfig.DEBUG) "debug" else "prerelease"
                o.isSendDefaultPii = false
                o.isAnrEnabled = true
                o.isAttachThreads = false
                o.maxBreadcrumbs = 200
                o.setTag("versionName", BuildConfig.VERSION_NAME)
                o.beforeSend = SentryOptions.BeforeSendCallback { e, _ -> scrub(e) }
                o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { b: Breadcrumb, _: Hint ->
                    b.message = b.message?.let { Redact.text(it) }; b
                }
            }
            dsnInUse = d
            true
        }.getOrDefault(false)
    }

    override fun breadcrumb(e: DebugLog.Entry, includeText: Boolean) {
        if (!enabled) return
        val b = Breadcrumb().apply {
            category = "${e.tag}.${e.event}"
            message = DebugLog.render(e, includeText && sendText())
            level = if (e.event.endsWith("error") || e.event == "run.stuck") SentryLevel.ERROR else SentryLevel.INFO
            e.runId?.let { setData("runId", it) }
        }
        Sentry.addBreadcrumb(b)
    }

    override fun capture(message: String, level: Telemetry.Level, runId: String?, extras: Map<String, String>) {
        if (!enabled) return
        Sentry.captureMessage(Redact.text(message), when (level) {
            Telemetry.Level.INFO -> SentryLevel.INFO; Telemetry.Level.WARNING -> SentryLevel.WARNING; Telemetry.Level.ERROR -> SentryLevel.ERROR
        }) { scope ->
            runId?.let { scope.setTag("runId", it) }
            scope.setTag("versionName", BuildConfig.VERSION_NAME)
            extras.forEach { (k, v) -> scope.setExtra(k, Redact.text(v)) }
        }
    }

    private fun scrub(e: SentryEvent): SentryEvent {
        e.message?.let { m -> m.formatted = m.formatted?.let(Redact::text); m.message = m.message?.let(Redact::text) }
        e.exceptions?.forEach { x -> x.value = x.value?.let(Redact::text) }
        e.breadcrumbs?.forEach { b -> b.message = b.message?.let(Redact::text) }
        e.user = null
        return e
    }

    companion object {
        /** https://<key>@<host>/<project>; anything else is ignored (a typo can't break start-up). */
        fun looksLikeDsn(s: String): Boolean = Regex("^https://[A-Za-z0-9]+@[A-Za-z0-9.-]+(:\\d+)?/\\d+$").matches(s.trim())
    }
}
