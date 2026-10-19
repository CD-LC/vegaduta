-- #604 ("read all messages from telegram" - Quick Task had no Telegram tool
-- at all, because no message HISTORY was ever persisted anywhere: V49's
-- last_message_preview on channel_session only ever holds the single most
-- recent message, overwritten on every touch(). This table is a real,
-- queryable per-message log so an agent tool can answer "what did this
-- Telegram chat say" instead of just "when did it last say something."
--
-- Deliberately a plain UUID column to channel_session (id), not a FK -
-- mirrors tool_call_log's V1 precedent (see channel_session's own FK-to-agent
-- comment for the contrasting case where a hard FK is deliberately used):
-- losing/rebinding a session should never block writing a log row for the
-- message that triggered it.
CREATE TABLE channel_message_log (
    id                   UUID PRIMARY KEY,
    channel              VARCHAR(16) NOT NULL,
    external_id          VARCHAR(64) NOT NULL,
    channel_session_id   UUID,
    body                 VARCHAR(2000) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL
);

-- ChannelMessageQueryPortImpl's read path: "most recent N for this channel"
-- and "most recent N for this channel+sender" are both ordered by created_at
-- DESC, scoped by channel first.
CREATE INDEX idx_channel_message_log_channel_created ON channel_message_log (channel, created_at DESC);
CREATE INDEX idx_channel_message_log_channel_external ON channel_message_log (channel, external_id, created_at DESC);
