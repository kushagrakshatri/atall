package app.atall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atall.coordinator.AnswerCard
import app.atall.coordinator.CallSheet
import app.atall.coordinator.PresenceAgent
import app.atall.coordinator.PresenceState
import app.atall.coordinator.PresenceView
import app.atall.core.AgentLogo
import app.atall.core.CardText
import app.atall.core.Main
import app.atall.ui.theme.AgentHue
import app.atall.ui.theme.AppTheme
import kotlinx.serialization.json.Json

private val CardJson = Json { ignoreUnknownKeys = true }

fun parseCard(json: String?): AnswerCard? = json?.let { runCatching { CardJson.decodeFromString(AnswerCard.serializer(), it) }.getOrNull() }

/** What the card needs from the thread: the agents' names and hues, and their run's avatar row (state + each answer). */
internal data class CardAgents(val peers: Map<String, PeerUi>, val presence: PresenceView?) {
    fun name(id: String): String = peers[id]?.name ?: presence?.agents?.firstOrNull { it.agentId == id }?.name ?: "Agent"
    fun hue(id: String): AgentHue = peers[id]?.hue ?: AgentHue.Slate
    fun logo(id: String): AgentLogo = peers[id]?.logo ?: AgentLogo.NONE
}

/** The tiles at the end of a line: a little smaller than the text's cap height plus padding, so they sit in the line. */
private val CreditSize = 18.sp
private val CreditGap = 3.sp
/** The space between a line's last word and its first tile (part of the one inline object). */
private val WordGap = 5.sp
private const val TAIL = "tail"

