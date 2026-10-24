# Inbox and people

The **Inbox & people** group is how what you built reaches people, and how people reach it: channels, the
senders behind them, the operator inbox, the lead pipeline and the human-in-the-loop queues.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Channels | `/admin/channels` | `PERM_MANAGE_CHANNELS` | channel credential, default-agent, send, pairing, routing and opt-in writes need `PERM_MANAGE_CHANNELS`; status reads are open |
| Contacts | `/admin/contacts` | `PERM_MANAGE_CHANNELS` | writes under `/api/channels/contacts`, `/contact-groups` and `/routing` need `PERM_MANAGE_CHANNELS` |
| Inbox | `/admin/inbox` | `PERM_MANAGE_CHANNELS` | handoff actions (take over, reply, release, escalate) need `PERM_MANAGE_CHANNELS` or platform admin |
| Leads | `/admin/leads` | `PERM_MANAGE_LEADS` | every verb under `/api/leads/**` needs `PERM_MANAGE_LEADS` (leads are personal data, reads included) |
| Approvals | `/admin/approvals` | `PERM_MANAGE_APPROVALS` | editing approval policy needs `PERM_MANAGE_APPROVALS`; deciding an approval is checked in the service |
| Input requests | `/admin/input-requests` | `PERM_MANAGE_APPROVALS` | submitting is checked in the workflow service |

Each of these pages redirects a caller without the scope to `/` and renders nothing. That is only a convenience; the
endpoints enforce their own rules.

## Channels (`/admin/channels`)

The channels screen. The top bar reads live and connected counts from `/api/channels/status`. The body is a
grid of channel tiles, a log, and an inspector for the selected channel. The tiles are, in order: WhatsApp, SMS,
WhatsApp QR, Telegram, Matrix, Instagram DM, Messenger, Slack, Discord, X - DMs, Microsoft Teams, iMessage,
Email - IMAP, Voice, Signal and Mobile SDK. The **SMS** tile ("Plain SMS") uses the Twilio number connected in the
WhatsApp tile: connect Twilio there first, then point its webhook at the URL the SMS tile shows.

Per channel the inspector offers, where the channel supports them:

- connect or disconnect credentials (`POST/DELETE /api/channels/<channel>/credential`; WhatsApp has two connection
  modes, **Meta** (including embedded signup) and **Twilio**, on separate tabs);
- bind a default agent that answers inbound messages (`PUT /api/channels/<channel>/default-agent`);
- send a test message (`POST /api/channels/<channel>/send`) and a health check (`POST /api/channels/health/{key}`);
- for voice, a keypad (DTMF) menu (`/api/channels/voice/dtmf-menu`);
- for Mobile SDK, a pairing code and the paired-device list with revoke and command history (`/api/devices/**`).
  The old `/devices` URL redirects here.

### WhatsApp opt-in records

On the WhatsApp tile, below the quality and binding cards, the **WhatsApp opt-in records** card is where you record
and withdraw consent for template messages. The send guard refuses a template send to a number with no active
opt-in, so this is the place to make that consent explicit. The card text: "Template messages are only sent to
numbers with an active opt-in. Record the consent you collected, and revoke it when a person withdraws it."

- **Record opt-in**: a phone number with country code, where consent was given (**Website form**, **Customer
  messaged first**, **Checkout**, **In person** or **Imported list**) and optional evidence (a form URL, message id
  or note). `POST /api/channels/whatsapp-compliance/opt-in`.
- **Revoke**: per row, or **Revoke by number** by typing a number. `DELETE /api/channels/whatsapp-compliance/opt-in?phone=`;
  a number with no record answers "No opt-in record exists for that number."
- **List**: the table is backed by `GET /api/channels/whatsapp-compliance/opt-in` (`limit` 1-500, default 50;
  `offset`; `activeOnly=true` hides revoked records; `X-Total-Count` and `X-Has-More` headers), newest consent first,
  and uses the shared server-mode pager. Each row shows the number (digits only), the source, whether it is active,
  and when consent was given or revoked; the evidence and the user who recorded it are never returned. If the list
  call fails (for example an older core) the card falls back to listing only the records handled in the current
  browser session; a number recorded earlier can still be revoked by typing it.
