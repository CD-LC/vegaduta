# Microsoft Teams

Put an agent in Microsoft Teams through your own Azure Bot registration. People message the bot in Teams and the
agent answers in the same conversation.

## How it fits together

```
Teams  ->  Azure Bot Service  --POST (Bot Framework JWT)-->  https://<your-core-host>/webhook/teams/<App ID>  ->  agent
Teams  <--  Bot Framework connector (serviceUrl)  <--  VegaDūta core (through the approval gate)
```

* **Inbound is a webhook, one path per bot.** The path carries your Microsoft App ID, which is how core finds your
  tenant.
* **Outbound** goes to the `serviceUrl` the incoming activity named, with the bot's Entra access token, through the
  approval gate as `channel.teams.send`. Core remembers the `serviceUrl` per conversation.

## Set it up

1. In the Azure portal create an **Azure Bot** resource (or reuse a bot registration) and add the Teams channel.
2. Copy the **Microsoft App ID**, and create a client secret under **Certificates & secrets** in the linked Entra
   app registration. The **Entra tenant id** is optional: leave it blank for the usual multi-tenant bot (core uses
   `botframework.com`); a single-tenant registration passes its own tenant GUID.
3. In the console open **Channels > Microsoft Teams** and enter the App ID and client secret. Core mints a real Bot
   Framework token with them before saving, and stores the secret encrypted.
4. In Azure, under the bot's **Configuration**, set the **Messaging endpoint** to
   `https://<your-core-host>/webhook/teams/<your App ID>`. **Inbound messages do not arrive until this is set.**
5. Choose the agent that answers and message the bot in Teams.

## Who the agent answers

A first-time conversation gets a pairing code and waits under **Channels > Pairing**. A conversation is keyed by the
Bot Framework conversation id. Only `message` activities with text are answered (an @mention of the bot is stripped
from the text); other activity types are ignored. A redelivered activity is processed once, de-duplicated on the
activity id.

## Security

* Every call must carry a Bot Framework JWT in `Authorization: Bearer`. Core checks the signature against
  Microsoft's published keys, that it is unexpired, issued by `api.botframework.com`, addressed to your App ID, and
  that its `serviceurl` claim equals the activity's `serviceUrl`. Anything else is a 403 and nothing is processed.
  There is no development bypass: the Bot Framework Emulator is not supported on this endpoint.
* The `serviceUrl` receives the bot's access token on every reply, so it must be `https` on an allow-listed host.
  The default list is Microsoft's connector hosts (`smba.trafficmanager.net`, `*.botframework.com`,
  `*.teams.microsoft.com` and the US government and China equivalents); override it with
  `app.teams.service-url-allowed-hosts` only if you know why.
* A request for an App ID that is not the connected bot is a 403.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| The bot never receives a message | The Messaging endpoint in Azure is not set to `/webhook/teams/<App ID>`. |
| 403 in the log "invalid Bot Framework JWT" | Wrong App ID in the URL or the console, or a `serviceUrl` outside the allow-list. |
