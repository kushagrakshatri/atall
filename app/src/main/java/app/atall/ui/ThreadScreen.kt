package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.atall.api.ChannelType
import app.atall.core.Main
import app.atall.core.Mentions
import app.atall.core.MessageKind
import app.atall.core.SendStatus
import app.atall.core.ThreadMessage
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ThreadUi(
    val messages: List<ThreadMessage>,
    val peers: List<PeerUi>,
    val notificationAccess: Boolean,
)

class ThreadActions(
    val onSend: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onTapToSend: (String) -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onOpenAgents: () -> Unit = {},
    val onAddAgent: () -> Unit = {},
    val onTurnOnAccess: () -> Unit = {},
)

private sealed interface Row {
    val key: String
    data class Day(val label: String) : Row { override val key get() = "day:$label" }
    data class Msg(val m: ThreadMessage, val header: Boolean, val lastInGroup: Boolean) : Row { override val key get() = m.id }
}

private const val GROUP_MS = 2 * 60_000L

private fun rows(messages: List<ThreadMessage>, zone: ZoneId, today: LocalDate): List<Row> {
    val out = ArrayList<Row>()
    var lastDay: LocalDate? = null
    messages.forEachIndexed { i, m ->
        val day = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
        if (day != lastDay) { out += Row.Day(dayLabel(day, today)); lastDay = day }
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val sameAsPrev = prev != null && prev.author == m.author && m.at - prev.at < GROUP_MS && prev.kind == MessageKind.TEXT &&
            Instant.ofEpochMilli(prev.at).atZone(zone).toLocalDate() == day
        val sameAsNext = next != null && next.author == m.author && next.at - m.at < GROUP_MS
        out += Row.Msg(m, header = !sameAsPrev, lastInGroup = !sameAsNext)
    }
    return out
}

fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
}

@Composable
fun ThreadScreen(state: ThreadUi, actions: ThreadActions, zone: ZoneId = ZoneId.systemDefault()) {
    val c = AppTheme.colors
    val peersById = remember(state.peers) { state.peers.associateBy { it.id } }
    val names = remember(state.peers) { Mentions.nameMap(state.peers.map { app.atall.core.AgentProfile(it.id, it.name) }) }
    val today = LocalDate.now(zone)
    val list = remember(state.messages, today) { rows(state.messages, zone, today) }
    val listState = rememberLazyListState()
    LaunchedEffect(list.size, state.messages.lastOrNull()?.text?.length) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1)
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AppBar(state.peers, actions.onOpenAgents)
        if (!state.notificationAccess) AccessBanner(actions.onTurnOnAccess)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) EmptyThread(state.peers)
            else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                items(list, key = { it.key }) { row ->
                    when (row) {
                        is Row.Day -> DayDivider(row.label)
                        is Row.Msg -> MessageRow(row, peersById, names, actions)
                    }
                }
            }
        }
        Composer(state.peers, actions.onSend, actions.onAddAgent)
    }
}

@Composable
private fun AppBar(peers: List<PeerUi>, onAgents: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MainAvatar(36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Main.NAME, style = AppTheme.type.headline, color = AppTheme.colors.ink)
            Text(Copy.withPeers(peers), style = AppTheme.type.body2, color = AppTheme.colors.ink2)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onAgents), contentAlignment = Alignment.Center) {
            Icon(Glyph.People, AppTheme.colors.ink)
        }
    }
}

@Composable
private fun AccessBanner(onTurnOn: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(AppTheme.radii.card).background(c.sunken)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Agent replies can't reach the thread.", Modifier.weight(1f), style = AppTheme.type.body2, color = c.ink)
        Chip("Turn on", onTurnOn)
    }
}

