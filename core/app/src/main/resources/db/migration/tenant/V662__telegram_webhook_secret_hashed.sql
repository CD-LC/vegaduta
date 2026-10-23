-- V662 - the Telegram webhook secret is kept as its SHA-256 digest, not as the token (2026-10-22).
--
-- X-Telegram-Bot-Api-Secret-Token is the only authentication on the public
-- /webhook/telegram/{botUserId} route, so a copy of this table must not be enough to forge inbound
-- messages as an already-paired chat. The platform only has to recognise the value Telegram echoes
-- back; it handed the raw token to Telegram once (setWebhook) and does not need it again. Existing
-- rows are rewritten in place to the hex digest (64 characters, which fits the existing
-- VARCHAR(64)); Telegram still holds the raw token, so registered webhooks keep working.
--
-- The column keeps its name. This runs once per schema: re-running it would digest a digest, so
-- never copy this statement into another migration.

UPDATE telegram_credential
   SET webhook_secret = encode(sha256(convert_to(webhook_secret, 'UTF8')), 'hex');
