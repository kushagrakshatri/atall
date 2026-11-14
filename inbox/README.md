# inbox: the Agent Inbox (innovation.md #5)

Every **unprompted** message Kush's agents send (kDot, Hark, later API agents) lands here instead of as a
separate ping. Replies to Juno's own requests are not inbox material; the thread shows them.

The inbox:
1. **Dedupes** near-duplicates across agents. Two agents flagging the same email or event become one card with
   both badges ("Hark and kDot both flagged this"). An agent repeating itself folds in too.
2. **Triages** each card into **Needs you / FYI / Noise**, with on-device heuristics first and a ChatGPT
   structured-JSON fallback only when the heuristics are unsure.
3. **Builds the digest** for Juno's evening summary (default 18:00, configurable times).
4. **Counts what's waiting on you** for the future home-screen widget.
5. **Keeps codes private.** OTP, 2FA, sign-in codes, PINs, temporary passwords and magic sign-in links are
   detected and redacted on device. Codes never reach disk, the LLM, or the digest.

Pure Kotlin/JVM, package `app.atall.inbox`. No `android.*` and no dependency on text-agents (the build script
enforces both).

```
src/main/kotlin/app/atall/inbox/
  Model.kt     IncomingMessage, InboxItem, Source, Lane, Signal, IngestResult, Notify, Clock
  Secrets.kt   Redactor (code detection and redaction), CodeShelf (memory-only code holder with a TTL)
  Text.kt      TextFeatures: content words, links, email addresses, amounts, ids, dates and times, due time
  Dedupe.kt    Deduper: same-thing rules plus vetoes
  Triage.kt    HeuristicTriage, InboxClassifier, ChatGptJsonClassifier + ResponsesTransport, LlmGate
  Digest.kt    DigestSchedule, DigestBuilder, Digest (plainText, promptForJuno)
  Store.kt     InboxStore, InMemoryInboxStore, FileInboxStore (snapshot + journal, crash-safe)
  Ui.kt        InboxUiState, InboxCardUi, AgentBadge, WaitingOnYou, Labels
  Inbox.kt     AgentInbox: the API
  testing/     FakeClock, FakeClassifier, RecordingTransport
adapters/text-agents/   TextAgentsBridge.kt: AgentMessage -> IncomingMessage, plus a pipe() helper
                        (copy into the app; type-checked against text-agents/core without editing it)
scripts/jvm-test.sh     kotlinc build + JUnit run, no Gradle
```

## API

```kotlin
val inbox = AgentInbox(
  store = FileInboxStore(File(context.noBackupFilesDir, "inbox")),
  classifier = ChatGptJsonClassifier(transport, model = junoModel),  // or null: unsure messages go to FYI
  config = InboxConfig(zone = ZoneId.systemDefault(), digest = DigestSchedule(listOf(LocalTime.of(18, 0)))),
)

// In
suspend fun ingest(msg: IncomingMessage): IngestResult       // IgnoredReply | AlreadySeen | Added | Merged
suspend fun ingestAll(msgs: List<IncomingMessage>): List<IngestResult>
suspend fun classifyPending(): Int                            // retry unsure items, e.g. after sign-in

// Out (UI-ready)
val state: StateFlow<InboxUiState>                            // needsYou / fyi / noise cards, waitingCount, noiseLabel
fun waitingOnYou(limit: Int = 5): WaitingOnYou                // widget: count, label, top items with due labels
fun items(lane: Lane? = null, includeClosed: Boolean = false): List<InboxItem>
fun item(id: String): InboxItem?
fun revealCode(itemId: String): String?                      // memory only, 10 min, null after restart

// User actions
fun markDone(id) / dismiss(id) / reopen(id) / snooze(id, until) / moveTo(id, lane)   // moveTo beats heuristics and LLM

// Digest
fun digest(now = clock.now()): Digest                         // headline, needsYou, fyi(+moreFyi), noiseByAgent, counts
fun markDigestSent(d: Digest)                                 // next window starts here (persisted)
fun nextDigestAt(): Long                                      // schedule the WorkManager job for this
Digest.isEmpty / plainText() / promptForJuno()                // skip, deterministic fallback, input for Juno's wording

// Housekeeping
fun sweep(): Int     // daily: drop closed items >14 d, Noise >3 d, purge stale codes
fun refresh()        // re-publish state (due labels, code expiry), e.g. on a minute tick while visible
```

`IngestResult.Added/Merged` carry `notify` (`BUZZ` only for a newly Needs-you item seen live; `QUIET` for new
FYI; `NONE` for Noise, duplicates of a shown card, and history) and `suppressOriginal` (true for Noise only by
default, per innovation.md: "suppress only Noise, with an undo"; `InboxConfig.suppressDuplicateOriginals` widens it).

`InboxCardUi` has: title, lane, agent badges (monograms), preview, sources with time labels, dueLabel
("Today 5:00 PM", "Tomorrow", "Wed", "Overdue"), dedupeLabel, reason in plain words, canRevealCode, and the
actions to show (`REPLY, SHOW_CODE, DONE, SNOOZE, MOVE, DISMISS` or `UNDO_DONE`).

## Wiring (app module)

