-- Per-node workflow triggers - replaces "one trigger channel per workflow"
-- (V41__workflow_trigger_channel.sql: workflow_definition.trigger_channel/
-- webhook_auth_mode + the one-row-per-channel channel_workflow_trigger table)
-- with "each Trigger node in a workflow's graph can be independently bound
-- to its own inbound channel". A workflow's graph can (per OrchestratorService
-- validation today) only have ONE trigger node, but that constraint is being
-- relaxed elsewhere as part of this same change - this table is keyed by
-- (workflow_id, node_id) rather than by workflow_id alone specifically so a
-- future multi-trigger-node graph needs no further migration here.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V145/V155's notes for the same rule - collisions
-- here have broken the build before). V172 looked free in tenant/ but is
-- already taken in controlplane/ (V172__session_idle_timeout_settings.sql);
-- controlplane/'s highest is V174__platform_tool_credential.sql and
-- tenant/'s highest is V171__subagent_run.sql, so V175 is the next slot free
-- in BOTH folders as of this writing.
--
-- SHAPE
-- -----
-- webhook_token_encrypted/webhook_token_hash/webhook_enabled/webhook_auth_mode
-- move here from workflow_definition unchanged in type/semantics (see V22/V102
-- for their history: AES-256-GCM ciphertext + a deterministic SHA-256 lookup
-- hash, since the token must be displayed back to the tenant on demand and a
-- one-way hash alone can't support that) - just now scoped per trigger NODE
-- instead of per workflow, since a webhook-channel trigger node is bound to
-- exactly one node in the graph, not the workflow as a whole.
--
-- node_id is UNIQUE (not just indexed) because a trigger node, once created
-- on the canvas, keeps a stable id for its lifetime and can only ever be
-- bound to ONE inbound channel - this table's PK is really the node, the
-- surrogate `id` column exists only for JPA/FK-target convention consistency
-- with every other entity in this schema.
--
-- The partial unique index on channel (excluding WEBHOOK) reproduces
-- channel_workflow_trigger's old "one workflow per non-webhook channel,
-- tenant-wide" UNIQUE(channel) constraint - WHATSAPP/SLACK/etc. still route
-- ALL of a tenant's inbound traffic on that channel to a single trigger node,
-- there's no per-node fan-out for those yet. WEBHOOK is excluded because
-- every workflow's webhook-channel trigger node needs its own row (each has
-- its own token), the same way workflow_definition.webhook_token was never
-- unique-by-value across workflows either.
CREATE TABLE workflow_trigger_node (
    id                       UUID PRIMARY KEY,
    workflow_id              UUID NOT NULL REFERENCES workflow_definition (id) ON DELETE CASCADE,
    node_id                  VARCHAR(64) NOT NULL UNIQUE,
    channel                  VARCHAR(16) NOT NULL,
    webhook_token_encrypted  VARCHAR(255),
    webhook_token_hash       VARCHAR(64) UNIQUE,
    webhook_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    webhook_auth_mode        VARCHAR(16) NOT NULL DEFAULT 'SHARED_SECRET',
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX workflow_trigger_node_channel_unique ON workflow_trigger_node (channel) WHERE channel <> 'WEBHOOK';

-- BACKFILL - both old sources (channel_workflow_trigger and
-- workflow_definition's webhook/trigger columns) still exist at this point
-- in the transaction, so this runs before either is dropped below.
--
-- gen_random_uuid() is already relied on elsewhere in this same tenant/
-- folder (V15__knowledge_collection.sql, V145__guardrail_rule.sql,
-- V147__resource_sharing.sql) with no explicit CREATE EXTENSION pgcrypto in
-- any of them - it ships built in on the Postgres versions this platform
-- targets (13+, via the same default-available pgcrypto this app already
-- assumes) - so no extension statement is added here either, matching that
-- established pattern rather than introducing a new one.
--
-- Both backfill blocks resolve node_id the same way: the id of the first
-- {"type":"trigger"} node in the workflow's graph_json (a workflow's graph
-- is validated to have exactly one Trigger node - OrchestratorService - so
-- "first" is really "the only one" for every graph this ever runs against;
-- LIMIT 1 is defensive, not a real ambiguity). A workflow whose graph_json
-- has no trigger node at all (shouldn't happen given that validation, but a
-- data migration must not assume application-level invariants always held)
-- is skipped rather than failing the migration - there's no node to bind the
-- old channel/webhook state to, so that binding is dropped with a NOTICE.
DO $$
DECLARE
    old_trigger RECORD;
    resolved_node_id VARCHAR(64);
BEGIN
    -- 1) channel_workflow_trigger -> one row per (workflow, its trigger node, channel).
    FOR old_trigger IN SELECT id, channel, workflow_id FROM channel_workflow_trigger LOOP
        SELECT node->>'id' INTO resolved_node_id
        FROM workflow_definition wd,
             jsonb_array_elements((wd.graph_json::jsonb)->'nodes') AS node
        WHERE wd.id = old_trigger.workflow_id
          AND node->>'type' = 'trigger'
        LIMIT 1;

        IF resolved_node_id IS NULL THEN
            RAISE NOTICE 'V175: workflow % has a channel_workflow_trigger row for channel % but no trigger node in its graph - skipping', old_trigger.workflow_id, old_trigger.channel;
            CONTINUE;
        END IF;

        INSERT INTO workflow_trigger_node (id, workflow_id, node_id, channel, webhook_enabled, webhook_auth_mode, created_at, updated_at)
        VALUES (gen_random_uuid(), old_trigger.workflow_id, resolved_node_id, old_trigger.channel, FALSE, 'SHARED_SECRET', now(), now())
        ON CONFLICT (node_id) DO NOTHING;
    END LOOP;

    -- 2) workflow_definition rows with webhook_enabled = true -> upsert onto
    -- the same node_id if step 1 already created a row for it (a workflow
    -- can only be trigger_channel = WEBHOOK when it has no
    -- channel_workflow_trigger row at all - see V41 - but a defensive
    -- upsert costs nothing and keeps this block correct even if that
    -- invariant is ever relaxed).
    FOR old_trigger IN
        SELECT id, webhook_token AS webhook_token_encrypted, webhook_token_hash, webhook_auth_mode
        FROM workflow_definition
        WHERE webhook_enabled = TRUE
    LOOP
        SELECT node->>'id' INTO resolved_node_id
        FROM workflow_definition wd,
             jsonb_array_elements((wd.graph_json::jsonb)->'nodes') AS node
        WHERE wd.id = old_trigger.id
          AND node->>'type' = 'trigger'
        LIMIT 1;

        IF resolved_node_id IS NULL THEN
            RAISE NOTICE 'V175: workflow % has webhook_enabled=true but no trigger node in its graph - skipping', old_trigger.id;
            CONTINUE;
        END IF;

        INSERT INTO workflow_trigger_node (id, workflow_id, node_id, channel, webhook_token_encrypted, webhook_token_hash, webhook_enabled, webhook_auth_mode, created_at, updated_at)
        VALUES (gen_random_uuid(), old_trigger.id, resolved_node_id, 'WEBHOOK', old_trigger.webhook_token_encrypted, old_trigger.webhook_token_hash, TRUE, old_trigger.webhook_auth_mode, now(), now())
        ON CONFLICT (node_id) DO UPDATE SET
            channel = 'WEBHOOK',
            webhook_token_encrypted = EXCLUDED.webhook_token_encrypted,
            webhook_token_hash = EXCLUDED.webhook_token_hash,
            webhook_enabled = TRUE,
            webhook_auth_mode = EXCLUDED.webhook_auth_mode,
            updated_at = now();
    END LOOP;
END
$$;

DROP TABLE channel_workflow_trigger;

ALTER TABLE workflow_definition
    DROP COLUMN trigger_channel,
    DROP COLUMN webhook_auth_mode,
    DROP COLUMN webhook_token,
    DROP COLUMN webhook_token_hash,
    DROP COLUMN webhook_enabled;
