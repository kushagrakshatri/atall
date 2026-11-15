package app.atall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.atall.agents.text.android.TextAgents
import app.atall.ui.AccessStep
import app.atall.ui.AccountUi
import app.atall.ui.AddAgentScreen
import app.atall.ui.AddAgentStep
import app.atall.ui.AgentDetailScreen
import app.atall.ui.AgentFormState
import app.atall.ui.AgentsScreen
import app.atall.ui.LicencesDialog
import app.atall.ui.SignInUi
import app.atall.ui.ThreadActions
import app.atall.ui.ThreadScreen
import app.atall.ui.ThreadUi
import app.atall.ui.WelcomeStep
import app.atall.ui.theme.AppTheme
import kotlinx.coroutines.launch

private sealed interface Screen {
    data class Onboarding(val step: Int) : Screen
    data object Thread : Screen
    data object Agents : Screen
    data class AddAgent(val back: Screen) : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    private val graph get() = (application as AtallApp).graph
    /** Bumped on every resume so access / live status re-read. */
    private var resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root() } }
    }

    override fun onStart() { super.onStart(); TalkNotifier.visible = true }
    override fun onStop() { TalkNotifier.visible = false; super.onStop() }

    /** Talks can take minutes: the first time the user starts one, ask (once) to let Juno say when it's done. */
    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val sp = getSharedPreferences("atall", MODE_PRIVATE)
        if (sp.getBoolean("asked_notifications", false)) return
        sp.edit().putBoolean("asked_notifications", true).apply()
        runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
    }

    override fun onResume() {
        super.onResume()
        BiometricApprovalSigner.current = java.lang.ref.WeakReference(this)
        resumes.intValue++
        graph.chatGpt.refresh()
        TextAgents.rescan()
        graph.backfill()
    }

    @Composable
    private fun Root() {
        val g = graph
        val tick by resumes
        var screen by remember { mutableStateOf<Screen>(if (g.prefs.onboarded) Screen.Thread else Screen.Onboarding(0)) }
        val messages by g.thread.messages.collectAsState()
        val agents by g.agents.collectAsState()
        val status by g.chatGpt.status.collectAsState()
        val signIn by g.chatGpt.signIn.collectAsState()
        val access = remember(tick) { TextAgents.hasNotificationAccess(this) }
        val peers = remember(agents, tick, messages.size) { g.peerUi() }
        var accessTried by remember { mutableStateOf(false) }
        val pendingApprovals by g.approvals.pending.collectAsState()
        var approvalNote by remember { mutableStateOf<String?>(null) }
        var licences by remember { mutableStateOf(false) }

        fun finishOnboarding() { g.prefs.onboarded = true; screen = Screen.Thread }
        fun openAccess() { accessTried = true; startSafely(TextAgents.notificationAccessIntent(this)) }

        LaunchedEffect(signIn) {
            if (signIn == SignInUi.Done && screen == Screen.Onboarding(0)) screen = Screen.Onboarding(1)
        }

        when (val s = screen) {
            is Screen.Onboarding -> when (s.step) {
                0 -> WelcomeStep(signIn, onContinue = { g.chatGpt.startSignIn(this) }, onCancelSignIn = { g.chatGpt.cancelSignIn() }, onLater = { screen = Screen.Onboarding(1) })
                1 -> {
                    BackHandler { screen = Screen.Onboarding(0) }
                    AccessStep(
                        granted = access, triedOnce = accessTried, restrictedApplies = TextAgents.restrictedSettingsApply,
                        onOpenSettings = ::openAccess, onOpenAppInfo = { startSafely(TextAgents.appDetailsIntent(this)) },
                        onContinue = { screen = Screen.Onboarding(2) }, onNotNow = { screen = Screen.Onboarding(2) },
                    )
                }
                else -> {
                    BackHandler { screen = Screen.Onboarding(1) }
                    val form = remember { AgentFormState(hue = g.registry.nextHue()) }
                    AddAgentStep(form, agents, onAdd = {
                        form.tried = true
                        if (form.valid(agents)) { g.addAgent(form.name, form.channel, form.address, form.hue); finishOnboarding() }
                    }, onSkip = ::finishOnboarding)
                }
            }
            Screen.Thread -> ThreadScreen(
                ThreadUi(messages, peers, access,
                    approvals = pendingApprovals.map { r ->
                        r.approvalId to app.atall.core.WakeSetup.approvalLine(r.peerId?.let { id -> agents.firstOrNull { it.id == id }?.name } ?: app.atall.core.RelayCopy.agentName(r.relayAgentId), r.action, r.amount, r.currency)
                    },
                    approvalNote = approvalNote),
                ThreadActions(
                    onSend = { text -> g.scope.launch { g.router.send(text) } },
                    onSendTalk = { text -> askForNotifications(); g.scope.launch { g.router.send(text, talk = true) } },
                    onRetry = { id -> g.scope.launch { g.router.retry(id) } },
                    // Tap-to-send opens Messages, which Android allows only from the foreground: run in the activity's scope.
                    onTapToSend = { id -> lifecycleScope.launch { g.router.tapToSend(id) } },
                    onSignIn = { g.chatGpt.startSignIn(this) },
                    onOpenAgents = { screen = Screen.Agents },
                    onAddAgent = { screen = Screen.AddAgent(Screen.Thread) },
                    onTurnOnAccess = ::openAccess,
                    onApprove = { id, yes -> g.scope.launch { g.router.approve(id, yes) } },
                    onStopWaiting = { runId -> g.scope.launch { g.juno.stopWaiting(runId) } },
                    onTalkOver = { runId, dispute -> askForNotifications(); g.scope.launch { g.juno.talkOver(runId, dispute) } },
                    onWrapUp = { runId -> g.scope.launch { g.juno.wrapUp(runId) } },
                    onLeaveOut = { runId, agent -> g.scope.launch { g.juno.leaveOut(runId, agent) } },
                    onChoice = { runId, choice -> g.scope.launch { g.juno.choiceTapped(runId, choice); g.router.send(choice) } },
                    // The fingerprint prompt needs this activity: run in its scope.
                    onAgentApproval = { id, yes -> lifecycleScope.launch { approvalNote = g.decideApproval(id, yes) } },
                ),
            )
            Screen.Agents -> {
                BackHandler { screen = Screen.Thread }
                // Prefilled with the ChatGPT first name; what you type is saved as you go (blank falls back to it).
                var nameField by remember(status.name) { mutableStateOf(app.atall.core.UserName.fieldValue(g.prefs.userName, g.chatGptFirstName())) }
                AgentsScreen(
                    peers, AccountUi(status.state, status.email), access,
                    onBack = { screen = Screen.Thread }, onOpen = { screen = Screen.Detail(it) },
                    onAdd = { screen = Screen.AddAgent(Screen.Agents) },
                    onSignIn = { g.chatGpt.startSignIn(this) }, onSignOut = { g.chatGpt.signOut() },
                    onManageUsage = { startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGpt.MANAGE_USAGE_URL))) },
                    onTurnOnAccess = ::openAccess, onLicences = { licences = true },
                    userName = nameField, onUserNameChange = { typed ->
                        nameField = typed
                        g.prefs.userName = app.atall.core.UserName.normalize(typed)
                    },
                    debug = debugUi(),
                    relay = relayParams(),
                )
            }
            is Screen.AddAgent -> {
                BackHandler { screen = s.back }
                val form = remember { AgentFormState(hue = g.registry.nextHue()) }
                AddAgentScreen(form, agents, onBack = { screen = s.back }, onAdd = {
                    form.tried = true
                    if (form.valid(agents)) { g.addAgent(form.name, form.channel, form.address, form.hue); screen = s.back }
                })
            }
            is Screen.Detail -> {
                BackHandler { screen = Screen.Agents }
                val profile = agents.firstOrNull { it.id == s.id }
                val peer = peers.firstOrNull { it.id == s.id }
                if (profile == null || peer == null) { LaunchedEffect(s.id) { screen = Screen.Agents }; return }
                val form = remember(s.id) { AgentFormState(profile.name, profile.channel, profile.address, profile.hue, profile.id) }
                AgentDetailScreen(
                    peer, form, agents, onBack = { screen = Screen.Agents },
                    onSave = {
                        form.tried = true
                        if (form.valid(agents)) {
                            g.updateAgent(profile.copy(name = form.name, channel = form.channel, address = form.address, hue = form.hue))
                            screen = Screen.Agents
                        }
                    },
                    onPause = { g.updateAgent(profile.copy(paused = it)) },
                    askBeforeSharing = profile.sharesPrivately,
                    onAskBeforeSharing = { g.updateAgent(profile.copy(askBeforeSharing = it)) },
                    onRemove = { g.removeAgent(profile.id); screen = Screen.Agents },
                    wake = wakeUi(profile),
                )
            }
        }
        if (licences) {
            val text = remember { runCatching { assets.open("licenses/OFL-Geist.txt").bufferedReader().readText() }.getOrDefault("") }
            LicencesDialog(text) { licences = false }
        }
    }

    /** Settings → Agents → Connected agents, and the debug test task. The list refreshes each time the screen opens. */
    @Composable
    private fun relayParams(): app.atall.ui.RelayParams {
        val r = graph.relay
        val ui by r.ui.collectAsState()
        val test by r.test.collectAsState()
        LaunchedEffect(Unit) { r.refreshAgents() }
        return app.atall.ui.RelayParams(
            ui = ui, onConnect = { code -> r.pair(code) }, onRevoke = { r.revoke(it) },
            test = test, onSendTest = { r.sendTestTask() }, onCloseTest = { r.closeTest() },
            onStory1 = { graph.runStory1() },
        )
    }

    /** C3: Settings → Agents → the agent → Wake-up, for agents on the connector. */
    @Composable
    private fun wakeUi(profile: app.atall.core.AgentProfile): app.atall.ui.WakeUi? {
        val g = graph
        // linkedRelayId: an agent on Full text has no connector route but still gets its Wake-up section.
        val relayId = g.relay.access.linkedRelayId(profile) ?: profile.relayId ?: return null
        val ws = g.wakeSettings
        var cfg by remember(relayId) { mutableStateOf(ws.config(relayId)) }
        var url by remember(relayId) { mutableStateOf(ws.saved.webhookUrls[relayId.lowercase()].orEmpty()) }
        var window by remember(relayId) { mutableStateOf(((cfg.windowMs ?: 0L) / 60_000L).takeIf { it > 0 }?.toString().orEmpty()) }
        var wa by remember(relayId) { mutableStateOf(ws.saved.museWhatsApp.orEmpty()) }
        var copied by remember(relayId) { mutableStateOf(false) }
        val textRoute = g.relay.hasTextRoute(profile.id)
        val kinds = app.atall.coordinator.adapters.WakeDefaults.choices(relayId, textRoute)
        fun save(c: app.atall.coordinator.adapters.WakeConfig) { cfg = c; ws.update { it.with(relayId, c) }; g.debugLog.add("wake", "setting", null, "$relayId ${c.kind}") }
        return app.atall.ui.WakeUi(
            labels = kinds.map { app.atall.core.WakeSetup.label(it) },
            selected = kinds.indexOf(cfg.kind).coerceAtLeast(0),
            caption = app.atall.core.WakeSetup.caption(relayId, cfg),
            onSelect = { i -> save(cfg.copy(kind = kinds[i])) },
            webhook = cfg.kind == app.atall.coordinator.adapters.WakeKind.WEBHOOK,
            webhookUrl = url, onWebhookUrl = { v -> url = v; ws.update { it.withUrl(relayId, v) } },
            webhookKeySaved = ws.hasWebhookKey(relayId), onWebhookKey = { v -> ws.setWebhookKey(relayId, v) },
            windowMinutes = if (cfg.kind == app.atall.coordinator.adapters.WakeKind.SCHEDULE) window else null,
            onWindow = { v -> window = v.filter(Char::isDigit).take(4); window.toLongOrNull()?.takeIf { it in 5..1440 }?.let { save(cfg.copy(windowMs = it * 60_000L)) } },
            whatsApp = if (relayId.equals("muse", true)) wa else null,
            onWhatsApp = { v -> wa = v; ws.update { it.copy(museWhatsApp = v.trim().ifBlank { null }) } },
            onCopySetup = {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("Atall setup", app.atall.core.WakeSetup.setupPrompt(relayId, cfg))) }
                copied = true
            },
            copied = copied,
            setup = app.atall.core.WakeSetup.needsSetup(cfg),
        )
    }

    /** Settings → Debugging. State lives here so the switch and the DSN field update as you use them. */
    @Composable
    private fun debugUi(): app.atall.ui.DebugUi {
        val g = graph
        var sendText by remember { mutableStateOf(g.prefs.sendMessageText) }
        var dsn by remember { mutableStateOf(g.prefs.sentryDsn) }
        var copied by remember { mutableStateOf<String?>(null) }
        var sentryOn by remember { mutableStateOf(g.telemetry.enabled) }
        var relayDown by remember { mutableStateOf(g.wakeSettings.simulateOutage) }
        val dsnNote = when {
            g.buildTimeDsn -> "This build has its own DSN; this field is ignored."
            dsn.isBlank() -> "Paste a DSN to turn Sentry on. Stored on this phone only."
            !SentryTelemetry.looksLikeDsn(dsn) -> "That doesn't look like a DSN (https://key@host/123)."
            sentryOn -> "Sentry is on."
            else -> "Saved. Sentry starts with it."
        }
        return app.atall.ui.DebugUi(
            sendText = sendText,
            onSendText = { v -> sendText = v; g.prefs.sendMessageText = v; g.debugLog.add("app", "setting", null, "send_message_text=$v") },
            onCopyLog = {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("Atall debug log", g.debugLogText())) }
                copied = "Copied ${g.debugLog.entries().size} lines"
            },
            copiedNote = copied,
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            dsn = dsn,
            onDsn = { typed ->
                dsn = typed
                g.prefs.sentryDsn = typed
                if (!g.buildTimeDsn && SentryTelemetry.looksLikeDsn(typed)) sentryOn = g.startTelemetry()
            },
            dsnNote = dsnNote,
            relayDown = relayDown,
            onRelayDown = { v -> relayDown = v; g.wakeSettings.simulateOutage = v; g.debugLog.add("app", "setting", null, "simulate_relay_outage=$v") },
        )
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            if (intent.action != android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
    }
}
