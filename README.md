# Atall

**One thread for all your AI agents.** Talk to **Juno**, the main agent, in a single chat on your Android
phone, and `@mention` any other agent you use to ask it directly. Atall is open source (MIT), runs fully on the
phone, and has no server.

> Status: **M0.3 (v0.1.3-m03), pre-release.** Juno coordinates: it asks your agents, waits, and merges
> their answers; `@all` asks everyone the same question and shows where they agree; and now your agents can
> **talk it over**.

## New in M0.3 (v0.1.3-m03): Talk it over

- **Talk it over.** With `@all` in the composer (or when you write "talk it over"), a **Talk it over** chip asks
  your agents to answer on their own first, then each one sees the others' answers and can reply once. A second
  round happens only if they still disagree or one agent @mentions another. Never more than two.
- **Agents see each other as data, not as you.** Other agents' answers are quoted as "Answer A / Answer B"
  (anonymised), framed as information from other AIs, with links, codes and numbers stripped and your question
  restated. Every relay stays under 1,800 characters.
- **Where they landed.** Juno answers first, in at most two sentences. A small card shows who changed their mind
  and why, only when someone moved or they still disagree. Tap **Show the talk** for the rounds and **What Juno
  sent**. Up to two choices you can tap; a split or anything that costs money sits under **Your call**.
