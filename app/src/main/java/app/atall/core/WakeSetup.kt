package app.atall.core

import app.atall.coordinator.adapters.WakeConfig
import app.atall.coordinator.adapters.WakeDefaults
import app.atall.coordinator.adapters.WakeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale

/**
 * C3 wake-ups, the app's pure parts: what each path is called in Settings, where a tap opens for each agent, the
 * one-time setup line Kush gives each agent, and the saved settings. Android glue lives in app.atall.Wake.
 */
object WakeSetup {
  private const val MIN = 60_000L

  /** Settings → Agents → the agent → Wake-up: one short label per path. */
  fun label(kind: WakeKind): String = when (kind) {
    WakeKind.TEXT -> "Text"
    WakeKind.FULL_TEXT -> "Full text"
    WakeKind.WEBHOOK -> "Webhook"
    WakeKind.EVENTS -> "ChatGPT event"
    WakeKind.TAP -> "Tap"
    WakeKind.SCHEDULE -> "Its schedule"
    WakeKind.OFF -> "Off"
  }

  /** The caption under the picker, for the chosen path. */
  fun caption(relayId: String, cfg: WakeConfig): String = when (cfg.kind) {
    WakeKind.TEXT -> "Texts \"Atall #ref\" when there's a task."
    WakeKind.FULL_TEXT -> FULL_TEXT_CAPTION
    WakeKind.WEBHOOK -> "Starts its routine when there's a task."
    WakeKind.EVENTS -> "ChatGPT gets an event when there's a task."
    WakeKind.TAP -> "A notification opens ${appName(relayId)} ready to check."
    WakeKind.SCHEDULE -> "Checks on its own about every ${minutes(cfg.windowMs ?: (180 * MIN))}."
    WakeKind.OFF -> "Atall won't wake it."
  }

  const val FULL_TEXT_CAPTION = "Texts the whole ask; reads the reply."

  /** Whether "Copy setup" applies: Full text needs no instruction (the ask is the text), so the chip is hidden. */
  fun needsSetup(cfg: WakeConfig): Boolean = cfg.kind != WakeKind.FULL_TEXT

  private fun minutes(ms: Long): String = (ms / MIN).let { if (it >= 120 && it % 60 == 0L) "${it / 60} hours" else "$it min" }

  fun appName(relayId: String): String = when (relayId.lowercase(Locale.ROOT)) {
    "duet", "claude" -> "Claude"
    "grok" -> "Grok"
    "muse" -> "Muse"
    "kdot" -> "ChatGPT"
    "hark" -> "Hark"
    else -> RelayCopy.agentName(relayId)
  }

  /**
   * The one-time instruction Kush gives the agent so a wake-up works ("Copy setup" in Settings). Plain words; each names
   * the Atall tools so the agent knows what to call.
   */
  fun setupPrompt(relayId: String, cfg: WakeConfig): String {
    val id = relayId.lowercase(Locale.ROOT)
    return when {
      cfg.kind == WakeKind.FULL_TEXT -> "Nothing to set up. Atall texts ${appName(relayId)} the whole ask and reads the reply."
      cfg.kind == WakeKind.WEBHOOK ->
        "Create a routine called \"Atall inbox\" with a webhook trigger. Instruction: call atall_inbox on the Atall connector, " +
          "then for each open task call atall_get_task, atall_claim, do it, and post with atall_post_result. Never spend money, book, " +
          "or change an account: use atall_request_approval and wait. Then show me the webhook URL and key."
      cfg.kind == WakeKind.EVENTS ->
        "Monitor Atall for task.assigned events for you. When one arrives, call atall_get_task with its task_id, atall_claim, do the task, " +
          "and post with atall_post_result. Never spend money, book, or change an account: use atall_request_approval and wait."
      cfg.kind == WakeKind.TEXT ->
        "When I text you \"Atall #\" and a code, check your Atall connector: call atall_inbox, then atall_get_task for that ref, " +
          "atall_claim, do it, and post with atall_post_result. Don't reply by text unless you can't reach Atall."
      cfg.kind == WakeKind.SCHEDULE && id == "muse" ->
        "Every ${(cfg.windowMs ?: 15 * MIN) / MIN} minutes, call atall_inbox on the Atall connector. For each open task, call atall_get_task, " +
          "atall_claim, do it, and post with atall_post_result. Stay quiet when there's nothing new. Never spend money or book: use atall_request_approval."
      cfg.kind == WakeKind.SCHEDULE ->
        "Treat new tasks in my Atall inbox as needing attention: on each check, call atall_inbox, and for each open task call atall_get_task, " +
          "atall_claim, do it, and post with atall_post_result. Never spend money or book: use atall_request_approval."
      else ->
        "When I send \"Check your Atall inbox for #\" and a code, call atall_inbox, then atall_get_task for that ref, atall_claim, do it, " +
          "and post with atall_post_result."
    }
  }