/**
 * Juno's combined answer (mocks A-D): the decision first (the lead line a touch larger, medium weight), each line ending
 * in the credit tiles of the agents it came from, glued to its last word; while agents are still out the text is muted
 * and the row of agent tiles shows who's working (an accent ring) and who's pending (faded, greyscale). One disagreement
 * line opens the Your call sheet; a late change is highlighted in place with "Changed just now"; action chips last.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AnswerCardView(card: AnswerCard, agents: CardAgents, onChoice: (String) -> Unit, now: () -> Long = System::currentTimeMillis) {
    val c = AppTheme.colors
    var take by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    val ranges = remember(card.previous, card.plain) { CardText.changedRanges(card.previous?.let { p -> p.lines().joinToString("\n") { CardText.plainLine(it) } }, card.lines.joinToString("\n") { CardText.plainLine(it.text) }) }
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(Modifier.fillMaxWidth()) {
        var offset = 0
        card.lines.forEachIndexed { i, line ->
            val text = CardText.plainLine(line.text)
            val lineRanges = CardText.rangesInLine(ranges, offset, text.length)
            offset += text.length + 1
            val lead = i == 0
            val style = if (lead) AppTheme.type.body.copy(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight(500), letterSpacing = (-0.2 / 18).em) else AppTheme.type.body
            val ink = if (card.thinking) c.ink2 else c.ink
            val pieces = remember(text, line.credits) { CardText.glued(text, line.credits) }
            val tail = pieces.lastOrNull() as? CardText.Piece.Tail
            val headLen = (pieces.firstOrNull() as? CardText.Piece.Words)?.text?.length ?: 0
            val annotated = remember(pieces, lineRanges, c.highlight) { gluedText(pieces, lineRanges, c.highlight) }
            val inline = if (tail == null) emptyMap() else {
                // The last word is measured in the line's own style, so the inline object is exactly word + gap + tiles wide.
                val word = remember(tail, lineRanges, c.highlight) { highlighted(tail.word, CardText.rangesInLine(lineRanges, headLen, tail.word.length), c.highlight) }
                val m = measurer.measure(word, style, softWrap = false, maxLines = 1)
                val n = tail.agentIds.size
                val tilesSp = CreditSize.value * n + CreditGap.value * (n - 1) + WordGap.value
                val wSp = with(density) { m.size.width.toSp() }.value + tilesSp
                val hSp = with(density) { m.size.height.toSp() }
                mapOf(TAIL to InlineTextContent(Placeholder(wSp.sp, hSp, PlaceholderVerticalAlign.TextCenter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(word, style = style, color = ink, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(with(density) { WordGap.toDp() }))
                        Row(horizontalArrangement = Arrangement.spacedBy(with(density) { CreditGap.toDp() })) {
                            tail.agentIds.forEach { id ->
                                AgentTile(agents.logo(id), agents.hue(id), with(density) { CreditSize.toDp() },
                                    Modifier.clip(RoundedCornerShape(percent = 30)).clickable(role = Role.Button) { take = id }
                                        .semantics { contentDescription = "From ${agents.name(id)}. Opens its answer." })
                            }
                        }
                    }
                })
            }
            Text(annotated, Modifier.padding(top = if (i == 0) 0.dp else 6.dp), style = style, color = ink, inlineContent = inline)
        }
        card.dissent?.let { d ->
            DissentRow(agents, d.agentId, card.dissentName ?: agents.name(d.agentId), d.line) { if (card.sheet != null) sheet = true else take = d.agentId }
        }
        card.changedAt?.let { at ->
            var tick by remember { mutableLongStateOf(now()) }
            LaunchedEffect(at) { while (true) { kotlinx.coroutines.delay(30_000); tick = now() } }
            Text(CardText.changedLabel(at, tick), Modifier.padding(top = 10.dp), style = AppTheme.type.caption, color = c.ink3)
        }
        agents.presence?.agents?.takeIf { it.isNotEmpty() }?.let { row ->
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { a ->
                    AgentTile(agents.logo(a.agentId), agents.hue(a.agentId), 20.dp,
                        Modifier.clip(RoundedCornerShape(percent = 34)).clickable(role = Role.Button) { take = a.agentId }
                            .semantics { contentDescription = a.name + ": " + stateWord(a.state) },
                        state = tileState(a.state))
                }
            }
        }
        if (card.actions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.actions.forEachIndexed { i, a -> ActionChip(a, primary = i == 0) { onChoice(a) } }
            }
        }
    }
    take?.let { id ->
        val a = agents.presence?.agents?.firstOrNull { it.agentId == id }
        val sheetTake = card.sheet?.takes?.firstOrNull { it.agentId == id }
        AgentTakeSheet(agents, id, a, sheetTake?.text) { take = null }
    }
    if (sheet) card.sheet?.let { s -> YourCallSheet(s, agents, onPick = { sheet = false; onChoice(it) }, onDismiss = { sheet = false }) }
}

/** One line: its words with the changed spans washed, then its last word and tiles as one inline object. */
internal fun gluedText(pieces: List<CardText.Piece>, changed: List<IntRange>, highlight: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (p in pieces) when (p) {
        is CardText.Piece.Words -> append(highlighted(p.text, CardText.rangesInLine(changed, 0, p.text.length), highlight))
        is CardText.Piece.Tail -> appendInlineContent(TAIL, p.word)
    }
}

/** [text] with [ranges] (already relative to it) on the highlight wash. */
internal fun highlighted(text: String, ranges: List<IntRange>, highlight: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { r -> if (r.first >= 0 && r.last < text.length) addStyle(SpanStyle(background = highlight), r.first, r.last + 1) }
}

private fun tileState(s: PresenceState): TileState = when (s) {
    PresenceState.WORKING -> TileState.WORKING
    PresenceState.LATER, PresenceState.NO_ANSWER -> TileState.WAITING
    PresenceState.NEEDS_YOU -> TileState.NEEDS_YOU
    PresenceState.IN, PresenceState.AGREED -> TileState.NORMAL
}

private fun stateWord(s: PresenceState) = when (s) {
    PresenceState.WORKING -> "working on it"
    PresenceState.LATER -> "will add to this later"
    PresenceState.NO_ANSWER -> "no answer yet"
    PresenceState.NEEDS_YOU -> "needs you"
    PresenceState.IN, PresenceState.AGREED -> "in. Opens its answer."
}

