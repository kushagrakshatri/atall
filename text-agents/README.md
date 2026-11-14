# text-agents — the Route C text-agent transport

Talks to closed agents (Wren, ChatGPT "qBit", …) that already text the user's own number in Google Messages.
It hears them through a `NotificationListenerService` and answers through the reply action (RemoteInput +
PendingIntent) Messages puts on its notification. When there is no usable action (cold start, reboot,
action too old or revoked), it opens Messages on the thread with the text prefilled and the user taps Send.
No SMS permissions, no relay, no second line.

Ported and hardened from the spike on `hermes-android` `feature/on-device`
(`AgentRules.java`, `AgentStore.java`, `AgentListener.java`, `HitherAgentsModule.java`, `AgentRulesTest.java`,
`on-device-spike/route-c.md`, `tools/reply-probe/`).

```
core/      pure Kotlin/JVM (no android.*): API, matching, parsing, correlation, rate limits, store, fakes   + tests
android/   Android library: listener service, StatusBarNotification -> snapshot, reply firing, draft intents
```

## Proven on his Pixel 9 Pro (from the spike), and what that means here

| Fact | Consequence in this module |
|---|---|
| A copied reply action delivers while the notification shows, and after the chat was read (~1 min tested) | Live action is used while showing or within `actionGraceMs` (15 min, untested beyond ~1 min) of removal |
| RCS delivery can lag minutes | `replyWindowMs` 30 min default; closed requests stay matchable 60 min (`lateMatchMs`) and late replies are flagged `late` |
| Cold start / reboot loses actions (a PendingIntent can't be persisted) | Rescan of active notifications on listener connect; otherwise draft fallback |
| "Sent" via action means *handed to Messages*, not delivered | `SendResult.Sent` documents exactly that |

## Wiring into the app

```kotlin
// settings.gradle.kts
include(":text-agents:core", ":text-agents:android")
project(":text-agents").projectDir = file("../text-agents")          // adjust path
project(":text-agents:core").projectDir = file("../text-agents/core")
project(":text-agents:android").projectDir = file("../text-agents/android")
// root build.gradle.kts must declare (apply false): com.android.library, org.jetbrains.kotlin.jvm,
// org.jetbrains.kotlin.plugin.serialization — same Kotlin version as the app (2.4.21 in app-skeleton).

// app/build.gradle.kts
dependencies { implementation(project(":text-agents:android")) }

// Application.onCreate
val hub = TextAgents.init(this)                       // optional: isForeground = { lifecycle is STARTED }
val wren = TextAgents.register(this, AgentConfig(AgentId("wren"), "Wren", "+14085550101"))
if (!TextAgents.hasNotificationAccess(this)) startActivity(TextAgents.notificationAccessIntent(this))
// Sideloaded on Android 13+: the switch is greyed out until App info > ⋮ > Allow restricted settings
// (TextAgents.restrictedSettingsApply, TextAgents.appDetailsIntent).
```

The library manifest merges the listener service (`BIND_NOTIFICATION_LISTENER_SERVICE`) and a `<queries>`
block for Google Messages / `smsto:`. Verified: `:text-agents:core:test` and `:text-agents:android:assembleDebug`
build with Gradle 9.9.1 + AGP 9.5.1 + Kotlin 2.4.21 (the app skeleton's versions) via a scratch harness.

## API surface (core, package `app.atall.agents.text`)

```kotlin
interface Agent {
  val id: AgentId; val displayName: String
  val incoming: Flow<AgentMessage>                  // hot; each agent message once, arrival order; not replayed
  suspend fun send(text: String, correlationId: String, options: SendOptions = SendOptions()): SendResult
  suspend fun history(limit: Int = 50): List<LogEntry>      // durable log, both directions
  suspend fun outstanding(): List<OutstandingRequest>
  suspend fun close(correlationId: String)                   // stop attributing replies to it
}
```
- `AgentMessage(id, agentId, text, sentAt, observedAt, correlation: Correlation?, historical)`;
  `Correlation(correlationId, confidence HIGH|MEDIUM|LOW, reason TAG|ONLY_OPEN_REQUEST|OLDEST_UNANSWERED|CONTINUATION, part, userInterjected, late)`.
- `SendOptions(tag = false, expectReply = true, replyWindowMs = null, allowDraft = true)`.
- `SendResult`: `Sent(at, actionAgeMs, sentText)` · `Drafted(at, why, sentText)` (user must tap Send) ·
  `NeedsUser(why APP_IN_BACKGROUND|DRAFTS_DISABLED, draft)` (post `TextAgents.draftPendingIntent` in a
  notification; the correlationId is *not* consumed, retry later) · `RateLimited(retryAfterMs, scope)` ·
  `Duplicate(firstAt)` (idempotent: a correlationId is never texted twice, survives restarts) ·
  `Failed(EMPTY|TOO_LONG|BAD_CORRELATION_ID|UNKNOWN_AGENT|NO_NUMBER|NO_MESSAGES_APP|TOO_MANY_OUTSTANDING)`.
- `TextAgentHub(store, drafts, clock, config, random, io)`: `register/unregister/agent/agents`,
  `liveStatus(id)`, `events: SharedFlow<HubEvent>` (`RequestClosed(request, COMPLETED|EXPIRED|LIKELY_MISSED|CLOSED_BY_CALLER)`,
  `ThreadOpened`, `UserLine`), listener entry points `onPosted(snapshot)`, `onRemoved(key, reason)`, `onRescan(list)`, `sweep()`.
- Seams: `NotificationSnapshot`/`SnapshotLine`, `ReplyHandle.fire(text): FireResult`, `DraftLauncher.open(req): DraftOutcome`, `Clock`.
- `TextAgentConfig` defaults: reply window 30 min, action grace 15 min, max text 2000, max 4 outstanding per agent,
  continuation 90 s, min reply latency 2 s, clock skew 5 s, rate limits 3 s interval / 6 per min / 60 per h per agent, 20 per min global.
- Test doubles in `app.atall.agents.text.testing`: `FakeMessagesApp` (simulated Google Messages: history
  re-posts, own-reply echo, chat-open suppression, APP_CANCEL on open, revoked actions, plain-text mode, drafts),
  `FakeAgent` (in-memory `Agent` for coordinator tests), `ManualClock`, `InMemoryStore`.

Android (`app.atall.agents.text.android`): `TextAgents` (init, hub, register+rescan, access checks/intents,
`draftPendingIntent`, `messagesInstalled`), `TextAgentListenerService`, `SnapshotReader`, `ActionReplyHandle`, `IntentDraftLauncher`.

## Correlation strategy

Messages gives no thread ids or reply-to, so replies are attributed by heuristics, per agent, in this order:

1. **Tag** (opt-in `SendOptions(tag = true)`): appends `\n(ref #k3f9)` (4 chars, no look-alikes, unique among
   open requests). A reply containing the tag as a whole word → that request, HIGH. Off by default: it adds noise
   to the user's thread and closed agents rarely echo it.
2. **Time window**: candidates are requests sent before the line (5 s skew allowed) whose window is live —
   unanswered until `replyWindowMs`; of the answered ones only the most recently replied, until 90 s after its last part.
3. **Oldest unanswered first** (agents answer in order). HIGH when it is the only candidate, else MEDIUM.
4. **Too-quick guard**: a line < 2 s after a new request, while an earlier answer is still streaming, is that
   earlier answer's next part, not the new request's reply.
5. **Continuation**: further lines within 90 s join the answered request as `part` 1, 2, … (MEDIUM).
6. Otherwise **uncorrelated** (unsolicited message; `correlation = null`).

Modifiers: if the user typed into the thread themselves after the request, confidence drops to LOW and
`userInterjected = true` (the agent may be answering the user). Lines older than the agent's registration are
`historical` and never correlate. Requests are persisted, so correlation survives process death. A line whose own
timestamp is in-window but observed after expiry (RCS lag) still correlates with `late = true`.

Recommendation for the coordinator: keep one outstanding request per agent where possible — that makes every
reply HIGH; use `tag` when it must pipeline; treat MEDIUM/LOW as "probably", and use `RequestClosed` to time out.

## Dedupe and the user's own lines
- MessagingStyle re-posts the unread history with every notification. Key = `in|agent|time|sha256(text)[:8]`;
  keys persist separately from the log (5000, compacted) so trimming the log never re-ingests old lines.
  A watermark per agent drops lines > 24 h older than the newest seen (belt and braces after key compaction).
- Plain-text notifications (no MessagingStyle) have only the post time, which changes on re-post: deduped by
  text within 10 min instead.
- Self lines (sender null/blank, or equal to the self display name) are never agent messages. A self line equal to
  something we sent is our **echo**: it confirms the request reached the thread (`echoedAt`), and for a draft it
  marks the moment the user tapped Send (the request's window is re-based to it). Any other self line is the
  user typing directly: logged as `Direction.USER`, emitted as `UserLine`, and lowers confidence as above.
- Group conversations are ignored entirely (a reply action there would answer the whole group).
- Registration rejects agents whose names/aliases/numbers overlap (routing would be ambiguous).

## Notifications suppressed while the chat is open
Messages posts no notification for a conversation open on screen, so those replies are invisible to us. The
module can't read them (no SMS/RCS provider access), so it detects the risk instead:
- Messages cancelling the agent's notification (`REASON_APP_CANCEL`/`REASON_CLICK`) while requests wait →
  `ThreadOpened` event and `threadOpenedAt` on the requests. Cancels within 15 s after our own action reply are
  ignored (Messages may clear the notification because we replied).
- A draft opens the chat by definition, so drafted requests start with `threadOpenedAt` set.
- A request that expires with `threadOpenedAt` set closes as `LIKELY_MISSED` — tell the user to look in Messages.

## Rate limiting
Sliding windows per agent (min interval 3 s, 6/min, 60/h) and global (20/min), counted on accepted sends
(action or draft). Guards against coordinator loops and getting a number flagged. `RateLimited` carries
`retryAfterMs`; the correlationId is not consumed. In-memory: resets on process restart.

## Persistence: plain files, not Room
`FileTextAgentStore` under `filesDir/text-agents/`: `agents.json`, `requests.json`, `meta.json` (atomic temp+rename),
`log.jsonl` (append, capped 2000→1600), `keys.txt` (append, compacted). Why not Room:
- The data is tiny and access is trivial (append, tail, rewrite a handful of open requests); no queries or joins.
- Keeps the whole store in `core`, JVM-testable with a temp dir — Room needs KSP and Android/Robolectric tests.
- Same proven shape as the spike (`files/agents/log.jsonl`), crash-safe (atomic rewrites; torn last line skipped
  and healed), no schema migrations to own.
- `TextAgentStore` is an interface; a Room implementation can replace it if the log ever needs search/paging.

## M0.1 changes (after the first Pixel test)
- **Reply actions.** Captured from every agent notification post and re-post (MessagingStyle updates included),
  from the notification's own actions, else the Wear extender's, else Android Auto's unread conversation. Kept per
  agent conversation, newest first (up to 3, a re-post of the same PendingIntent refreshes it); a post without an
  action never drops what is held. A send tries the newest usable one and falls back to older ones on
  CANCELED/ERROR; the draft is used only when none is usable. Usable = showing, or removed less than
  `actionGraceMs` (15 min) ago (the Route C probe delivered 40 s after the chat was read).
- **Diagnostics.** `TextAgentHub(diagnostics = …)` gets one line per capture, removal, send and fallback (agent id,
  reason, ages, action counts; never message text or numbers). On Android, `TextAgents.diagnostics = true` sends
  them to logcat at DEBUG under `TextAgents`.
- **Reactions.** Tapback lines ("👍 to “…”", "Reacted 👍 to “…”", "Removed 👍 from “…”", iMessage-style
  "Liked “…”") are parsed by `Reactions.parse`, never correlated to a request (so they can't swallow the real reply
  that follows) and arrive as `AgentMessage.reaction` with the matching request's correlation id when the quote
  matches something we sent. The user's own tapbacks in Messages are ignored.

## Tests
`./gradlew :text-agents:core:test` (originally verified with a plain-kotlinc harness: **59 tests, all pass**);
it also fails if `core` imports `android.*`, and type-checks `android/` against `android-34/android.jar`.
Gradle: `:text-agents:core:test` + `:text-agents:android:assembleDebug` pass under the app's toolchain.
- `RulesAndParsingTest` — the spike's matching cases, short codes, ids, routing (package, summary, group, titles), self lines, plain-text fallback.
- `CorrelatorTest` — single/oldest-first/continuation/too-quick/tag/interjection/closure rules.
- `StoreAndLimiterTest` — file store round trip, trimming, key compaction, torn/corrupt files; every rate scope.
- `TextAgentHubTest` (over `FakeMessagesApp`) — live-action reply + multi-part answer; history dedupe; user typing;
  draft + confirmation; canceled/failed/expired actions; background → NeedsUser; validation and idempotence (also across
  restart); rate/outstanding caps; fire-and-forget; pipelined requests; tags; close; chat-open → LIKELY_MISSED;
  own-reply cancel guard; RCS lag → late; restart + rescan recovery; cold start → draft; pre-registration history;
  stale history; plain-text echo; group chats; overlapping registrations.
- `FakeAgentAndConcurrencyTest` — the fake agent; 300 posts racing 300 sends from 3 threads: no lost/duplicate lines.

## Known limits
- **Untested on device in this form.** The proven pieces are the spike's (same reply/draft calls); this module's
  Android layer compiles and builds but has not run on the Pixel. Re-run the route-c.md device plan.
- Action age beyond ~1 min after removal and across hours is unproven; `actionGraceMs` 15 min is a guess. Reboot
  always means draft until the agent texts again.
- Replies shown in an open chat are lost (detected as `LIKELY_MISSED`, not recovered).
- Correlation is heuristic without tags; an agent that answers out of order or merges two answers into one will be
  misattributed (MEDIUM). Unsolicited agent messages inside a reply window are attributed to the waiting request.
- Draft fallback needs the app in the foreground (Android background-activity-start rules) → `NeedsUser`; and the
  user may edit the draft (then the echo isn't recognized, it's logged as a user line and lowers confidence).
- Depends on Google Messages' notification format (MessagingStyle bundle keys, null sender for self, title =
  contact name). A format change degrades to the plain-text path; a renamed contact needs an alias.
- Rate-limit windows and live actions are memory-only; the log, requests, keys and registry persist.
- `Sent` ≠ delivered (RCS can stall); delivery receipts are not visible to a notification listener.
- Group chats and other messaging apps are out of scope.
