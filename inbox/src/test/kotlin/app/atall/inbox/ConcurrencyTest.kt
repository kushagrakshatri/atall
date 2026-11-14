package app.atall.inbox

import app.atall.inbox.testing.FakeClassifier
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ConcurrencyTest {
  @Test fun parallelIngestFromTwoAgentsLosesNothingAndPersistsConsistently() {
    val dir = Files.createTempDirectory("inbox-conc").toFile()
    // A slow classifier, so LLM calls overlap with ingests on other threads.
    val slow = object : InboxClassifier {
      val inner = FakeClassifier(decide = { "fyi" })
      override suspend fun classify(items: List<ClassifyItem>): List<ClassifyAnswer> { delay(5); return inner.classify(items) }
    }
    val inbox = AgentInbox(FileInboxStore(dir, compactAfter = 40), slow, app.atall.inbox.testing.FakeClock(T0), InboxConfig(zone = LA))
    val topics = (0 until 30).map { "Update on support ticket ZX${1000 + it}Q" }
    runBlocking(Dispatchers.Default) {
      val jobs = topics.flatMap { t ->
        listOf(
          async { inbox.ingest(IncomingMessage("h-$t", "nova", "Nova", t, T0)) },
          async { inbox.ingest(IncomingMessage("k-$t", "scout", "Scout", t, T0 + 1000)) },
        )
      }
      jobs.awaitAll()
    }
    val items = inbox.items()
    assertEquals(30, items.size)
    assertEquals(60, items.sumOf { it.sources.size })
    assertEquals(30, items.count { it.isCrossAgent })
    val reloaded = FileInboxStore(dir).load()
    assertEquals(items.map { it.id }.toSet(), reloaded.items.map { it.id }.toSet())
    assertEquals(60, reloaded.seen.size)
  }
}
