package com.agenticai.core.tenancy.account;

/**
 * Published (synchronously, on the request thread, with {@code TenantContext}
 * still set) by {@link AccountSelfDeletionService} when a member of a SHARED
 * workspace deletes their own account, BEFORE their Keycloak user is removed.
 *
 * <p>Exists because the modules that own per-user rows sit downstream of
 * {@code core/tenancy} in the Maven graph ({@code core/devices} depends on
 * tenancy, not the other way round), so tenancy cannot call their
 * repositories directly. Listeners delete the rows they own for
 * {@link #userId()}; a listener exception aborts the request before the
 * Keycloak user is deleted, so the user can retry.
 *
 * @param tenantId TenantContext tenant id (= tenant schema name)
 * @param userId   Keycloak subject of the account being deleted
 */
public record AccountDeletionRequested(String tenantId, String userId) {
}
