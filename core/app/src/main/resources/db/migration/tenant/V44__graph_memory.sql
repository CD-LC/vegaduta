-- Agent Superintelligence Build Plan, Stage C - one Apache AGE graph per
-- tenant schema, the "graph DB along with Postgres" piece: entities/
-- relationships extracted from remembered facts (GraphMemoryService), so an
-- agent can answer questions vector similarity structurally can't ("what
-- else connects to X") by traversing the graph instead of just ranking by
-- embedding distance.
--
-- Same precedent V10__knowledge_chunks.sql already established for pgvector:
-- CREATE EXTENSION is database-wide and idempotent, so running it once per
-- tenant schema (this migration runs once per schema via TenantSchemaMigrator/
-- ExistingTenantSchemaMigrationRunner, same as every other tenant/ migration)
-- is harmless - the second and every subsequent tenant just finds it already
-- installed.
--
-- The graph itself, unlike a normal table, is NOT schema-qualified the way
-- knowledge_chunks/agent_memory are - Apache AGE's create_graph() creates its
-- own dedicated Postgres schema to hold the graph's internal tables, so
-- "one graph per tenant" instead means "one uniquely-named graph per tenant",
-- named after the tenant's own schema (<tenant_schema>_kg) so it can never
-- collide with another tenant's graph - see GraphMemorySchemaSupport, which
-- computes this exact same name at runtime via TenantSchemaResolver.

CREATE EXTENSION IF NOT EXISTS age;

DO $$
DECLARE
    graph_name text := current_schema() || '_kg';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ag_catalog.ag_graph WHERE name = graph_name) THEN
        PERFORM ag_catalog.create_graph(graph_name);
    END IF;
END $$;
