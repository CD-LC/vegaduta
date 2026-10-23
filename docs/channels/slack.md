# Slack

Let people talk to an agent in Slack: direct messages to the bot and, if you subscribe to them, messages in
channels the bot has joined. You bring your own Slack app (a bot token and a signing secret); nothing runs in a
shared VegaDūta Slack app.

## How it fits together

```
Slack (your app)  --Events API POST-->  https://<your-core-host>/webhook/slack  -->  agent
Slack  <--chat.postMessage--  VegaDūta core  (through the approval gate)
```

* **Inbound is a webhook.** Slack's Events API posts every subscribed event to `/webhook/slack`. Core must be
  reachable from the internet over HTTPS (see `OMNICHANNEL_PUBLIC_BASE_URL` in the
  [channels overview](README.md#shared-requirements)).
* **Outbound goes through the approval gate** as `channel.slack.send`, like every other channel, so approval
  policies, the audit log and the send quota apply.
* **One workspace belongs to one tenant.** Slack tells core only the workspace (`team_id`), and every app
  installed in a workspace shares it, so connecting a workspace that another tenant on the same platform already
  connected is refused ("already connected to another workspace"). Reconnecting your own workspace is fine.

## Set it up

1. At [api.slack.com/apps](https://api.slack.com/apps) create an app (**From scratch**) in your workspace.
2. Under **OAuth & Permissions** add the bot token scopes `chat:write`, `im:history` and `channels:history`, then
   install the app to the workspace and copy the **Bot User OAuth Token** (`xoxb-...`).
3. Under **Basic Information > App Credentials** copy the **Signing Secret**.
4. In the console open **Channels > Slack**, paste the bot token and the signing secret and connect. Core checks
   the token with Slack's `auth.test` before saving anything.
5. Under **Event Subscriptions** turn events on, set the **Request URL** to
   `https://<your-core-host>/webhook/slack`, and subscribe to the bot events `message.im` (direct messages) and,
   if you want the agent in channels, `message.channels`. Slack's URL check succeeds once the platform answers its
   `url_verification` handshake, which it does without a signature (the handshake carries no workspace id to find
   your signing secret with); it echoes only a short challenge token and changes nothing.
6. Choose the agent that answers (**Route inbound to**), and send the bot a message.

## Who the agent answers

* A first-time conversation gets one reply with a **pairing code** and waits under **Channels > Pairing** until an
  admin approves it; until then further messages are ignored.
* A conversation is keyed by the **Slack channel id** (a DM is its own channel), and the reply goes back to that
  channel. Everyone in an approved channel can then talk to the agent.
* Only plain new messages from people are answered: the bot's own messages, anything with a `bot_id`, and
  message subtypes (edits, deletions, joins) are dropped, so the bot cannot answer itself.

## Security

* Every event must carry a valid `X-Slack-Signature` for the tenant's signing secret and an
  `X-Slack-Request-Timestamp` no more than five minutes from the server's clock; anything else is dropped with
  nothing processed. The comparison is constant-time and runs before any processing. The signing secret is stored
  encrypted.
* A repeated delivery (Slack retries when answered slowly) is processed once, de-duplicated on the event id.
* Slack is the only inbound channel with a signed timestamp window. See [replay protection](../security-model.md#replay-protection-on-inbound-channels)
  for the others.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Slack refuses the Request URL | Core is not reachable over HTTPS from the internet, or the URL is not exactly `/webhook/slack`. |
| Messages arrive but get no reply | The conversation is waiting for approval under **Channels > Pairing**, or no default agent is chosen. |
| Events stopped after connecting a second app in the workspace | The workspace link belongs to one tenant; connect only one app per workspace per platform. |
