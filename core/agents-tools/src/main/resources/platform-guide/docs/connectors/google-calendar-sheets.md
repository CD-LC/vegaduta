# Google Calendar booking and Sheets append

Built-in agent tools for the two jobs most small-business pilots start with:
booking and rescheduling appointments, and keeping a running list in a
spreadsheet. They use the tenant's existing **Connect Google** connection
(Integrations → Google Workspace). There is no separate connector to install,
and no MCP server process: the tools run in-process on the agent's turn, the
same as `google_calendar` and `google_sheets` already did.

## Where it shows in the console

- **Tools → Google Workspace:** one card per tool. Each action carries a
  `read`, `write` or `irreversible · needs approval` badge, and the move and
  cancel cards say that a person approves them first.
- **Integrations → Google Workspace:** the connect panel lists booking among
  what the connection gives every agent.
- **Approvals:** a parked move or cancel shows a plain summary instead of
  "Tool call: …": the event id, the new time and whether Google will email the
  attendees (`describeForConfirmation`).

<!-- Screenshot placeholder: Tools page, Google Workspace section, showing
     "Calendar free slots", "Reschedule event" and "Cancel event" with their
     read/write badges. Capture from a stage tenant with Google connected and
     save as docs/connectors/img/google-booking-tools.png. -->
> **Screenshot to add:** the Tools page with the three booking tools listed.

## Tools and their risk

Every tool declares its risk in `BuiltInToolClassifications`
(`core/agents/.../tools/classification/`). The confirmation gate
(`app.tools.classification.confirmation-gate`, on by default) asks a human
before any tool classified irreversible and consequential runs.

| Tool | What it does | Risk | Needs approval by default |
|---|---|---|---|
| `google_calendar_find_free_slots` | Open slots between two dates, inside working hours, skipping busy time | read | No |
| `google_calendar_move_event` | Moves an event to a new start/end; optionally Google emails every attendee | irreversible | **Yes** |
| `google_calendar_cancel_event` | Deletes an event; optionally Google emails every attendee | irreversible | **Yes** |
| `google_calendar` (`list_upcoming`, `create_event`) | Unchanged | irreversible (classified), but on the default exempt list | No, see below |
| `google_sheets` `append_rows` (new action) | Adds rows after the table's last row; never overwrites | write | No |
| `google_sheets` `read_range` / `write_range` | Unchanged | write | No |

Move and cancel are separate tool names, not new actions on
`google_calendar`, on purpose. The classification registry judges a tool by
its name and never sees the arguments, and `google_calendar` is on the
default `app.tools.classification.confirmation-gate-exempt-tools` list so
scheduled agents do not stall on reads. Adding move and cancel as actions
there would have let them run with no human. As separate names they are gated
(pinned by `AgentToolGovernanceBookingToolsTest`).

**Known gap, not changed here:** `google_calendar` `create_event` with
attendees makes Google send an invitation email and still runs without
approval because the whole tool is exempt. To gate it, remove
`google_calendar` from `TOOL_CONFIRMATION_GATE_EXEMPT`. Its `list_upcoming`
reads are then gated too; agents that only need availability should use
`google_calendar_find_free_slots` instead.

`notify_attendees` is a required argument on move and cancel (no default), so
the agent states whether the customer is told and the approver sees it.

## Free-slot rules

- Busy time comes from `events.list` on the primary calendar. Ignored as not
  busy: cancelled events, events set to "show as available", and events the
  account owner declined. All-day events block the whole day in the
  calendar's own time zone.
- Slots lie inside working hours (default 09:00-18:00), inside the window,
  start no earlier than now, and do not overlap each other. Starts sit on a
  grid of `min(duration, 30 minutes)`. Weekends are skipped unless
  `include_weekends` is true.
- A window is at most 31 days. If the calendar has more than 1,250 events in
  it, the tool refuses and asks for a narrower window rather than offering
  slots it could not check.
- Results are in the calendar's time zone unless `time_zone` is given, as
  offset date-times that `create_event` accepts unchanged.

## Sheets append and formula injection

`append_rows` writes with `valueInputOption=RAW`. Rows an agent appends often
hold text a customer typed. In the `USER_ENTERED` mode that `write_range`
uses, a cell such as `=IMPORTXML(...)` would run as a formula in the owner's
spreadsheet. RAW stores it as text. One call appends at most 200 rows.

## Authentication and scopes

