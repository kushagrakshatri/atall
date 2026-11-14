package app.atall

import android.content.Context
import android.util.Log
import app.atall.agents.text.Agent
import app.atall.agents.text.RequestClosed
import app.atall.agents.text.android.TextAgents
import app.atall.api.ChannelAdapter
import app.atall.api.ChannelType
import app.atall.api.Coordinator
import app.atall.api.Inbox
import app.atall.api.PeerDirectory
import app.atall.api.PeerInfo
import app.atall.api.ShowEverything
import app.atall.core.AgentProfile
import app.atall.core.AgentRegistry
import app.atall.core.JunoBridge
import app.atall.coordinator.FileRunStore
import app.atall.coordinator.JsonLinesScoreLog
import app.atall.coordinator.adapters.ResponsesLlm
import app.atall.core.PlainJuno
import app.atall.core.Router
import app.atall.core.UserName
import app.atall.core.ThreadStore
import app.atall.ui.PeerUi
import app.atall.ui.hueOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual DI: one of each per process. Seams:
 *  - [juno]: Juno's coordinator (`app.atall.coordinator`): plan, ask agents, wait, merge; `@all` Agreement Map.
 *  - [coordinator]: the plain streaming chat, used when there is no agent to ask.
 *  - [inbox]: bind `app.atall.inbox` here (shows everything until then).
 *  - [adapters]: add the `app-agents` WhatsApp / app-notification adapters here; RCS is built in.
 */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = File(context.noBackupFilesDir, "atall")

    val registry = AgentRegistry(File(dataDir, "agents.json"))
    val thread = ThreadStore(File(dataDir, "thread.jsonl"))
    val chatGpt = ChatGpt(context, scope)
    val prefs = Prefs(context)
    private val talkNotifier = TalkNotifier(context)

    val rcs = RcsChannel(context)
    val adapters: Map<ChannelType, ChannelAdapter> = mapOf(ChannelType.RCS to rcs)

    var coordinator: Coordinator = PlainJuno(userName = ::userName)
    var inbox: Inbox = ShowEverything

    val peers = object : PeerDirectory {
        override fun peers(): List<PeerInfo> = registry.all().map { it.toPeer() }
        override fun agent(peerId: String): Agent? {
            val p = registry.get(peerId)?.takeIf { !it.paused && it.channel.implemented } ?: return null
            return adapters[p.channel]?.agent(p.id)
        }
    }

    val juno = JunoBridge(
        thread = thread, registry = registry, peers = peers,
        llm = { chatGpt.responsesOrNull()?.let { client -> ResponsesLlm(client, model = { prefs.model }) } },
        store = FileRunStore(File(dataDir, "runs").apply { mkdirs() }),
        scores = JsonLinesScoreLog(File(dataDir.apply { mkdirs() }, "scores.jsonl")),
        scope = scope, userName = ::userName,
        onTalkDone = { runId, text -> talkNotifier.talkDone(runId, text) },
    )

    val router = Router(
        thread = thread, registry = registry, peers = peers,
        coordinator = { coordinator }, responses = { chatGpt.responsesOrNull() },
        model = { prefs.model }, inbox = { inbox }, juno = { juno }, userName = ::userName,
    )

    /**
     * What agents and Juno call the user ("From Atall (Kush's thread):"): the "Your name" setting, else the first name
     * on the ChatGPT account.
     */
    fun userName(): String = UserName.effective(prefs.userName, chatGptFirstName())

    /** The first name on the ChatGPT account ("Kushagra Kshatri" -> "Kushagra"); blank when signed out. */
    fun chatGptFirstName(): String = UserName.firstName(chatGpt.status.value.name)

    private val _agents = MutableStateFlow(registry.all())
    val agents: StateFlow<List<AgentProfile>> = _agents
    private val collectors = HashMap<String, Pair<Agent, Job>>()

    init {
        // Reply-action capture and every draft fallback with its reason go to logcat (DEBUG, tag TextAgents) on the
        // device only; no message text or numbers.
        TextAgents.diagnostics = true
        TextAgents.init(context)
        scope.launch { rcs.hub.events.collect { if (it is RequestClosed) runCatching { router.onRequestClosed(it) } } }
        sync()
        juno.start()
        chatGpt.refresh()
        // The listener may deliver before our collectors subscribe; the durable log covers the gap.
        scope.launch { delay(1_500); backfill() }
    }

    /** Make every channel match the registry, and (re)subscribe to each live agent's messages. */
    @Synchronized
    fun sync() {
        val all = registry.all()
        for ((type, adapter) in adapters) {
            val wanted = all.filter { it.channel == type && !it.paused }
            if (type == ChannelType.RCS) (rcs.registeredIds() - wanted.map { it.id }.toSet()).forEach { rcs.unregister(it) }
            all.filter { it.channel == type && it.paused }.forEach { adapter.unregister(it.id) }
            for (p in wanted) {
                try { adapter.register(p.toPeer()) } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "agent ${p.id} not registered: ${e.message}")
                }
            }
        }
        val live = all.filter { !it.paused && it.channel.implemented }.mapNotNull { p -> peers.agent(p.id)?.let { p.id to it } }.toMap()
        (collectors.keys - live.keys).toList().forEach { collectors.remove(it)?.second?.cancel() }
        for ((id, agent) in live) {
            val cur = collectors[id]
            if (cur != null && cur.first === agent) continue
            cur?.second?.cancel()
            collectors[id] = agent to scope.launch { agent.incoming.collect { router.onPeerMessage(id, it) } }
        }
        _agents.value = all
    }

    fun backfill() = scope.launch {
        for (p in registry.all()) {
            val a = peers.agent(p.id) ?: continue
            runCatching { router.backfill(p.id, a.history(200)) }
        }
    }

    fun peerUi(): List<PeerUi> = _agents.value.map { p ->
        PeerUi(p.id, p.name, hueOf(p.hue), p.channel, p.address, p.paused, statusOf(p))
    }

    /** Listening · Can reply now · Needs a tap · Paused · Not available yet. */
    fun statusOf(p: AgentProfile): String = when {
        p.paused -> "Paused"
        !p.channel.implemented -> "Not available yet"
        p.channel == ChannelType.RCS -> {
            val s = runCatching { rcs.liveStatus(p.id) }.getOrNull()
            when {
                !TextAgents.hasNotificationAccess(context) -> "Needs notification access"
                s != null && s.usable -> "Can reply now"
                s != null && s.hasAction -> "Needs a tap"
                else -> "Listening"
            }
        }
        else -> "Listening"
    }

    fun addAgent(name: String, channel: ChannelType, address: String, hue: Int): AgentProfile {
        val p = registry.add(name, channel, address, hue)
        sync()
        return p
    }

    fun updateAgent(p: AgentProfile) { registry.update(p); sync() }
    fun removeAgent(id: String) { adapters.values.forEach { it.unregister(id) }; registry.remove(id); sync() }

    companion object { private const val TAG = "Atall" }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("atall", Context.MODE_PRIVATE)
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }
    /** The Responses model slug; a setting because the SIWC route's accepted models may change. */
    var model: String
        get() = sp.getString("model", Router.DEFAULT_MODEL) ?: Router.DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v).apply() }
    /** What agents call the user in context headers. Blank: the first name on the ChatGPT account. */
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) { sp.edit().putString("user_name", v).apply() }
}
