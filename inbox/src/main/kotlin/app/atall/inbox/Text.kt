package app.atall.inbox

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * What dedupe and triage look at in a (redacted) message: content words folded to a common form, plus hard
 * entities (links, email addresses, amounts, long ids, dates and times). Pure functions, no I/O.
 */
data class Features(
  val tokens: Set<String>,
  val urls: Set<String>,
  val emails: Set<String>,
  val amounts: Set<String>,
  val ids: Set<String>,
  val times: Set<LocalTime>,
  val dates: Set<LocalDate>,
  /** Earliest date-time mentioned, epoch ms (a date with no time means end of that day). */
  val whenAt: Long?,
) {
  val strongEntities: Set<String> get() = urls + emails.map { "@$it" } + ids.map { "#$it" }
}

object TextFeatures {
  private val stop = (
    "a an the and or but if so of to in on at for from with by about into over as is are was were be been being am " +
      "it its this that these those there here i me my mine we us our you your yours he she they them their his her " +
      "do does did done doing have has had having will would can could should shall may might must not no yes ok okay " +
      "just also still again now then than too very really quick quickly note heads up fyi hey hi hello re fw fwd " +
      "remind reminder reminding reminders don't dont forget want wanted wants let know please pls thanks thank " +
      "some any all more most one two get got gonna going go new another other via sent send sending what when where " +
      "which who how why today tonight tomorrow yesterday morning evening afternoon night am pm code link sign secret " +
      "monday tuesday wednesday thursday friday saturday sunday mon tue tues wed thu thur thurs fri sat sun " +
      "january february march april may june july august september october november december jan feb mar apr jun jul " +
      "aug sep sept oct nov dec th st nd rd"
    ).split(' ').toSet()

  /** Different words agents use for the same thing. Kept small and literal on purpose. */
  private val synonyms = mapOf(
    "mail" to "email", "e-mail" to "email", "emails" to "email", "inbox" to "email", "message" to "email",
    "meeting" to "meeting", "call" to "meeting", "sync" to "meeting", "standup" to "meeting", "1:1" to "meeting",
    "appt" to "appointment", "appointments" to "appointment",
    "payment" to "pay", "paid" to "pay", "paying" to "pay", "pay" to "pay",
    "invoice" to "bill", "bills" to "bill", "statement" to "bill",
    "parcel" to "package", "delivery" to "package", "shipment" to "package", "order" to "package", "packages" to "package",
    "flight" to "flight", "flights" to "flight", "boarding" to "flight",
    "reservation" to "booking", "booked" to "booking", "reserved" to "booking",
    "due" to "deadline", "expires" to "deadline", "expiring" to "deadline", "deadline" to "deadline",
    "replied" to "reply", "respond" to "reply", "response" to "reply", "answer" to "reply",
  )