OAuth 2.0 through the platform's existing Google connection
(`core/google/GoogleOAuthService`). The refresh token is stored encrypted
(`google_credential` table, `APP_ENCRYPTION_KEY`); an access token is minted
per call and never stored. Nothing is read from environment files.

No new scope was added, so existing connections work without re-consent.

| Scope | Used by | Why it is the minimum |
|---|---|---|
| `https://www.googleapis.com/auth/calendar.events` | free slots, move, cancel, create | Lets the app read and change events. The narrower `calendar.freebusy` / `calendar.events.freebusy` scopes cannot create, move or cancel, and `freeBusy.query` does not accept `calendar.events`, which is why free slots are computed from `events.list`. The broader `calendar` scope (calendar settings, sharing) is not requested. |
| `https://www.googleapis.com/auth/spreadsheets` | read, write, append | Sheets has no per-file scope of its own. `drive.file` would only reach spreadsheets this app created, while pilots use the sheet the business already keeps. |

## What is logged

The tools log only the tool name and the exception class on failure, never
arguments, event titles, cell values or Google's error body (which can quote
them). The platform's tool-call audit log (`tool_call_log`) still records
each call's input like every other tool, under the tenant's retention
settings.

## Tests and what was not tested

- Client request mapping on recorded response shapes taken from Google's
  documented responses (`GoogleCalendarClientTest`, `GoogleSheetsClientTest`),
  the slot rules (`CalendarFreeSlotsTest`), tool validation and classification
  (`GoogleBookingToolsTest`), and the dispatch decision with the shipped
  defaults (`AgentToolGovernanceBookingToolsTest`).
- **Not run against a real Google account.** The fixtures follow Google's
  documentation, not captured traffic. Before a pilot, check on a test
  account: a recurring event moved with `singleEvents` ids, `sendUpdates`
  emails actually arriving, all-day events in a non-UTC calendar, and an
  append to a sheet with a header row and blank rows.

## Pending decisions

Open until the product owner decides; nothing below is built.

1. **Gate `create_event` invites.** `google_calendar` is on the default
   confirmation-exempt list, so `create_event` with attendees sends Google
   invitations without approval. Option: remove `google_calendar` from
   `TOOL_CONFIRMATION_GATE_EXEMPT` (its `list_upcoming` reads are then gated
   too; `google_calendar_find_free_slots` covers availability without a gate).
   Pinned by `AgentToolGovernanceBookingToolsTest#theOlderMultiActionCalendarToolIsStillExemptByDefault`.
2. **CRM agent tools: per agent or per tenant.** Workspace tools reach every
   agent of a tenant. Decide whether CRM tools (create or update lead, add
   note, list recent leads on the built-in Leads CRM) need a per-agent opt-in
   (an agent setting, a migration and a builder toggle) or are offered
   tenant-wide.
3. **Zoho CRM.** Build now (a Zoho entry in `OAuthProviderRegistry` with
   per-data-centre accounts URLs and token refresh, plus the generic CRM
   contract mapped onto it) or wait for a pilot that runs on Zoho. Zoho's API
   terms were not reviewed.
4. **Generic booking adapter.** Build with the first pilot that books through
   a non-Google system with a documented public API (for example Cal.com v2).

## Not built, and why

The original request also listed Razorpay, a CRM (Zoho or generic) and a
generic booking adapter. Each was checked against the code first.

- **Razorpay payment and UPI links:** already built in. `create_payment_link`
  and `check_payment_status` (and `request_upi_collect` under a payment
  mandate) call Razorpay through `core/billing` and `core/payments`, with a
  ledger and reconciliation, and match the default `*payment*` approval
  pattern. A second Razorpay connector would be a second path for moving money
  that skips the ledger and mandates.
- **CRM:** the built-in Leads CRM is reachable from workflows (the CRM Lead
  node: upsert, stage, activity). Agent tools for it are worth adding, but
  every Workspace tool is offered to every agent, so they first need a
  per-agent opt-in to avoid adding three tool schemas to every agent's
  prompt. Zoho CRM is available today as an unverified piece-host entry with a
  pasted, expiring token. A first-party Zoho connector needs a Zoho entry in
  `OAuthProviderRegistry` (Zoho runs a separate accounts server per data
  centre) and token refresh, which was not built.
- **Generic booking adapter:** free slots plus `create_event`, move and cancel
  are the booking flow for any business on Google Calendar. An adapter
  interface with no second real backend would be an abstraction without a
  user; add it with the first pilot that books through another system that
  has a documented API.
