# Atall

**One thread for all your AI agents.** Talk to **Juno**, the main agent, in a single chat on your Android
phone, and `@mention` any other agent you use to ask it directly. Atall is open source (MIT), runs fully on the
phone, and has no server.

> Status: **M0 (v0.1.0-m0), pre-release.** The thread, `@mentions`, texting agents and onboarding work. Juno
> answers with a plain ChatGPT call; delegation (Juno asking your agents and merging their answers) lands with
> the coordinator in a later milestone.

## What it does today (M0)

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

1. Download `atall-0.1.0-m0-arm64.apk` from the [latest release](https://github.com/kushagrakshatri/atall/releases) and open it. Allow installs from your browser if asked.
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

Seams for work in progress:
- **Coordinator** (`app.atall.coordinator`): implement `app.atall.api.Coordinator` and bind it in `Graph.coordinator`.
  Until then `PlainJuno` answers with one Responses call.
- **Inbox** (`app.atall.inbox`): implement `app.atall.api.Inbox` and bind it in `Graph.inbox`.
- **More channels** (WhatsApp, app notifications): implement `app.atall.api.ChannelAdapter` (each returns the
  same `Agent` interface as the texting transport), add it to `Graph.adapters`, and flip `ChannelType.implemented`.

### Privacy

- No SMS/RCS read permission. Atall reads only Google Messages notifications from conversations with agents
  you add, and ignores the rest.
- The thread, agents and the ChatGPT sign-in stay in app-private storage and are excluded from backups. The
  sign-in is encrypted with a key in the Android Keystore.
- What you send to Juno goes to OpenAI. Agents only get what you `@mention` them with.
- Sign in with ChatGPT uses OpenAI's route for open-source, locally hosted apps; usage counts against your
  ChatGPT plan and you can cap it in ChatGPT → Settings → Usage.

## Build

```bash
./gradlew :app:testDebugUnitTest :text-agents:core:test :chatgpt-auth:test assembleRelease
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
