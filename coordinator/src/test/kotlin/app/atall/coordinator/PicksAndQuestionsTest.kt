package app.atall.coordinator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M0.4: disagreement in substance on the decision (products vs an experience), and one combined question. */
class PicksAndQuestionsTest {
  private val labels = mapOf("A" to "juno", "B" to "kdot", "C" to "hark")

  // The birthday answers as a claims call returns them: each with its own topic wording.
  private val birthday = """{"decision":"what gift to get mugdha","answers":[
    {"label":"A","pick":"small gift + note + outing","pick_kind":"physical item","asks_user":["What's your budget?","How do you know Mugdha?","What's one thing she's into?"],
     "claims":[{"topic":"what to give","value":"small gift tied to her interests","statement":"A small gift.","evidence":""}]},
    {"label":"B","pick":"dinner together","pick_kind":"experience","asks_user":["What is Mugdha to you?","What does she love?"],
     "claims":[{"topic":"best gift type","value":"an experience together","statement":"An experience together.","evidence":""}]},
    {"label":"C","pick":"WHOOP upgrade","pick_kind":"physical items","asks_user":["When is her birthday?","Roughly what budget?"],
     "claims":[{"topic":"gift recommendation","value":"WHOOP upgrade, spa day or jewelry","statement":"A WHOOP upgrade.","evidence":"she kept the last one"}]}]}"""

  @Test fun productsVersusAnExperienceIsADispute() {
    val parsed = assertNotNull(Agreement.parse(birthday, labels))
    // Topic by topic nothing lines up: before M0.4 this map had no dispute and no talk started.
    assertTrue(Agreement.cluster(parsed.answers).disputed.isEmpty())
    val c = Agreement.clusterWithPicks(parsed)
    val d = c.disputed.first()
    assertEquals("d-pick", d.id)
    assertEquals("what gift to get mugdha", d.topic)
    assertEquals(listOf(listOf("juno", "hark"), listOf("kdot")), d.sides.map { it.agentIds })
    assertEquals(0, d.majority)
    assertEquals(VerdictLevel.MAJORITY, Agreement.level(3, c))
  }

  @Test fun sameKindOfPickIsNoDispute() {
    val same = birthday.replace("\"pick_kind\":\"experience\"", "\"pick_kind\":\"physical item\"")
    val parsed = assertNotNull(Agreement.parse(same, labels))
    assertNull(Agreement.pickDispute(parsed.decision, parsed.answers))
  }

  @Test fun olderClaimsWithoutPicksStillParse() {
    val old = """{"answers":[{"label":"A","claims":[{"topic":"day","value":"Sunday","statement":"Sunday","evidence":""}]},{"label":"B","claims":[{"topic":"day","value":"Saturday","statement":"Saturday","evidence":""}]}]}"""
    val parsed = assertNotNull(Agreement.parse(old, labels))
    assertEquals(1, Agreement.clusterWithPicks(parsed).disputed.size)
    assertEquals("", parsed.decision)
  }

  @Test fun questionsFoldIntoOneShortQuestion() {
    val parsed = assertNotNull(Agreement.parse(birthday, labels))
    // Peers first (kDot, Hark), then Juno's own; "Roughly what budget?" and "What's your budget?" are one question.
    val asks = parsed.answers.filter { it.agentId != "juno" }.flatMap { it.asks } + parsed.answers.first { it.agentId == "juno" }.asks
    // Budget and timing first (only Kush can say), at most two things, one question mark.
    assertEquals("Roughly what budget, and when is her birthday?", Questions.combine(asks))
    assertEquals("Roughly what budget, and when is her birthday?", Questions.combine(listOf("When is her birthday?", "Roughly what budget?", "What's your budget?")))
    assertEquals("What's your budget?", Questions.combine(listOf("What's your budget?", "Roughly what budget?")))
    assertNull(Questions.combine(emptyList()))
    assertEquals("budget", Questions.concept("roughly what budget"))
  }
}
