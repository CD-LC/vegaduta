-- Knowledge-base write quarantine (Phase 0 safety, OWASP ASI06 / LLM04), companion to V676.
--
-- Text an agent saves with add_to_knowledge_base on a turn that handled untrusted content is still
-- attacker-authored when search_knowledge_base retrieves it later. `trust` is TRUSTED (retrievable)
-- or QUARANTINED (stored, excluded from every retrieval path until a person approves it);
-- `source_taint` is a JSON array of what tainted the turn. Existing rows were written by people or
-- before this control, so they default to TRUSTED. Raw JDBC table (no Hibernate mapping, see V10).

ALTER TABLE knowledge_chunks
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_trust_chk CHECK (trust IN ('TRUSTED', 'QUARANTINED'));

-- Review listings only ever look at the (small) quarantined set.
CREATE INDEX idx_knowledge_chunks_quarantined ON knowledge_chunks(collection_id) WHERE trust = 'QUARANTINED';
