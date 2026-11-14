package app.atall.coordinator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouterAndPolicyTest {
  private val r = testRoster

  @Test fun noMentionGoesToJuno() {
    val route = assertIs<Route.ToJuno>(Router.route("Plan Saturday dinner for four", r))
    assertEquals("Plan Saturday dinner for four", route.text)
    assertTrue(route.mustAsk.isEmpty())
  }

  @Test fun singleLeadingMentionIsDirectCaseInsensitive() {
    val route = assertIs<Route.Direct>(Router.route("@NOVA, what's on my calendar tomorrow?", r))
    assertEquals("nova", route.agent.id)
    assertEquals("what's on my calendar tomorrow?", route.text)
  }

  @Test fun midSentenceMentionKeepsTheName() {
    val route = assertIs<Route.Direct>(Router.route("what does @scout think about this", r))
    assertEquals("what does Scout think about this", route.text)
  }

  @Test fun aliasResolves() {
    assertEquals("scout", assertIs<Route.Direct>(Router.route("@Dot hi", r)).agent.id)
  }

  @Test fun severalPeersOrJunoPlusPeersGoToJunoWithConstraints() {
    val a = assertIs<Route.ToJuno>(Router.route("@Juno ask @Scout and @Nova, then pick the cheaper option", r))
    assertEquals(listOf("scout", "nova"), a.mustAsk.map { it.id })
    val b = assertIs<Route.ToJuno>(Router.route("@scout and @nova: compare these", r))
    assertEquals(2, b.mustAsk.size)
    val c = assertIs<Route.ToJuno>(Router.route("@juno hello", r))
    assertTrue(c.mustAsk.isEmpty())
  }

  @Test fun allFansOutToActivePeersOnly() {
    val f = assertIs<Route.FanOut>(Router.route("@all is it going to rain Saturday in SF?", r))
    assertEquals("is it going to rain Saturday in SF?", f.text)
    assertEquals(listOf("scout", "nova"), f.agents.map { it.id }) // Hermes is paused
    assertIs<Route.FanOut>(Router.route("Quick poll @Everyone: tabs or spaces", r))
  }

  @Test fun emailsAndUnknownNames() {
    assertIs<Route.ToJuno>(Router.route("email kush@gmail.com the plan", r)).also { assertTrue(it.unknown.isEmpty()) }
    val u = assertIs<Route.ToJuno>(Router.route("@zx can you help", r))
    assertEquals(listOf("zx"), u.unknown)
    val m = Router.mentions("ask @Nova. then @Scout-", r)
    assertEquals(listOf("@Nova", "@Scout"), m.map { it.raw })
  }

  @Test fun emptyAndPaused() {
    assertEquals("empty", assertIs<Route.Nothing>(Router.route("@Nova", r)).reason)
    assertEquals("paused", assertIs<Route.Nothing>(Router.route("@hermes fix the build", r)).reason)
    assertEquals("empty", assertIs<Route.Nothing>(Router.route("@all  ", r)).reason)
  }

  @Test fun approvalGate() {
    assertTrue(ApprovalGate.check("Book a table for 4 at Rooh, Saturday 7:30pm.", false).gated)
    assertTrue(ApprovalGate.check("Please pay the Comcast bill.", false).gated)
    assertTrue(ApprovalGate.check("Yes, go ahead", false).gated)
    assertTrue(ApprovalGate.check("Order the large one for $24", false).gated)
    assertTrue(ApprovalGate.check("Cancel my Hulu subscription", false).gated)
    assertTrue(ApprovalGate.check("Anything", true).gated)
    assertFalse(ApprovalGate.check("Find three dinner spots in San Jose for 4 on Saturday. Don't book anything yet.", false).gated)
    assertFalse(ApprovalGate.check("What's on my calendar tomorrow?", false).gated)
    assertFalse(ApprovalGate.check("Check availability for booking a table at 7 on Saturday.", false).gated)
  }

  @Test fun plannerHintsUseGoodAtTags() {
    val fits = Planner.fits("Can you get a reservation Saturday and some ideas for dessert?", r)
    assertEquals(setOf("nova", "scout"), fits.map { it.agent.id }.toSet())
    assertTrue(Planner.hint("hello there", r, emptyList()).contains("answer yourself"))
    assertTrue(Planner.hint("x", r, listOf(r.byId("nova")!!)).contains("ask each of them"))
  }

  @Test fun promptsLoadAndRender() {
    val p = Prompts.render("juno_system", mapOf("main" to "Juno", "roster" to Prompts.rosterBlock(r), "maxAsks" to "4", "maxRounds" to "3"))
    assertTrue(p.startsWith("You are Juno"))
    assertTrue(p.contains("- Nova: good at bookings, calendar, reservations"))
    assertFalse(p.contains("Hermes")) // paused
    assertFalse(p.contains("{{"))
    listOf("juno_blind", "claims_extract", "verdict", "challenge_resolve", "late_followup", "challenge_ask").forEach { assertTrue(Prompts.raw(it).isNotBlank()) }
  }
}
