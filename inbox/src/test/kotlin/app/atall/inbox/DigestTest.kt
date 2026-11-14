package app.atall.inbox

import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DigestTest {
  private fun dayRig(): Rig {
    val r = Rig()
    r.added(nova("Please sign the lease by Wednesday"))                                         // needs you, later
    r.added(scout("Rent of \$2,450 is due tomorrow", T0 + 10 * MIN))                             // needs you, sooner
    r.added(nova("Your package from Amazon was delivered.", T0 + 20 * MIN))                     // fyi
    r.added(nova("Your table at ROOH is confirmed for Saturday 7pm.", T0 + 30 * MIN))           // fyi
    r.merged(scout("Booking at ROOH confirmed: Saturday 7:00 PM", T0 + 35 * MIN))               // same, cross-agent
    r.added(scout("Pro tip: you can ask me to track packages too", T0 + 40 * MIN))              // noise
    r.added(scout("Still working on it", T0 + 50 * MIN))                                         // noise
    r.added(nova("Your Chase verification code is 482913", T0 + HOUR))                          // code
    val done = r.added(nova("Should I renew the domain atall.co?", T0 + 70 * MIN))
    r.inbox.markDone(done.item.id)
    return r
  }

  @Test fun eveningDigestHasTheRightShape() {
    val r = dayRig()
    r.clock.t = at(2026, 11, 13, 18)
    val d = r.inbox.digest()
    assertEquals("2 things need you, plus 2 updates.", d.headline)
    assertEquals(listOf("Rent of \$2,450 is due tomorrow.".trimEnd('.'), "Please sign the lease by Wednesday"), d.needsYou.map { it.title.trimEnd('.') })
    assertEquals("Tomorrow", d.needsYou[0].dueLabel)
    assertEquals(2, d.fyi.size)
    assertTrue(d.fyi.first { it.title.contains("ROOH") }.crossAgent)
    assertEquals(mapOf("Scout" to 2), d.noiseByAgent)
    assertEquals(1, d.dedupedCount)
    assertEquals(1, d.codesCount)
    assertEquals(1, d.doneCount)
    assertFalse(d.isEmpty)
    val text = d.plainText()
    assertTrue(text.startsWith("2 things need you"))
    assertTrue(text.contains("Rent of \$2,450 is due tomorrow (Scout, Tomorrow)"))
    assertTrue(text.contains("(Nova and Scout"))
    assertTrue(text.contains("muted 2 low-value messages"))
    assertFalse(text.contains("482913"))
    assertTrue(d.promptForJuno().contains("\"headline\""))
  }

  @Test fun nextDigestOnlyCoversWhatIsNewButKeepsOpenNeedsYou() {
    val r = dayRig()
    r.clock.t = at(2026, 11, 13, 18)
    r.inbox.markDigestSent(r.inbox.digest())
    r.clock.t = at(2026, 11, 14, 9)
    r.added(nova("Your dry cleaning is ready for pickup.", at(2026, 11, 14, 9)))
    r.clock.t = at(2026, 11, 14, 18)
    val d = r.inbox.digest()
    assertEquals(2, d.needsYou.size)                   // still waiting
    assertEquals(listOf("Your dry cleaning is ready for pickup."), d.fyi.map { it.title })
    assertEquals(0, d.noiseCount)
    assertEquals(0, d.codesCount)
  }

  @Test fun emptyDayIsSkipped() {
    val r = Rig()
    r.added(scout("Still working on it"))
    val d = r.inbox.digest(at(2026, 11, 13, 18))
    assertTrue(d.isEmpty)
    assertEquals("Nothing needs you tonight.", d.headline)
    assertEquals(1, d.noiseCount)
  }

  @Test fun fyiIsCappedWithACountOfTheRest() {
    val r = Rig()
    val news = listOf("Your dry cleaning is ready", "Seahawks won 24-17", "Weather looks clear all weekend", "The library hold arrived",
      "Gas prices dipped nearby", "Your Spotify playlist updated", "Costco restocked the protein bars", "New episode of the podcast is out",
      "The Caltrain schedule changes in January")
    news.forEachIndexed { i, t -> r.added(nova(t, T0 + i * MIN)) }
    val d = r.inbox.digest(T0 + HOUR)
    assertEquals(6, d.fyi.size)
    assertEquals(3, d.moreFyi)
    assertTrue(d.plainText().contains("and 3 more"))
  }

  @Test fun scheduleHandlesTimesOfDayAndDst() {
    val s = DigestSchedule(listOf(LocalTime.of(18, 0)), LA)
    assertEquals(at(2026, 11, 13, 18), s.nextAfter(at(2026, 11, 13, 17)))
    assertEquals(at(2026, 11, 14, 18), s.nextAfter(at(2026, 11, 13, 18)))
    val two = DigestSchedule(listOf(LocalTime.of(18, 0), LocalTime.of(8, 0)), LA)
    assertEquals(at(2026, 11, 14, 8), two.nextAfter(at(2026, 11, 13, 19)))
    // US fall-back is 2026-11-01: 18:00 the next day is still 18:00 on the wall clock (a 25 h gap).
    val before = at(2026, 10, 31, 19)
    val next = s.nextAfter(before)
    assertEquals(ZonedDateTime.of(2026, 11, 1, 18, 0, 0, 0, LA).toInstant().toEpochMilli(), next)
    assertEquals(23 * HOUR + HOUR, next - before)
    val inbox = Rig().inbox
    assertEquals(at(2026, 11, 13, 18), inbox.nextDigestAt())
  }
}
