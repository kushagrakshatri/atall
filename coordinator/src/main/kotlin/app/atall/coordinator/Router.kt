package app.atall.coordinator

/** One `@name` found in a message. [agent] is null for `@all` and for names that match nobody. */
data class Mention(val raw: String, val start: Int, val end: Int, val agent: AgentProfile?, val isAll: Boolean) {
  val handle: String get() = raw.removePrefix("@")
  val isUnknown: Boolean get() = agent == null && !isAll
}

/** Where a message from the composer goes. [unknown] lists `@names` that match no agent (UI: "No agent called …"). */
sealed interface Route {
  val unknown: List<String>

  /** One peer, addressed directly. Bypasses Juno and the model; [text] is what gets sent (leading mention removed). */
  data class Direct(val agent: AgentProfile, val text: String, override val unknown: List<String> = emptyList()) : Route

  /** Juno coordinates. [mustAsk] are peers the user named alongside Juno or each other: hard constraints for the plan. */
  data class ToJuno(val text: String, val mustAsk: List<AgentProfile> = emptyList(), override val unknown: List<String> = emptyList()) : Route

  /** `@all`: the same question, blind, to every active peer (and Juno). [text] has the `@all` removed. */
  data class FanOut(val text: String, val agents: List<AgentProfile>, override val unknown: List<String> = emptyList()) : Route

  /** Nothing to send: empty after removing mentions, or the only addressee is paused. [reason]: "empty" | "paused". */
  data class Nothing(val reason: String, val agent: AgentProfile? = null, override val unknown: List<String> = emptyList()) : Route
}

/**
 * Pure mention parsing and routing. Rules:
 *  - `@name` must start the text or follow a non-word, non-`@` char, so emails (kush@gmail.com) never match.
 *  - Matching is case-insensitive on name and aliases. `@all` and `@everyone` mean everyone.
 *  - No mention, only `@Juno`, or only unknown names -> Juno.
 *  - `@all` anywhere -> fan-out (other mentions are absorbed; everyone is asked anyway).
 *  - Exactly one peer and no `@Juno` -> direct to that peer.
 *  - Several peers, or Juno plus peers -> Juno with those peers as must-ask constraints (story 6).
 */
object Router {
  private val MENTION = Regex("(?<![\\w@.])@([A-Za-z][A-Za-z0-9_.\\-]*)")
  private val ALL = setOf("all", "everyone")

  fun mentions(text: String, roster: Roster): List<Mention> = MENTION.findAll(text).map { m ->
    // A trailing "." or "-" is punctuation ("ask @Hark."), not part of the name.
    val handle = m.groupValues[1].trimEnd('.', '-')
    val raw = "@$handle"
    val start = m.range.first
    val isAll = handle.lowercase() in ALL && roster.resolve(handle) == null
    Mention(raw, start, start + raw.length, if (isAll) null else roster.resolve(handle), isAll)
  }.toList()

  fun route(text: String, roster: Roster): Route {
    val ms = mentions(text, roster)
    val unknown = ms.filter { it.isUnknown }.map { it.handle }.distinct()
    val known = ms.filter { it.agent != null }
    val mainMentioned = known.any { it.agent!!.kind == AgentKind.MAIN }
    val peers = known.mapNotNull { it.agent }.filter { it.kind != AgentKind.MAIN }.distinctBy { it.id }

    if (ms.any { it.isAll }) {
      val body = clean(text, ms.filter { it.isAll || it.agent?.kind == AgentKind.MAIN }, leadingOnly = false)
      if (body.isBlank()) return Route.Nothing("empty", unknown = unknown)
      return Route.FanOut(body, roster.activePeers, unknown)
    }
    if (peers.size == 1 && !mainMentioned) {
      val p = peers.single()
      val body = clean(text, known.filter { it.agent?.id == p.id }, leadingOnly = true)
      if (body.isBlank()) return Route.Nothing("empty", p, unknown)
      if (p.paused) return Route.Nothing("paused", p, unknown)
      return Route.Direct(p, body, unknown)
    }
    if (text.isBlank() || clean(text, known, leadingOnly = false).isBlank()) return Route.Nothing("empty", unknown = unknown)
    return Route.ToJuno(text.trim(), peers.filter { !it.paused }, unknown)
  }

  /**
   * Removes mentions for the agent-bound text: a leading mention (and the punctuation after it) is dropped; a mention
   * mid-sentence keeps the name without the "@" ("what does @Hark think" -> "what does Hark think") when [leadingOnly].
   */
  internal fun clean(text: String, remove: List<Mention>, leadingOnly: Boolean): String {
    if (remove.isEmpty()) return text.trim()
    val sb = StringBuilder()
    var i = 0
    for (m in remove.sortedBy { it.start }) {
      sb.append(text, i, m.start)
      val leading = sb.isBlank()
      if (!leadingOnly || leading) {
        // drop it; also eat a following ":" or "," right after a leading mention
        var j = m.end
        if (leading) while (j < text.length && (text[j] == ':' || text[j] == ',')) j++
        i = j
      } else {
        sb.append(m.agent?.name ?: m.handle)
        i = m.end
      }
    }
    sb.append(text.substring(i))
    return sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
  }
}
