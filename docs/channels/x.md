# X (Twitter) direct messages

Answer direct messages sent to an X account. It needs **X's paid Basic API tier or higher** for DM access, and you
bring your own X developer app (an OAuth 2.0 client id and secret).

## How it fits together

* **Inbound is polled.** X's DM endpoints have no delivery mechanism at the API tiers a self-service connection can
  reasonably ask for, so core polls `GET /2/dm_events` every 60 seconds for every tenant with a connected account
  (20 seconds after start-up). One replica polls at a time, because the poll position is advanced without
  concurrency control. Expect a reply within a minute or two, not instantly.
* **Outbound** replies go to the same DM conversation through the approval gate as `channel.x.send`.
* A conversation is keyed by the X **DM conversation id**.

## Set it up (two steps)

1. In the [X developer portal](https://developer.x.com/en/portal/dashboard) create a project and app. Under **User
   authentication settings** turn on OAuth 2.0, set the app type to **Confidential client**, and request the scopes
   `dm.read`, `dm.write`, `tweet.read`, `users.read` and `offline.access`. Add the platform's **callback URL** as a
   Callback URI: it is `<app.x.oauth.redirect-uri>`, the same value for every tenant, so ask your platform operator.
2. In the console open **Channels > X - DMs**: enter the **Client ID** and **Client Secret** and **Register app**
   (the secret is stored encrypted), then **Connect X Account** to approve access on X. X sends you back to the
   console's Channels page when you finish.

## Operator settings

| Property | Meaning |
|---|---|
| `app.x.oauth.redirect-uri` | The callback URL registered with X (path `/oauth/x/callback` on core's public address). |
| `app.x.oauth.console-redirect-uri` | Where X sends the browser after consent. Defaults to `<app.web.public-url>/admin/channels`; with neither set, the callback refuses with a message naming both. |

## Security

The consent flow carries the tenant and the PKCE verifier in an encrypted `state` value, which expires after ten
minutes and is single-use. The browser that completes consent is not proven to be the one that started it unless
`app.oauth.state.require-browser-cookie` is on (off by default); see
[security hardening](../engineering/SECURITY-HARDENING.md) for the trade-off.
Access and refresh tokens are stored encrypted.
