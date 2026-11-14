package app.atall.inbox.testing

import app.atall.inbox.ClassifyAnswer
import app.atall.inbox.ClassifyItem
import app.atall.inbox.Clock
import app.atall.inbox.InboxClassifier
import app.atall.inbox.ResponsesTransport
import kotlinx.serialization.json.JsonObject

/** A clock tests move by hand. */
class FakeClock(var t: Long) : Clock {
  override fun now() = t
  fun advance(ms: Long) { t += ms }
}

/**
 * Scripted classifier. [decide] maps each item to a lane name ("needs_you" | "fyi" | "noise") or null to omit it
 * from the answer. Records every batch it saw so tests can assert what would have left the phone.
 */
class FakeClassifier(
  var failWith: Exception? = null,
  var decide: (ClassifyItem) -> String? = { "fyi" },
) : InboxClassifier {
  val calls = mutableListOf<List<ClassifyItem>>()

  override suspend fun classify(items: List<ClassifyItem>): List<ClassifyAnswer> {
    calls += items
    failWith?.let { throw it }
    return items.mapNotNull { i -> decide(i)?.let { ClassifyAnswer(i.ref, it, "fake") } }
  }
}

/** A Responses transport that records request bodies and returns a canned output text. Never touches the network. */
class RecordingTransport(var reply: (JsonObject) -> String) : ResponsesTransport {
  val bodies = mutableListOf<JsonObject>()
  override suspend fun send(body: JsonObject): String { bodies += body; return reply(body) }
}
