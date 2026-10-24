-- Turn journal: find a turn by its chat session (docs/architecture/ADR-0020-turn-journal.md).
--
-- A turn used to be reachable only by typing its random turn id. Every event of a turn now carries
-- session_hash, the SHA-256 (hex) of the chat session id the turn ran in, so "the turns of this session" is one
-- indexed read. Only the digest is stored: the session id is a client-chosen string, the column is an index and not
-- a copy of it, and a fixed-size key keeps the index small whatever the id looks like. The same digest is also
-- written into the turn's INTAKE summary, which is inside the hash chain, so the link between a turn and its
-- session is tamper-evident even though this column is not.
--
-- Nullable: rows written before this migration, and turns that have no session (a unit-test fixture), have none.
-- The append-only trigger rejects UPDATE, so old rows keep NULL by design. Additive and idempotent.

ALTER TABLE turn_journal_event ADD COLUMN IF NOT EXISTS session_hash CHAR(64);

CREATE INDEX IF NOT EXISTS idx_turn_journal_event_agent_session
    ON turn_journal_event (agent_id, session_hash, created_at)
    WHERE session_hash IS NOT NULL;