```kotlin
// text-agents -> inbox (adapters/text-agents/TextAgentsBridge.kt)
appScope.pipe(hubAgent, inbox) { r -> /* BUZZ: post heads-up; suppressOriginal: cancelNotification(key) with undo */ }

// ChatGPT transport over the chatgpt-auth module (blocking client -> IO dispatcher)
val transport = ResponsesTransport { body ->
  withContext(Dispatchers.IO) { responsesClient.stream(RequestShaper.shape(body)).text }
}
```

## Heuristics (on device, first match wins)

Signals (regex over redacted text, plus extracted features):
`OTP` (a code was redacted) · `URGENT` (urgent, asap, security alert, account locked) · `MONEY_PROBLEM`
(declined, overdue, late fee, fraud, unauthorized) · `MONEY` (an amount, or payment/bill/rent/refund words) ·
`QUESTION` (a "?" or "should I / want me to / let me know / reply YES") · `ACTION_REQUEST` (action required,
please sign/approve/pay, needs your..., reply/pay/sign **by**...) · `DEADLINE` (due, expires, last day, by/before
a day or time) · `CONFIRMATION` (confirmed, booked, shipped, delivered, receipt) · `PROMO` (% off, sale,
newsletter, pro tip, upgrade now) · `CHATTER` (ok/thanks/on it/still working, short status lines).

| Rule | Lane | Confidence |
|---|---|---|
| Code | Needs you (expires with the code, 10 min) | high |
| Urgent or money problem | Needs you (promo wording too: Noise, ask LLM) | high |
| Promo + question/action | Noise, provisional | medium, **ask LLM** |
| Promo | Noise | high |
| Chatter, no ask | Noise | high |
| Question or action request | Needs you | high |
| Deadline + money (not a receipt) | Needs you | high |
| Deadline within 72 h (or undated) | Needs you | medium |
| Deadline later | FYI | medium |
| Confirmation / receipt | FYI | high |
| Money only | FYI | medium |
| Has a date or time | FYI | medium |
| Nothing matched | FYI, provisional | low, **ask LLM** |

LLM fallback: one batched Responses call (`text.format` strict `json_schema`, `store:false`), output
`{"items":[{"ref","lane","reason"}]}`. Code items are never offered; every text passes `LlmGate` (re-runs the
detector and throws if anything still looks like a code). Max 2 attempts per item; after that the item keeps its
provisional lane, marked `DEFAULT` ("Couldn't sort this; check it"). A user's `moveTo` always wins, including
for later duplicates.

Dedupe (within 48 h of the item's last message), first rule wins after vetoes:
- **Vetoes:** different amounts, different dates, different times, or different long ids (when both sides name one).
- Shared link, email address, or long id (tracking/confirmation) and ≥1 shared content word.
- Same date and same time and ≥1 shared word.
- Same amount and ≥2 shared words.
- Same date, ≥2 shared words, and ≥50% of the smaller word set shared.
- Word Jaccard ≥ 0.45, or ≥70% of the smaller set shared with ≥3 shared words.

Words are lowercased, stop-worded (incl. agent filler like "heads up", "reminder"), crudely stemmed, and folded
through a small synonym table (mail/inbox → email, invoice → bill, parcel/delivery → package, ...).
A merge can raise the lane (FYI → Needs you buzzes then), never lower it. A Done item reopens when a new duplicate
is a high-confidence Needs-you; a Dismissed one stays dismissed.

Code detection: a number-like token (`482913`, `582 913`, `G-582913`, `X7K2P9`) counts only within 60 chars of a
code word (code, OTP, passcode, verification, 2FA, PIN, ...), excluding "promo/zip/confirmation/order code".
Also `password: …`/`PIN is …` values and URLs in sign-in-link messages. Prices, times, dates, phone numbers and
order numbers are left alone (tested).

## Persistence

`FileInboxStore(dir)`: `snapshot.json` (atomic temp+rename) + `journal.jsonl` (append). Every record is
idempotent (full item upserts, set-style seen/meta), so replaying a journal over a newer snapshot is harmless.
A torn last line is skipped and newline-healed; a corrupt snapshot is moved aside (`snapshot.corrupt-*.json`) and
the journal still loads. Compaction every 500 records. Seen ids capped at 5,000. Text on disk is redacted.

## Build and test

```
scripts/jvm-test.sh            # all: compile main, compile tests, run JUnit, type-check the text-agents adapter
scripts/jvm-test.sh main|test|run|adapter   # one step at a time (each fits a 2-minute shell limit)
```
JDK 17 at `/workspace/work/sdk/jdk`, kotlinc 2.0.21 at `/workspace/work/kt/kotlinc`, jars in `/workspace/work/kt/libs`.
45 tests: dedupe (10), triage (14), OTP/privacy (8), digest (5), persistence/recovery (7), concurrency (1).
`build.gradle.kts` mirrors text-agents/core for the app's Gradle build (not run here).

## Known limits

- English-only regexes; Hinglish or other languages fall to the LLM (or FYI).
- Codes with no code word nearby ("4821 – don't share") are not caught; over-redaction inside code messages is accepted.
- Dedupe is lexical: "your landlord wrote" vs "Essex property mgmt emailed" won't merge. A `Deduper` with an
  on-device embedder (MediaPipe Text Embedder) is the upgrade path.
- An unprompted text that text-agents mis-attributes to an open Juno request (design.md rule 2) is treated as a
  reply and skipped here.
- Whether the ChatGPT-plan route accepts `text.format: json_schema` is unverified; the parser also accepts a
  plain JSON answer and code fences, and failures fall back safely.
