# Channels overview

A **channel** is a way for people to reach an agent (and for an agent to reach them) outside the console. VegaDūta
has fifteen channel types, one for each value of `ChannelType` in the code. They all feed the same runtime:
a message arrives, becomes a conversation, is routed to an agent, and the reply leaves through the approval gate.
This page is the map; each channel's page has the setup steps, what it enforces and what can go wrong.

Connect and manage channels on the **Channels** screen of the console (`/admin/channels`; see
[Inbox and people](../console/inbox-and-people.md)). Each channel is a tile; the inspector connects or disconnects
it, sets the agent that answers, sends a test message and checks health.

## The channels

| Channel | `ChannelType` | How messages arrive | Inbound endpoint, or what core connects to | What you provide | Page |
|---|---|---|---|---|---|
| WhatsApp, Meta Cloud API | `WHATSAPP` | Webhook | `GET`/`POST /webhook/meta/whatsapp` | Phone Number ID, WABA ID, access token, App Secret | [WhatsApp](whatsapp.md) |
| WhatsApp, Twilio | `WHATSAPP` | Webhook | `POST /webhook/twilio/whatsapp` (status: `/webhook/twilio/status`) | Account SID, Auth Token, WhatsApp sender | [WhatsApp](whatsapp.md) |
| WhatsApp QR (your own number) | `WHATSAPP_QR` | Pulled from a bridge you run | the `whatsapp-qr` bridge (`app.whatsappqr.bridge-url`) | a QR scan | [WhatsApp QR](whatsapp-qr.md) |
| Messenger | `MESSENGER` | Webhook | `GET`/`POST /webhook/meta/social` | Page ID, Page access token, App Secret | [Messenger and Instagram](messenger-instagram.md) |
| Instagram DMs | `INSTAGRAM` | Webhook (same endpoint) | `GET`/`POST /webhook/meta/social` | the same connection, plus the Instagram Business Account ID | [Messenger and Instagram](messenger-instagram.md) |
| Slack | `SLACK` | Webhook | `POST /webhook/slack` | Bot token, Signing Secret | [Slack](slack.md) |
| Discord | `DISCORD` | Send-only; receiving needs a bridge **not in this repository** | `POST /webhook/discord`, called by that bridge | Bot token | [Discord](discord.md) |
| Telegram | `TELEGRAM` | Webhook, or long polling | `POST /webhook/telegram/{botUserId}`, or core calls `getUpdates` | Bot token | [Telegram](telegram.md) |
| Microsoft Teams | `TEAMS` | Webhook | `POST /webhook/teams/{appId}` | App ID, client secret | [Teams](teams.md) |
| Email (IMAP) | `EMAIL` | Polled every 5 minutes | core connects to your IMAP server | IMAP host, port, user, password | [Email](email.md) |
| X (Twitter) DMs | `X` | Polled every 60 seconds | core calls the X API | OAuth 2.0 Client ID and secret, then consent | [X](x.md) |
| Voice (phone calls) | `VOICE` | Twilio webhook plus a WebSocket | `POST /webhook/twilio/voice`, then `wss://<core>/webhook/twilio/voice-relay` | the Twilio connection | [Voice](voice.md) |
| SMS | `SMS` | Webhook | `POST /webhook/twilio/sms` | the Twilio connection | [SMS](../sms-channel.md) |
| Signal | `SIGNAL` | Pulled from signal-cli | your signal-cli REST API | base URL, number | [Signal](signal.md) |
| Matrix | `MATRIX` | Sync loop | your homeserver (`app.matrix.sync.enabled`) | homeserver, bot access token | [Matrix](../matrix-channel.md) |
| iMessage (BlueBubbles) | `IMESSAGE` | Webhook, or polling | `POST /webhook/imessage/{webhookId}`, or core polls your BlueBubbles server | server URL, password | [iMessage](../imessage-channel.md) |

