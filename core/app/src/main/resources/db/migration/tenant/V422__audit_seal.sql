-- Tamper-evident audit log: periodic SEALS over ranges of audit_log_entry (core/audit, seal/*).
--
-- Tenant schema, next to the audit_log_entry rows each seal covers (those rows are already append-only,
-- see V75). A seal records a contiguous range of audit rows ordered by (created_at, id), a SHA-256
-- sequential digest of that range (range_hash), the hash of the previous seal (prev_seal_hash; 64 zeros for
-- the first), its own hash over all of that (seal_hash), and an Ed25519 signature (compact JWS in
-- `signature`, with the signer's kid and raw public key so the seal is self-describing). The hash/encoding
-- contract is documented on AuditSealHasher and re-implemented by scripts/verify-audit-export.py.
--
-- Nothing here alters audit_log_entry. The write path of the audit log is untouched (no per-row hash,
-- no per-row lock): seals are produced off the request path by AuditSealSweep.
--
-- first_row_at / last_row_at hold the boundary rows' created_at (timestamptz = microsecond precision, the
-- precision the seal hash is computed over). purged_at is the retention marker: set before a purge job
-- deletes a range's rows so verification checks that range by chain + signature only. It is NOT part of
-- seal_hash, and it is the only column the trigger below lets change.
--
-- Additive only: a new table, nothing existing is altered. Out-of-order safe (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS audit_seal (
    id              UUID PRIMARY KEY,
    seq             BIGINT       NOT NULL,
    tenant_scope    VARCHAR(255) NOT NULL,
    first_row_id    UUID         NOT NULL,
    first_row_at    TIMESTAMPTZ  NOT NULL,
    last_row_id     UUID         NOT NULL,
    last_row_at     TIMESTAMPTZ  NOT NULL,
    row_count       INTEGER      NOT NULL,
    range_hash      VARCHAR(64)  NOT NULL,
    prev_seal_hash  VARCHAR(64)  NOT NULL,
    seal_hash       VARCHAR(64)  NOT NULL,
    algorithm       VARCHAR(32)  NOT NULL,
    signature       TEXT,
    key_id          VARCHAR(128),
    public_key      VARCHAR(128),
    sealed_at       TIMESTAMPTZ  NOT NULL,
    purged_at       TIMESTAMPTZ,
    CONSTRAINT uq_audit_seal_seq UNIQUE (seq)
);

CREATE INDEX IF NOT EXISTS ix_audit_seal_range ON audit_seal (last_row_at, first_row_at);

-- Same database-level append-only guarantee (and the same honest caveats about a superuser/owner who can
-- disable triggers) as V75 gives audit_log_entry: DELETE is refused outright; UPDATE is refused unless it
-- changes only the purged_at retention marker.
CREATE OR REPLACE FUNCTION audit_seal_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_seal is append-only: DELETE is not permitted (seal id=%)', OLD.id;
    END IF;
    IF (NEW.id, NEW.seq, NEW.tenant_scope, NEW.first_row_id, NEW.first_row_at, NEW.last_row_id, NEW.last_row_at,
        NEW.row_count, NEW.range_hash, NEW.prev_seal_hash, NEW.seal_hash, NEW.algorithm, NEW.signature,
        NEW.key_id, NEW.public_key, NEW.sealed_at)
       IS DISTINCT FROM
       (OLD.id, OLD.seq, OLD.tenant_scope, OLD.first_row_id, OLD.first_row_at, OLD.last_row_id, OLD.last_row_at,
        OLD.row_count, OLD.range_hash, OLD.prev_seal_hash, OLD.seal_hash, OLD.algorithm, OLD.signature,
        OLD.key_id, OLD.public_key, OLD.sealed_at) THEN
        RAISE EXCEPTION 'audit_seal is append-only: only purged_at may be updated (seal id=%)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_seal_immutable ON audit_seal;
CREATE TRIGGER trg_audit_seal_immutable
    BEFORE UPDATE OR DELETE ON audit_seal
    FOR EACH ROW
    EXECUTE FUNCTION audit_seal_block_mutation();