  /** Where a tap opens for this agent. [prompt] is filled in (URL-encoded) where the app or site supports it. */
  data class TapTarget(
    /** A link to open (the app handles it when installed and verified, else the browser). Null: share sheet. */
    val url: String?,
    /** Preferred app package for [url] or the share, when installed. */
    val pkg: String?,
    /** The text for the share sheet / clipboard. */
    val text: String,
  )

  /**
   * Claude: claude.ai/new?q= (documented for the desktop's claude:// scheme; on Android the https link opens the
   * Claude app when it handles claude.ai links, else the browser; assumed). Grok: grok.com/?q= (sends at once).
   * Muse: its WhatsApp chat when Kush saved Muse's WhatsApp number (wa.me/<n>?text=), else the share sheet.
   */
  fun tapTarget(relayId: String, prompt: String, museWhatsApp: String? = null): TapTarget {
    val q = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
    return when (relayId.lowercase(Locale.ROOT)) {
      "duet", "claude" -> TapTarget("https://claude.ai/new?q=$q", "com.anthropic.claude", prompt)
      "grok" -> TapTarget("https://grok.com/?q=$q", "ai.x.grok", prompt)
      "kdot" -> TapTarget("https://chatgpt.com/?q=$q", "com.openai.chatgpt", prompt)
      "muse" -> {
        val n = museWhatsApp?.filter { it.isDigit() }.orEmpty()
        if (n.length >= 8) TapTarget("https://wa.me/$n?text=$q", "com.whatsapp", prompt) else TapTarget(null, null, prompt)
      }
      else -> TapTarget(null, null, prompt)
    }
  }

  /** Saved per agent (Settings). Secrets (Grok's webhook key) are kept apart, encrypted. */
  @Serializable
  data class Saved(
    val configs: Map<String, WakeConfig> = emptyMap(),
    val webhookUrls: Map<String, String> = emptyMap(),
    val museWhatsApp: String? = null,
    /** Settings format: 1 = C3.1 (Hark's default moved to Full text). */
    val version: Int = 0,
  ) {
    fun config(relayId: String): WakeConfig = configs[relayId.lowercase(Locale.ROOT)] ?: WakeDefaults.forAgent(relayId)
    fun with(relayId: String, cfg: WakeConfig) = copy(configs = configs + (relayId.lowercase(Locale.ROOT) to cfg), version = Companion.VERSION)
    fun withUrl(relayId: String, url: String) = copy(webhookUrls = if (url.isBlank()) webhookUrls - relayId.lowercase(Locale.ROOT) else webhookUrls + (relayId.lowercase(Locale.ROOT) to url.trim()))
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
      private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
      const val VERSION = 1
      fun decode(s: String?): Saved = runCatching { if (s.isNullOrBlank()) Saved() else JSON.decodeFromString(serializer(), s).migrated() }.getOrDefault(Saved())

      /**
       * C3.1, once: a Hark wake saved as Text (C3's default, which needs a standing instruction Hark declined) goes back
       * to the default, Full text. Every other choice is kept.
       */
      private fun Saved.migrated(): Saved {
        if (version >= VERSION) return this
        val hark = configs["hark"]
        return copy(configs = if (hark?.kind == WakeKind.TEXT) configs - "hark" else configs, version = VERSION)
      }
    }
  }

  /** A routine webhook URL we'll POST to: https only, a real host. */
  fun webhookUrlOk(url: String): Boolean = runCatching {
    val u = java.net.URI(url.trim())
    u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null
  }.getOrDefault(false)

  /** The webhook body: no content, only the ids (protocol.md §6: "a nudge carries no content"). */
  fun webhookBody(ref: String, taskId: String): String = """{"ref":"$ref","task_id":"$taskId","source":"atall"}"""

  /** "Grok wants your OK" line: the action, and the amount when there is one. */
  fun approvalLine(agent: String, action: String, amount: Double?, currency: String?): String =
    "$agent: ${action.trim().take(160)}" + (money(amount, currency)?.let { " · $it" } ?: "")

  /** "$42", "$12.50", "30 EUR"; null for no amount. */
  fun money(amount: Double?, currency: String?): String? = amount?.takeIf { it > 0 }?.let { a ->
    val n = if (a % 1.0 == 0.0) a.toLong().toString() else String.format(Locale.US, "%.2f", a)
    if (currency.isNullOrBlank() || currency.equals("USD", true)) "$$n" else "$n ${currency.uppercase(Locale.ROOT)}"
  }
}
