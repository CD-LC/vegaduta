-- B9-PR1 seed fixture — business data for ONE tenant schema. Run with:
--   psql ... -v schema=seed_free_tenant -f seed.sql
-- (deploy/seed/seed.sh drives this after the schema has been created and
-- tenant-migrated via the real core/app/.../db/migration/tenant Flyway
-- scripts — see seed.sh's own header. Do not run this against a schema
-- that hasn't been tenant-migrated yet; the tables below won't exist.)
--
-- All 3 agents + the knowledge fixture + the workflow are seeded into
-- THIS ONE schema (seed.sh only calls this for seed_free_tenant) — the
-- B9-PR1 spec's fixture counts ("3 agents, 1 knowledge collection ...,
-- 1 workflow") read as a single representative fixture set, and
-- stack.yml's smoke curl only ever logs into one tenant (GET /api/agents
-- returns 3), so splitting agents across both tenants would make that
-- assertion ambiguous for no benefit. seed_premium_tenant only gets the
-- control-plane row + BYOK credential stub (see control-plane-seed.sql /
-- tenant-byok-seed.sql) — flagged here in case a later PR wants business
-- data in both tenants instead.
--
-- provider_name PLATFORM_HOSTED matches how a real FREE-tier agent is
-- actually created (AgentChatService routes provider_name=PLATFORM_HOSTED
-- through HostedLlmChatClientFactory regardless of the agent's own
-- provider_name/model columns when the OWNING TENANT is FREE — see
-- TenantPlan's javadoc); model 'mock-gpt' matches mock-provider/main.py's
-- MODEL_ID constant.
SET search_path TO :"schema", public;

INSERT INTO agent
  (id, name, system_prompt, provider_name, model, max_tokens, temperature, knowledge_base_enabled, created_at, updated_at)
VALUES
  ('00000000-0000-4000-9000-000000000001', 'Seed Support Agent',
   'You are a helpful support agent for the seeded B9-PR1 CI fixture tenant.',
   'PLATFORM_HOSTED', 'mock-gpt', 1024, 0.7, TRUE, now(), now()),
  ('00000000-0000-4000-9000-000000000002', 'Seed Sales Agent',
   'You are a sales assistant for the seeded B9-PR1 CI fixture tenant.',
   'PLATFORM_HOSTED', 'mock-gpt', 1024, 0.7, FALSE, now(), now()),
  ('00000000-0000-4000-9000-000000000003', 'Seed Research Agent',
   'You are a research assistant for the seeded B9-PR1 CI fixture tenant.',
   'PLATFORM_HOSTED', 'mock-gpt', 1024, 0.5, TRUE, now(), now())
ON CONFLICT (id) DO NOTHING;

-- 2 pre-embedded fixture chunks, into the Default knowledge_collection
-- V15__knowledge_collection.sql's own migration already inserted for this
-- schema the moment it was tenant-migrated (is_default = TRUE, exactly one
-- row per tenant) — no new collection created here on purpose, matching
-- KnowledgeCollectionService's "exactly one default per tenant" invariant.
-- embedding is vector(768) as of V26__knowledge_multi_model.sql (widened
-- from the original 384) — these fixtures are a small 4-value pattern
-- zero-padded to 768 dims, the same zero-padding scheme that migration's
-- own header comment documents as safe for pgvector's cosine distance
-- operator (a zero coordinate contributes nothing to a dot product or an
-- L2 norm, so padding doesn't perturb cosine similarity).
INSERT INTO knowledge_chunks (id, source, chunk_index, content, embedding, embedding_model, collection_id, created_at)
SELECT
  '00000000-0000-4000-9100-000000000001', 'seed-fixture.md', 0,
  'This is the first seed fixture chunk for the B9-PR1 full-stack CI smoke stack.',
  ('[' || array_to_string(ARRAY[0.10,0.20,0.30,0.40]::real[] || array_fill(0::real, ARRAY[764]), ',') || ']')::vector,
  'minilm-l6-v2', kc.id, now()
FROM knowledge_collection kc
WHERE kc.is_default = TRUE
ON CONFLICT (id) DO NOTHING;

INSERT INTO knowledge_chunks (id, source, chunk_index, content, embedding, embedding_model, collection_id, created_at)
SELECT
  '00000000-0000-4000-9100-000000000002', 'seed-fixture.md', 1,
  'This is the second seed fixture chunk, for exercising semantic search against the seeded stack.',
  ('[' || array_to_string(ARRAY[0.15,0.25,0.35,0.05]::real[] || array_fill(0::real, ARRAY[764]), ',') || ']')::vector,
  'minilm-l6-v2', kc.id, now()
FROM knowledge_collection kc
WHERE kc.is_default = TRUE
ON CONFLICT (id) DO NOTHING;

-- 1 workflow with a minimal starter graph. graph_json's shape (id/type/
-- position per node, source/target per edge) mirrors what
-- WorkflowCanvas.tsx's toWireGraph() produces at a glance — a single
-- manual-trigger -> agent-reply pair, referencing the Seed Support Agent
-- above. NOT verified against a real WorkflowExecutionEngine run
-- in-sandbox (no Maven/JDK toolchain here — see this repo's standing
-- .claude-handoffs caveat); if the engine's real node/edge schema differs,
-- fix this literal, not workflow_definition's columns.
INSERT INTO workflow_definition (id, name, description, graph_json, status, created_at, updated_at)
VALUES (
  '00000000-0000-4000-9200-000000000001',
  'Seed Starter Workflow',
  'Minimal trigger -> agent-reply workflow seeded for the B9-PR1 full-stack CI stack.',
  '{"nodes":[{"id":"trigger-1","type":"manualTrigger","position":{"x":0,"y":0},"data":{"label":"Manual Trigger"}},{"id":"agent-1","type":"agentReply","position":{"x":240,"y":0},"data":{"label":"Seed Support Agent","agentId":"00000000-0000-4000-9000-000000000001"}}],"edges":[{"id":"e1","source":"trigger-1","target":"agent-1"}]}',
  'ACTIVE',
  now(), now()
)
ON CONFLICT (id) DO NOTHING;
