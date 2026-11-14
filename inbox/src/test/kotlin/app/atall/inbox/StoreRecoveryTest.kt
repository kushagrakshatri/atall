package app.atall.inbox

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StoreRecoveryTest {
  private fun tmp(): File = Files.createTempDirectory("inbox-store").toFile()

  private fun item(id: String, title: String = "t $id", lane: Lane = Lane.FYI) = InboxItem(
    id = id, title = title, lane = lane, triagedBy = TriagedBy.HEURISTIC, confidence = Confidence.HIGH, reason = "r",
    sources = listOf(Source("m-$id", "nova", "Nova", title, T0)), firstAt = T0, lastAt = T0,
  )

  @Test fun roundTrip() {
    val dir = tmp()
    FileInboxStore(dir).apply {
      upsert(item("a")); upsert(item("b")); upsert(item("a", "a v2")); remove("b")
      markSeen("m1", 1); markSeen("m2", 2); putMeta("lastDigestAt", 42)
    }
    val snap = FileInboxStore(dir).load()
    assertEquals(listOf("a v2"), snap.items.map { it.title })
    assertEquals(listOf("m1", "m2"), snap.seen.keys.toList())
    assertEquals(42L, snap.meta["lastDigestAt"])
  }

  @Test fun tornLastLineIsSkippedAndHealed() {
    val dir = tmp()
    FileInboxStore(dir).apply { upsert(item("a")); upsert(item("b")) }
    File(dir, "journal.jsonl").appendText("{\"t\":\"item\",\"item\":{\"id\":\"c\",\"tit") // crash mid-append
    val s = FileInboxStore(dir)
    assertEquals(listOf("a", "b"), s.load().items.map { it.id })
    assertEquals(1, s.skippedOnLoad)
    s.upsert(item("d"))
    val again = FileInboxStore(dir)
    assertEquals(listOf("a", "b", "d"), again.load().items.map { it.id })
    assertEquals(1, again.skippedOnLoad) // the torn line stays skipped; the next record is intact
  }

  @Test fun compactionKeepsStateAndLeavesNoTempFiles() {
    val dir = tmp()
    val s = FileInboxStore(dir, compactAfter = 5)
    repeat(12) { s.upsert(item("i$it")) }
    assertTrue(File(dir, "snapshot.json").exists())
    assertTrue(File(dir, "journal.jsonl").readLines().filter { it.isNotBlank() }.size < 5)
    assertEquals(12, FileInboxStore(dir).load().items.size)
    assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
  }

  @Test fun crashBetweenSnapshotAndJournalTruncateReplaysIdempotently() {
    val dir = tmp()
    val s = FileInboxStore(dir, compactAfter = 1000)
    s.upsert(item("a")); s.upsert(item("b", "b v1")); s.upsert(item("b", "b v2")); s.remove("a"); s.markSeen("m", 5)
    val journal = File(dir, "journal.jsonl").readText()
    s.compact()
    File(dir, "journal.jsonl").writeText(journal) // as if the process died before the journal was emptied
    val snap = FileInboxStore(dir).load()
    assertEquals(listOf("b v2"), snap.items.map { it.title })
    assertEquals(mapOf("m" to 5L), snap.seen)
  }

  @Test fun corruptSnapshotIsSetAsideAndJournalStillLoads() {
    val dir = tmp()
    File(dir, "snapshot.json").writeText("{ this is not json")
    File(dir, "journal.jsonl").writeText("")
    FileInboxStore(dir).upsert(item("x"))
    val s = FileInboxStore(dir)
    assertEquals(listOf("x"), s.load().items.map { it.id })
    assertTrue(dir.listFiles()!!.any { it.name.startsWith("snapshot.corrupt-") })
  }

  @Test fun inboxSurvivesRestart() {
    val dir = tmp()
    val r1 = Rig(store = FileInboxStore(dir))
    val lease = r1.added(nova("Heads up: your landlord emailed about the lease renewal, reply by Monday.", id = "m-lease"))
    r1.merged(scout("Reminder: you have an unread email from your landlord about renewing the lease, due Monday.", T0 + 5 * MIN))
    val code = r1.added(nova("Your Chase verification code is 482913", T0 + 6 * MIN))
    r1.inbox.markDigestSent(r1.inbox.digest(T0 + 7 * MIN))

    // New process, same files.
    val r2 = Rig(clock = r1.clock, store = FileInboxStore(dir))
    assertEquals(lease.item.id, r2.inbox.items().first { it.isCrossAgent }.id)
    assertEquals(IngestResult.AlreadySeen, r2.ingest(nova("dup", id = "m-lease")))
    assertNull(r2.inbox.revealCode(code.item.id)) // codes lived only in memory
    assertFalse(r2.inbox.state.value.needsYou.first { it.hasCode }.canRevealCode)
    // A later duplicate still finds the restored item (features are rebuilt from stored text).
    val m = r2.merged(scout("Landlord lease renewal email: want me to draft a reply?", T0 + HOUR))
    assertEquals(lease.item.id, m.item.id)
    assertEquals(3, m.item.sources.size)
    // The digest window continues from the persisted last-digest time.
    assertEquals(T0 + 7 * MIN, r2.inbox.digest(T0 + 2 * HOUR).since)
  }

  @Test fun sweepDropsOldClosedAndNoise() {
    val r = Rig()
    val a = r.added(nova("Your package from Amazon was delivered."))
    r.added(scout("Still working on it"))
    r.added(nova("Please sign the lease by Wednesday"))
    r.inbox.markDone(a.item.id)
    r.clock.advance(15 * DAY)
    assertEquals(2, r.inbox.sweep())
    assertEquals(1, r.inbox.items(includeClosed = true).size)
  }
}
