# Telegram

Link a Telegram bot to an agent. The bot is yours (made with @BotFather); people message it and the agent answers.

## How it fits together

Telegram can deliver messages two ways and core uses whichever your install allows:

| Mode | When | How |
|---|---|---|
| **Webhook** | `app.telegram.webhook-base-url` (`TELEGRAM_WEBHOOK_BASE_URL`) is set to core's public HTTPS URL | Connecting the bot registers `https://<base>/webhook/telegram/{botUserId}` with Telegram, along with a secret token. Telegram posts each update there. |
| **Long polling** | No webhook base URL, and `app.telegram.polling.enabled=true` (on in the `lite` profile) | Core calls `getUpdates` itself, one poller per bot, so a laptop or office server with no public address works with nothing but the token. |

Telegram delivers by webhook or by polling, never both. Run polling in **one** process per bot (two pollers on one
bot get HTTP 409); a multi-replica deployment should use the webhook. Replies leave through the approval gate as
`channel.telegram.send`.

## Set it up

1. Open [@BotFather](https://t.me/BotFather) in Telegram, send `/newbot`, follow the prompts and copy the token
   (it looks like `123456789:ABC...`).
2. In the console open **Channels > Telegram** and paste the token. Core asks Telegram who the bot is before saving
   anything, and stores the token encrypted.
3. Choose the agent that answers, then message the bot.

## Who the agent answers

A first-time chat gets one reply with a pairing code and waits under **Channels > Pairing** until an admin approves
it. Updates other than new messages (edits, callback queries) are ignored for now. A repeated update (Telegram
replays pending updates after downtime) is processed once, de-duplicated on `update_id`.

## Security

* **Webhook secret.** Each connection gets a random 192-bit secret that Telegram echoes in the
  `X-Telegram-Bot-Api-Secret-Token` header. The platform stores only the secret's **SHA-256 digest**, never the
  secret, so a copy of the database cannot be used to forge messages; the header is hashed and compared with the
  stored digest in constant time, before anything else is read. A missing, empty or wrong header is a 403, and an
  unknown bot id is ignored with a 200.
* The webhook route is public (the bot's numeric id is in the URL), so the secret is the only authentication.
* **Reconnecting** the bot rotates the secret.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| No messages arrive on a laptop install | Polling is off outside the `lite` profile; set `app.telegram.polling.enabled=true` or give core a public HTTPS URL and set the webhook base URL. |
| HTTP 409 in the log | Two processes are polling the same bot. |
| Webhook calls answered 403 | The secret did not match: reconnect the bot so Telegram and core agree. |
