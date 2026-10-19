-- Turn journal (docs/architecture/ADR-0020-turn-journal.md): an append-only, hash-chained record of the
-- stages of one agent turn (intake, guard, model call, tool call, approval, verify, commit).
--
-- Payloads are NEVER stored: payload_hash is the SHA-256 of the prompt / tool arguments / answer and
-- summary is a short redacted structural description. Each row's hash covers prev_hash and its own
-- content, so an edited, removed or reordered row is detectable (TurnHashChain.verify).
--
-- agent_id is NOT a foreign key on purpose (as edge_turn, V191): the evidence must outlive the agent.
-- Rows may be deleted (retention, DPDP erasure) but never updated: a trigger rejects UPDATE.
-- Written only when app.turn-journal.enabled=true. Schema-per-tenant: no tenant_id column.

CREATE TABLE IF NOT EXISTS turn_journal_event (
    turn_id       UUID         NOT NULL,
    seq           INTEGER      NOT NULL,
    agent_id      UUID         NOT NULL,
    event_type    VARCHAR(16)  NOT NULL,
    payload_hash  CHAR(64)     NOT NULL,
    summary       VARCHAR(512) NOT NULL,
    prev_hash     CHAR(64)     NOT NULL,
    hash          CHAR(64)     NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (turn_id, seq),
    CONSTRAINT turn_journal_event_type_chk CHECK (
        event_type IN ('INTAKE','GUARD','MODEL_CALL','TOOL_CALL','APPROVAL','VERIFY','COMMIT')),
    CONSTRAINT turn_journal_event_seq_chk CHECK (seq >= 0)
);

CREATE INDEX IF NOT EXISTS idx_turn_journal_event_agent_created
    ON turn_journal_event (agent_id, created_at);

CREATE OR REPLACE FUNCTION turn_journal_event_no_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'turn_journal_event is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_turn_journal_event_no_update ON turn_journal_event;
CREATE TRIGGER trg_turn_journal_event_no_update
    BEFORE UPDATE ON turn_journal_event
    FOR EACH ROW EXECUTE FUNCTION turn_journal_event_no_update();
