-- Memory provenance and quarantine (Phase 0 safety, OWASP ASI06 / LLM04).
--
-- A fact remembered on a turn that handled untrusted content is still attacker-authored when it is
-- recalled next month. `trust` is TRUSTED (recallable) or QUARANTINED (stored, never recalled or
-- injected into a prompt until a person approves it); `source_taint` is a JSON array of what
-- tainted the turn. Existing rows were written by people or before this control, so they default
-- to TRUSTED. Raw JDBC tables (no Hibernate mapping, see V18 / V77).

ALTER TABLE agent_memory
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE tenant_memory
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE agent_memory  ADD CONSTRAINT agent_memory_trust_chk  CHECK (trust IN ('TRUSTED', 'QUARANTINED'));
ALTER TABLE tenant_memory ADD CONSTRAINT tenant_memory_trust_chk CHECK (trust IN ('TRUSTED', 'QUARANTINED'));
