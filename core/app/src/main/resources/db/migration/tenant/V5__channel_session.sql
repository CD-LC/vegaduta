-- Omnichannel Build Plan Stage 2 (closing its Definition of Done): maps an
-- inbound channel identity (a WhatsApp phone number today, a Telegram chat
-- id once Stage 3 lands) to the session_id SessionMemoryService already uses
-- for Redis-backed chat memory, and to which agent handles it - PRD Section
-- 3.2's "each user channel must map to a unique session_id" requirement.
--
-- FK to agent(id) is a deliberate real integrity constraint (unlike
-- tool_call_log's plain UUID column in V1) - an inbound webhook pointing at
-- a misconfigured/deleted agent id should fail loudly at insert time, not
-- silently create an orphaned session row.

CREATE TABLE channel_session (
    id               UUID PRIMARY KEY,
    channel          VARCHAR(16) NOT NULL,
    external_id      VARCHAR(64) NOT NULL,
    session_id       VARCHAR(64) NOT NULL,
    agent_id         UUID NOT NULL REFERENCES agent (id),
    created_at       TIMESTAMPTZ NOT NULL,
    last_message_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_channel_session_channel_external UNIQUE (channel, external_id)
);

CREATE INDEX idx_channel_session_agent_id ON channel_session (agent_id);
