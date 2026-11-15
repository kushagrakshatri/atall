# C3 relay changes (branch c3, NOT deployed)

Live is still function `atall` v6 + migrations c0, c1. To ship C3:

1. Apply migration `supabase/migrations/20261116000000_atall_c3.sql` (additive: `atall_devices.approval_key_spki`,
   tables `atall_approvals`, `atall_event_subs`, their SQL functions, same RLS/service_role lock-down).
2. Redeploy the `atall` function (same multipart deploy as v6). No new secrets.
3. ChatGPT: rescan the Atall plugin so it sees `events` (task.assigned) next to the tools; then tell kDot once to
   monitor Atall for task.assigned events.

What changed:
- MCP: `capabilities.events`, `events/list|subscribe|unsubscribe` (src/events.ts), callback verification challenge,
  Standard Webhooks-signed `task.assigned` deliveries `{task_id, ref, agent_id, attempt}` (no content). Server version 0.3.0-c3.
- Phone: `POST /phone/tasks` delivers task.assigned to subscribed agents; `POST /phone/wake` (nudge, returns `delivered`);
  `POST /phone/approval-key` (pinned per device, 409 on a different key); `POST /phone/approvals` (deny, or approve with a
  token signed by the approval key: action hash, task, agent, single-use jti, ≤15 min, max_amount checked);
  `GET /phone/agents` adds `events: true|false`.
- Tools: `atall_request_approval` stores the request; `atall_get_task` shows `approvals` (status, and the token when
  approved); `atall_inbox` lists decisions; instructions say approvals come from Kush's fingerprint.
