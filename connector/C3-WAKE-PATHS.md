# C3 wake paths (researched 2026-11-15)

MCP is pull-based: an agent sees an Atall card only when it next calls `atall_inbox`. C3 wakes it by the best real path
below. A wake carries no content: only the ref (`Atall #k3f9`) or `{task_id, ref}`. "Verified" = an official doc says so;
"assumed" = inferred or third-party, needs the device test.

| Agent | Built-in path (default in the app) | Backup / nudge | Status |
|---|---|---|---|
| **Grok Bot** | **Webhook routine.** Phone POSTs the routine's webhook URL with `Authorization: Bearer <key>`, body `{ref, task_id, source}`; a 200 means a run started. Kush creates the routine once ("Copy setup" in Settings), then pastes URL + key into Settings → Agents → Grok → Wake-up. | One-tap notification → `grok.com/?q=Check your Atall inbox for #ref` (Grok submits on load). | Webhook trigger, Bearer key, 200 = run started, 300 runs/h, usage per run: **verified** (docs.x.ai Skills and routines). Routine runs "can call connected tools", and custom connectors are account-wide: **verified in docs**, not yet tried with Atall. Prefill link auto-submits: third-party (ailinks.swerdlow.dev), **assumed**. |
| **kDot** (ChatGPT dot) | **MCP Events.** The relay now advertises `events`, implements `events/list|subscribe|unsubscribe`, verifies the callback with a signed challenge, and POSTs `task.assigned` `{task_id, ref, agent_id, attempt}` signed with Standard Webhooks when a card is posted. Nudge = `POST /phone/wake`. kDot must be told once: "Monitor Atall for task.assigned events…" ("Copy setup"). | `Atall #ref` text through text-agents (his note: unreliable), then a tap. | MCP Events exist and work "with dots", webhook + Standard Webhooks signing, verification challenge: **verified** (developers.openai.com/plugins/build/mcp-events.md). Event-triggered tasks need Work on Plus/Pro: **verified** (help.openai.com scheduled tasks). Whether Pro's "read/fetch only" limit blocks it: **assumed OK** (C0 showed his write tools work). **Needs relay deploy.** Scheduled tasks can use plugins (hourly at best): verified (learn.chatgpt.com), not used. |
| **Hark** | **Text** `Atall #ref` to Hark's number through text-agents (live reply action). Hark needs a one-time standing instruction ("Copy setup"). | No live reply action → one-tap "Wake Hark · #ref" opens Messages with the text typed in. | An inbound text starts a Hark turn: **verified** in practice (that's how Kush texts Hark). Hark then calling `atall_inbox` on its own: **assumed** (needs the standing instruction; MCP server "atall" is in his Hark account). A Hark scheduled poll is possible but costs usage: decision for Kush. |
| **Claude Duet** | **Its own schedule.** Duet "checks your connected accounts every few hours" (default window 3 h, editable). Nothing is sent. | After window + 30 min: one-tap → `claude.ai/new?q=Check your Atall inbox for #ref` (copied to the clipboard too). | Every-few-hours checks, connectors shared with the Claude account: **verified** (claude.com Introducing Claude Duet; support "Get started with Claude Duet"). `claude://claude.ai/new?q=` prefill: **verified for Claude Desktop**; the Android app's `claude://` links are documented only for Code, so the app opens the https link and relies on Claude's app link: **assumed**. Claude app scheduled tasks (hourly, cloud, connectors) exist on paid plans: **verified** (support.claude.com scheduled tasks) but they run in Claude, not Duet; not used by default. |
| **Meta Muse** | **Its own schedule.** A Muse recurring task ("Every 15 minutes, call atall_inbox…", "Copy setup"). Nothing is sent. | One-tap → Muse's WhatsApp chat (`wa.me/<number>?text=…` when Kush saves Muse's WhatsApp number) or the share sheet. | Recurring tasks on a custom interval: **verified** (meta.com Help, reminders and scheduled tasks). A scheduled run calling a custom MCP connector: third-party only (livewithbonsai.com docs), **assumed**. Muse lives in WhatsApp: **verified** (faq.whatsapp.com); Atall's WhatsApp reply path isn't built (text-agents is Google Messages only), so WhatsApp is a tap, not automatic. No webhook/event trigger documented. |

## C3.1 (2026-11-15): Hark is a pure text agent ("Full text")
Hark declined the standing instruction: it won't pull tasks from an outside board or post results there. New route
**Full text** (`WakeKind.FULL_TEXT`), Hark's default and pickable for kDot: the whole packed ask (the same text the
ladder's text rung sends) goes by text at once and the text reply is the answer, as in v0.1.4. No relay task, no
`Atall #ref` nudge, no ladder; "Copy setup" is hidden. A saved C3 "Text" wake for Hark resets to Full text once. The Hark
row above describes C3's Text wake, which stays pickable.

## Fallback ladder (protocol.md §8), as built
`connector + wake → one nudge (backup path) → same ask by text (tagged) → tap with the whole ask`
- Nudge after max(2 × latency, 3 min) with no claim/progress; schedule agents: window + 30 min.
- Text fallback at the agent's soft timeout with no claim or progress (Hark/kDot 6 min). Agents with no text route get a
  one-tap notification with the whole ask instead (Grok at 6 min). Schedule agents never fall back on the clock.
- Relay failing ≥ 60 s while tasks are open: every texting agent's open ask goes by text at once. First answer wins; a
  late connector result folds in as a late reply.

## Sources
- https://docs.x.ai/grok-bot/skills-routines-and-automations.md
- https://developers.openai.com/plugins/build/mcp-events.md
- https://help.openai.com/en/articles/10291617-scheduled-tasks-in-chatgpt
- https://learn.chatgpt.com/docs/automations
- https://claude.com/resources/articles/claude-duet
- https://support.claude.com/en/articles/17631495-get-started-with-claude-duet
- https://support.claude.com/en/articles/14729294-open-claude-desktop-with-a-link
- https://support.claude.com/en/articles/14898120-open-the-claude-mobile-app-with-a-link
- https://support.claude.com/en/articles/13854387-schedule-recurring-tasks-in-claude-cowork
- https://www.meta.com/help/artificial-intelligence/1484325780075655
- https://www.meta.com/help/artificial-intelligence/1687253048996149
- https://livewithbonsai.com/docs/muse/ (third-party)
- https://faq.whatsapp.com/1131618509041969
- https://ailinks.swerdlow.dev/grok/prompt/ (third-party)
