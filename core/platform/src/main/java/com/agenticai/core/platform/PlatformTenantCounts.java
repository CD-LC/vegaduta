package com.agenticai.core.platform;

/**
 * {@code GET /api/platform/tenant-counts} - the two populations the admin
 * console used to report as one number.
 *
 * <p>{@code registeredActive} is what "active tenants" means to an operator:
 * ACTIVE tenants that someone registered for. The anonymous {@code /try}
 * sandboxes are tenants too (each one is a real row and a real Postgres
 * schema), so they are reported, but separately: {@code demoLive} are
 * sandboxes still answering, {@code demoExpiredUnreclaimed} are lapsed ones
 * that {@code DemoSessionExpiryJob} suspended and whose schema is still on
 * disk - the figure that says how much there is to reclaim.
 */
public record PlatformTenantCounts(
    long registeredActive,
    long demoLive,
    long demoExpiredUnreclaimed
) {
}
