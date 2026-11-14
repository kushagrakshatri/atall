package app.atall.agents.text

/** A snapshot line classified for one agent. */
data class ParsedLine(
  val id: String,
  val text: String,
  val time: Long,
  val self: Boolean,
  /** False for the plain-text fallback, whose time is the notification's post time (changes on re-post). */
  val timeReliable: Boolean,
)

/** Pure: which agent a Messages notification belongs to, and its lines. */
object Parsing {
  /**
   * The agent this notification is for, or null to leave it alone.
   * Only Google Messages, never group summaries, never group conversations (a reply action there would
   * answer the whole group). The conversation title (or EXTRA_TITLE) must equal an agent's name/alias/number;
   * a 1:1 chat with no title falls back to the sender of its newest non-self line.
   */
  fun route(s: NotificationSnapshot, agents: List<AgentConfig>): AgentConfig? {
    if (s.packageName != Rules.MESSAGES_PACKAGE || s.isGroupSummary || s.isGroupConversation) return null
    val title = s.conversationTitle?.takeIf { it.isNotBlank() } ?: s.title
    Rules.find(agents, title)?.let { return it }
    for (line in s.lines.asReversed()) {
      if (isSelf(line, s.selfName)) continue
      Rules.find(agents, line.sender)?.let { return it }
    }
    return null
  }

  fun isSelf(line: SnapshotLine, selfName: String?): Boolean {
    val sender = line.sender?.trim().orEmpty()
    if (sender.isEmpty()) return true
    return !selfName.isNullOrBlank() && Rules.fold(sender) == Rules.fold(selfName)
  }

  /** Lines in time order (stable for ties), blank ones dropped. */
  fun lines(s: NotificationSnapshot, agent: AgentId): List<ParsedLine> {
    if (s.lines.isNotEmpty()) {
      return s.lines
        .filter { it.text.isNotBlank() }
        .sortedBy { it.time }
        .map { l ->
          val self = isSelf(l, s.selfName)
          ParsedLine(Rules.messageId(agent, l.time, l.text, self), l.text.trim(), l.time, self, timeReliable = true)
        }
    }
    val text = s.text?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return listOf(ParsedLine(Rules.messageId(agent, s.postTime, text), text, s.postTime, self = false, timeReliable = false))
  }
}
