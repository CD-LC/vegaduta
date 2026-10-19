package com.agenticai.core.security;

import java.util.Optional;
import java.util.Set;

/**
 * Per-request holder of who the caller is - populated from the validated
 * JWT by {@link TenantContextFilter} once Phase 0 Stage 3's "security"
 * profile is active. Read-only from the rest of the app's perspective;
 * only the filter ever calls {@link #set}.
 *
 * <p>{@code tenantId} is the seam Phase 0 Stage 4 (schema-per-tenant
 * Hibernate multi-tenancy, not yet built) will consume to pick a Postgres
 * schema per request - this class exists now, ahead of Stage 4, because
 * Stage 3 is what first has a JWT to read it from.
 *
 * <p>Data-governance audit fix (2026-07-20): {@code externalTriggerId} is a
 * second, deliberately generic seam alongside {@code subject} - an opaque id
 * for the specific external credential/trigger that authenticated this
 * request, when there is one (e.g. an inbound MCP API key's {@code
 * McpServerAccessGrant} id), as opposed to {@code subject}, which is a
 * human-readable label. Kept generic (not {@code mcpGrantId}) so any other
 * no-JWT, external-caller entry point this codebase already has (see
 * {@code WorkflowWebhookController}'s shared-secret path) can populate the
 * same field later instead of every module inventing its own thread-local -
 * {@code core/mcpserver}'s {@code McpApiKeyAuthFilter} is simply the first
 * caller of the new {@link #of(String, String, Set, String)} overload.
 * {@code null} for every JWT-authenticated request, exactly like today.
 *
 * <p>F-14 (2026-08-07): absence of this context on a thread that runs
 * tenant-scoped queries is no longer silent - {@code
 * com.agenticai.core.tenancy.TenantSchemaResolver} counts every resulting
 * public-schema fallback ({@code tenant.context.public.fallback}, tagged by
 * resolution path) and can fail closed behind {@code
 * app.tenancy.fail-on-missing-context}. If you are adding a new async hop
 * or background entry point, propagate this context explicitly (the
 * capture-and-re-set pattern {@code TenantContextPropagatingToolCallback}
 * documents) rather than relying on the fallback; the resolver's javadoc
 * maps which tenant-less paths are legitimate.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private final String tenantId;
    private final String subject;
    private final Set<String> roles;
    private final String externalTriggerId;

    private TenantContext(String tenantId, String subject, Set<String> roles, String externalTriggerId) {
        this.tenantId = tenantId;
        this.subject = subject;
        this.roles = roles;
        this.externalTriggerId = externalTriggerId;
    }

    public static TenantContext of(String tenantId, String subject, Set<String> roles) {
        return new TenantContext(tenantId, subject, roles, null);
    }

    /**
     * Data-governance audit fix (2026-07-20) - see this class's own javadoc for
     * {@code externalTriggerId}'s purpose. Everything else behaves identically to
     * {@link #of(String, String, Set)}.
     */
    public static TenantContext of(String tenantId, String subject, Set<String> roles, String externalTriggerId) {
        return new TenantContext(tenantId, subject, roles, externalTriggerId);
    }

    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubject() {
        return subject;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public String getExternalTriggerId() {
        return externalTriggerId;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