- **Limits and controls.** At most 6 relays per talk, each agent addressed at most twice, 12 minutes in all.
  **Stop** any time; **Wrap up now** and **Leave X out** appear when an agent is slow. Anything you type during a
  talk joins it. One notification per talk, when the answer lands (and only if Atall isn't open).
- **Your OK at the transport.** Only your own words can tell an agent to buy, book or go ahead. A relayed "yes, go
  ahead" waits for your tap, and a direct @mention no longer carries another agent's go-ahead in its recent turns.
- **Private agents.** Answers from agents that read your accounts (Hark and Muse by default; a switch in each
  agent's page) go to another agent only after you tap **Share with …?**.
- **Agents' @mentions are buttons.** When an agent @mentions another, you get **Ask X about this?**, which fills
  the composer for you to send. Nothing is sent by itself.
- On the Agreement Map, **Check this** is now **Talk it over**, and Juno starts a talk by itself when `@all`'s first
  answers clash on the thing you're deciding (the strip says so).
- Per-topic reliability (who changed their mind, who went along, what you picked) is logged on the phone for a
  scoreboard later. No scoreboard yet.

## New in M0.2 (v0.1.2-m02)

- **A tapback is never an answer.** Reactions on multi-line, truncated or unclosed quotes (and any emoji,
  including skin tones, ZWJ sequences, flags and keycaps) are recognised. On one of Juno's asks the reaction
  shows as a chip on that sub-task ("Reacted 🧳 · waiting") and the ask keeps waiting for the real reply.
- **Shorter texts to agents.** Juno's delegations send only the header and Juno's self-contained sub-task, plus
  one line naming who else is asked. Direct `@mentions` still carry your words and a few recent turns. Nothing
  sent to an agent contains Markdown.
- **Your name.** Settings has a **Your name** field (prefilled with your ChatGPT first name). It is used in the
  "From Atall (Kush's thread):" header and Juno uses it too.
- **Formatted answers.** Juno's bold text and lists render in the thread instead of showing `**`.

## M0.1 (v0.1.1-m01)

- **Juno coordinates.** A message you don't address (or `@Juno`) goes through Juno's tool loop: it writes a
  sub-task for each agent it picks, sends them, waits, and answers with what came back. The sub-tasks and each
  agent's replies show under Juno's turn ("Asking Nova and Scout" → "Asked Nova and Scout · 18s"). Asks that
  spend money or change an account wait for your **Yes, send it** (enforced in code, not just the prompt).
- **`@all`.** Everyone gets the same question, blind, and Juno answers too; the **Agreement Map** shows what
  everyone agrees on, where they split (with **Check this** for one targeted recheck) and who said what.
- **Agents get context.** Every message to an agent starts with "From Atall (Kush's thread):" (M0.2: delegations
  carry only Juno's sub-task; direct @mentions carry your words and a few trimmed recent turns).
- **Tapbacks are reactions.** "👍 to “…”" from an agent becomes a small chip on your message, not a reply.
- **Consistent labels.** Every agent line says **replied directly**, **answered Juno** or **messaged you**; the
  "may have answered in Messages" hint disappears once that agent's reply arrives.
- **Fewer Messages drafts.** Reply buttons are captured from every agent notification (including Wear and Auto
  variants), kept per conversation (the newest few), reused after the chat is read, and a cancelled one falls
  back to an older one. A draft opens only when no live reply button exists; each fallback's reason is logged
  on the device (`adb logcat -s TextAgents:D`, no message text).

## What it does (M0)

- **One thread, saved on the phone.** Your messages, Juno's answers and agent replies, in order, kept across restarts.
- **`@mention` picker.** Type `@` (or tap the @ button) to pick Juno or any agent you added; matching is by
  prefix. A message that starts with `@Name` goes to that agent only ("To Name only").
- **Texting agents (SMS/RCS through Google Messages).** Add any agent that texts you: a name plus the number it
  texts from. Atall hears its texts through notification access and answers through the Reply button Google
  Messages puts on the notification. Replies show in the thread as **replied directly**.
- **Tap-to-send fallback.** If there's no live reply button (the agent hasn't texted recently, or the phone
  restarted), the thread shows **Tap to send to Name**; one tap opens Google Messages with the text typed in.
- **Channel-agnostic agents.** Every agent has a channel: Text (SMS/RCS) works now; WhatsApp and app
  notifications are in the model and the add screen, marked *soon*.
- **Juno.** Anything you don't `@mention` goes to Juno. Signed in with ChatGPT, Juno answers through the
  Responses API on your ChatGPT plan; signed out, it says *Sign in with ChatGPT to talk to Juno.*
- **Onboarding.** Welcome (Continue with ChatGPT), notification access (with the Android 13+ *Allow restricted
  settings* guide), add an agent.

## Install (Pixel / Android 8+)

1. Download `atall-0.1.3-m03-arm64.apk` from the [latest release](https://github.com/kushagrakshatri/atall/releases) and open it. Allow installs from your browser if asked.
2. Open Atall. **Continue with ChatGPT** signs you in on OpenAI's page, or tap **Set up later**.
3. **Open settings** and switch Atall on. If the switch is greyed out (sideloaded apps on Android 13+): App
   info → ⋮ → **Allow restricted settings**, then try again.
4. Add an agent: its name (the one you'll `@mention`) and the number it texts you from.

## How it's built

Kotlin + Jetpack Compose (Material 3), no NDK, no Play Services, no Firebase.

| Module | What |
|---|---|
| `:app` | Compose UI, thread store, agent registry, router, Sign in with ChatGPT glue (Custom Tab + Android Keystore) |
| `:api` | Seams other modules plug into: `Coordinator`, `Inbox`, `ChannelAdapter`, `PeerDirectory`, `ChannelType` |
| `:text-agents:core` | Pure-Kotlin texting transport: matching, dedupe, correlation, rate limits, durable store |
| `:text-agents:android` | Notification listener, reply-action firing, Messages draft intents |
| `:chatgpt-auth` | Pure-Kotlin Sign in with ChatGPT (PKCE + loopback) and a streaming Responses client |
| `:coordinator` | Pure-Kotlin Juno: routing, the plan → ask → wait → merge tool loop, approval gate, `@all` Agreement Map, scoreboard |
| `:coordinator:adapters` | Glue: Responses client → Juno's model, text-agents → Juno's peers, text-agents → inbox |
| `:inbox` | Pure-Kotlin Agent Inbox (dedupe, triage, digest, code redaction); built and tested, not wired into the thread yet |

In the app, `core/JunoBridge.kt` runs Juno against the thread: it starts runs, feeds agent replies and transport
closures in, and projects each run into thread lines (`RUN`, `SUBTASK`, replies, approvals, the answer or the
map). `core/ContextPack.kt` builds what agents receive; `core/PeerLines.kt` holds attribution and reaction rules.
With no agent to ask, `PlainJuno` answers with one streaming Responses call.

Seams for work in progress:
- **Inbox** (`app.atall.inbox`): implement `app.atall.api.Inbox` with `AgentInbox` and bind it in `Graph.inbox`.
- **More channels** (WhatsApp, app notifications): implement `app.atall.api.ChannelAdapter` (each returns the
  same `Agent` interface as the texting transport), add it to `Graph.adapters`, and flip `ChannelType.implemented`.

### Privacy

- No SMS/RCS read permission. Atall reads only Google Messages notifications from conversations with agents
  you add, and ignores the rest.
- The thread, agents and the ChatGPT sign-in stay in app-private storage and are excluded from backups. The
  sign-in is encrypted with a key in the Android Keystore.
- What you send to Juno goes to OpenAI. An agent gets what you `@mention` it with, or Juno's sub-task, plus a
  compact context block (your request, a few recent lines of the thread, who else is being asked).
- Sign in with ChatGPT uses OpenAI's route for open-source, locally hosted apps; usage counts against your
  ChatGPT plan and you can cap it in ChatGPT → Settings → Usage.

## Build

```bash
./gradlew :app:testDebugUnitTest :text-agents:core:test :chatgpt-auth:test :coordinator:test :inbox:test assembleRelease
```

JDK 17, Android SDK platform 37. Release signing reads a properties file kept outside the repo
(`-PreleaseSigningProps=/path/signing.properties` or `RELEASE_SIGNING_PROPS`, keys `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`); without it the release APK is unsigned. `build.sh` wraps Gradle
for aarch64 Linux hosts, where AGP's x86-64 aapt2 can't run (`tools/aapt2-shim`). CI
(`.github/workflows/android.yml`) builds and tests on ubuntu and uses no secrets.

The APK is arm64-v8a only.

## Licence

MIT © 2026 Kushagra Kshatri. Geist and Geist Mono fonts are under the SIL Open Font License 1.1
(`licenses/OFL-Geist.txt`, also shown in the app under Agents → Open-source licences).
