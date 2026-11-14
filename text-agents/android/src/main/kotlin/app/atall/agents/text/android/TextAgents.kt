package app.atall.agents.text.android

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.atall.agents.text.AgentConfig
import app.atall.agents.text.DraftLauncher
import app.atall.agents.text.DraftOutcome
import app.atall.agents.text.DraftRequest
import app.atall.agents.text.FileTextAgentStore
import app.atall.agents.text.RemovalReason
import app.atall.agents.text.Rules
import app.atall.agents.text.TextAgent
import app.atall.agents.text.TextAgentConfig
import app.atall.agents.text.TextAgentHub
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide entry point. Call [init] from Application.onCreate (optional: the listener initializes with
 * defaults if it starts first). Holds one [TextAgentHub] over files/text-agents/.
 */
object TextAgents {
  internal const val TAG = "TextAgents"
  private const val SWEEP_MS = 30_000L

  @Volatile private var hub: TextAgentHub? = null

  /**
   * Debug diagnostics for reply-action capture and draft fallbacks: one line per decision to logcat at DEBUG under
   * [TAG], on the device only. Lines carry agent ids, reasons and ages, never message text or phone numbers.
   * Off unless the app turns it on.
   */
  @Volatile var diagnostics: Boolean = false
  /** Also hands each diagnostics line to the app (its "Copy debug log" and telemetry). Same content as logcat. */
  @Volatile var diagnosticsSink: ((String) -> Unit)? = null
  @Volatile internal var listener: TextAgentListenerService? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** [isForeground] decides whether a draft may open now (Android blocks background activity starts). */
  @Synchronized
  fun init(context: Context, config: TextAgentConfig = TextAgentConfig(), isForeground: (() -> Boolean)? = null): TextAgentHub {
    hub?.let { return it }
    val app = context.applicationContext
    val h = TextAgentHub(
      store = FileTextAgentStore(File(app.filesDir, "text-agents")),
      drafts = IntentDraftLauncher(app, isForeground ?: { processInForeground() }),
      config = config,
      diagnostics = { line -> if (diagnostics) Log.d(TAG, line); diagnosticsSink?.let { runCatching { it(line) } } },
    )
    hub = h
    // Expiry is evaluated on every event; this tick makes RequestClosed arrive on time when nothing happens.
    scope.launch { while (isActive) { delay(SWEEP_MS); runCatching { h.sweep() } } }
    return h
  }

  fun hub(context: Context): TextAgentHub = hub ?: init(context)

  /** Register (or update) an agent, then re-read the notifications showing so a waiting text counts at once. */
  fun register(context: Context, agent: AgentConfig): TextAgent {
    val a = hub(context).register(agent)
    rescan()
    return a
  }

  fun rescan() { listener?.rescan() }

  fun hasNotificationAccess(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(context, TextAgentListenerService::class.java).flattenToString()
    return flat.split(':').any { it == me }
  }

  /** Straight to this app's notification-access switch where supported (API 30+), else the list. */
  fun notificationAccessIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= 30) {
      Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        ComponentName(context, TextAgentListenerService::class.java).flattenToString(),
      )
    } else {
      Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

  /** App info, where a sideloaded install on Android 13+ must "Allow restricted settings" (⋮ menu) first. */
  fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

  val restrictedSettingsApply: Boolean get() = Build.VERSION.SDK_INT >= 33

  /** Whether Google Messages is installed (needs the <queries> entry in this module's manifest). */
  fun messagesInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(Rules.MESSAGES_PACKAGE, 0) }.isSuccess

  /** For [app.atall.agents.text.SendResult.NeedsUser]: post this in a notification; a tap opens the draft. */
  fun draftPendingIntent(context: Context, draft: DraftRequest, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(context, requestCode, draftIntent(draft, Rules.MESSAGES_PACKAGE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  internal fun draftIntent(draft: DraftRequest, pkg: String?): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(draft.number)))
      .putExtra("sms_body", draft.text)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .setPackage(pkg)

  private fun processInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
  }
}

/** ACTION_SENDTO smsto:<number> + sms_body, aimed at Google Messages, any SMS app if that is missing. */
class IntentDraftLauncher(private val context: Context, private val isForeground: () -> Boolean) : DraftLauncher {
  override fun open(request: DraftRequest): DraftOutcome {
    if (!isForeground()) return DraftOutcome.IN_BACKGROUND
    for (pkg in listOf(Rules.MESSAGES_PACKAGE, null)) {
      try {
        context.startActivity(TextAgents.draftIntent(request, pkg))
        return DraftOutcome.OPENED
      } catch (e: ActivityNotFoundException) {
        // try the next
      } catch (e: Exception) {
        Log.w(TextAgents.TAG, "draft failed: ${e.javaClass.simpleName}")
      }
    }
    return DraftOutcome.NO_APP
  }
}

/**
 * Hears agents that text this phone in Google Messages. Every Messages notification goes to the hub, which
 * ignores anything not from a registered agent's 1:1 thread; other apps' notifications are dropped unread.
 */
class TextAgentListenerService : NotificationListenerService() {
  private val hub get() = TextAgents.hub(applicationContext)

  override fun onListenerConnected() {
    TextAgents.listener = this
    Log.i(TextAgents.TAG, "listener connected")
    rescan() // access granted, process restart or rebind: recover reply actions still showing
  }

  override fun onListenerDisconnected() {
    if (TextAgents.listener === this) TextAgents.listener = null
    if (Build.VERSION.SDK_INT >= 24) runCatching { requestRebind(ComponentName(this, TextAgentListenerService::class.java)) }
  }

  internal fun rescan() {
    try {
      val active = activeNotifications ?: return
      hub.onRescan(active.mapNotNull { SnapshotReader.read(this, it) })
    } catch (e: Exception) {
      Log.w(TextAgents.TAG, "active notifications unreadable: ${e.javaClass.simpleName}")
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName != Rules.MESSAGES_PACKAGE) return
    try { SnapshotReader.read(this, sbn)?.let(hub::onPosted) } catch (e: Exception) {
      Log.w(TextAgents.TAG, "notification skipped: ${e.javaClass.simpleName}")
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
    if (sbn.packageName != Rules.MESSAGES_PACKAGE) return
    val r = when (reason) {
      REASON_APP_CANCEL -> RemovalReason.APP_CANCEL
      REASON_CLICK -> RemovalReason.CLICK
      REASON_CANCEL, REASON_CANCEL_ALL -> RemovalReason.USER_DISMISS
      else -> RemovalReason.OTHER
    }
    hub.onRemoved(sbn.key, r)
  }
}
