# Discord

Send messages to Discord channels and DMs as an agent, with your own bot. **Receiving** Discord messages needs a
separate Gateway bridge service that **this repository does not contain**; without one, Discord is send-only.

## What works out of the box, and what does not

| | Works with just a bot token | Needs the Gateway bridge |
|---|---|---|
| Send a message or file to a channel or DM (through the approval gate, as `channel.discord.send`) | yes | no |
| An agent answering people who message the bot | no | yes |
| Workflow and agent triggers on "Discord message received" | no | yes |

Discord has no public webhook for ordinary messages. Reading them means holding a persistent Gateway WebSocket
open with the bot's token, and that connection is held by a standalone Node service (referred to in the code and
the console as `channels/discord`) that forwards each message to core. That service is not part of this
repository, its compose files or the Helm chart, so on a stock install the console's Discord tile can send but
not receive.

## Set it up (sending)

1. At [discord.com/developers/applications](https://discord.com/developers/applications) create an application,
   add a **Bot**, turn on **Message Content Intent**, and copy the bot token.
2. Under **OAuth2 > URL Generator** select the `bot` scope with *Send Messages* and *Read Message History* and open
   the generated URL to invite the bot to your server.
3. In the console open **Channels > Discord** and paste the bot token. Core asks Discord who the bot is and stores
   the application id and name; the token is stored encrypted.

## If you run your own Gateway bridge

Core defines the contract; the bridge is yours to write or obtain. Both settings must be set for core to talk to it:

| Property | Env | Meaning |
|---|---|---|
| `app.omnichannel.discord.base-url` | `DISCORD_GATEWAY_SERVICE_URL` | Where the bridge listens. A private address must also be listed in `app.security.outbound.allowed-private-hosts`. |
| `app.omnichannel.discord.shared-secret` | `DISCORD_GATEWAY_SHARED_SECRET` | One secret for both directions of the link. Empty means every inbound call is refused. |

* When a tenant connects a bot, core calls `POST {base-url}/bots/{applicationId}/start` with
  `{"botToken": "..."}` (and `DELETE {base-url}/bots/{applicationId}` on disconnect), sending
  `X-Internal-Secret: <shared secret>`. The call is best-effort: if it fails, the connection still saves and
  sending still works.
* For each `MESSAGE_CREATE` the bridge hears, it must `POST https://<your-core-host>/webhook/discord` with the same
  `X-Internal-Secret` header and a JSON body `{"applicationId", "channelId", "authorId", "content", "messageId"}`
  (`messageId` is optional but lets core drop a replay). A missing or wrong secret is answered 403 before anything
  is looked up.
* A conversation is keyed by the Discord channel id, the same pairing rules apply as for every channel, and
  replies go out through core's own Discord REST client, never through the bridge.

## Security

The shared secret is compared in constant time and the webhook fails closed when it is not configured. Because
the secret is the only authentication, run the bridge on a private network and treat the secret like a password.
