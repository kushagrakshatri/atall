package app.atall.inbox

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DedupeTest {
  @Test fun twoAgentsFlaggingTheSameEmailBecomeOneItemCitingBoth() {
    val r = Rig()
    val a = r.added(nova("Heads up: your landlord emailed about the lease renewal, reply by Monday."))
    val b = r.merged(scout("Reminder: you have an unread email from your landlord about renewing the lease, due Monday.", T0 + 40 * MIN))
    assertEquals(a.item.id, b.item.id)
    assertTrue(b.crossAgent)
    assertEquals(listOf("Nova", "Scout"), b.item.agentNames)
    assertEquals(2, b.item.sources.size)
    assertEquals(1, r.inbox.items().size)
    val card = r.inbox.state.value.needsYou.single()
    assertEquals("Nova and Scout both flagged this", card.dedupeLabel)
    assertEquals(listOf("N", "S"), card.agents.map { it.monogram })
    // The second ping adds nothing new for the user: no second buzz.
    assertEquals(Notify.NONE, b.notify)
  }

  @Test fun sharedEmailAddressIsAStrongMatch() {
    val r = Rig()
    r.added(nova("Dana (dana@acme.com) emailed: the contract needs your signature."))
    val m = r.merged(scout("You got a note from dana@acme.com asking you to sign the contract.", T0 + 5 * MIN))
    assertTrue(m.crossAgent)
  }

  @Test fun sameEventSameDateAndTimeFromDifferentWording() {
    val r = Rig()
    r.added(nova("Dentist appointment tomorrow at 3pm."))
    val m = r.merged(scout("Don't forget: dentist at 3:00 PM tomorrow", T0 + 2 * HOUR))
    assertTrue(m.crossAgent)
  }

  @Test fun differentDaysOrAmountsStaySeparate() {
    val r = Rig()
    r.added(nova("Dinner with Priya Friday 7pm at ROOH."))
    assertIs<IngestResult.Added>(r.ingest(scout("Dinner with Priya Saturday 7pm at ROOH.")))
    r.added(nova("Your PG&E bill of \$84.20 is due Nov 20."))
    assertIs<IngestResult.Added>(r.ingest(scout("Your Comcast bill of \$60.00 is due Nov 20.")))
    assertEquals(4, r.inbox.items().size)
  }

  @Test fun unrelatedMessagesStaySeparate() {
    val r = Rig()
    r.added(nova("Your package from Amazon was delivered."))
    assertIs<IngestResult.Added>(r.ingest(scout("Your flight to Seattle is confirmed for Dec 4.")))
  }

  @Test fun outsideTheWindowIsANewItem() {
    val r = Rig()
    r.added(nova("Your landlord emailed about the lease renewal."))
    assertIs<IngestResult.Added>(r.ingest(scout("Your landlord emailed about the lease renewal.", T0 + 3 * DAY)))
  }

  @Test fun sameAgentRepeatingItselfFoldsIn() {
    val r = Rig()
    r.added(nova("Your landlord emailed about the lease renewal."))
    val m = r.merged(nova("Your landlord emailed about the lease renewal.", T0 + 3 * HOUR))
    assertFalse(m.crossAgent)
    assertEquals("Nova reminded you 2 times", Labels.dedupe(m.item))
  }

  @Test fun repliesToJunoAndRepostsAreIgnored() {
    val r = Rig()
    assertEquals(IngestResult.IgnoredReply, r.ingest(nova("Booked: table for 4 at 7pm", inReplyTo = "ask-1")))
    val first = nova("Your landlord emailed about the lease renewal.", id = "same-id")
    r.added(first)
    assertEquals(IngestResult.AlreadySeen, r.ingest(first))
    assertEquals(1, r.inbox.items().size)
  }

  @Test fun scorerVetoesConflictingTimes() {
    val d = Deduper()
    assertNull(d.score(features("Standup moved to 10am today"), features("Standup moved to 11am today")))
    assertTrue(d.score(features("Standup moved to 10am today"), features("standup now at 10:00 AM today"))!!.first > 0)
  }

  @Test fun mergeCanUpgradeLaneAndThenBuzz() {
    val r = Rig()
    val a = r.added(nova("Your landlord emailed about the lease renewal."))
    assertEquals(Lane.FYI, a.item.lane)
    val m = r.merged(scout("Landlord email about the lease renewal: want me to draft a reply?", T0 + 10 * MIN))
    assertEquals(Lane.NEEDS_YOU, m.item.lane)
    assertEquals(Notify.BUZZ, m.notify)
  }
}