- Writes need `PERM_MANAGE_CHANNELS`. The WhatsApp quality card reads `GET /api/channels/whatsapp-compliance/quality`.

What Meta requires and what the product enforces is in [WhatsApp policy](../channels/whatsapp-policy.md).

## Contacts (`/admin/contacts`)

Titled "Contacts & routing". A contact is a sender your agents already know; approving a new sender makes them a
contact and routes their messages to an agent. Sections: **Channel routing** (the per-channel switch for whether a
new sender needs approval), **Pending approval**, **Contacts**, **Contact groups** and **Import contacts**. The contact list is server-paged with `limit`/`offset`. The sidebar row shows a count badge of pending
senders. API: `/api/channels/contacts`, `/api/channels/contacts/import`, `/api/channels/contact-groups`,
`/api/channels/routing/{channel}`, `/api/channels/pairing/pending`, `/api/channels/pairing/{sessionId}/approve`.

## Inbox (`/admin/inbox`)

The operator surface for the same inbound traffic: every channel session, not only those waiting for a human. A
stats strip (`GET /api/handoff/stats`) sits above a conversation list (`GET /api/handoff/conversations`, with
channel, view and text filters and server paging) and a two-way thread that shows inbound and outbound messages
(agent replies included). The details pane covers the lifecycle: approve or reject pairing, reassign the agent,
escalate, **take over**, **reply**, **release** back to the agent, and unpair. A failed load shows a banner with
retry, never an empty "all clear". API: `/api/handoff/**`, `/api/channels/pairing/**`.

## Leads (`/admin/leads`)

A lead pipeline board plus a table over the built-in lead store that workflows and the bulk-email stream feed. A
lead opens in a drawer with fields and an activity timeline (log calls and emails, move the stage). CSV import and
export are available. Pipeline stages are updated by workflows through the CRM lead node. API: `/api/leads`,
`/api/leads/pipeline`, `/api/leads/{id}/stage`, `/api/leads/{id}/activities`, `/api/leads/import`,
`/api/leads/export.csv`.

## Approvals (`/admin/approvals`)

The approvals home. A list of approval requests with a status filter and approve and reject actions, and a policy
editor that chooses, per action type, which tool or channel actions require human approval. The action list is
prepopulated with the action types that real channel adapters gate through, in a searchable dropdown. The same
panel is also embedded in [Maintenance](administration.md#maintenance-adminmaintenance) for full admins. API:
`GET /api/approvals?status=`, `POST /api/approvals/{id}/approve`, `POST /api/approvals/{id}/reject`,
`PUT /api/approvals/policy/{actionType}` (`PERM_MANAGE_APPROVALS`). The list endpoint supports `limit`/`offset`
([list endpoint paging](../api/LIST-ENDPOINT-PAGING.md)).

Who may decide a request depends on its kind. A gated agent tool call, and any request marked admin-only, needs
`PERM_MANAGE_APPROVALS`. A workflow node's approval needs run access to that workflow, and an ambient-mode sentinel's
`agent.proposal` needs run access to the agent it proposes for (approving it starts a job of that agent as you); a
holder of `PERM_MANAGE_APPROVALS` may decide either. Nobody but a holder of `PERM_MANAGE_APPROVALS` may decide a
request they raised themselves. Channel sends (`channel.*.send`) can still be decided by any other member of the
workspace.

## Input requests (`/admin/input-requests`)

The queue that **Human Input** workflow nodes write to: a run that pauses for structured input shows up here as a
form to fill in instead of an approve and reject pair. Filter by status (Pending is the actionable queue, All, or
Submitted for the audit view) and submit the answer. API: `GET /api/workflows/input-requests`,
`POST /api/workflows/input-requests/{id}/submit`.
