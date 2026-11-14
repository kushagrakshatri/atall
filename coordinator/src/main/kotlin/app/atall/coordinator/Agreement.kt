package app.atall.coordinator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------------------ UI-ready model (Compose renders this as is)

@Serializable
enum class MapStatus {
  /** Answers still arriving; [AgreementMap.participants] and raw answers fill in, no claims yet. */
  COLLECTING,
  /** Claims compared. May still update if a late answer lands or a challenge resolves. */
  READY,
  /** A challenge on a disputed claim is out. */
  CHALLENGING,
}

@Serializable
enum class ParticipantState { WAITING, ANSWERED, DIDNT_ANSWER, NEEDS_TAP }

@Serializable
data class Participant(val agentId: String, val name: String, val state: ParticipantState, val answeredInMs: Long? = null)

/** How sure the comparison is. Shown as the "how sure" meter. */
@Serializable
enum class VerdictLevel {
  /** Everyone who answered agrees on every shared point. */
  UNANIMOUS,
  /** Disagreements exist, but each has a clear majority side. */
  MAJORITY,
  /** At least one disagreement with no majority. */
  SPLIT,
  /** Fewer than two answers came back. */
  TOO_FEW,
  /** Claims couldn't be extracted; read the raw answers. */
  UNCLEAR,
}

@Serializable
data class Verdict(val level: VerdictLevel, val line: String, val agreeCount: Int, val disputedCount: Int, val uniqueCount: Int)

/** One claim as one or more agents made it. */
@Serializable
data class ClaimCluster(
  val id: String,
  val topic: String,
  /** A representative sentence (the first agent's wording). */
  val statement: String,
  val value: String,
  val agentIds: List<String>,
  val evidence: Map<String, String> = emptyMap(),
)

@Serializable
data class DisputeSide(val value: String, val statement: String, val agentIds: List<String>, val evidence: Map<String, String> = emptyMap())

@Serializable
enum class ChallengeState { NONE, ASKED, RESOLVED }

@Serializable
data class Dispute(
  val id: String,
  val topic: String,
  /** Largest side first. */
  val sides: List<DisputeSide>,
  /** Index into [sides] of a strict majority, or null when split. */
  val majority: Int?,
  val challenge: ChallengeState = ChallengeState.NONE,
  /** After a challenge: "kDot changed to Sunday", "Both kept their answers". */
  val resolution: String? = null,
)

@Serializable
data class RawAnswer(val agentId: String, val name: String, val text: String, val uncertain: Boolean = false, val late: Boolean = false)

/**
 * The @all result. Sections in display order: [verdict] line + meter, [agreed] (collapsed one-liners), [disputed]
 * (sides with agents and evidence, a Challenge button while [Dispute.challenge] is NONE), [unique] ("only one said
 * it"), and [raw] one tap away.
 */
@Serializable
data class AgreementMap(
  val question: String,
  val status: MapStatus,
  val participants: List<Participant>,
  val agreed: List<ClaimCluster> = emptyList(),
  val disputed: List<Dispute> = emptyList(),
  val unique: List<ClaimCluster> = emptyList(),
  val verdict: Verdict? = null,
  val raw: List<RawAnswer> = emptyList(),
)

// ------------------------------------------------------------------ claim extraction + clustering (pure)

@Serializable
data class Claim(val topic: String, val value: String, val statement: String, val evidence: String = "")

@Serializable
data class AgentClaims(val agentId: String, val claims: List<Claim>)

object Agreement {
  /** Structured-output schema for the claims call. Answers are labelled A, B, C… (agent names hidden: less bias). */
  val claimsSchema: JsonObject = Schema.obj(
    "answers" to Schema.array(
      "One entry per answer, in the order given",
      Schema.obj(
        "label" to Schema.string("The answer's label, e.g. A"),
        "claims" to Schema.array(
          "Atomic claims in this answer",
          Schema.obj(
            "topic" to Schema.string("The question this claim answers, 2-6 words, lowercase, shared across answers"),
            "value" to Schema.string("The claim's answer to the topic, normalized and short"),
            "statement" to Schema.string("The claim as one plain sentence"),
            "evidence" to Schema.string("What it cited or reasoned from, or empty"),
          ),
        ),
      ),
    ),
  )

