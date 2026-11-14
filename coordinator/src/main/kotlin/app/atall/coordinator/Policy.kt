package app.atall.coordinator

/** Prompt files under resources/app/atall/coordinator/prompts, with `{{key}}` substitution. */
object Prompts {
  private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

  fun raw(name: String): String = cache.getOrPut(name) {
    val path = "/app/atall/coordinator/prompts/$name.md"
    Prompts::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
      ?: error("missing prompt resource $path")
  }

  /** Substitutes [vars]. A line that is only an unset or empty placeholder (an optional line like `{{userLine}}`) is dropped. */
  fun render(name: String, vars: Map<String, String> = emptyMap()): String =
    vars.entries.fold(raw(name)) { acc, (k, v) -> acc.replace("{{$k}}", v) }
      .replace(OPTIONAL_LINE, "")
      .replace(Regex("\\n{3,}"), "\n\n")
      .trim()

  /** A line holding only a placeholder no var filled. */
  private val OPTIONAL_LINE = Regex("(?m)^[ \\t]*\\{\\{\\w+\\}\\}[ \\t]*\\n?")

  /** The line naming the user in Juno's prompt; empty when the name is unknown. */
  fun userLine(name: String): String {
    val n = name.trim().replace(Regex("\\s+"), " ").take(40)
    return if (n.isEmpty()) "" else "The user's name is $n. Call them $n, never by a longer or more formal name, and use $n (not \"the user\") in messages you write for agents."
  }

  /** The roster block in Juno's system prompt: one line per active peer with its tags and how it's reached. */
  fun rosterBlock(roster: Roster): String {
    val lines = roster.activePeers.map { p ->
      val how = when (p.kind) { AgentKind.TEXT -> "texts; replies can take minutes"; AgentKind.API -> "API; usually quick"; AgentKind.MAIN -> "" }
      val tags = if (p.goodAt.isEmpty()) "general help" else p.goodAt.joinToString(", ")
      "- ${p.name}: good at $tags ($how)"
    }
    return if (lines.isEmpty()) "- (no other agents are available right now; answer yourself)" else lines.joinToString("\n")
  }
}

/**
 * The hard money-and-accounts rule (design.md §6): any ask that would buy, book, pay, order, transfer, sign up, cancel
 * or change an account, or that confirms an agent's offer to do so ("yes, go ahead"), waits for the user's explicit
 * yes. The model's own `money_or_account` flag is honoured too; the code check is the backstop when the model forgets.
 * Negated mentions ("don't book anything yet") don't gate.
 */
object ApprovalGate {
  data class Verdict(val gated: Boolean, val reason: String?)

  private val ACTIONS = Regex(
    "\\b(buy|buying|purchase|purchasing|order(?:ing)?|pay(?:ing|ment)?|check ?out|checkout|book(?:ing)?|reserve|reservation|" +
      "subscribe|unsubscribe|cancel(?:ling|ing)?|refund|transfer|wire|venmo|zelle|paypal|send (?:money|payment|\\$)|tip|donate|" +
      "sign (?:me |us )?up|create (?:an |my )?account|delete (?:my |the )?account|close (?:my |the )?account|change (?:my |the )?password|" +
      "reset (?:my |the )?password|withdraw|deposit|sell|trade|upgrade (?:my |the )?plan|downgrade (?:my |the )?plan)\\b",
    RegexOption.IGNORE_CASE,
  )
  private val MONEY = Regex("[$€£₹]\\s?\\d")
  private val NEGATION = Regex("\\b(don'?t|do not|dont|never|not|no need to|without|before you|n't)\\b[^.!?\\n]{0,40}$", RegexOption.IGNORE_CASE)
  private val NOUN_CONTEXT = Regex("\\b(ideas?|options?|availability|available|which|what|where|when|how much|price|prices|cost|compare|find|look ?up|check|search)\\b", RegexOption.IGNORE_CASE)
  private val CONFIRM = Regex("^\\s*(yes|yep|yeah|sure|ok(?:ay)?|confirm(?:ed)?|go ahead|do it|approved?|sounds good)\\b[\\s\\S]{0,40}$", RegexOption.IGNORE_CASE)

  fun check(text: String, modelFlag: Boolean): Verdict {
    if (modelFlag) return Verdict(true, "flagged as a money or account action")
    if (CONFIRM.containsMatchIn(text)) return Verdict(true, "confirms something on the user's behalf")
    for (m in ACTIONS.findAll(text)) {
      val before = text.substring(0, m.range.first)
      val clauseStart = maxOf(before.lastIndexOf('.'), before.lastIndexOf('!'), before.lastIndexOf('?'), before.lastIndexOf('\n')) + 1
      val clause = before.substring(clauseStart)
      if (NEGATION.containsMatchIn(clause)) continue
      // "find booking options", "check availability to reserve": a lookup, not an action, unless money is named.
      val sentence = text.substring(clauseStart, (text.indexOfAny(charArrayOf('.', '!', '?', '\n'), m.range.last).takeIf { it >= 0 } ?: text.length))
      if (NOUN_CONTEXT.containsMatchIn(sentence) && !MONEY.containsMatchIn(sentence) && !imperativeAt(clause)) continue
      return Verdict(true, "asks to ${m.value.lowercase()}")
    }
    return Verdict(false, null)
  }

  /** Every money/account action verb in [text]. */
  fun actionVerbs(text: String): List<MatchResult> = ACTIONS.findAll(text).toList()

  /** The clause before an action verb negates it ("don't book", "never pay"). */
  fun negated(clauseBefore: String): Boolean {
    val start = maxOf(clauseBefore.lastIndexOf('.'), clauseBefore.lastIndexOf('!'), clauseBefore.lastIndexOf('?'), clauseBefore.lastIndexOf('\n')) + 1
    return NEGATION.containsMatchIn(clauseBefore.substring(start))
  }

  /** True when the action verb starts its clause ("Book the 7:30", "please pay"), i.e. it is a command. */
  private fun imperativeAt(clauseBefore: String): Boolean =
    clauseBefore.trim().lowercase().let { it.isEmpty() || it == "please" || it.endsWith(" and") || it.endsWith(" then") || it.endsWith("go ahead and") }
}

/**
 * "Good at" tag matching for planning hints. Cheap and deterministic: a tag matches when one of its words (4+ chars,
 * prefix-matched to cope with plurals and -ing) appears in the message. The result is a hint for the model, never a
 * silent route (innovation.md #8: suggest, don't auto-route).
 */
object Planner {
  data class Fit(val agent: AgentProfile, val matched: List<String>)

  fun fits(text: String, roster: Roster): List<Fit> {
    val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
    return roster.activePeers.mapNotNull { p ->
      val matched = p.goodAt.filter { tag ->
        tag.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.any { t ->
          val stem = t.take(maxOf(4, t.length - 2))
          words.any { w -> w.startsWith(stem) || t.startsWith(w.take(maxOf(4, w.length - 2))) && w.length >= 4 }
        }
      }
      if (matched.isEmpty()) null else Fit(p, matched)
    }.sortedByDescending { it.matched.size }
  }

  fun hint(text: String, roster: Roster, mustAsk: List<AgentProfile>): String = buildString {
    val f = fits(text, roster)
    if (f.isNotEmpty()) append("Likely fits for this message: ").append(f.joinToString("; ") { "${it.agent.name} (${it.matched.joinToString(", ")})" }).append(".")
    else append("No agent's tags clearly match this message; answer yourself unless you need someone's accounts.")
    if (mustAsk.isNotEmpty()) append("\nThe user named ").append(mustAsk.joinToString(" and ") { it.name }).append(": ask each of them before replying.")
  }
}
