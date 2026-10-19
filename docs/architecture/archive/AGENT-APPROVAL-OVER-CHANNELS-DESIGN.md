# Approve your coding agent from your phone — design

*2026-09-12 · design only, nothing here is built · needs `core` changes*

## The flow

A coding agent runs on the user's own laptop. It reaches a decision it should not
take alone — a schema change, a failing test, `npm install`, a push. It **parks**.
The user's phone buzzes: a Telegram (later WhatsApp) message with the question and
the options. They reply `2`. The agent resumes on the laptop and finishes.

Why this and not something else: the 2026 shift in coding agents is not better
generation, it is **staying in the loop while the agent runs**. OpenAI shipped
Codex remote control into the ChatGPT mobile app in May 2026 for exactly this;
people are hand-rolling Telegram bots to forward permission prompts. VegaDūta is
the only platform in this space that already owns the messaging channels, and the
only one targeting users whose default channel is WhatsApp rather than a desktop
IDE.

## What already exists (verified 2026-09-12, do not rebuild)

| Piece | Where | State |
|---|---|---|
| Approval parking | `core/agents/.../job/approval/` — `AgentJobApprovalParkDetector`, `AgentJobRepository.parkForApproval` | Works. Guarded UPDATE RUNNING→`AWAITING_APPROVAL`, frees the worker by returning without `finish()`. |
| Persistence | columns in **V207**; `V211__agent_job_approval_parking.sql` adds only two indexes | Works. `park_timeout` 24h, `max-parks-per-job: 3`. |
| Resume | `AgentJobApprovalSweep` `@Scheduled` PT60S → `AgentJobExecutor.tryDispatch` → `resumeClaim`, fresh turn | Works. Worst-case resume latency **60s + 90s** (`REPLAY_GRACE`). |
| Approval REST | `ApprovalController` — `GET/POST /api/approvals`, `/{id}/approve`, `/{id}/reject` | Works, **JWT only**. |
| Outbound Telegram | `TelegramApiClient.sendMessage`, `POST /api/channels/telegram/send` | Works. No time window. User must have `/start`ed the bot. |
| Outbound WhatsApp | `WhatsAppDispatchService`, `MetaGraphApiClient.sendTextMessage`, `POST /api/channels/whatsapp/send` | Works **only inside Meta's 24h window** — see the blocker below. |
| Per-machine command queue | `core/devices/` — `DeviceCommandService`, `DeviceGatewayController`, `X-Device-Token` | Works. `desktop-agent` polls every 5s. |

## The five things that are missing

### 1. A parked approval must reach a channel
`HumanApprovalGateService` notifies on rejection only, and the notification is a
web bell pointing at `/admin/inbox`. Needed: on park, resolve the requesting
user's verified channel binding and dispatch the question outbound.

### 2. An inbound reply must be able to resolve it — this is the real blocker
`ApprovalController.enforceApproverAuthorization` derives the decider from the
Keycloak JWT and `TenantContext`. Client-supplied `decidedBy` was **deliberately
removed**. No webhook, device-token or signed-link path can approve anything.

**Do NOT solve this with a signed approval link.** Telegram and WhatsApp fetch
URLs to build link previews, so a preview crawler would follow an approval GET
and approve the action before the human ever read the message. Any link-based
path must be a POST behind an interstitial the human clicks, never a bare GET
that mutates.

**Preferred: reply matching.** The inbound webhook already identifies a chat.
Bind `channel identity → tenant user` once, verified (see §5), then resolve the
oldest pending approval for that user in that tenant. Keep the existing
server-derived-decider invariant: the decider is the bound user, still never
taken from the message payload.

### 3. Options and answers have nowhere to live
`approval_request` carries `summary` + `payload_json` only. Add an options list
and a free-text answer so `2` or "use the other approach" survives to the resumed
turn. New tenant migration — check **both** controlplane and tenant folders for
the true max version first: they share one Flyway sequence and a collision is an
outage-shaped boot failure.

### 4. A laptop agent has no way to park
`RequestHumanToolCallback` returns immediately, never parks, and refuses outright
when the session is not a paired channel session — which an edge/desktop session
never is. It is a handoff-to-operator-inbox, not a question/answer round trip.
`/api/edge/tools/execute` likewise just returns `GuardedToolCallback`'s
explanatory string. Needed: a HITL tool that actually parks, plus an
`AWAITING_APPROVAL` state and a resume command type for `desktop-agent`, which
today understands only `RUN_EDGE_LLM_TURN`.

### 5. Channel identity must be proved, not asserted
A phone number in a webhook payload is an assertion by whoever sent it. Binding
must be initiated from an authenticated session (console issues a short-lived
code, the user sends it to the bot), never inferred from an inbound message. The
whole security of §2 rests on this.

## Sequencing

1. **Telegram, read-only notify.** Park → outbound message with the question.
   Approval still happens in the console. Ships value immediately, no new auth
   surface, and proves the notification path.
2. **Verified channel binding** (§5) + reply matching (§2) + options/answers (§3).
   The security-sensitive step. Needs its own review.
3. **Desktop agent parking** (§4) — the `AWAITING_APPROVAL` state and resume
   command. This is what makes it work for a laptop-resident agent rather than
   only for hosted jobs.
4. **WhatsApp**: template sending + 24h-window tracking. Neither exists. Until
   then, WhatsApp works only if the user messaged in the last 24 hours, and a
   cold ping is silently dropped by Meta — do not ship a WhatsApp claim before
   this lands.

## Open questions for the maintainer

- Is a 60–150s resume latency acceptable, or does this need the event-driven path
  (`ApprovalRequestExecutionRecordedEvent`) promoted from optimisation to primary?
- Approval by reply is a real privilege. Should it be limited to non-destructive
  action types, with anything irreversible still console-only?
- `max-parks-per-job: 3` was chosen for hosted jobs. A coding task may need far
  more round trips — is a different limit right for the edge path?

## Related

`docs/audits/AUTONOMOUS-AGENTS-CAPABILITY-STATUS-2026-08-17.md` (gaps 8/10 cover the 90s
race, gap 11 the absent frontend), `docs/plans/VegaDuta-Build-Specs.md:744` (the 24h
window, designed and unbuilt). Those internal documents are not part of this repository.