/** "<Agent>: <one line>" on a soft tinted row with a chevron. Opens Your call. */
@Composable
private fun DissentRow(agents: CardAgents, agentId: String, name: String, line: String, onOpen: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sunken)
            .clickable(role = Role.Button, onClick = onOpen).padding(start = 12.dp, end = 10.dp, top = 11.dp, bottom = 11.dp)
            .semantics { contentDescription = "$name: $line. Opens your call." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentTile(agents.logo(agentId), agents.hue(agentId), 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight(600), color = c.ink)) { append("$name: ") }
                withStyle(SpanStyle(color = c.ink2)) { append(line) }
            },
            Modifier.weight(1f), style = AppTheme.type.body2.copy(fontSize = 15.sp, lineHeight = 21.sp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, c.ink3, Modifier.rotate(-90f), size = 18.dp)
    }
}

/** Under the answer: the first next step filled, the second outlined. Tapping sends it as Kush's own message. */
@Composable
internal fun ActionChip(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (primary) c.ink else c.bg)
            .then(if (primary) Modifier else Modifier.hairlineBorder(CircleShape))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppTheme.type.label.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(600)), color = if (primary) c.onInk else c.ink) }
}

/** Your call (mock D): the question, each agent's take with its mark and name (plain ink), the if/then options, and "Let Juno pick". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YourCallSheet(s: CallSheet, agents: CardAgents, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        YourCallContent(s, agents, onPick)
    }
}

@Composable
internal fun YourCallContent(s: CallSheet, agents: CardAgents, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
        Text(s.title, style = AppTheme.type.title, color = c.ink)
        if (s.subtitle.isNotBlank()) Text(s.subtitle, Modifier.padding(top = 4.dp), style = AppTheme.type.body2, color = c.ink3)
        Spacer(Modifier.height(16.dp))
        s.takes.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(c.hairline))
            Row(Modifier.fillMaxWidth()) {
                AgentTile(agents.logo(t.agentId), agents.hue(t.agentId), 28.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = AppTheme.type.sender.copy(fontSize = 14.sp), color = c.ink)
                    Text(t.text, Modifier.padding(top = 2.dp), style = AppTheme.type.body, color = c.ink)
                }
            }
        }
        if (s.options.isNotEmpty()) {
            Text("Your call", Modifier.padding(top = 22.dp, bottom = 10.dp), style = AppTheme.type.label, color = c.ink3)
            s.options.forEach { o ->
                Row(
                    Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).hairlineBorder(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { onPick(o.pick) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        o.condition?.let { Text(it, style = AppTheme.type.body2, color = c.ink3) }
                        Text(o.pick, style = AppTheme.type.bodyStrong.copy(fontSize = 17.sp), color = c.ink)
                    }
                    Icon(Glyph.Chevron, c.ink3, Modifier.rotate(-90f), size = 18.dp)
                }
            }
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button) { onPick(LET_JUNO_PICK) },
            contentAlignment = Alignment.Center) {
            Text("Let ${Main.NAME} pick", style = AppTheme.type.button, color = c.ink, textAlign = TextAlign.Center)
        }
    }
}

/** What "Let Juno pick" sends as Kush's message: Juno answers it in the thread with its pick. */
const val LET_JUNO_PICK = "You pick."

/** One agent's take, one tap from its tile: its mark, name and answer (without its self-filled tail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTakeSheet(agents: CardAgents, id: String, a: PresenceAgent?, fallback: String?, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentTile(agents.logo(id), agents.hue(id), 32.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(agents.name(id), style = AppTheme.type.headline, color = c.ink)
                    val note = when (a?.state) {
                        PresenceState.WORKING -> "Working on it"
                        PresenceState.LATER -> "Will add to this later"
                        PresenceState.NO_ANSWER -> "No answer yet"
                        PresenceState.NEEDS_YOU -> "Needs you"
                        else -> null
                    }
                    note?.let { Text(it, style = AppTheme.type.caption, color = c.ink3) }
                }
            }
            val text = a?.text ?: fallback
            if (text != null) {
                val shown = remember(text) { markdownText(text, c.sunken) }
                Text(shown, Modifier.padding(top = 14.dp), style = AppTheme.type.body, color = c.ink)
            }
        }
    }
}

