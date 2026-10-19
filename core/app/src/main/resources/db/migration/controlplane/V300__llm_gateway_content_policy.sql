-- LLM gateway content-policy chokepoint (Gap-radar P0: "a real LLM gateway
-- (PII redaction/injection screening)") - per-tenant on/off configuration for
-- the two content-level checks LlmGatewayContentPolicyAdvisor (core/chat/
-- .../gateway) applies to every outbound LLM call it sees, on top of the
-- pre-existing budget/rate-limit gateway checks (LlmGatewayBudgetService /
-- LlmGatewayRateLimiterService).
--
-- CONTROL-PLANE table, same reasoning as V185 (control_plane.tenant.
-- ai_model_preferences) and V253 (control_plane.tenant_sso_config): this is
-- platform-level configuration about a tenant, not tenant-schema business
-- data. A dedicated table rather than more columns on control_plane.tenant
-- itself (V162's "fixed small set of columns" rule technically still applies
-- here, but a standalone table keeps this fan-out task's migration isolated
-- from the shared Tenant entity/table other concurrent sessions are also
-- touching - same isolation reasoning V253's tenant_sso_config/
-- tenant_scim_token pair already used for the same reason).
--
-- One row per tenant, created on first opt-in (via the settings API this
-- task does not build a UI for - LlmGatewayContentPolicyService resolves a
-- missing row to "everything OFF", so no row is required for existing
-- tenants and the feature is a pure no-op until a tenant is explicitly
-- opted in).
--
-- pii_redact_* / injection_screening_* are sub-toggles under their own
-- master switch (pii_redaction_enabled / injection_screening_enabled) - the
-- master switch is what LlmGatewayContentPolicyAdvisor checks first (so a
-- tenant with the master off pays zero per-call overhead, no row read
-- needed beyond the initial lookup), the sub-toggles let a tenant that HAS
-- opted in exclude one category (e.g. redact card/government-ID numbers but
-- not email addresses) without a schema change.
--
-- Version 300: V300-V303 claimed for this task in
-- docs/AGENT-COORDINATION.md's 2026-09-23 gap-radar CLAIM entry; the shared
-- controlplane+tenant version namespace applies (see V185/V253's own header
-- comments for the collision class this avoids). Verified against this
-- worktree's own migration directories before authoring (max seen: tenant
-- V280, controlplane V281).

CREATE TABLE IF NOT EXISTS control_plane.llm_gateway_content_policy (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                       VARCHAR(64)  NOT NULL UNIQUE,

    -- PII redaction (outbound prompt content, before it reaches any provider).
    pii_redaction_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    pii_redact_email                BOOLEAN      NOT NULL DEFAULT TRUE,
    pii_redact_phone                BOOLEAN      NOT NULL DEFAULT TRUE,
    pii_redact_government_id        BOOLEAN      NOT NULL DEFAULT TRUE,
    pii_redact_credit_card          BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Prompt-injection screening (inbound user content, before it is folded
    -- into a system-privileged prompt / sent to the model).
    injection_screening_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    injection_screening_action      VARCHAR(16)  NOT NULL DEFAULT 'FLAG'
        CHECK (injection_screening_action IN ('FLAG', 'BLOCK')),

    created_by                      VARCHAR(255),
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
