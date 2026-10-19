-- #815 Governance follow-up (AI-GOVERNANCE-ASSESSMENT.md's other flagged top
-- next investment, alongside per-agent autonomy tiers in V74) - make
-- audit_log_entry tamper-evident at the database layer, not just "nothing
-- in this codebase currently updates or deletes it" (AuditLogService's own
-- javadoc already establishes that as the ONLY write path - see that class
-- - but a javadoc convention isn't a guarantee against a future bug, a
-- forgotten migration, or direct psql access).
--
-- IMPORTANT caveat, stated honestly rather than glossed over: this app
-- connects to Postgres as a single role (POSTGRES_USER, default `agentic`
-- per application.yml) that both owns this schema (it ran every migration,
-- including this one) and performs all DML. In Postgres, a table's OWNER
-- bypasses ordinary GRANT/REVOKE privilege checks entirely - so
-- `REVOKE UPDATE, DELETE ON audit_log_entry FROM agentic` below is
-- genuinely inert against that same role today. It's included anyway as
-- correct, forward-compatible policy for the day this platform splits into
-- a least-privileged app role distinct from the migration/owner role - at
-- that point this REVOKE starts actually doing something, with zero
-- follow-up migration required.
--
-- The control that actually holds TODAY is the trigger below: a BEFORE
-- UPDATE OR DELETE trigger fires for every role, including the table owner
-- (triggers are not subject to the ownership bypass GRANT/REVOKE is) - so
-- it blocks tampering regardless of which DB user issues the statement.
-- The one honest hole: a superuser (or the owner) could still
-- `ALTER TABLE audit_log_entry DISABLE TRIGGER ...` before tampering. That
-- action itself lands in Postgres's own DDL/statement logging if
-- log_statement is configured - closing that residual gap is infrastructure
-- (WAL shipping / external log forwarding), not application code, and is
-- explicitly out of scope for this migration.
--
-- Deliberately does NOT block INSERT (that's the only allowed write) and
-- does NOT attempt row-level erasure exceptions for GDPR/right-to-erasure
-- here - see #833 (PII handling review), which is the right place to
-- decide how a legally-required deletion is reconciled with "immutable."
-- Shipping blanket immutability now and revisiting erasure specifically,
-- rather than inventing an untested carve-out today, is the more honest
-- sequencing.

REVOKE UPDATE, DELETE ON audit_log_entry FROM CURRENT_USER;

CREATE OR REPLACE FUNCTION audit_log_entry_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log_entry is append-only: % is not permitted (row id=%)',
        TG_OP, COALESCE(OLD.id, NULL);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_entry_immutable
    BEFORE UPDATE OR DELETE ON audit_log_entry
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_entry_block_mutation();
