-- Optimistic locking for the tenant-schema entities where lost updates hurt
-- most (JAVA-007):
--   agent               - ~30 per-field PATCH endpoints each load -> set one
--                         field -> save the full 58-column row; two concurrent
--                         PATCHes reverted each other.
--   workflow_definition - editor saves vs. trigger/sharing updates.
--   studio_app          - publishedVersion + 1 read-modify-write.
--   payment_transaction - money state; transitions are additionally row-locked
--                         (PaymentTransactionRepository#findByIdForUpdate).
-- Each entity now has a JPA @Version; a lost race is a 409, not a silent
-- overwrite.
--
-- VERSION NUMBER: V365.2 - OSS-local dotted minor version (see
-- controlplane/V365.1 and docs/engineering/FLYWAY-VERSIONING.md). One shared
-- namespace across controlplane/ and tenant/.
--
-- Additive, metadata-only on Postgres 11+ (constant DEFAULT, no rewrite).
ALTER TABLE agent ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workflow_definition ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE studio_app ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_transaction ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
