package com.agenticai.core.security;

/**
 * Names of the chat hot-path caches (see {@link TtlCache}) and the eviction entry points the write
 * paths call. Lives in {@code security} so {@code tenancy}/{@code providers} entity listeners can
 * evict without depending on {@code agents}/{@code chat}. Every eviction is a no-op when the
 * corresponding cache is disabled or absent.
 */
public final class HotpathCaches {

    public static final String TENANT_PLAN = "chat.tenant-plan";
    public static final String TENANT_POOL = "chat.tenant-pool";
    public static final String PROVIDER_KEY = "chat.provider-key";
    public static final String GATEWAY_CLIENT = "chat.gateway-client";
    // Per-request filter-chain caches (TenantStatusGuardImpl#isUsable runs on
    // EVERY authenticated request - the single hottest read path there is).
    // Status/usable answers are short-TTL'd AND evicted on every write through
    // the same listener discipline as the plan caches, so an emergency stop on
    // this pod takes effect on the very next request, not after the TTL.
    public static final String TENANT_STATUS = "tenant.status";
    public static final String PLATFORM_STATUS = "platform.status";
    // GranularPermissionConverter's per-request stored-grant read
    // (UserPermissionService#getOrSeedEffectivePermissions) - the second of
    // the filter chain's fixed per-request DB hits. Keys are
    // key(tenantId, keycloakUserId); evicted per-user by every write path in
    // UserPermissionService so a revocation on this pod is immediate.
    public static final String USER_PERMISSIONS = "user.effective-permissions";
    // SystemStatusController#get() - the expensive fan-out behind both
    // /api/system/status and /api/platform/health (health contributors,
    // Hikari MXBean, a fresh Kafka AdminClient, outbox counts, the LLM
    // usage summary, MCP registry, two external liveness probes). Callers
    // poll it every ~15s from multiple shells; a short TTL is honest
    // because a status answer is point-in-time by definition. Global key -
    // the payload is platform-wide, not tenant-scoped.
    public static final String SYSTEM_STATUS = "system.status";
    // Immutable snapshot of the agent fields the chat turn reads many times (tool allowlists,
    // autonomy tier,
    // capability flags). Keys are key(tenantId, agentId); evicted on every agent write on this pod.
    public static final String AGENT_PROJECTION = "agent.projection";
    // "Is this integration configured for the tenant" answers the tool resolvers ask on every turn
    // (see
    // HotpathLookups). Keys are key(tenantId, lookup, arg); a write to any watched config entity on
    // this
    // pod drops the tenant's whole namespace. PLATFORM_CONFIG is the same idea for platform-wide
    // rows.
    public static final String TENANT_CONFIG = "tenant.config";
    public static final String PLATFORM_CONFIG = "platform.config";

    private HotpathCaches() {}

    /**
     * Plan changed / comp grant / tenant row rewritten: drop this tenant's plan and hosted-pool
     * answers.
     */
    public static void evictTenantPlan(String tenantId) {
        if (tenantId == null) {
            TtlCache.evictAll(TENANT_PLAN);
            TtlCache.evictAll(TENANT_POOL);
            return;
        }
        TtlCache.evictPrefix(TENANT_PLAN, tenantId);
        TtlCache.evictPrefix(TENANT_POOL, tenantId);
    }

    /**
     * Tenant row written (status flip, emergency stop/resume, provisioning): drop the cached {@code
     * isUsable} answer so the very next request re-reads. Called from the same listener that evicts
     * the plan caches - one entity write, one eviction hook for every derived answer.
     */
    public static void evictTenantStatus(String tenantId) {
        if (tenantId == null) {
            TtlCache.evictAll(TENANT_STATUS);
            return;
        }
        TtlCache.evictPrefix(TENANT_STATUS, tenantId);
    }

    /** Platform kill-switch flipped: drop the cached "platform is stopped" answer immediately. */
    public static void evictPlatformStatus() {
        TtlCache.evictAll(PLATFORM_STATUS);
    }

    /**
     * One user's stored grants changed (admin replace, role-assignment reseed): drop exactly their
     * cached effective set - key(tenantId, keycloakUserId) is both the stored key and an
     * unambiguous prefix, so this is an exact-key evict via the prefix path. Revocation propagates
     * to the very next request on this pod.
     */
    public static void evictUserPermissions(String tenantId, String keycloakUserId) {
        if (tenantId == null || keycloakUserId == null) {
            TtlCache.evictAll(USER_PERMISSIONS);
            return;
        }
        TtlCache.evictPrefix(USER_PERMISSIONS, tenantId, keycloakUserId);
    }

    /**
     * An agent row was written (edit, version restore, capability toggle, delete): drop its cached
     * projection so the very next turn on this pod sees the change. With no tenant id (no request
     * context) every projection is dropped, which is always safe for a cache.
     */
    public static void evictAgent(String tenantId, String agentId) {
        if (tenantId == null || agentId == null) {
            TtlCache.evictAll(AGENT_PROJECTION);
            return;
        }
        TtlCache.evictPrefix(AGENT_PROJECTION, tenantId, agentId);
    }

    /**
     * A tenant-scoped integration setting was written: drop this tenant's cached config answers.
     */
    public static void evictTenantConfig(String tenantId) {
        if (tenantId == null) {
            TtlCache.evictAll(TENANT_CONFIG);
            return;
        }
        TtlCache.evictPrefix(TENANT_CONFIG, tenantId);
    }

    /**
     * A platform-wide setting was written: drop every cached platform config answer on this pod.
     */
    public static void evictPlatformConfig() {
        TtlCache.evictAll(PLATFORM_CONFIG);
    }

    /**
     * Credential saved/rotated/deactivated/deleted: drop the cached key and any client built from
     * it.
     */
    public static void evictProviderCredential(String tenantId, String provider) {
        if (tenantId == null || provider == null) {
            TtlCache.evictAll(PROVIDER_KEY);
            TtlCache.evictAll(GATEWAY_CLIENT);
            return;
        }
        TtlCache.evictPrefix(PROVIDER_KEY, tenantId, provider);
        TtlCache.evictPrefix(GATEWAY_CLIENT, tenantId, provider);
    }
}
