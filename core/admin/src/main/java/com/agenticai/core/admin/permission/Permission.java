package com.agenticai.core.admin.permission;

/**
 * A fixed, code-defined catalog of fine-grained capabilities a tenant admin can grant or withhold
 * per user, layered on top of the platform's 4 fixed Keycloak realm roles ({@code tenant-admin},
 * {@code agent-builder}, {@code delegated-user}, {@code end-customer} - see {@code
 * TenantAssignableRoles}). Not tenant-customizable - unlike roles, there is no path to define a new
 * permission from the admin console; the catalog only grows by changing this enum.
 *
 * <p><b>Enforcement is real (2026-09-23).</b> This catalog used to be data-model-and-UI only:
 * {@code SecurityConfig} gated every admin surface behind a single blanket {@code
 * ROLE_tenant-admin} rule, so a stored grant could never admit or deny anyone differently. {@code
 * SecurityConfig}'s tenant-scoped admin matchers now require {@code PERM_<name>} authorities, which
 * the JWT converter emits from the caller's stored {@code user_permission_grant} rows via {@code
 * PermissionAuthoritySource} / {@code AdminPermissionAuthoritySource}. Grants bind <b>every</b>
 * caller, tenant-admin included - a tenant-admin with no stored rows holds no permissions (the role
 * is a bootstrap that seeds the full bundle, not an ongoing bypass). See {@code
 * UserPermissionService}'s seed-marker javadoc for how legacy users get backfilled and how a
 * deliberate revoke-to-empty is kept distinct from never-seeded.
 *
 * <p>Each constant below is documented with the routes it gates in {@code SecurityConfig} today.
 * The read-open/write-gated split this codebase already follows is preserved: only the matchers
 * that used to require {@code ROLE_tenant-admin} now require the permission - plain member reads
 * still fall through to {@code authenticated()}.
 */
public enum Permission {

    /**
     * User creation, role/permission assignment, enable/disable - {@code /api/admin/users/**} (and
     * the {@code /api/admin/**} catch-all for any future admin subtree).
     */
    MANAGE_USERS,

    /**
     * Plan changes, invoices, billing profile, subscription cancellation, the commerce catalog and
     * payment checkout - {@code /api/tenant/upgrade}, {@code /api/tenant/invoices/**}, {@code
     * /api/tenant/billing-profile}, {@code /api/tenant/subscription/cancel}, writes under {@code
     * /api/tenant/commerce/**}, {@code POST /api/billing/checkout/**}.
     */
    MANAGE_BILLING,

    /**
     * MCP registry/server grants, provider credentials, SDLC credentials and sandboxes, and
     * knowledge/OCR BYOK credentials - {@code /api/mcp/registry/**}, {@code
     * /api/mcp/registrations/*}/{@code test|acknowledge}, {@code /api/mcpserver/grants/**}, {@code
     * /api/providers}, {@code /api/sarvam/credential}, {@code /api/sdlc/credential/**}, {@code
     * /api/sdlc/sandbox/**}, {@code /api/knowledge/ocr-provider}.
     */
    MANAGE_INTEGRATIONS,

    /**
     * Tenant-wide settings, white-label branding, and AI model preferences - {@code
     * /api/tenant/settings}, {@code /api/tenant/branding/logo}, {@code
     * /api/tenant/ai-model-preferences/**}.
     */
    MANAGE_TENANT_SETTINGS,

    /**
     * Workflow webhooks, schedules, and trigger-channel configuration - {@code
     * /api/workflows/*}/{@code nodes/*}/{@code webhook}, {@code /api/workflows/*}/{@code schedule},
     * {@code /api/workflows/*}/{@code nodes/*}/{@code trigger-channel}.
     */
    MANAGE_WORKFLOWS_ADMIN,

    /** Reading the tenant's audit trail - {@code /api/audit-log}, {@code /api/audit-log/**}. */
    VIEW_AUDIT_LOG,

    /**
     * Editing which tool/action types require human approval - {@code /api/approvals/policy/**}.
     */
    MANAGE_APPROVALS,

    /**
     * Data export, deletion, and usage reporting - {@code /api/tenant/data-export}, {@code
     * /api/tenant/data-deletion}, {@code /api/tenant/data-usage}.
     */
    MANAGE_DATA_GOVERNANCE,

    /**
     * Channel connection and routing administration: every channel credential write (Twilio,
     * ElevenLabs, Telegram, email, Meta WhatsApp incl. embedded signup, Meta social, Slack,
     * Discord, Google, X), default-agent bindings, voice DTMF menus, sender pairing decisions
     * (approve/reject/rebind/erase-subject), and the contacts/groups/routing surface - {@code
     * /api/channels/**} writes, {@code /api/elevenlabs/credential}.
     */
    MANAGE_CHANNELS,

    /**
     * Autonomous-agent administration: schedules and event triggers ({@code /api/agents/*}/{@code
     * schedule}, {@code /api/agents/*}/{@code triggers/**}) plus the Agent-Learning
     * policy/preference surfaces ({@code /api/rl/agents/*}/{@code policies/**}, {@code
     * .../preferences}), and knowledge-base authoring: document ingest/upload/delete, collection
     * create/update/delete, re-embedding, RAG eval and face enrollments (writes under {@code
     * /api/knowledge/**} except search, {@code /api/face-enrollments}). Reads and search stay open
     * to every member.
     */
    MANAGE_AGENTS,

    /** Tenant-authored guardrail rules - POST/PUT/DELETE {@code /api/guardrails/rules/**}. */
    MANAGE_GUARDRAILS,

    /**
     * Tenant-authored tool surfaces: custom code tools and OpenAPI tool sources - writes under
     * {@code /api/tools/code/**} (the stateless {@code generate}/{@code validate} pair stays
     * role-gated for agent-builders) and {@code /api/tools/openapi/**}.
     */
    MANAGE_TOOLS,

    /**
     * Tenant-level community-template management (the tenant's own templates). NOT gallery
     * moderation: {@code /api/admin/templates/**} (approve/reject/verify/pending) is {@code
     * ROLE_platform-admin} only (SEC-010), because every tenant admin holds this.
     */
    MANAGE_TEMPLATES,

    /**
     * LLM usage and spend reporting - {@code /api/admin/llm-usage/**}, {@code
     * /api/admin/spend-reports/**}.
     */
    VIEW_USAGE,

    /**
     * Built-in Leads CRM - every route under {@code /api/leads/**} (leads are PII, so reads are
     * gated too).
     */
    MANAGE_LEADS
}
