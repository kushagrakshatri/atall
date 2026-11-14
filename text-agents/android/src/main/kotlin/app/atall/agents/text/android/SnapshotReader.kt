package app.atall.agents.text.android

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import app.atall.agents.text.FireResult
import app.atall.agents.text.NotificationSnapshot
import app.atall.agents.text.ReplyHandle
import app.atall.agents.text.Rules
import app.atall.agents.text.SnapshotLine

/**
 * StatusBarNotification -> [NotificationSnapshot]. Reads only Google Messages notifications (package checked
 * before any extra is touched); everything else returns null untouched. All parsing decisions live in core.
 */
object SnapshotReader {
  fun read(context: Context, sbn: StatusBarNotification): NotificationSnapshot? {
    if (sbn.packageName != Rules.MESSAGES_PACKAGE) return null
    val n = sbn.notification ?: return null
    val x = n.extras ?: return null
    return NotificationSnapshot(
      packageName = sbn.packageName,
      key = sbn.key,
      postTime = sbn.postTime,
      isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
      conversationTitle = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)),
      title = str(x.getCharSequence(Notification.EXTRA_TITLE)),
      text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)) ?: str(x.getCharSequence(Notification.EXTRA_TEXT)),
      isGroupConversation = Build.VERSION.SDK_INT >= 28 && x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
      selfName = selfName(x),
      lines = lines(x, sbn.postTime),
      reply = replyAction(n)?.let { ActionReplyHandle(context.applicationContext, it) },
    )
  }

  @Suppress("DEPRECATION")
  private fun selfName(x: Bundle): String? {
    if (Build.VERSION.SDK_INT >= 28) {
      val me = x.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
      str(me?.name)?.let { return it }
    }
    return str(x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME))
  }

  /** MessagingStyle history (bundle keys "text", "time", "sender", "sender_person" as the platform writes them). */
  @Suppress("DEPRECATION")
  private fun lines(x: Bundle, postTime: Long): List<SnapshotLine> {
    val raw = x.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
    return raw.mapNotNull { p ->
      val b = p as? Bundle ?: return@mapNotNull null
      var sender = str(b.getCharSequence("sender"))
      if (Build.VERSION.SDK_INT >= 28) {
        val person = b.getParcelable<Person>("sender_person")
        str(person?.name)?.let { sender = it }
      }
      SnapshotLine(str(b.getCharSequence("text")) ?: "", b.getLong("time", postTime), sender)
    }
  }

  /** The action with a free-form RemoteInput; SEMANTIC_ACTION_REPLY wins when several qualify. */
  fun replyAction(n: Notification): Notification.Action? {
    var best: Notification.Action? = null
    for (a in n.actions ?: return null) {
      val inputs = a.remoteInputs ?: continue
      if (inputs.isEmpty() || a.actionIntent == null || inputs.none { it.allowFreeFormInput }) continue
      if (best == null || (Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY)) best = a
    }
    return best
  }

  private fun str(s: CharSequence?): String? = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Fills the RemoteInput(s) and fires Messages' PendingIntent, as the spike's reply-probe proved on a Pixel 9 Pro. */
class ActionReplyHandle(private val context: Context, private val action: Notification.Action) : ReplyHandle {
  override fun fire(text: String): FireResult = try {
    val inputs = action.remoteInputs
    val results = Bundle().apply { for (ri in inputs) putCharSequence(ri.resultKey, text) }
    val fill = Intent()
    RemoteInput.addResultsToIntent(inputs, fill, results)
    if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
    action.actionIntent.send(context, 0, fill)
    FireResult.OK
  } catch (e: android.app.PendingIntent.CanceledException) {
    FireResult.CANCELED
  } catch (e: Exception) {
    FireResult.ERROR
  }
}
