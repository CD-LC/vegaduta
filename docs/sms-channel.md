# SMS conversations

Anyone with a phone can text a VegaDūta agent. No app, no account, no data
plan: a feature phone and a Twilio number are enough. Replies are plain text,
short, and go through the same approval gate as every other outbound message.

## Set it up

1. **Connect Twilio** in Channels → WhatsApp (the credential is shared; one
   Twilio number carries WhatsApp, voice and SMS).
2. In the Twilio console, set the number's **"A message comes in"** webhook to
   `https://<your-core-host>/webhook/twilio/sms` (HTTP POST). This is the same
   host the WhatsApp webhook uses; the request is verified with your Twilio
   auth token, never a bearer token.
3. In Channels → WhatsApp → **Plain SMS on this number**, choose the agent that
   answers SMS. It can differ from the WhatsApp agent: a short-reply persona
   suits SMS better.
4. Text the number. The first message from a new phone number gets a pairing
   code and waits in Channels → Pairing until an admin approves it (or turn on
   *auto-approve new senders* for a public number). From then on the agent
   answers.

With `./scripts/vegaduta up --lite --local-ai`, the whole path runs on your own
hardware: the only external party is the carrier.

## What the agent sees and sends

- The inbound text is logged on the session as sent. The agent receives it with
  one extra line: *this arrived by SMS, reply in plain text, keep it under about
  300 characters*. That line costs one turn, not every turn.
- The reply is sent as `channel.sms.send`. `channel.*.send` is a sensitive
  action by default, so a fresh tenant's first SMS reply waits for approval in
  the Approvals screen until the action type is auto-approved.
- The reply is cut at the last sentence (else word) boundary before
  `app.omnichannel.sms.max-reply-chars` (default 1,500) and ends with an
  ellipsis when something was dropped. Twilio rejects bodies over 1,600
  characters, and a carrier concatenates at most about ten segments; Indic and
  other non-Latin scripts use 70-character segments, so the same limit holds
  fewer characters.
- The phone number is the memory subject, as for WhatsApp and voice, so the
  agent remembers the person across conversations.
- A workflow bound to the SMS trigger channel (workflow canvas → Trigger →
  channel SMS) takes over the number entirely while the binding exists; the
  chat path is not used then.

## Operations

| Item | Where |
|---|---|
| Conversation inbox, human handoff | Admin → Inbox, channel filter **SMS** |
| Connection state and probe | Channels overview card **SMS**, `POST /api/channels/health/sms` |
| Agent-trigger and webhook event | `channel.sms.message.received` |
| Outbound audit | `channel_message_log` rows with channel `SMS` |
| Cost | the operator pays Twilio per segment; the model cost follows the agent's provider (zero with a [free local model](local-models.md)) |

Not built: inbound MMS, delivery receipts into the session, long-reply paging
("reply MORE"), STOP/HELP handling beyond what Twilio does on its own for
long codes, and SMS over any provider other than Twilio.
