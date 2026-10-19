# Edge relay: your laptop is the model behind your number

One signed-in browser with an on-device model can answer a whole SMS number or
WhatsApp account. No API key, no GPU server, no per-token cost: a teacher's
laptop serves the class group, a clinic's front-desk PC answers the patients'
texts, a shop's phone answers its customers, all with the model that the
[free local models](local-models.md) page installed in the browser.

## How it works

1. On **/edge**, with an on-device model installed, pick the channels this
   device should answer (SMS, WhatsApp) and press **Start answering**. The
   browser registers as a *relay* for your workspace and heartbeats every
   twenty seconds.
2. An inbound message on one of those channels, from a paired sender with no
   workflow bound, is queued in Valkey instead of being sent to the hosted
   model. The webhook still acknowledges the provider at once.
3. The browser long-polls, claims the message, and runs it through the local
   tier ladder with the agent's own persona and the last twelve messages of
   that conversation. It hands the text back.
4. The server thread that owns the inbound message sends the reply through
   the same approval gate a hosted reply would use (`channel.sms.send`,
   `channel.whatsapp.send`). The browser never talks to a channel.
5. If no relay is online, the queue is full, the relay does not answer within
   a minute, declines (no local model ready), or Valkey is unavailable, the
   **hosted model answers** the same message on the same thread. A message is
   never lost and never answered twice: a timed-out job is cancelled and a
   late reply is dropped.

Several devices can relay at once; each claims from the queues of the
channels it registered for. The /edge card lists the other devices answering
right now and how many messages each has answered.

## Who may do this

Starting a relay needs the channel-management permission
(`PERM_MANAGE_CHANNELS`), the same one that sets a channel's default agent.
The relay sees the inbound text of its own workspace's senders, which that
permission already grants through the conversation inbox; the sender's phone
number is masked in what the browser receives. A job can only be answered by
the device that claimed it, and only for its own workspace.

## Configuration

| Property | Env | Default | |
|---|---|---|---|
| `app.omnichannel.edge-relay.enabled` | `EDGE_RELAY_ENABLED` | `true` | `false` removes the feature: the register call returns 409 and every message goes hosted |
| `app.omnichannel.edge-relay.heartbeat-ttl-seconds` | `EDGE_RELAY_HEARTBEAT_TTL_SECONDS` | `45` | a relay is online while its heartbeat key lives |
| `app.omnichannel.edge-relay.claim-wait-seconds-max` | `EDGE_RELAY_CLAIM_WAIT_SECONDS_MAX` | `25` | longest a long-poll may block |
| `app.omnichannel.edge-relay.reply-wait-seconds` | `EDGE_RELAY_REPLY_WAIT_SECONDS` | `60` | how long the inbound thread waits for the relay before answering hosted |
| `app.omnichannel.edge-relay.lease-seconds` | `EDGE_RELAY_LEASE_SECONDS` | `90` | lease on a claimed job |
| `app.omnichannel.edge-relay.queue-max` | `EDGE_RELAY_QUEUE_MAX` | `100` | queued messages per channel beyond which new ones go hosted at once |
| `app.omnichannel.edge-relay.history-turns` | `EDGE_RELAY_HISTORY_TURNS` | `12` | earlier messages of the session handed to the relay |

API, all under `/api/channels/edge-relay`: `GET /status`, `POST /register`,
`POST /{relayId}/heartbeat`, `DELETE /{relayId}`, `GET /{relayId}/next?wait=20`
(200 with the job, 204 when idle), `POST /jobs/{jobId}/reply`,
`POST /jobs/{jobId}/fail`.

## Limits, honestly

- Channels: SMS and WhatsApp (Twilio and Meta). Telegram, Slack and the
  others still use the hosted path; adding one is a three-line hook in its
  webhook controller.
- The relay answers text only. A voice note or an image on WhatsApp goes
  hosted as before.
- Server-side tools do not run on the relay path unless the local tier calls
  them through the edge tool bridge; a hosted reply has the full tool set.
- A busy relay answers one message at a time per tab; the queue absorbs
  bursts up to `queue-max`, then overflow goes hosted. Reply latency is the
  device's generation time plus the poll interval.
- Unit-tested on both sides; not yet exercised against a real phone number.
