# WhatsApp QR channel (linked device)

Talk to an agent over WhatsApp **from your own number**, linked like WhatsApp Web is: a QR scan
pairs a companion device held by a small bridge service you run. No Meta Business account, no
cloud API credential, and - the part that matters on a personal install - **no public URL**: the
bridge only dials out to WhatsApp, so this works on a laptop that nothing on the internet can
reach. This is the answer to "why does the WhatsApp connect form only offer Meta/Twilio?": those
are the official Business Platform paths; this channel is the unofficial WhatsApp-Web-style one,
and it lives here so the two are never confused.

```
WhatsApp  <->  whatsapp-qr bridge (yours, Baileys)  <->  VegaDūta core  ->  agent
                     state: whatsapp-qr-data volume        (pulls messages, sends replies)
```

## Know the trade-off first

Linked-device sessions ride the WhatsApp Web protocol through a third-party library
([Baileys](https://github.com/WhiskeySockets/Baileys)). Meta does not bless this the way it does
the Business Platform, and an account can in principle be flagged or challenged. For a personal
assistant on your own number that is usually an acceptable risk - it is the same posture every
OpenClaw-style bot takes - but it is your call to make, and Meta Cloud API remains the right
answer for anything business-facing.

* One session = your number. Incoming messages become conversations in the same inbox, pairing
  and approval machinery as every other channel; replies go through the `channel.whatsappqr.send`
  approval action (policies, audit, quotas apply).
* A first-time sender gets one pairing code; nobody is answered until you approve the session in
  the console. That is not optional on this channel - your own number is the published address.
* **Groups are not supported in this version**: the bridge drops `@g.us`, broadcasts and status
  posts before they reach core.
* If Meta Cloud API or Twilio is also configured, sends still prefer them (provider precedence
  10/20 over this channel's 30); the QR session answers when it is the only WhatsApp path or when
  a conversation lives on it.

## Set it up

1. In `deploy/compose/.env` set one shared secret and point core at the bridge (all four lines;
   the allow-list gets `whatsapp-qr:8080` appended to whatever it already holds):

   ```
   WHATSAPP_QR_BRIDGE_SECRET=<openssl rand -hex 32>
   APP_WHATSAPPQR_BRIDGE_URL=http://whatsapp-qr:8080
   APP_WHATSAPPQR_BRIDGE_SECRET=<the same value>
   APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS=whatsapp-qr:8080,host.docker.internal:11434
   ```
2. Start the bridge next to your stack:

   ```
   docker compose -f deploy/compose/personal.yml -f deploy/compose/whatsapp-qr.yml \
     --env-file deploy/compose/.env --profile whatsapp-qr up -d
   ```

   First time only, build the image: the same command with `build whatsapp-qr` instead of `up`.
   The service publishes no port.
3. In the console open **Channels > WhatsApp QR**, press **Link a device**, and scan the code
   with WhatsApp > Settings > Linked devices > Link a device. The code rotates on its own; the
   page follows it.
4. Choose the agent that answers (**Route inbound to**).

The linked device survives restarts in the `whatsapp-qr-data` volume. **Unlink** in the console
logs the device out on WhatsApp's side too; you can also remove it from the phone under Linked
devices.

## Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `app.whatsappqr.bridge-url` | `APP_WHATSAPPQR_BRIDGE_URL` | empty | Where the bridge lives. Empty hides the whole channel. |
| `app.whatsappqr.bridge-secret` | `APP_WHATSAPPQR_BRIDGE_SECRET` | empty | Shared Bearer token, sent only over https or to an allow-listed host. |
| `app.whatsappqr.receive.enabled` | `APP_WHATSAPPQR_RECEIVE_ENABLED` | `true` | Run the per-session poll loop. Idle until a session exists; `false` in the jobs/worker shapes. |
| `app.whatsappqr.receive.poll-seconds` | (none) | `15` | Long-poll wait per request (1 to 20). |
| `app.whatsappqr.max-message-chars` | (none) | `60000` | A reply longer than this is split into several messages. |

Bridge side: `WHATSAPP_BRIDGE_PORT` (8080), `WHATSAPP_BRIDGE_DATA_DIR` (`/data`),
`WHATSAPP_BRIDGE_QUEUE_CAP` (500 undelivered messages per session).

Run the receiver in ONE core process: a session's long-poll hands each message out once.

## API

All under `/api/channels/whatsapp-qr`, all gated on the channel-management permission - GET
included, because while pairing is open it returns the QR payload that completes a link.

| Method and path | Purpose |
|---|---|
| `POST /session` | Start or restart pairing; answer is `{status, bridgeStatus, qr, phoneNumber, configured}`. |
| `GET /session` | Current state; `qr` only while `PAIRING`. 204 when nothing was ever linked. |
| `DELETE /session` | Unlink: logs the device out on the bridge, keeps the row at DISCONNECTED. |
| `POST /send` | Send `{to, body}` through the approval gate; `to` is digits or a stored `<digits>@lid`. |
| `PUT /default-agent` | Which agent answers new conversations. |

## Limits and notes

* The QR payload is never stored and never logged; it lives in the bridge's memory and in the
  console's response body while pairing is open.
* A sender who hides their number behind WhatsApp's privacy identifier appears as `<digits>@lid`;
  their session, pairing and replies work on that opaque id.
* Messages that arrive while core is down wait in the bridge's queue (500 per session) and are
  delivered when the receiver reconnects.
* A bridge restart loses in-memory sessions; the credentials on disk resume the link without a
  new scan.
* Not covered by a test against a real WhatsApp: the tests use a fake bridge that speaks the
  documented endpoints, and the bridge's own tests use a fake socket.
