# coordinator: Juno's brain (`app.atall.coordinator`)

Pure Kotlin/JVM. No Android imports (the build script checks this), no network, no Gradle. It covers:
- @mention routing
- Juno's plan → ask → wait → merge loop, with approvals, timeouts and crash-safe resume
- the `@all` Agreement Map
- the delegation strip model
- the reliability scoreboard log

The model and the agents come in through two small interfaces (`Llm`, `Peer`), so everything is testable with fakes.

```
./gradlew :coordinator:test :coordinator:adapters:compileKotlin   # from the repo root
```
- **Result:** 40 tests, all green (also on CI). Test data uses placeholder agents (Nova, Scout).

## Files
| File | What |
|---|---|
| `Peers.kt` | `AgentProfile` (with `goodAt` tags, per-agent timeouts), `Roster`, `Peer`, `PeerSend`, `PeerSignal`, `Certainty`, `CloseOutcome` |
| `Router.kt` | `Router.mentions` / `Router.route` → `Route.Direct / ToJuno / FanOut / Nothing` |
| `Llm.kt` | `Llm`, `LlmRequest/Response`, `ToolSpec`, `LlmException(kind)`, Responses `Items`, JSON-schema helpers |
| `Policy.kt` | `Prompts` (resource loader), `ApprovalGate` (money/account rule), `Planner` (good-at hints) |
| `State.kt` | Serializable `RunState` / `AskState` / `PendingCall`, `RunStore` + `InMemoryRunStore` + `FileRunStore` |
| `Juno.kt` | The coordinator: `Juno`, `JunoConfig`, `RunUpdate`, `DriveResult` |
| `Agreement.kt` | UI model `AgreementMap` + claim parsing, clustering, verdict levels |
| `View.kt` | `RunView`, `DelegationStrip`, `FoldedReplies`, `NeedsUserLine` (pure, from state) |
| `Scoreboard.kt` | `ScoreEvent` log (`InMemoryScoreLog`, `JsonLinesScoreLog`) + `Scoreboard.compute` → `AgentScore` |
| `testing/Fakes.kt` | `FakeLlm` (scripted by purpose), `FakePeer` (async replies on virtual time, idempotent sends) |
| `resources/…/prompts/*.md` | `juno_system`, `juno_blind`, `claims_extract`, `verdict`, `challenge_ask`, `challenge_resolve`, `late_followup` |
| `adapters/` | `ResponsesLlm` (chatgpt-auth → `Llm`), `TextAgentPeer` + `TextAgentPump` (text-agents → `Peer` / `ingest`) |

## API

```kotlin
val juno = Juno(
  rosterProvider = { roster },                // Roster(listOf(AgentProfile("juno","Juno",MAIN), AgentProfile("hark","Hark",TEXT, goodAt=listOf("bookings","calendar")), …))
  peerFor = { id -> peers[id] },              // Peer per agent id
  llm = ResponsesLlm(client, model = { settings.model }),
  store = roomRunStore,                       // RunStore
  scores = roomScoreLog,                      // ScoreLog, from day 1
)

// Composer
when (val s = juno.submit(Router.route(text, roster), context = lastThreadLines)) {
  is Juno.Submitted.DirectSent -> /* @Hark …: no model; NeedsUser → show "Tap to send to Hark" */
  is Juno.Submitted.RunStarted -> runService.drive(s.runId)   // juno.drive(runId)
  is Juno.Submitted.NotSent   -> /* "empty" | "paused" */
}

juno.drive(runId, inline = true)    // DriveResult.Done | WaitingOnUser | Suspended(wakeAt) (inline=false) | Failed | Busy
juno.ingest(signal)                 // every peer message / RequestClosed / draft sent: from the event pump
juno.approve(runId, askId, yes)     // money/account gate, then drive again
juno.answer(runId, text)            // reply to Juno's ask_user question, then drive again
juno.interject(runId, text)         // user wrote mid-run: the current wait ends early and Juno sees it
juno.userSentDraft(askId)           // "Tap to send" completed (if the transport can't see the echo)
juno.cancel(runId)                  // "Stop waiting"
juno.challenge(runId, disputeId)    // @all: one targeted re-ask on one disputed claim, then drive again
juno.recover()                      // after process start: live run ids to drive
juno.sweep()                        // periodic backstop: timeouts, quiet windows, queued sends, late follow-ups/re-merges
juno.view(runId): RunView           // everything the thread renders, rebuilt from persisted state
juno.updates: SharedFlow<RunUpdate> // Answer, FollowUpPosted, NeedsUser, MapUpdated, Failed, Unsolicited, DirectReply, Changed
juno.scoreboard(): List<AgentScore>
```

### Model tools (strict JSON schema)

