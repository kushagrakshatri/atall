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

    override fun onResume() {
        super.onResume()
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
                ThreadUi(messages, peers, access),
                ThreadActions(
                    onSend = { text -> g.scope.launch { g.router.send(text) } },
                    onRetry = { id -> g.scope.launch { g.router.retry(id) } },
                    // Tap-to-send opens Messages, which Android allows only from the foreground: run in the activity's scope.
                    onTapToSend = { id -> lifecycleScope.launch { g.router.tapToSend(id) } },
                    onSignIn = { g.chatGpt.startSignIn(this) },
                    onOpenAgents = { screen = Screen.Agents },
                    onAddAgent = { screen = Screen.AddAgent(Screen.Thread) },
                    onTurnOnAccess = ::openAccess,
                    onApprove = { id, yes -> g.scope.launch { g.router.approve(id, yes) } },
                    onStopWaiting = { runId -> g.scope.launch { g.juno.stopWaiting(runId) } },
                    onChallenge = { runId, dispute -> g.scope.launch { g.juno.challenge(runId, dispute) } },
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
                    onRemove = { g.removeAgent(profile.id); screen = Screen.Agents },
                )
            }
        }
        if (licences) {
            val text = remember { runCatching { assets.open("licenses/OFL-Geist.txt").bufferedReader().readText() }.getOrDefault("") }
            LicencesDialog(text) { licences = false }
        }
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            if (intent.action != android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
    }
}
