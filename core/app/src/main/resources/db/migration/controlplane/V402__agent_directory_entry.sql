-- Public agent directory (Know-Your-Agent discovery surface).
--
-- A tenant PUBLISHES one of its agents into the platform-wide directory; the
-- entry stores signed snapshots (A2A card + capability manifest, both Ed25519
-- over the operator key served at /.well-known/jwks.json) so the public read
-- path never has to hop into the tenant schema - the directory row IS the
-- artifact verifiers consume. agent_id is globally unique (UUIDv4, generated
-- inside one tenant schema) and doubles as the PK: one directory entry per
-- agent, full stop.
--
-- CONTROL-PLANE table: it is cross-tenant by definition (the whole point is
-- that callers from ANYWHERE list it) and carries no secrets - only data a
-- tenant explicitly published. tenant_id is the SCHEMA NAME (what
-- TenantContext.getTenantId() carries), matching commerce_product.tenant_id.
--
-- Version 402: V401-V412 claimed for this work in docs/AGENT-COORDINATION.md;
-- the shared controlplane+tenant version namespace applies.

CREATE TABLE IF NOT EXISTS control_plane.agent_directory_entry (
    agent_id          UUID         NOT NULL,
    tenant_id         VARCHAR(255) NOT NULL,
    display_name      VARCHAR(200) NOT NULL,
    description       TEXT         NULL,
    -- JSON array of capability strings the publisher advertised (tool names,
    -- protocol ids like "a2a/1.0", "x402", "mcp").
    capabilities      TEXT         NULL,
    homepage_url      VARCHAR(1024) NULL,
    -- Signed artifacts captured at publish time (detached-JWS "signatures"
    -- field inside each JSON document). Snapshot-on-publish, not live-read:
    -- an unpublished/tampered tenant row cannot silently change what the
    -- world verifies.
    card_snapshot     TEXT         NOT NULL,
    manifest_snapshot TEXT         NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED'
                      CHECK (status IN ('PUBLISHED','UNPUBLISHED')),
    published_at      TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id)
);

-- Backs the public listing: published rows only, newest first.
CREATE INDEX IF NOT EXISTS idx_agent_directory_published
    ON control_plane.agent_directory_entry (published_at DESC)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS idx_agent_directory_tenant
    ON control_plane.agent_directory_entry (tenant_id);