  private val urlRx = Regex("(?i)\\bhttps?://([^\\s/?#]+)([^\\s?#]*)")
  private val emailRx = Regex("(?i)\\b[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+\\b")
  private val moneyRx = Regex("(?i)(?:[$£€₹]\\s?(\\d[\\d,]*(?:\\.\\d{1,2})?))|(?:\\b(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s?(?:usd|dollars|bucks|inr|eur)\\b)")
  private val idRx = Regex("\\b(?=[A-Za-z0-9-]*\\d)[A-Za-z0-9][A-Za-z0-9-]{5,}\\b")
  private val time12 = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm|a\\.m\\.|p\\.m\\.)")
  private val time24 = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b(?!\\s?(?:am|pm))", RegexOption.IGNORE_CASE)
  private val noon = Regex("(?i)\\b(noon|midday|midnight|eod|end of day|cob)\\b")
  private val monthDay = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")
  private val dayMonth = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b")
  private val slashDate = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
  private val weekday = Regex("(?i)\\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day|nesday|rsday|urday|sday)?\\b")
  private val relDay = Regex("(?i)\\b(today|tonight|tomorrow|tmrw|tmr|this evening|this afternoon|this morning)\\b")
  private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

  fun extract(text: String, sentAt: Long, zone: ZoneId): Features {
    val urls = urlRx.findAll(text).map { (it.groupValues[1] + it.groupValues[2]).lowercase().trimEnd('/', '.', ',') }.toSet()
    val emails = emailRx.findAll(text).map { it.value.lowercase() }.toSet()
    val amounts = moneyRx.findAll(text).mapNotNull { m ->
      val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }.replace(",", "")
      raw.toBigDecimalOrNull()?.setScale(2)?.toPlainString()
    }.toSet()
    val stripped = urlRx.replace(emailRx.replace(text, " "), " ")
    val ids = idRx.findAll(stripped).map { it.value.uppercase().replace("-", "") }
      .filter { it.length >= 6 && it.any(Char::isDigit) }
      .filter { !Regex("\\d{1,2}(AM|PM)").matches(it) }
      .toSet()

    val base = Instant.ofEpochMilli(sentAt).atZone(zone).toLocalDate()
    val times = LinkedHashSet<LocalTime>()
    time12.findAll(text).forEach { m ->
      var h = m.groupValues[1].toInt() % 12
      if (m.groupValues[3].lowercase().startsWith("p")) h += 12
      val min = m.groupValues[2].toIntOrNull() ?: 0
      if (h in 0..23 && min in 0..59) times += LocalTime.of(h, min)
    }
    time24.findAll(text).forEach { m -> times += LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
    noon.findAll(text).forEach { m ->
      times += when (m.value.lowercase()) { "midnight" -> LocalTime.of(23, 59); "noon", "midday" -> LocalTime.NOON; else -> LocalTime.of(17, 0) }
    }

    val dates = LinkedHashSet<LocalDate>()
    relDay.findAll(text).forEach { m ->
      dates += if (m.value.lowercase().startsWith("t") && m.value.lowercase().let { it.startsWith("tom") || it.startsWith("tm") }) base.plusDays(1) else base
    }
    weekday.findAll(text).forEach { m -> dayOf(m.groupValues[1])?.let { dates += base.with(TemporalAdjusters.nextOrSame(it)) } }
    monthDay.findAll(text).forEach { m -> monthDate(base, months.indexOf(m.groupValues[1].lowercase().take(3)) + 1, m.groupValues[2].toInt())?.let { dates += it } }
    dayMonth.findAll(text).forEach { m -> monthDate(base, months.indexOf(m.groupValues[2].lowercase().take(3)) + 1, m.groupValues[1].toInt())?.let { dates += it } }
    slashDate.findAll(text).forEach { m ->
      if (m.groupValues[3].isEmpty() || m.groupValues[3].length == 4) monthDate(base, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { dates += it }
    }

    val whenAt: Long? = when {
      dates.isNotEmpty() -> {
        val d = dates.min()
        val t = times.minOrNull() ?: if (Regex("(?i)\\btonight|this evening\\b").containsMatchIn(text)) LocalTime.of(21, 0) else LocalTime.of(23, 59)
        d.atTime(t).atZone(zone).toInstant().toEpochMilli()
      }
      times.isNotEmpty() -> {
        var at = base.atTime(times.min()).atZone(zone).toInstant().toEpochMilli()
        if (at < sentAt) at += 24 * 3600_000L
        at
      }
      else -> null
    }

    return Features(tokens(stripped), urls, emails, amounts, ids, times, dates, whenAt)
  }

  fun tokens(text: String): Set<String> =
    text.lowercase()
      .replace(Regex("\\[[a-z -]+]"), " ") // our placeholders
      .replace(Regex("[^a-z0-9:'+-]+"), " ")
      .split(' ')
      .asSequence()
      .map { it.trim('\'', '-', ':', '+') }
      .filter { it.length >= 2 && it !in stop && !it.all { c -> c.isDigit() || c == ':' } }
      .map { synonyms[it] ?: stem(it) }
      .map { synonyms[it] ?: it }
      .filter { it !in stop }
      .toSet()

  fun stem(w: String): String = when {
    w.endsWith("'s") -> w.dropLast(2)
    w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
    w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
    w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
    w.endsWith("es") && w.length > 4 && w[w.length - 3] in "sxz" -> w.dropLast(2)
    w.endsWith("s") && w.length > 3 && !w.endsWith("ss") -> w.dropLast(1)
    else -> w
  }

  private fun dayOf(s: String): DayOfWeek? = when (s.lowercase().take(3)) {
    "mon" -> DayOfWeek.MONDAY; "tue" -> DayOfWeek.TUESDAY; "wed" -> DayOfWeek.WEDNESDAY; "thu" -> DayOfWeek.THURSDAY
    "fri" -> DayOfWeek.FRIDAY; "sat" -> DayOfWeek.SATURDAY; "sun" -> DayOfWeek.SUNDAY; else -> null
  }

  private fun monthDate(base: LocalDate, month: Int, day: Int): LocalDate? {
    if (month !in 1..12 || day !in 1..31) return null
    val d = runCatching { LocalDate.of(base.year, month, day) }.getOrNull() ?: return null
    return if (d.isBefore(base.minusDays(60))) d.plusYears(1) else d
  }
}
