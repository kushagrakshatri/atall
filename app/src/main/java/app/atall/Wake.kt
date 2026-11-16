package app.atall

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.atall.agents.text.SendOptions
import app.atall.agents.text.SendResult
import app.atall.api.PeerDirectory
import app.atall.core.DebugLog
import app.atall.core.WakeSetup
import app.atall.coordinator.adapters.ApprovalRequest
import app.atall.coordinator.adapters.ApprovalSigner
import app.atall.coordinator.adapters.ConnectorTask
import app.atall.coordinator.adapters.WakeConfig
import app.atall.coordinator.adapters.WakeOutcome
import app.atall.coordinator.adapters.WakeSinks
import app.atall.relay.RelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.ref.WeakReference
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/** Per-agent wake settings (Settings → Agents → Wake-up). Grok's webhook key is encrypted with a Keystore AES key. */
class WakeSettings(context: Context) {
    private val sp = context.getSharedPreferences("atall_wake", Context.MODE_PRIVATE)
    @Volatile private var cached: WakeSetup.Saved = WakeSetup.Saved.decode(sp.getString("saved", null))

    val saved: WakeSetup.Saved get() = cached
    fun config(relayId: String): WakeConfig = cached.config(relayId)
    fun update(f: (WakeSetup.Saved) -> WakeSetup.Saved) { cached = f(cached); sp.edit().putString("saved", cached.encode()).apply() }

    fun webhookKey(relayId: String): String? = sp.getString("key_${relayId.lowercase()}", null)?.let { SecretBox.open(it) }
    fun setWebhookKey(relayId: String, key: String) {
        val k = "key_${relayId.lowercase()}"
        if (key.isBlank()) sp.edit().remove(k).apply() else SecretBox.seal(key.trim())?.let { sp.edit().putString(k, it).apply() }
    }
    fun hasWebhookKey(relayId: String) = sp.contains("key_${relayId.lowercase()}")

    /** The approval key is on the relay (set after the first approval). */
    var approvalKeyRegistered: Boolean
        get() = sp.getBoolean("approval_key_registered", false)
        set(v) { sp.edit().putBoolean("approval_key_registered", v).apply() }

    /** Debug: pretend the relay is down (to test the text fallback without killing the real relay). */
    var simulateOutage: Boolean
        get() = sp.getBoolean("simulate_outage", false)
        set(v) { sp.edit().putBoolean("simulate_outage", v).apply() }
}

/** AES-GCM with a non-exportable Android Keystore key, for small secrets kept in prefs. */
object SecretBox {
    private const val ALIAS = "atall_secretbox_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun seal(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()
    fun open(sealed: String): String? = runCatching {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
}

/**
 * The app's wake paths: an "Atall #ref" text through text-agents, Grok's routine webhook, the relay's MCP-event nudge,
 * and a one-tap notification. Never sends task content anywhere but the connector.
 */
class AndroidWakeSinks(
    private val context: Context,
    private val settings: WakeSettings,
    private val client: RelayClient,
    private val peers: PeerDirectory,
    private val subscribed: (relayId: String) -> Boolean,
    private val names: (peerId: String) -> String,
    private val log: DebugLog,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
) : WakeSinks {
    val notifier = WakeNotifier(context)

    override suspend fun text(task: ConnectorTask, text: String): WakeOutcome {
        val agent = peers.agent(task.peerId) ?: return WakeOutcome.UNAVAILABLE
        // Not a request: no reply is expected or attributed (the answer comes over the connector).
        return when (val r = agent.send(text, "wake:${task.cid}:${task.wakes}", SendOptions(tag = false, expectReply = false, allowDraft = false))) {
            is SendResult.Sent, is SendResult.Duplicate -> WakeOutcome.SENT
            is SendResult.NeedsUser -> { notifier.smsDraft(task, names(task.peerId), r.draft.number, r.draft.text); WakeOutcome.TAP }
            is SendResult.Drafted -> WakeOutcome.TAP
            else -> WakeOutcome.FAILED
        }
    }

    override suspend fun webhook(task: ConnectorTask): WakeOutcome {
        val url = settings.saved.webhookUrls[task.relayAgentId.lowercase()]?.takeIf { WakeSetup.webhookUrlOk(it) } ?: return WakeOutcome.UNAVAILABLE
        val key = settings.webhookKey(task.relayAgentId) ?: return WakeOutcome.UNAVAILABLE
        // A card known only by its ref (409 on our own ref) is still woken: the routine reads its inbox, the ids are a hint.
        val taskId = task.taskId
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $key")
                .post(WakeSetup.webhookBody(task.ref, taskId).toRequestBody("application/json".toMediaType())).build()
            runCatching { http.newCall(req).execute().use { it.code } }.fold(
                { code -> log.add("wake", "webhook", null, "${task.relayAgentId} #${task.ref} http=$code"); if (code in 200..299) WakeOutcome.SENT else WakeOutcome.FAILED },
                { e -> log.add("wake", "webhook.error", null, "${task.relayAgentId} ${e.javaClass.simpleName}"); WakeOutcome.FAILED },
            )
        }
    }

    override suspend fun events(task: ConnectorTask, first: Boolean): WakeOutcome {
        // The relay already sent the first event when the card was posted; it only counts if the agent subscribed.
        if (first) return if (subscribed(task.relayAgentId)) WakeOutcome.SENT else WakeOutcome.UNAVAILABLE
        val taskId = task.taskId ?: return WakeOutcome.UNAVAILABLE
        return runCatching { client.wake(taskId, task.relayAgentId) }.fold({ n -> if (n > 0) WakeOutcome.SENT else WakeOutcome.UNAVAILABLE }, { WakeOutcome.FAILED })
    }

