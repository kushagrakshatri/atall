package app.atall.agents.text

/**
 * The seams to Android. The android module implements these from a StatusBarNotification, a
 * Notification.Action and an Intent; tests and the fake transport implement them in plain Kotlin.
 */

fun interface Clock { fun now(): Long }

object SystemClock : Clock { override fun now() = System.currentTimeMillis() }

/** A Messages notification reduced to plain values (see android/SnapshotReader). */
data class NotificationSnapshot(
  val packageName: String,
  /** StatusBarNotification.key; stable per conversation notification. */
  val key: String,
  val postTime: Long,
  val isGroupSummary: Boolean = false,
  val conversationTitle: String? = null,
  val title: String? = null,
  /** EXTRA_BIG_TEXT or EXTRA_TEXT, used only when [lines] is empty (no MessagingStyle). */
  val text: String? = null,
  val isGroupConversation: Boolean = false,
  /** The user's own display name in this conversation (MessagingStyle user / self display name). */
  val selfName: String? = null,
  /** MessagingStyle history, oldest first, as Messages posted it. */
  val lines: List<SnapshotLine> = emptyList(),
  val reply: ReplyHandle? = null,
  /** How many actions the notification carried, and where [reply] came from ("actions", "wearable", "car").
   *  Diagnostics only: lets the fallback log say why no live reply action existed. */
  val actionCount: Int = 0,
  val replySource: String? = null,
)

/** One MessagingStyle message. [sender] null or blank is how MessagingStyle marks the user's own lines. */
data class SnapshotLine(val text: String, val time: Long, val sender: String?)

/** A captured notification reply action (RemoteInput + PendingIntent). Lives in memory only. */
interface ReplyHandle {
  /** Fill the RemoteInput(s) with [text] and fire the PendingIntent. */
  fun fire(text: String): FireResult

  /** Equal for two handles that wrap the same PendingIntent (a re-post of the same notification). Null: unknown. */
  val identity: Any? get() = null
}

enum class FireResult { OK, CANCELED, ERROR }

/** Opens Google Messages on the number's thread with [text] prefilled (ACTION_SENDTO smsto: + sms_body). */
fun interface DraftLauncher { fun open(request: DraftRequest): DraftOutcome }

enum class DraftOutcome { OPENED, IN_BACKGROUND, NO_APP }

/** Why a notification went away (NotificationListenerService REASON_*), reduced. */
enum class RemovalReason {
  /** Messages cancelled it itself: usually the user opened or read the chat. */
  APP_CANCEL,
  USER_DISMISS,
  CLICK,
  OTHER,
}
