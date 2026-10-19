-- #815 Governance follow-up (roadmap §10.3 item 1) - WorkflowDefinition.webhookToken hash+encrypt
-- migration, previously "flagged, deferred" (see this migration's companion code changes in
-- WorkflowDefinition/WorkflowService/WorkflowDefinitionRepository/WorkflowWebhookController).
--
-- webhookToken is a bearer secret (anyone holding it can POST /webhook/workflows/{token} and run
-- this tenant's workflow) that V22__workflow_webhook_trigger.sql originally stored in plaintext -
-- a DB read (backup leak, replica misconfiguration, insider access) would hand out live,
-- immediately-usable trigger secrets for every workflow with webhooks enabled, across every
-- tenant schema. Unlike a typical hashed secret (a password, or an API key shown once at
-- creation and never again), this token must be DISPLAYED BACK to the tenant on demand
-- (WorkflowService#getWebhook is called every time the canvas needs to show "your webhook URL",
-- not just once at creation) - a one-way hash alone can't support that. So webhook_token now
-- stores AES-256-GCM ciphertext (EncryptionService, the same component every other secret-at-rest
-- in this codebase already goes through) instead of plaintext - widened from VARCHAR(64) (a bare
-- 32-byte hex token) to VARCHAR(255) (base64(IV + ciphertext + GCM tag) is longer than the
-- plaintext it encrypts) - and its old UNIQUE constraint is dropped, since GCM's random
-- per-encryption IV means the SAME plaintext token re-encrypted twice produces different
-- ciphertext (deliberately - that unpredictability is what makes a DB dump resistant to frequency
-- analysis), so ciphertext equality can no longer mean "same token".
--
-- webhook_token_hash (NEW) - a deterministic SHA-256 hex digest of the plaintext token - takes
-- over as the UNIQUE lookup key: WorkflowDefinitionRepository#findByWebhookTokenHash hashes an
-- inbound caller's raw token the same way (see WebhookTokenHasher) and queries by that instead of
-- an equality match against the encrypted column.
--
-- Honest scope note: this is a clean-slate migration, not a backfill. Encrypting an EXISTING
-- plaintext row requires calling into the running application's EncryptionService (key material
-- lives in app config, not reachable from a plain SQL migration) - any workflow that already had
-- its webhook enabled before this migration runs will have its (still-plaintext, now
-- non-authoritative) webhook_token value and a NULL webhook_token_hash, meaning its existing
-- trigger URL stops resolving until re-enabled from the canvas (WorkflowService#enableWebhook
-- mints a fresh token, encrypts it, and populates the hash correctly). Acceptable for this stage
-- of the build (no real external integrations depend on a live trigger URL yet); a real backfill
-- job would be its own follow-up if this ever needs a zero-downtime migration path in production.
ALTER TABLE workflow_definition
    DROP CONSTRAINT IF EXISTS workflow_definition_webhook_token_key,
    ALTER COLUMN webhook_token TYPE VARCHAR(255);

ALTER TABLE workflow_definition ADD COLUMN webhook_token_hash VARCHAR(64) UNIQUE;
