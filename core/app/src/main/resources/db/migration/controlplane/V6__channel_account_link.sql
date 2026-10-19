-- Phase 0 Stage 4 follow-up: resolves which tenant schema owns an external
-- channel account (a Twilio Account SID today). Needed because inbound
-- webhooks carry no Keycloak JWT, so TenantContextFilter never runs for
-- them - there is no other signal to pick a tenant schema from before
-- looking at the payload itself.
--
-- Lives in control_plane, not any tenant schema - "which tenant does this
-- belong to" is a cross-tenant question by definition, the same reasoning
-- V2's control_plane.tenant table already lives outside every tenant
-- schema it describes.

CREATE TABLE control_plane.channel_account_link (
    id                    UUID PRIMARY KEY,
    provider              VARCHAR(32) NOT NULL,
    external_account_id   VARCHAR(128) NOT NULL,
    tenant_schema         VARCHAR(63) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_channel_account_link_provider_external UNIQUE (provider, external_account_id)
);