@Composable
private fun EmptyThread(peers: List<PeerUi>) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MainAvatar(40.dp)
        Spacer(Modifier.height(16.dp))
        Text("What can I take off your plate?", style = AppTheme.type.headline, color = AppTheme.colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        val names = peers.filter { !it.paused }.map { it.name }
        val sub = when (names.size) {
            0 -> "Ask anything. Add agents any time to share the work."
            1 -> "Ask anything, or @mention ${names[0]} to ask it directly."
            else -> "Ask anything, or @mention ${names.dropLast(1).joinToString(", ")} or ${names.last()} to ask them directly."
        }
        Text(sub, style = AppTheme.type.body2, color = AppTheme.colors.ink2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DayDivider(label: String) {
    Text(label, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = AppTheme.type.label, color = AppTheme.colors.ink3, textAlign = TextAlign.Center)
}

@Composable
private fun MessageRow(row: Row.Msg, peers: Map<String, PeerUi>, names: Map<String, String>, actions: ThreadActions) {
    val m = row.m
    val top = if (row.header) AppTheme.spacing.turnGap / 2 else AppTheme.spacing.groupGap
    Box(Modifier.fillMaxWidth().padding(top = top)) {
        when {
            m.author == ThreadMessage.USER -> UserBubble(m, row.lastInGroup, names, peers, actions.onRetry)
            m.kind == MessageKind.NEEDS_TAP -> NeedsTap(m, peers[m.peerId], actions.onTapToSend)
            m.kind == MessageKind.NEEDS_SIGN_IN -> AgentTurn(null, m, row.header) { Spacer(Modifier.height(8.dp)); Chip("Sign in", actions.onSignIn, filled = true) }
            m.kind == MessageKind.NOTICE -> Notice(m.text)
            m.author == Main.AUTHOR -> AgentTurn(null, m, row.header)
            else -> AgentTurn(peers[m.author] ?: PeerUi(m.author, "Removed agent", AgentHue.Slate, ChannelType.RCS, ""), m, row.header)
        }
    }
}

@Composable
private fun UserBubble(m: ThreadMessage, last: Boolean, names: Map<String, String>, peers: Map<String, PeerUi>, onRetry: (String) -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.padding(start = 56.dp).clip(AppTheme.radii.userBubble(last)).background(c.userBubble)
                .alpha(if (m.status == SendStatus.SENDING) 0.6f else 1f)
                .clickable(enabled = m.status == SendStatus.NOT_SENT) { onRetry(m.id) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(mentionText(m.text, names, peers, c.onUserBubble.copy(alpha = 0.14f)), style = AppTheme.type.body, color = c.onUserBubble)
        }
        if (m.status == SendStatus.NOT_SENT) {
            Text(
                listOfNotNull("Not sent", m.statusNote, "Tap to retry").joinToString(" · "),
                Modifier.padding(top = 4.dp), style = AppTheme.type.caption, color = c.danger,
            )
        }
    }
}

/** Mentions in 600 weight on a soft highlight. */
private fun mentionText(text: String, names: Map<String, String>, peers: Map<String, PeerUi>, highlight: androidx.compose.ui.graphics.Color): AnnotatedString {
    val found = Mentions.find(text, names)
    if (found.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var pos = 0
        for (m in found) {
            append(text.substring(pos, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight(600), background = highlight)) { append(text.substring(m.range.first, m.range.last + 1)) }
            pos = m.range.last + 1
        }
        append(text.substring(pos))
    }
}

@Composable
private fun AgentTurn(peer: PeerUi?, m: ThreadMessage, header: Boolean, extra: @Composable () -> Unit = {}) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 16.dp)) {
        Box(Modifier.width(28.dp)) {
            if (header) { if (peer == null) MainAvatar(28.dp) else PeerAvatar(peer.name, peer.hue, 28.dp) }
        }
        Spacer(Modifier.width(AppTheme.spacing.avatarToText))
        Column(Modifier.weight(1f)) {
            if (header) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peer?.name ?: Main.NAME, style = AppTheme.type.sender, color = if (peer == null) c.ink else AppTheme.agent(peer.hue).solid)
                    if (peer != null && m.direct) {
                        Spacer(Modifier.width(6.dp))
                        Text("replied directly", style = AppTheme.type.caption, color = c.ink3)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            if (m.text.isNotBlank()) Text(m.text, style = AppTheme.type.body, color = c.ink)
            else if (m.streaming) Dot(c.ink3)
            extra()
        }
    }
}