  val challengeSchema: JsonObject = Schema.obj(
    "positions" to Schema.array(
      "One per agent",
      Schema.obj(
        "agent" to Schema.string("The agent's label as given"),
        "keeps" to Schema.boolean("True if it stood by its original answer"),
        "value" to Schema.string("Its final answer to the disputed topic"),
        "reason" to Schema.string("Why, in a few words"),
      ),
    ),
  )

  fun label(i: Int): String = if (i < 26) ('A' + i).toString() else "A$i"

  /** Parses the claims JSON into per-agent claims; [labels] maps "A" -> agentId. Null when unusable. */
  fun parseClaims(raw: String, labels: Map<String, String>): List<AgentClaims>? {
    val o = parseJsonObject(raw) ?: return null
    val answers = o["answers"] as? JsonArray ?: return null
    val out = answers.mapNotNull { el ->
      val a = el as? JsonObject ?: return@mapNotNull null
      val agent = labels[a.str("label")?.trim()?.uppercase()] ?: return@mapNotNull null
      val claims = (a["claims"] as? JsonArray).orEmpty().mapNotNull { c ->
        val co = c as? JsonObject ?: return@mapNotNull null
        val topic = co.str("topic")?.trim().orEmpty(); val value = co.str("value")?.trim().orEmpty()
        if (topic.isEmpty() || value.isEmpty()) null
        else Claim(topic, value, co.str("statement")?.trim()?.ifEmpty { null } ?: "$topic: $value", co.str("evidence")?.trim().orEmpty())
      }
      AgentClaims(agent, claims)
    }
    return out.takeIf { it.isNotEmpty() && it.any { a -> a.claims.isNotEmpty() } }
  }

  private val STOP = setOf("the", "a", "an", "is", "are", "was", "of", "to", "in", "on", "for", "and", "or", "it", "its", "be", "at", "by", "with", "your", "you")

  fun normalize(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9$%. ]"), " ").replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
      .split(Regex("\\s+")).filter { it.isNotBlank() && it !in STOP }.joinToString(" ")

  private fun tokens(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }.map { it.trimEnd('s').ifEmpty { it } }.toSet()

  fun similarity(a: String, b: String): Double {
    val na = normalize(a); val nb = normalize(b)
    if (na == nb) return 1.0
    val nums = Regex("\\d+(?:\\.\\d+)?")
    val ma = nums.findAll(na).map { it.value.toDouble() }.toList(); val mb = nums.findAll(nb).map { it.value.toDouble() }.toList()
    if (ma.isNotEmpty() && mb.isNotEmpty() && ma != mb) return 0.0 // different numbers never match
    val ta = tokens(a); val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    return ta.intersect(tb).size.toDouble() / ta.union(tb).size
  }

  const val TOPIC_MATCH = 0.6
  const val VALUE_MATCH = 0.75

  data class Clusters(val agreed: List<ClaimCluster>, val disputed: List<Dispute>, val unique: List<ClaimCluster>)

  private data class Entry(val agentId: String, val claim: Claim)

