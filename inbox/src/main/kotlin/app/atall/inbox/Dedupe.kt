package app.atall.inbox

/**
 * Decides whether a new message is about the same thing as an existing item. Cheap, deterministic, on device.
 *
 * Same thing when, within [windowMs] of the item's last message and with no conflicting hard fact:
 *  1. they share a strong entity (link, email address, long id such as a tracking or confirmation number) and
 *     at least [minSharedWithEntity] content words; or
 *  2. they name the same date and the same time and share at least one content word; or
 *  3. they name the same date, share >= 2 content words, and the smaller set is >= half covered; or
 *  4. their content words are similar: Jaccard >= [jaccard], or the smaller set is >= [overlap] covered and
 *     they share at least 3 words.
 * Conflicting hard facts veto a match: different amounts, different dates, or different times (both sides must
 * name one). "Dinner Fri 7pm" and "Dinner Sat 7pm" stay two items.
 */
class Deduper(
  val windowMs: Long = 48 * 3600_000L,
  private val jaccard: Double = 0.45,
  private val overlap: Double = 0.7,
  private val minSharedWithEntity: Int = 1,
) {
  data class Match(val item: InboxItem, val score: Double, val why: String)

  fun score(a: Features, b: Features): Pair<Double, String>? {
    if (a.amounts.isNotEmpty() && b.amounts.isNotEmpty() && a.amounts.intersect(b.amounts).isEmpty()) return null
    if (a.dates.isNotEmpty() && b.dates.isNotEmpty() && a.dates.intersect(b.dates).isEmpty()) return null
    if (a.times.isNotEmpty() && b.times.isNotEmpty() && a.times.intersect(b.times).isEmpty()) return null
    if (a.ids.isNotEmpty() && b.ids.isNotEmpty() && a.ids.intersect(b.ids).isEmpty() &&
      a.urls.intersect(b.urls).isEmpty() && a.emails.intersect(b.emails).isEmpty()) return null

    val shared = a.tokens.intersect(b.tokens).size
    val union = (a.tokens + b.tokens).size
    val jac = if (union == 0) 0.0 else shared.toDouble() / union
    val minSize = minOf(a.tokens.size, b.tokens.size)
    val ovl = if (minSize == 0) 0.0 else shared.toDouble() / minSize

    if (a.strongEntities.intersect(b.strongEntities).isNotEmpty() && shared >= minSharedWithEntity) return 1.0 + jac to "same link, address or id"
    if (a.dates.intersect(b.dates).isNotEmpty() && a.times.intersect(b.times).isNotEmpty() && shared >= 1) return 0.9 + jac to "same date and time"
    if (a.amounts.intersect(b.amounts).isNotEmpty() && shared >= 2) return 0.8 + jac to "same amount"
    if (a.dates.intersect(b.dates).isNotEmpty() && shared >= 2 && ovl >= 0.5) return 0.7 + jac to "same day, same subject"
    if (jac >= jaccard) return jac to "similar wording"
    if (ovl >= overlap && shared >= 3) return ovl * 0.9 to "similar wording"
    return null
  }

  /** Best matching item among [candidates] (each with the features of its sources), or null. */
  fun find(f: Features, at: Long, candidates: List<Pair<InboxItem, List<Features>>>): Match? {
    var best: Match? = null
    for ((item, feats) in candidates) {
      if (at - item.lastAt > windowMs || item.firstAt - at > windowMs) continue
      for (g in feats) {
        val (s, why) = score(f, g) ?: continue
        if (best == null || s > best.score) best = Match(item, s, why)
      }
    }
    return best
  }
}
