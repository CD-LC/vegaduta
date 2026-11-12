-- Scratchpad and data-table row quarantine (Phase 0 safety, OWASP ASI06 / LLM04), companion to V676 / V677.
--
-- A note an agent writes with scratchpad_write, or a row it writes with data_table_write, on a turn that
-- handled untrusted content is still attacker-authored when scratchpad_read / data_table_query returns it
-- in a later turn (the agent pad is kept across conversations). `trust` is TRUSTED (readable) or
-- QUARANTINED (stored, hidden from every read path until a person approves it); `source_taint` is a JSON
-- array of what tainted the turn. Existing rows were written before this control, so they default to TRUSTED.

ALTER TABLE agent_scratchpad
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE agent_scratchpad
    ADD CONSTRAINT agent_scratchpad_trust_chk CHECK (trust IN ('TRUSTED', 'QUARANTINED'));

ALTER TABLE data_table_row
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE data_table_row
    ADD CONSTRAINT data_table_row_trust_chk CHECK (trust IN ('TRUSTED', 'QUARANTINED'));

-- Review listings only ever look at the (small) quarantined set.
CREATE INDEX idx_agent_scratchpad_quarantined ON agent_scratchpad(agent_id) WHERE trust = 'QUARANTINED';
CREATE INDEX idx_data_table_row_quarantined ON data_table_row(table_id) WHERE trust = 'QUARANTINED';
