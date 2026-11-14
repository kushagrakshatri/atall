package app.atall.agents.text

import java.util.Locale

/**
 * Pure correlation of one incoming agent line to the agent's outstanding requests.
 *
 * Order of rules:
 *  1. TAG: the line contains the tag of an open request -> that request, HIGH.
 *  2. Candidates are requests sent no later than the line (allowing [Tuning.clockSkewMs]) and still live:
 *     unanswered ones until their reply deadline; of the answered ones, only the one replied to most recently,
 *     until [Tuning.continuationMs] after its last reply.
 *  3. Unanswered candidates take the line oldest first (agents answer in order). If the oldest unanswered was
 *     sent less than [Tuning.minReplyLatencyMs] before the line and an answered request is still in its
 *     continuation, the line is a further part of that answer instead (too quick to be a reply to the new one).
 *  4. Otherwise an answered request in continuation takes it as the next part.
 *  5. Nothing -> uncorrelated (an unsolicited message).
 * Confidence: HIGH only with one candidate overall and no user interjection; MEDIUM for oldest-first or
 * continuation choices among several; LOW when the user typed into the thread after the request (the agent may
 * be answering the user, not us).
 */
object Correlator {
  data class Tuning(
    val clockSkewMs: Long = 5_000,
    val continuationMs: Long = 90_000,
    val minReplyLatencyMs: Long = 2_000,
  )

  data class Decision(val request: OutstandingRequest, val correlation: Correlation)

  fun decide(
    open: List<OutstandingRequest>,
    text: String,
    time: Long,
    lastUserLineAt: Long,
    tuning: Tuning = Tuning(),
  ): Decision? {
    val reqs = open.sortedBy { it.sentAt }
    // 1. tag
    reqs.firstOrNull { it.tag != null && containsTag(text, it.tag) }?.let { r ->
      return Decision(r, Correlation(r.correlationId, Confidence.HIGH, CorrelationReason.TAG, r.replies, interjected(r, lastUserLineAt, time)))
    }
    // 2. candidates
    val unanswered = reqs.filter { it.replies == 0 && it.sentAt <= time + tuning.clockSkewMs && time <= it.replyDeadline }
    // The live answered thread is the one replied to most recently (a later answered request supersedes).
    val continuing = reqs
      .filter { it.replies > 0 && time >= it.sentAt - tuning.clockSkewMs && time <= it.lastReplyAt + tuning.continuationMs }
      .maxByOrNull { it.lastReplyAt }
    val candidates = unanswered.size + (if (continuing != null) 1 else 0)
    if (candidates == 0) return null

    val oldest = unanswered.firstOrNull()
    // 3. too-quick guard
    if (oldest != null && continuing != null && time - oldest.sentAt < tuning.minReplyLatencyMs) {
      return cont(continuing, time, lastUserLineAt)
    }
    if (oldest != null) {
      val single = candidates == 1
      val reason = if (single) CorrelationReason.ONLY_OPEN_REQUEST else CorrelationReason.OLDEST_UNANSWERED
      val inter = interjected(oldest, lastUserLineAt, time)
      val conf = when { inter -> Confidence.LOW; single -> Confidence.HIGH; else -> Confidence.MEDIUM }
      return Decision(oldest, Correlation(oldest.correlationId, conf, reason, 0, inter))
    }
    // 4. continuation
    return cont(continuing!!, time, lastUserLineAt)
  }

  private fun cont(r: OutstandingRequest, time: Long, lastUserLineAt: Long): Decision {
    val inter = interjected(r, lastUserLineAt, time)
    return Decision(r, Correlation(r.correlationId, if (inter) Confidence.LOW else Confidence.MEDIUM, CorrelationReason.CONTINUATION, r.replies, inter))
  }

  private fun interjected(r: OutstandingRequest, lastUserLineAt: Long, time: Long) =
    lastUserLineAt > r.sentAt && lastUserLineAt <= time

  /** The request after a reply was attributed. */
  fun attributed(r: OutstandingRequest, time: Long): OutstandingRequest =
    r.copy(state = RequestState.ANSWERED, replies = r.replies + 1, lastReplyAt = maxOf(r.lastReplyAt, time))

  /** Whether the request is finished at [now], and how. Null while it is still live. */
  fun closure(r: OutstandingRequest, now: Long, tuning: Tuning = Tuning()): RequestClosed.Outcome? = when {
    r.replies > 0 -> if (now > r.lastReplyAt + tuning.continuationMs) RequestClosed.Outcome.COMPLETED else null
    now > r.replyDeadline -> if (r.threadOpenedAt in r.sentAt..r.replyDeadline && r.threadOpenedAt > 0) RequestClosed.Outcome.LIKELY_MISSED else RequestClosed.Outcome.EXPIRED
    else -> null
  }

  // ---------------------------------------------------------------- tags

  private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alikes (0/o, 1/l/i)

  /** A 4-character tag not used by any of [taken]. */
  fun newTag(taken: Set<String>, random: java.util.Random): String {
    while (true) {
      val t = (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
      if (t !in taken) return t
    }
  }

  fun withTag(text: String, tag: String) = "$text\n(ref #$tag)"

  /** "#k3f9" or a bare "k3f9" as a whole word, case-insensitive. */
  fun containsTag(text: String, tag: String): Boolean =
    Regex("(^|[^a-z0-9])#?${Regex.escape(tag.lowercase(Locale.ROOT))}([^a-z0-9]|$)").containsMatchIn(text.lowercase(Locale.ROOT))
}
