# Ep 5 — Channels, contacts & the inbox

**Length** ~4.5 min · **Level** everyone · **Renders to** `ep05-channels-inbox.mp4`

The Ledger connects to its readers over the channels they already use.
This lesson walks the three screens that make that real: connecting a
channel, deciding who answers, and watching what arrives.

## Coverage

- `/admin/channels` — the channel grid, connect panels, approve-new-senders
- `/admin/channels?channel=whatsappqr` — link your own WhatsApp (personal tier)
- `/admin/channels?channel=email` — the IMAP mailbox form
- `/admin/contacts` — per-agent address book + the sender-review gate
- `/admin/inbox` — the triaged conversation queues and takeover controls

## Click path

| Step | Where | What happens |
|---|---|---|
| Open Channels | `/admin/channels` | Card grid: WhatsApp, WhatsApp QR, Telegram, Matrix, Instagram, Messenger, iMessage, Email-IMAP, Voice, Signal, Mobile SDK |
| Open WhatsApp QR | `?channel=whatsappqr` | "Link your own WhatsApp number as a companion device" — no Meta account needed on personal |
| Set routing | same panel | "Route new inbound conversations to" → pick **Assistant** → **Save** |
| Open Email | `?channel=email` | IMAP host (`imap.gmail.com`), username, password — password goes to the vault, never the page |
| Sender review | `/admin/contacts` | "Review each new sender" = pairing gate; pick the agent up top to see its senders |
| Triage queues | `/admin/inbox` | Waiting for a human / Held / Pending pairing / Agent-handled |
| Takeover | `/admin/inbox` | Open a conversation → transcript, reply-as-human, re-route, end pairing |

## Mental model

```
CHANNELS  (the doors)     →  CONTACTS (the guest list)  →  INBOX (the desk)
WhatsApp, email, ...         approve new senders?          Waiting / Held /
                               who routes to whom            Pending pairing /
                                                             Agent-handled
```

- A connected channel with **no routing agent** answers no one — the
  routing pick inside the connect panel is the part that matters.
- **Review each new sender** is on by default: a stranger writes in, gets
  one reply carrying a pairing code, and waits in Pending pairing until a
  human approves them in Contacts. Messages from before approval are not
  replayed.
- The inbox is not read-only — a human can take any conversation over,
  reply directly, re-route it, or unpair the sender.
- Outbound sends can themselves need approval — if a pairing reply never
  arrived, check `/admin/approvals`.

## After watching

Continue to **Ep 6 — Guardrails: the editor's red pen** (the next lesson
in [the curriculum](../INDEX.md)), where the newsroom learns what it is
allowed to print.