@Composable
private fun NeedsTap(m: ThreadMessage, peer: PeerUi?, onTap: (String) -> Unit) {
    val c = AppTheme.colors
    val name = peer?.name ?: "agent"
    Row(Modifier.fillMaxWidth().padding(start = 38.dp), verticalAlignment = Alignment.CenterVertically) {
        when (m.status) {
            SendStatus.DRAFTED -> Text("Opened in Messages. Tap Send there.", style = AppTheme.type.body2, color = c.ink2)
            SendStatus.SENT -> Text("Sent to $name", style = AppTheme.type.body2, color = c.ink2)
            else -> Column {
                Chip("Tap to send to $name", { onTap(m.id) })
                m.statusNote?.let { Text(it, Modifier.padding(top = 4.dp), style = AppTheme.type.caption, color = c.danger) }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = 38.dp), style = AppTheme.type.body2, color = AppTheme.colors.ink3)
}

// ------------------------------------------------------------------------------------------------ previews

internal val SamplePeers = listOf(
    PeerUi("p1", "Nova", AgentHue.Teal, ChannelType.RCS, "+14085550101", status = "Can reply now"),
    PeerUi("p2", "Scout", AgentHue.Ember, ChannelType.RCS, "+14085550102", status = "Listening"),
)

internal fun sampleThread(now: Long = 1_763_000_000_000L): List<ThreadMessage> = listOf(
    ThreadMessage("1", now - 600_000, ThreadMessage.USER, "Find us a table Saturday around 7:30.", status = SendStatus.SENT),
    ThreadMessage("2", now - 590_000, Main.AUTHOR, "Saturday at 7:30 is busy downtown. Want me to look at 8 instead, or @mention Scout to check bookings?"),
    ThreadMessage("3", now - 300_000, ThreadMessage.USER, "@Nova where's the game again?", status = SendStatus.SENT),
    ThreadMessage("4", now - 280_000, "p1", "Watson Park, field 2, at 3:00. Want a reminder at 2:15?", direct = true),
    ThreadMessage("5", now - 100_000, ThreadMessage.USER, "@Scout book it for 8", status = SendStatus.SENT),
    ThreadMessage("6", now - 99_000, Main.AUTHOR, "Tap to send to Scout", kind = MessageKind.NEEDS_TAP, peerId = "p2", draftText = "book it for 8", correlationId = "d:x"),
    ThreadMessage("7", now - 50_000, ThreadMessage.USER, "What's the weather like?", status = SendStatus.SENT),
    ThreadMessage("8", now - 49_000, Main.AUTHOR, "Sign in with ChatGPT to talk to Juno.", kind = MessageKind.NEEDS_SIGN_IN),
)

@Preview(name = "Thread · light", widthDp = 412, heightDp = 900)
@Composable
private fun ThreadPreview() = PreviewTheme { ThreadScreen(ThreadUi(sampleThread(), SamplePeers, notificationAccess = true), ThreadActions()) }

@Preview(name = "Thread · dark", widthDp = 412, heightDp = 900)
@Composable
private fun ThreadDarkPreview() = PreviewTheme(dark = true) { ThreadScreen(ThreadUi(sampleThread(), SamplePeers, notificationAccess = false), ThreadActions()) }

@Preview(name = "Thread · empty", widthDp = 412, heightDp = 900)
@Composable
private fun EmptyPreview() = PreviewTheme { ThreadScreen(ThreadUi(emptyList(), SamplePeers, notificationAccess = true), ThreadActions()) }

@Composable
fun PreviewTheme(dark: Boolean = false, content: @Composable () -> Unit) =
    app.atall.ui.theme.AppTheme(darkTheme = dark) { Box(Modifier.background(AppTheme.colors.bg)) { content() } }