| Tool | Arguments | What it does |
|---|---|---|
| `ask_agent` | `agent` (enum of active peers), `text`, `why`, `money_or_account` | Sends one ask |
| `wait` | `ask_ids` (ids or agent names; empty means all open asks), `max_seconds` (5–180) | Waits for replies |
| `ask_user` | `question` | Asks the user one question |
| `reply` | `text` | Posts the answer and ends the run |

### UI models

**`DelegationStrip`** follows the fixed vocabulary in design-system §7.3.
- **`phase`:** LIVE, DONE or FAILED.
- **`label`:** a list of `LabelPart`s. Parts that name an agent carry its id, so the UI can bold the name in the agent's hue. Labels by state:
  - Live, nobody has replied: "Asking Hark", or "Asking Hark and kDot".
  - Live, someone has replied: "Asked Hark · waiting on kDot".
  - Done: "Asked Hark and kDot", or "Asked Hark, kDot and 2 more".
  - Failed: "kDot didn't answer".
- **`timer`:** "0:09" while live, "18s" or "2m 5s" when done.
- **`slow` and `slowHint`:** after 45 s, slow is set and the hint reads "Still waiting on kDot".
- **`notes`:** gaps when the run is done, such as "Hark didn't answer" or "Hark may have answered in Messages".
- **`lines`:** one per ask: the request sent, a reply preview, duration, an uncertain flag and a late flag.
- **`accessibility`:** the TalkBack sentence.

**`RunView`** also carries the following.
- **`folded`:** the raw replies, labelled "From kDot and Hark".
- **`needsUser`:** one line and one action each. The kinds:
  - TAP_TO_SEND: "Tap to send to kDot"
  - APPROVE: "Okay to have Hark do this?"
  - LOOK_IN_MESSAGES
  - ANSWER_QUESTION
- **`followUps`:** late answers, such as "Hark's answer is in: …".
- **`agreement`**
- **`errorKind`:** SIGN_IN means show "Sign in again"; LIMIT means show "Limit reached · Manage usage".

**`AgreementMap`** (Compose renders it as is):
- **`status`:** COLLECTING, READY or CHALLENGING.
- **`participants`:** each with ANSWERED, WAITING, DIDNT_ANSWER or NEEDS_TAP, and how fast it answered.
- **`agreed`:** claims everyone who spoke to the point agrees on.
- **`disputed`:** sides, largest first. Each side has its agents and their evidence; the majority side is marked, and challenge state is NONE, ASKED or RESOLVED, plus a resolution line.
- **`unique`:** claims only one agent made.
- **`verdict`:** level (UNANIMOUS, MAJORITY, SPLIT, TOO_FEW or UNCLEAR), Juno's one-line verdict, and counts.
- **`raw`:** the full answers, flagged uncertain or late where relevant.

## How the app wires it in (design.md §3, §5)

1. **One process-wide `Juno`.**
   - `RunStore` is backed by Room: one row per run holding `RunCodec.encode(state)`, or columns if you prefer. `live()` returns non-terminal runs, plus done runs that still have open asks.
   - `ScoreLog` is a Room table. Scores are rolled up for Settings → Agents.
2. **Event pump.**
   - Call `TextAgentPump.start(appScope, juno, hub.agents…, hub.events)` once per process, **before** `juno.recover()`. That way, replies recovered by the listener's rescan land on persisted asks.
   - API agents call `juno.ingest(PeerSignal.Message(…, correlationId = askId))` themselves.
3. **RunService (foreground, only while something is WAITING or MERGING).**
   - On start: `recover()`, then `drive(id)` for each.
   - When `submit` returns `RunStarted`: `drive(id)`.
   - Stop the service when no run is WAITING.
4. **WorkManager backstop.**
   - A periodic job (and one per deadline) calls `juno.recover()`, `juno.sweep()` and `juno.drive(id, inline = false)`.
   - Reschedule at `Suspended.wakeAt`. That covers process death between events.
5. **UI.**
   - Observe `updates`. On `Changed(runId)` re-read `view(runId)`, and render the strip, answer, fold, needs-user lines and map.
   - `RunUpdate.Unsolicited` and `RunUpdate.DirectReply` become plain attributed agent messages (stories 2 and 3).
   - `NeedsUser` lines in the background become the "Tap to send to Hark" notification.
6. **Transport window.**
   - Set `TextAgentPeer(replyWindowMs = 30 min)`, the same as Juno's hard timeout. If the transport expires a request earlier, Juno marks it as didn't answer at that point.
7. **Model setting.** `ResponsesLlm(client, model = { settings.model })`. Reasoning effort defaults to low.

## Key design choices

- **Event-driven state machine, persisted before effects.**
  - Every transition is saved first. That covers the model output, the asks, and the pending tool calls with their outputs.
  - Asks are written as `SENDING` *before* `peer.send`. The ask id doubles as the correlation id, and sends are idempotent: after a crash, the resend returns `Duplicate`, not a second text.
  - The transcript is the Responses input itself (store:false), so resuming means calling the model with the saved transcript.
  - While waiting, the model is never called. `drive` sleeps on `ingest` events and deadlines.
