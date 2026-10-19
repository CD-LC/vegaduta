-- B9-PR1 seed fixture — control-plane rows (control_plane.tenant +
-- control_plane.platform_hosted_llm_provider). Runs against the default
-- connection schema (where core's own boot-time Flyway run already applied
-- controlplane/*.sql — see application.yml's flyway comment). Idempotent:
-- every INSERT below is ON CONFLICT DO NOTHING / WHERE NOT EXISTS keyed on
-- a fixed seed id or a natural unique column, safe to re-run.
--
-- Two tenants per the B9-PR1 spec:
--   seed_free_tenant    - TenantPlan.FREE. Per TenantPlan's own javadoc,
--                         FREE tenants are ALWAYS routed through the
--                         platform-hosted pool (no BYOK) — so seeding the
--                         platform_hosted_llm_provider row below is
--                         sufficient on its own to give this tenant a
--                         zero-real-API-key chat path. This is also the
--                         tenant deploy/seed/seed.sh logs into for the
--                         stack.yml smoke curl (GET /api/agents).
--   seed_premium_tenant - TenantPlan.PREMIUM with a stub BYOK
--                         llm_provider_credential row (see
--                         tenant-byok-seed.sql) AND use_platform_llm=TRUE
--                         (V100__tenant_use_platform_llm.sql's opt-in for
--                         non-FREE tenants) so it ALSO reaches
--                         mock-provider in CI without a real OpenAI key —
--                         the BYOK row exists to satisfy "one with a stub
--                         BYOK provider row" literally, but its
--                         encrypted_api_key is a harmless placeholder that
--                         is never decrypted/used while use_platform_llm
--                         stays TRUE. Flagged here since this is a
--                         deliberate interpretation of an otherwise
--                         underspecified requirement, not a literal reading
--                         of the original PR prompt text.

INSERT INTO control_plane.tenant
  (id, name, plan, schema_name, status, keycloak_realm, created_at, account_type, experience_mode, use_platform_llm)
VALUES
  ('00000000-0000-4000-8000-0000000000f1', 'Seed Free Tenant', 'FREE', 'seed_free_tenant', 'ACTIVE', 'agentic-ai', now(), 'B2C', 'SIMPLE', FALSE),
  ('00000000-0000-4000-8000-0000000000f2', 'Seed Premium Tenant', 'PREMIUM', 'seed_premium_tenant', 'ACTIVE', 'agentic-ai', now(), 'B2C', 'SIMPLE', TRUE)
ON CONFLICT (id) DO NOTHING;

-- mock-provider, reached via the compose network's own DNS name (this row
-- is read by HostedLlmChatClientFactory at chat-call time, from inside the
-- `core` container — see that class's "no trailing /v1" baseUrl convention,
-- followed here). priority 1 so it's always tried first if other rows are
-- ever added later. No real API key — mock-provider does not check it. NOTE: the
-- placeholder below is not AES-GCM ciphertext, so core cannot decrypt it and leaves this row
-- out of the hosted pool (one warning, see deploy/seed/README.md "Known limitation"); a
-- FREE-tier chat needs the mock registered via POST /api/platform/hosted-llm-providers.
INSERT INTO control_plane.platform_hosted_llm_provider
  (label, base_url, model, encrypted_api_key, enabled, priority, created_at, updated_at)
SELECT 'B9-PR1 mock-provider (CI)', 'http://mock-provider:9101', 'mock-gpt', 'unused-mock-key', TRUE, 1, now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM control_plane.platform_hosted_llm_provider WHERE base_url = 'http://mock-provider:9101'
);
