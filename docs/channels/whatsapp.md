# WhatsApp (Meta Cloud API and Twilio)

The official WhatsApp Business paths. You connect **one** of two transports in **Channels > WhatsApp**, each on its
own tab. (The unofficial linked-device path is [WhatsApp QR](whatsapp-qr.md), and Meta's rules about what an
assistant may do are in [WhatsApp policy limits](whatsapp-policy.md).)

Connecting is a plan entitlement, enforced when you connect and not only in the console: Meta needs
`plan_limits.whatsapp_allowed`, Twilio needs `plan_limits.external_channels_allowed`.

| | Meta WhatsApp Cloud API | Twilio WhatsApp |
|---|---|---|
| Inbound webhook | `GET` + `POST https://<your-core-host>/webhook/meta/whatsapp` | `POST https://<your-core-host>/webhook/twilio/whatsapp` |
| Delivery failures | in the Meta payload | `POST /webhook/twilio/status` (failed and undelivered sends are written to the audit log) |
| You give the platform | Phone Number ID, WABA ID, access token, App Secret | Account SID, Auth Token, WhatsApp sender number (an API key is optional) |
| Inbound is authenticated by | `X-Hub-Signature-256`, an HMAC of the body with your App Secret | `X-Twilio-Signature`, an HMAC of the exact URL and form fields with your Auth Token |
| Tenant is found from | `metadata.phone_number_id` in the payload | the `AccountSid` in the form |
| Outbound action | `channel.whatsapp.send` through the approval gate | `channel.whatsapp.send` through the approval gate |

## Meta WhatsApp Cloud API

1. At [developers.facebook.com](https://developers.facebook.com) create a **Business** app and add the **WhatsApp**
   product. Under **WhatsApp > API Setup** copy the **Phone Number ID** and **WABA ID** and create an access token
   (a permanent System User token is the production choice; the temporary one expires). Under **App Settings >
   Basic** copy the **App Secret**.
2. In the console open **Channels > WhatsApp > Meta** and enter the four values. They are stored encrypted.
3. In Meta's webhook settings set the **Callback URL** to `https://<your-core-host>/webhook/meta/whatsapp` and the
   **Verify token** to the value of `META_WEBHOOK_VERIFY_TOKEN` (`app.meta.webhook-verify-token`), which the
   operator sets on core once for the platform. Subscribe to the `messages` field. Meta's verification call is
   answered only when that token matches (constant-time) and is refused when no token is configured.
4. Choose the agent that answers and send the number a message.

Operator options: **embedded signup** (a guided connection that uses the platform's own Meta app, configured with
`app.meta.embedded-signup.*` and needing Meta's app review), **message templates**
(`app.omnichannel.whatsapp.templates.enabled`, off by default), and **voice notes** answered in the sender's
language (`app.omnichannel.whatsapp.voice-notes.enabled`, off by default). Without voice notes enabled, only text
messages are answered and other message types are skipped.

## Twilio WhatsApp

1. In the [Twilio console](https://www.twilio.com/console) copy the **Account SID** and **Auth Token**. For testing
   use Twilio's shared WhatsApp sandbox number; for production register a WhatsApp sender (Messaging > Senders).
   An API key (SID `SK...` plus secret) can be supplied as well, for everyday API calls, but the Auth Token is
   still required, because the inbound signature has no API-key equivalent.
2. In the console open **Channels > WhatsApp > Twilio** and enter the Account SID, Auth Token and the WhatsApp
   sender number. The credentials are stored encrypted.
3. In Twilio set the sender's **"When a message comes in"** webhook to
   `https://<your-core-host>/webhook/twilio/whatsapp` (HTTP POST).
4. Set `OMNICHANNEL_PUBLIC_BASE_URL` (`app.omnichannel.public-base-url`) to core's public HTTPS address whenever core
   sits behind a proxy or tunnel: Twilio signs the exact URL it called, and core must rebuild that URL to verify it.
   A wrong value makes every call fail the signature check.

The same Twilio connection also carries [SMS](../sms-channel.md) and [voice](voice.md).

## Who the agent answers

A first-time number gets one reply with a pairing code and waits under **Channels > Pairing** until an admin
approves it (or the channel is set to auto-approve). The number is the conversation, so the same person keeps one
memory. A redelivery is processed once, de-duplicated on Meta's message id (`wamid`) or Twilio's `MessageSid`.
If many messages arrive at once, an overloaded webhook answers 503 so the provider redelivers
(`INBOUND_EXECUTOR_SHED_MODE`, see [configuration](../configuration.md)).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Meta cannot verify the callback URL | `META_WEBHOOK_VERIFY_TOKEN` is unset on core or differs from the token typed in Meta. |
| Every Twilio call is rejected | `OMNICHANNEL_PUBLIC_BASE_URL` does not match the URL Twilio calls, or the Auth Token is wrong. |
| Messages arrive but nothing is sent back | Waiting for pairing approval, or the send is queued for approval on the Approvals page. |
| A template send is refused | No active opt-in for that number; see [WhatsApp policy limits](whatsapp-policy.md). |