The console also has a **Mobile SDK** tile for pairing an Android or iOS device; it is not a messaging channel and
has no `ChannelType`.

## What every channel shares

* **Default deny with pairing.** A first-time sender gets one reply with a pairing code, and nothing reaches the
  agent until an admin approves that conversation under **Channels > Pairing**. Until then further messages are
  ignored. Contacts you already know, an allow-list or an auto-approve setting skip the step.
* **Replies go through the approval gate** as `channel.<name>.send` (voice is the one exception: a call cannot wait
  for an approval). Approval policies, the audit log and the send quota apply, and the first send of an action type
  waits for approval until you auto-approve it.
* **Inbound turns can be rate limited** per tenant, channel and sender (`app.omnichannel.rate-limit.*`; on in
  `personal`, opt-in elsewhere). A message over the limit is dropped without a reply.
* **A redelivered message is processed once**, de-duplicated on the provider's own message id for 60 minutes
  (`app.omnichannel.dedup.ttl-minutes`). The de-duplication fails open if Redis is down; see
  [replay protection](../security-model.md#replay-protection-on-inbound-channels).
* **Credentials are stored encrypted** with `APP_ENCRYPTION_KEY` and are never returned by the API or written to a
  log. Connecting a channel on a plan that excludes it is refused by the service, not only hidden in the console.
* **Outbound calls to a tenant-chosen address go through the SSRF guard**, which refuses private addresses unless an
  operator listed the host in `app.security.outbound.allowed-private-hosts`
  ([configuration](../configuration.md)).

## Shared requirements

* **A public HTTPS address for webhook channels.** Webhook channels (WhatsApp, Messenger and Instagram, Slack,
  Telegram in webhook mode, Teams, SMS and voice) need core reachable from the internet over HTTPS. iMessage in
  webhook mode needs core reachable from the Mac that runs BlueBubbles. The polled and pulled channels (email, X, Signal, WhatsApp QR, Matrix, Telegram polling) do not, which is
  why they suit a laptop install.
* **`OMNICHANNEL_PUBLIC_BASE_URL`** (`app.omnichannel.public-base-url`): core's public HTTPS base URL. Twilio signs the
  exact URL it called, so behind a proxy or tunnel this must match it or every Twilio call (WhatsApp, SMS, voice)
  fails its signature check. Voice also builds the `wss://` address from it.
* **`META_WEBHOOK_VERIFY_TOKEN`** (`app.meta.webhook-verify-token`): the verify token Meta's webhook handshake sends
  for WhatsApp, Messenger and Instagram. Unset, the handshake is refused.
* **A running Redis** for de-duplication; a Redis outage does not stop messages, it only stops the duplicate check.

## How inbound requests are authenticated

Webhook routes are public by design (the provider has no login), so each is authenticated by what the provider can
prove. The check runs before the message is looked at.

| Channel | What authenticates the call |
|---|---|
| WhatsApp (Meta), Messenger, Instagram | `X-Hub-Signature-256`, HMAC-SHA256 of the body with the tenant's App Secret |
| WhatsApp and SMS and voice (Twilio) | `X-Twilio-Signature`, HMAC of the URL and form fields with the tenant's Auth Token |
| Slack | `X-Slack-Signature` over a timestamp and the body with the tenant's Signing Secret, within 5 minutes (the one-time URL handshake carries no tenant and is answered without it) |
| Telegram | `X-Telegram-Bot-Api-Secret-Token`, compared by SHA-256 digest |
| Teams | a Bot Framework JWT |
| Discord | the shared secret in `X-Internal-Secret` from your bridge |
| iMessage | the per-connection webhook secret (BlueBubbles cannot send headers, so it travels in the query string; keep the path private) |
| Email, X, Signal, WhatsApp QR, Matrix | none needed: core calls out and the provider authenticates core |

Comparisons are constant-time and a missing secret fails closed. See the
[security model](../security-model.md) for what this does and does not protect against.
