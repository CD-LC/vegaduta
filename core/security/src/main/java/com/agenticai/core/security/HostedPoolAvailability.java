package com.agenticai.core.security;

/**
 * Whether this deployment has a usable platform-hosted LLM pool at all - the
 * master switch is on AND at least one pool row is enabled.
 *
 * <p>The hosted SaaS always has one; a self-hosted install usually does not.
 * {@code TenantLimits#usesPlatformPool} consults this under
 * {@code app.llm.default-source=auto} so that a tenant which never made an
 * explicit choice falls back to its own provider key (BYOK) instead of being
 * routed at an empty pool. Lives in {@code security} for the same reason
 * {@link TenantLimits} does: the implementation ({@code providers}) sits above
 * the consumer ({@code tenancy}) in the module graph.
 */
public interface HostedPoolAvailability {

    /** @return true when at least one platform-hosted provider can currently serve a call. */
    boolean isPoolConfigured();
}
