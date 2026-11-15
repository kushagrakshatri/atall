package app.atall.coordinator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How structured claims (connector results) are shown to the model: compact JSON in a wait output, lines in a prompt. */
object Claims {
  /** At most this many claims per answer go to the model. */
  const val MAX = 12

  fun json(claims: List<SourcedClaim>): JsonArray = buildJsonArray {
    claims.take(MAX).forEach { c ->
      add(buildJsonObject {
        put("text", c.text.take(400))
        c.confidence?.let { put("confidence", it) }
        if (c.sources.isNotEmpty()) put("sources", JsonArray(c.sources.take(3).map { JsonPrimitive(it.url) }))
      })
    }
  }

  /** "- Rooh has a table for 4 at 7:30 (confidence 0.8; sources: https://…)". */
  fun lines(claims: List<SourcedClaim>): String = claims.take(MAX).joinToString("\n") { c ->
    val extra = listOfNotNull(
      c.confidence?.let { "confidence " + String.format(java.util.Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') },
      c.sources.takeIf { it.isNotEmpty() }?.let { s -> "sources: " + s.take(3).joinToString(", ") { it.url } },
    )
    "- " + c.text.trim().take(400) + if (extra.isEmpty()) "" else " (" + extra.joinToString("; ") + ")"
  }
}
