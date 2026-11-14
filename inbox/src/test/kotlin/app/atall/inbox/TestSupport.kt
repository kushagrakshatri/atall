package app.atall.inbox

import app.atall.inbox.testing.FakeClassifier
import app.atall.inbox.testing.FakeClock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

val LA: ZoneId = ZoneId.of("America/Los_Angeles")

/** Friday 2026-11-13 09:00 Pacific. */
val T0: Long = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, LA).toInstant().toEpochMilli()
const val MIN = 60_000L
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR

fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, LA).toInstant().toEpochMilli()

private val seq = AtomicInteger()

fun nova(text: String, at: Long = T0, id: String = "h${seq.incrementAndGet()}", inReplyTo: String? = null, historical: Boolean = false) =
  IncomingMessage(id, "nova", "Nova", text, at, at, inReplyTo, historical)

fun scout(text: String, at: Long = T0, id: String = "k${seq.incrementAndGet()}", inReplyTo: String? = null) =
  IncomingMessage(id, "scout", "Scout", text, at, at, inReplyTo)

class Rig(
  val clock: FakeClock = FakeClock(T0),
  val store: InboxStore = InMemoryInboxStore(),
  val classifier: FakeClassifier? = FakeClassifier(),
  config: InboxConfig = InboxConfig(zone = LA),
) {
  private val n = AtomicInteger()
  val inbox = AgentInbox(store, classifier, clock, config) { "item${n.incrementAndGet()}" }
  fun ingest(m: IncomingMessage): IngestResult = runBlocking { inbox.ingest(m) }
  fun added(m: IncomingMessage): IngestResult.Added = ingest(m) as IngestResult.Added
  fun merged(m: IncomingMessage): IngestResult.Merged = ingest(m) as IngestResult.Merged
}

fun features(text: String, at: Long = T0) = TextFeatures.extract(text, at, LA)
