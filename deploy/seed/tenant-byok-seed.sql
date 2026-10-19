-- B9-PR1 seed fixture — the "stub BYOK provider row" for
-- seed_premium_tenant. Run with: psql ... -v schema=seed_premium_tenant -f tenant-byok-seed.sql
--
-- encrypted_api_key is a literal placeholder string, NOT real
-- EncryptionService ciphertext (this script has no Java/APP_ENCRYPTION_KEY
-- access) — safe only because seed_premium_tenant.use_platform_llm=TRUE
-- (set in control-plane-seed.sql) means AgentChatService routes this
-- tenant's chat calls through HostedLlmChatClientFactory / mock-provider
-- instead of ever decrypting this row. If a future change makes BYOK
-- decryption run unconditionally (not just on actual BYOK-routed calls),
-- this placeholder will fail loudly (EncryptionService rejects malformed
-- ciphertext) rather than silently — treat that as a signal this seed
-- needs a real encrypted value, not a bug in EncryptionService.
SET search_path TO :"schema", public;

INSERT INTO llm_provider_credential (id, provider_name, status, encrypted_api_key, created_at, updated_at)
VALUES (
  '00000000-0000-4000-9300-000000000001',
  'OPENAI',
  'ACTIVE',
  'seed-placeholder-not-real-ciphertext',
  now(), now()
)
ON CONFLICT (id) DO NOTHING;