  /**
   * Groups claims by topic (fuzzy), then by value within a topic:
   *  - one agent made claims on the topic -> unique;
   *  - several agents, one value -> agreed;
   *  - several agents, several values -> disputed (sides largest first; strict majority marked).
   * An agent counts once per topic (its first claim on it). Agents silent on a topic are neither for nor against.
   */
  fun cluster(answers: List<AgentClaims>): Clusters {
    val topics = mutableListOf<Pair<String, MutableList<Entry>>>()
    for (a in answers) for (c in a.claims) {
      val group = topics.firstOrNull { (t, _) -> similarity(t, c.topic) >= TOPIC_MATCH }
      if (group == null) topics += c.topic to mutableListOf(Entry(a.agentId, c))
      else if (group.second.none { it.agentId == a.agentId }) group.second += Entry(a.agentId, c)
    }
    val agreed = mutableListOf<ClaimCluster>(); val disputed = mutableListOf<Dispute>(); val unique = mutableListOf<ClaimCluster>()
    topics.forEachIndexed { ti, (topic, entries) ->
      val values = mutableListOf<MutableList<Entry>>()
      for (e in entries) {
        val v = values.firstOrNull { g -> similarity(g.first().claim.value, e.claim.value) >= VALUE_MATCH }
        if (v == null) values += mutableListOf(e) else v += e
      }
      val agents = entries.map { it.agentId }.distinct()
      fun clusterOf(es: List<Entry>, id: String) = ClaimCluster(
        id, topic, es.first().claim.statement, es.first().claim.value, es.map { it.agentId },
        es.filter { it.claim.evidence.isNotBlank() }.associate { it.agentId to it.claim.evidence },
      )
      when {
        agents.size == 1 -> unique += clusterOf(entries, "u$ti")
        values.size == 1 -> agreed += clusterOf(entries, "g$ti")
        else -> {
          val sides = values.sortedByDescending { it.size }.map { g ->
            DisputeSide(g.first().claim.value, g.first().claim.statement, g.map { it.agentId },
              g.filter { it.claim.evidence.isNotBlank() }.associate { it.agentId to it.claim.evidence })
          }
          val majority = if (sides[0].agentIds.size * 2 > agents.size) 0 else null
          disputed += Dispute("d$ti", topic, sides, majority)
        }
      }
    }
    return Clusters(agreed, disputed, unique)
  }

  fun level(answered: Int, c: Clusters?): VerdictLevel = when {
    answered < 2 -> VerdictLevel.TOO_FEW
    c == null -> VerdictLevel.UNCLEAR
    c.disputed.isEmpty() && c.agreed.isEmpty() && c.unique.isEmpty() -> VerdictLevel.UNCLEAR
    c.disputed.isEmpty() -> VerdictLevel.UNANIMOUS
    c.disputed.all { it.majority != null } -> VerdictLevel.MAJORITY
    else -> VerdictLevel.SPLIT
  }

  /** A plain verdict line when the model call fails. */
  fun fallbackLine(level: VerdictLevel, c: Clusters?, names: (String) -> String): String = when (level) {
    VerdictLevel.TOO_FEW -> "Only one answer came back, so there's nothing to compare yet."
    VerdictLevel.UNCLEAR -> "I couldn't line these answers up point by point. Read them below."
    VerdictLevel.UNANIMOUS -> "They all agree" + (c?.agreed?.firstOrNull()?.let { ": ${it.statement}" } ?: ".")
    VerdictLevel.MAJORITY -> {
      val d = c!!.disputed.first(); val s = d.sides[d.majority!!]
      "Most agree on ${d.topic}: ${s.value} (${s.agentIds.joinToString(" and ") { names(it) }})."
    }
    VerdictLevel.SPLIT -> "They're split on ${c!!.disputed.first().topic}. Read both sides below."
  }

  /** The comparison as plain text for the verdict call. */
  fun describe(question: String, c: Clusters, names: (String) -> String): String = buildString {
    append("Question: ").append(question).append("\n\n")
    append("Agree:\n"); c.agreed.forEach { append("- ").append(it.statement).append(" (").append(it.agentIds.joinToString { a -> names(a) }).append(")\n") }
    append("\nDisputed:\n"); c.disputed.forEach { d ->
      append("- ").append(d.topic).append(": ")
      append(d.sides.joinToString(" vs ") { s -> "${s.value} (${s.agentIds.joinToString { a -> names(a) }})" })
      d.resolution?.let { append(" — after a check: ").append(it) }
      append("\n")
    }
    append("\nOnly one said it:\n"); c.unique.forEach { append("- ").append(it.statement).append(" (").append(names(it.agentIds.first())).append(")\n") }
  }
}