    override suspend fun tap(task: ConnectorTask, prompt: String, full: String?): WakeOutcome {
        val t = WakeSetup.tapTarget(task.relayAgentId, full ?: prompt, settings.saved.museWhatsApp)
        return if (notifier.tap(task, names(task.peerId), t, full != null)) WakeOutcome.TAP else WakeOutcome.FAILED
    }
}

/** Wake and approval notifications. Minimal words: the agent's name and the ref. */
class WakeNotifier(private val context: Context) {
    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(id: String, name: String, importance: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    /** "Wake Grok · #k3f9": a tap opens Grok with the prompt (or the whole ask, last rung) filled in. */
    fun tap(task: ConnectorTask, name: String, target: WakeSetup.TapTarget, full: Boolean): Boolean {
        if (!canPost()) return false
        channel(WAKE, "Wake agents", NotificationManager.IMPORTANCE_DEFAULT)
        val i = Intent(context, WakeTapActivity::class.java)
            .putExtra(WakeTapActivity.URL, target.url).putExtra(WakeTapActivity.PKG, target.pkg).putExtra(WakeTapActivity.TEXT, target.text)
            .putExtra(WakeTapActivity.NOTE, task.cid.hashCode())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, task.cid.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, WAKE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (full) "Send to $name" else "Wake $name")
            .setContentText("#${task.ref}")
            .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(task.cid.hashCode(), n) }.isSuccess
    }

    /** No live reply action for a texting agent: one tap opens Messages with "Atall #ref" typed in. */
    fun smsDraft(task: ConnectorTask, name: String, number: String, text: String): Boolean {
        if (!canPost()) return false
        channel(WAKE, "Wake agents", NotificationManager.IMPORTANCE_DEFAULT)
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, task.cid.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, WAKE)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Wake $name").setContentText("#${task.ref}")
            .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true).build()
        return runCatching { NotificationManagerCompat.from(context).notify(task.cid.hashCode(), n) }.isSuccess
    }

    /** "Grok wants your OK": opens Atall, where Approve asks for a fingerprint. Never acts from the shade. */
    fun approval(r: ApprovalRequest, line: String) {
        if (!canPost()) return
        channel(APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH)
        val open = PendingIntent.getActivity(context, r.approvalId.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Wants your OK").setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(r.approvalId.hashCode(), n) }
    }

    fun clearApproval(approvalId: String) = runCatching { NotificationManagerCompat.from(context).cancel(approvalId.hashCode()) }

    companion object {
        const val WAKE = "atall-wake"
        const val APPROVALS = "atall-approvals"
    }
}

/**
 * The tap on a wake notification: copies the prompt (so it can be pasted if the app ignores the link), then opens the
 * agent's app or site with it filled in, or the share sheet. Finishes at once.
 */
class WakeTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(URL)
        val pkg = intent.getStringExtra(PKG)
        val text = intent.getStringExtra(TEXT).orEmpty()
        runCatching { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Atall", text)) }
        val installed = pkg != null && runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        val tries = buildList {
            if (url != null) {
                if (installed) add(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
                add(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            if (installed) add(Intent(share).setPackage(pkg))
            add(Intent.createChooser(share, null))
        }
        for (i in tries) if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
        finish()
    }

    companion object {
        const val URL = "url"
        const val PKG = "pkg"
        const val TEXT = "text"
        const val NOTE = "note"
    }
}

/**
 * The approval key: P-256 in the Android Keystore, created with user authentication required for every use (biometric
 * strong), so it can sign only inside a fingerprint prompt. Android 11+ (Pixel). On older versions approvals can't be
 * signed and stay pending.
 */
class BiometricApprovalSigner(private val context: Context, private val activity: () -> Activity?) : ApprovalSigner {
    private val ks: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensure() {
        if (ks.containsAlias(ALIAS)) return
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run { initialize(b.build()); generateKeyPair() }
    }

    override val publicKeySpkiB64: String
        get() { ensure(); return Base64.encodeToString(ks.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP) }

    fun available(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun signWithFingerprint(req: ApprovalRequest, data: ByteArray): ByteArray? {
        if (Build.VERSION.SDK_INT < 30 || !available()) return null
        val act = activity() ?: return null
        val sig = try {
            ensure()
            Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ALIAS, null) as PrivateKey) }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // A fingerprint was added or removed: make a new key (it must be registered again: pair again).
            runCatching { ks.deleteEntry(ALIAS) }; return null
        } catch (e: Exception) { return null }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                val amount = WakeSetup.money(req.amount, req.currency)?.let { " · $it" }.orEmpty()
                val prompt = BiometricPrompt.Builder(act)
                    .setTitle("Approve for ${app.atall.core.RelayCopy.agentName(req.relayAgentId)}?")
                    .setSubtitle(req.action.take(120) + amount)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton("Cancel", ContextCompat.getMainExecutor(act)) { _, _ -> if (cont.isActive) cont.resume(null) }
                    .build()
                prompt.authenticate(BiometricPrompt.CryptoObject(sig), cancel, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val s = runCatching { result.cryptoObject?.signature?.run { update(data); sign() } }.getOrNull()
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { if (cont.isActive) cont.resume(null) }
                    // onAuthenticationFailed: a finger that didn't match; the prompt stays up for another try.
                })
            }
        }
    }

    companion object {
        const val ALIAS = "atall_approval_v1"
        /** The activity on screen, for the prompt (set by MainActivity). */
        @Volatile var current: WeakReference<Activity>? = null
    }
}
