-- V452 (assigned to the chat-portability lane; one Flyway namespace is shared across
-- controlplane + tenant dirs). Tenant schema: unqualified, no tenant_id.
--
-- imported_chat - a conversation the user brought in from another assistant (ChatGPT /
-- Claude export). Live chat sessions are Redis lists with a TTL (no durable session
-- table exists), so an imported conversation is its own small read-only record owned by
-- the importing user's subject: `messages` is the linear transcript as
-- [{"role":"user|assistant","content":"...","ts":"ISO-8601|null","attachments":["name"]}].
-- Attachment BINARIES are never stored, only their names. Nothing here is ever read by a
-- model, memory or embedding path. The feature is OFF by default (app.chat-import.enabled);
-- this DDL is inert until then.

CREATE TABLE IF NOT EXISTS imported_chat (
    id                 UUID PRIMARY KEY,
    owner_subject      VARCHAR(255) NOT NULL,
    source             VARCHAR(20)  NOT NULL,
    source_id          VARCHAR(128),
    title              VARCHAR(300) NOT NULL,
    source_created_at  TIMESTAMPTZ,
    source_updated_at  TIMESTAMPTZ,
    imported_at        TIMESTAMPTZ  NOT NULL,
    message_count      INTEGER      NOT NULL DEFAULT 0,
    truncated          BOOLEAN      NOT NULL DEFAULT FALSE,
    messages           JSONB        NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_imported_chat_owner ON imported_chat (owner_subject, imported_at DESC);

-- Re-importing the same export must not duplicate a conversation (source_id is the id the
-- other assistant gave it; rows without one are never deduplicated).
CREATE UNIQUE INDEX IF NOT EXISTS uq_imported_chat_source
    ON imported_chat (owner_subject, source, source_id) WHERE source_id IS NOT NULL;