- **Replies come in through `ingest`, not per-run collectors.**
  - A reply that lands while no run is in memory is still attached to its persisted ask.
  - An uncorrelated reply, or one for an unknown ask, is surfaced as unsolicited.
- **Waits resolve on the earliest of three things:**
  - every ask is settled (a reply plus a 20 s quiet window, a timeout, likely-missed, declined or failed);
  - `max_seconds` passes (capped at the 3-minute soft timeout);
  - the user writes again.

  A slow agent gets "still_working". Juno then replies partially, and the late reply becomes **one** follow-up message ("Hark's answer is in: …"). The 30-minute hard timeout leads to "didn't answer".
- **Correlation edge cases are passed to the model as plain notes, never hidden:**
  - LOW certainty: "Uncertain: the user also typed in that chat…"
  - Late replies are flagged.
  - LIKELY_MISSED becomes `maybe_answered_in_messages`, and the user gets a "may have answered in Messages" line.
  - NeedsUser and Drafted become `waiting_for_user_tap`. The tap starts the clock.
- **Approval gate is code, not just prompt.**
  - Any ask flagged `money_or_account` by the model, or matched by `ApprovalGate`, pauses the run in AWAITING_USER, and the ask is never sent.
  - `ApprovalGate` matches buy, book, pay, order, cancel, transfer, sign-up and account changes, plus bare confirmations like "yes, go ahead". Negations ("don't book anything yet") and lookups ("check availability…") don't trigger it.
  - Approving gives the related wait a fresh window. Declining tells Juno not to retry.
- **Limits from design.md:**
  - 3 rounds and 4 asks per run.
  - 1 in-flight ask per texting agent, across runs: later asks queue.
  - A cap on model calls per run (8). When it's hit, Juno assembles a "Here's what came back" answer, flagged `fallback`.
  - A must-ask nudge, once, when the user named agents.
  - A model failure with partial replies also falls back. Without any replies the run fails, with an `errorKind`.
- **@all follows the research:**
  - Every agent gets the same question, blind, with Juno answering too (no tools).
  - Collection is partial: the map stays COLLECTING with no model spend until answers are in.
  - Then one structured-output claims call. Answers are labelled A, B, C so the extractor doesn't see agent names.
  - **Deterministic clustering:** fuzzy topic match (Jaccard ≥ 0.6), value match (≥ 0.75), and different numbers never merge.
  - A verdict call follows, with a deterministic fallback.
  - **Challenge:** one round, per dispute. It sends only that claim and the other side's evidence, and updates the map without re-extracting. There is no open-ended debate.
  - A late answer re-merges the map.
- **Planning hints, not routes.** `goodAt` tags appear in Juno's roster, plus a per-message "Likely fits" hint (prefix match). The model decides; the tags never route silently (innovation #8).
- **Scoreboard from day 1.** For each send it logs:
  - first-reply latency and certainty;
  - the settled outcome, and whether it came within the soft timeout;
  - needs-user events (tap, approval, look in Messages);
  - unsolicited messages and direct sends.

  Rates are reported with counts, because samples are small.

## Open risks

1. **Structured output on SIWC is unverified.**
   - `text.format=json_schema` may be rejected or ignored on the ChatGPT-plan route.
   - Parsing is lenient (code fences, surrounding prose), and failure degrades to an UNCLEAR map with the raw answers. Still, this needs a live check.
2. **Approval gate heuristics.**
   - It can false-positive ("in order to…") and false-negative on odd phrasing. The model flag is the main signal and the regex is the backstop.
   - Direct @mentions bypass Juno and the gate entirely, because they are the user's own words. Confirm that this is the intended policy.
3. **Claim clustering quality.** It depends on the extractor reusing topic wording across answers. Jaccard matching is crude: paraphrased topics can split into "unique" claims, and close-but-different values can show up as disputes. The raw answers are always one tap away.
4. **Late re-merge drops resolved challenges.** A late @all answer re-runs extraction from scratch, so an earlier challenge resolution is lost. That's acceptable for v1 but worth fixing.
5. **One in-flight ask per agent is enforced only for runs in memory.** Call `recover()` at startup so every live run is loaded.
6. **The transport and Juno each keep their own timeouts.** If text-agents' reply window is shorter than Juno's hard timeout, asks expire early.
7. **Rate-limited sends fail the ask** (no automatic retry yet).
8. **Prompts are untested against a real model.** The tone, the rewrite quality and whether Juno reliably calls `wait` before `reply` still need a live session. The code nudges a `reply` that comes straight after new asks ("wait_first").
9. **Adapters** now build with Gradle as `:coordinator:adapters` (package `app.atall.*`) and are wired into the app (`app/…/core/JunoBridge.kt`).
