package app.atall.core

import app.atall.api.Coordinator
import app.atall.api.MainContext
import app.atall.api.MainTurn
import app.atall.api.NeedsUserKind
import app.atall.chatgpt.ApiError
import app.atall.chatgpt.Cancellation
import app.atall.chatgpt.ChatGptException
import app.atall.chatgpt.Items
import app.atall.chatgpt.PlanUsageNotAllowed
import app.atall.chatgpt.Recovery
import app.atall.chatgpt.SignInRequired
import app.atall.chatgpt.StreamEvent
import app.atall.chatgpt.TransientFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M0 stand-in for `app.atall.coordinator`: Juno answers with one plain ChatGPT Responses call (no delegation).
 * Signed out → one line asking to sign in. Replace by binding the real coordinator in [app.atall.Graph].
 */
class PlainJuno(private val now: () -> Long = System::currentTimeMillis) : Coordinator {

    override suspend fun onTurn(turn: MainTurn, context: MainContext) {
        val client = context.responses
        if (client == null) {
            context.sink.needsUser(NeedsUserKind.SIGN_IN, SIGN_IN_COPY)
            return
        }
        val names = context.peers.peers().associate { it.id to it.name }
        val input = context.history.map { line ->
            when (line.author) {
                ThreadMessage.USER -> Items.user(line.text)
                Main.AUTHOR -> Items.assistant(line.text)
                else -> Items.user("[${names[line.author] ?: "Another agent"} wrote] ${line.text}")
            }
        }.ifEmpty { listOf(Items.user(turn.text)) }
        val body = buildJsonObject {
            put("model", context.model)
            put("instructions", instructions(context.mainName, names.values.toList()))
            put("input", JsonArray(input))
        }
        val cancel = Cancellation()
        val text = StringBuilder()
        var id: String? = null
        suspend fun push(t: String) {
            if (t.isBlank()) return
            val cur = id
            if (cur == null) id = context.sink.start(t) else context.sink.update(cur, t)
        }
        try {
            val final = coroutineScope {
                val ticker = launch {
                    while (isActive) { delay(STREAM_TICK_MS); push(synchronized(text) { text.toString() }) }
                }
                try {
                    withContext(Dispatchers.IO) {
                        runInterruptible {
                            client.stream(body, cancel) { ev ->
                                if (ev is StreamEvent.TextDelta) synchronized(text) { text.append(ev.delta) }
                            }
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }
            push(final.text.ifBlank { synchronized(text) { text.toString() } }.trim().ifEmpty { "…" })
        } catch (e: ChatGptException) {
            val (kind, copy) = copyFor(e)
            if (kind != null) context.sink.needsUser(kind, copy) else context.sink.needsUser(NeedsUserKind.NOTICE, copy)
        } finally {
            cancel.cancel()
        }
    }

    companion object {
        const val STREAM_TICK_MS = 150L
        const val SIGN_IN_COPY = "Sign in with ChatGPT to talk to Juno."

        fun instructions(name: String, peers: List<String>) = buildString {
            append("You are $name, the main agent in Atall, a single chat thread on the user's Android phone. ")
            append("Answer briefly and plainly, in sentence case, with no markdown headings. ")
            if (peers.isNotEmpty()) {
                append("The user also has these agents in the thread: ${peers.joinToString(", ")}. ")
                append("In this version you cannot contact them yourself. If another agent would help, tell the user to @mention it. ")
                append("Never claim you asked, booked, bought or sent anything. ")
            } else {
                append("You cannot contact other agents or act outside this chat. Never claim you booked, bought or sent anything. ")
            }
            append("Lines starting with \"[Name wrote]\" are messages other agents sent into the thread.")
        }

        /** One-line copy per error; a non-null kind means show it as a "needs you" line. */
        fun copyFor(e: ChatGptException): Pair<NeedsUserKind?, String> = when (e) {
            is SignInRequired -> NeedsUserKind.SIGN_IN to "Your ChatGPT sign-in ended. Sign in again to talk to Juno."
            is PlanUsageNotAllowed -> NeedsUserKind.SIGN_IN to "Allow Atall to use your ChatGPT plan to talk to Juno."
            is TransientFailure -> null to if (e.code == "offline") "You're offline. Try again when you're back." else "ChatGPT didn't answer. Try again in a minute."
            is ApiError -> when (e.recovery) {
                Recovery.USAGE_LIMIT -> null to "You've hit your ChatGPT limit for Atall. Manage usage at chatgpt.com/settings/usage."
                Recovery.SIGN_IN_AGAIN -> NeedsUserKind.SIGN_IN to "Your ChatGPT sign-in ended. Sign in again to talk to Juno."
                Recovery.NOT_ELIGIBLE -> null to "This ChatGPT account can't be used with Atall."
                Recovery.RETRY_LATER -> null to "ChatGPT is busy. Try again in a minute."
                else -> null to "Juno couldn't answer just now."
            }
            else -> null to "Juno couldn't answer just now."
        }
    }
}
