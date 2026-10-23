package com.agenticai.core.admin.permission;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads and writes a user's {@link UserPermissionGrant} rows. Two distinct write modes, matching
 * two distinct callers:
 *
 * <ul>
 *   <li>{@link #replacePermissions} - an admin explicitly setting a user's permissions
 *       (creation-time {@code initialPermissions}, or {@code PUT .../permissions}).
 *       Full-set-replace, same semantics as {@code KeycloakAdminClient#replaceUserRealmRoles}: the
 *       result matches exactly what was submitted, not an accumulation.
 *   <li>{@link #seedMissingDefaultPermissions} - an automatic side effect of a role assignment
 *       (creation-time {@code initialRoles}, or {@code PUT .../roles}). Additive only, so a role
 *       change never claws back a permission an admin previously hand-picked for this user.
 * </ul>
 *
 * <p><b>The seed marker (2026-09-23, granular enforcement).</b> Grants now bind <i>every</i> caller
 * - including tenant-admins - so "which users have rows" finally matters for users whose roles were
 * granted <i>outside</i> {@code UserAdminController} and therefore never seeded (the signup
 * provisioning path grants {@code tenant-admin} straight through {@code KeycloakAdminClient}).
 * {@link #getOrSeedEffectivePermissions} closes that gap lazily: the first request from a
 * bundled-role user with zero rows persists the {@link #SEED_MARKER} sentinel row plus the bundle,
 * and returns it. The sentinel exists because "no rows at all" and "an admin deliberately revoked
 * every permission" are indistinguishable without it - after a revoke-to-empty only the marker
 * remains, so reseeding never resurrects permissions an admin intentionally removed. The marker is
 * not a real {@link Permission} name, {@code TenantAssignablePermissions} already rejects it from
 * any API submission, and every read path below filters it out so it never leaks into a UI or an
 * authority set.
 */
@Service
public class UserPermissionService {

    /**
     * Sentinel stored as a {@link UserPermissionGrant#getPermission()} value to mark "this user has
     * been seeded from their roles at least once". Deliberately not a {@link Permission} constant -
     * it must never be grantable, listable, or enforceable; it only distinguishes never-seeded from
     * revoked-to-empty.
     */
    public static final String SEED_MARKER = "SEED_MARKER";

    private final UserPermissionGrantRepository repository;

    /**
     * Per-user effective-set cache for the enforcement read below. {@code
     * GranularPermissionConverter} calls {@link #getOrSeedEffectivePermissions} on EVERY
     * authenticated request that carries a tenant claim - one {@code
     * findByTenantIdAndKeycloakUserId} round-trip per request, per user, for an answer that only
     * changes when an admin writes grants (or a role-assignment reseed does). Registered under
     * {@link HotpathCaches#USER_PERMISSIONS}; both write paths in this class evict the affected
     * user's key at flush and again after commit, so a revocation on this pod is effective on the
     * very next request. Other pods lag by at most the TTL ({@code
     * app.security.permission-cache-ttl}, default 10s - deliberately short because this is a
     * security answer, not just a perf number); {@code 0s} restores the previous uncached behavior.
     */
    private final TtlCache<Set<String>> effectivePermissions;

    public UserPermissionService(
            UserPermissionGrantRepository repository,
            @Value("${app.security.permission-cache-ttl:10s}") Duration permissionCacheTtl) {
        this.repository = repository;
        this.effectivePermissions =
                new TtlCache<>(HotpathCaches.USER_PERMISSIONS, permissionCacheTtl, 10_000);
    }

    @Transactional(readOnly = true)
    public Set<String> getGrantedPermissions(String tenantId, String keycloakUserId) {
        return repository.findByTenantIdAndKeycloakUserId(tenantId, keycloakUserId).stream()
                .map(UserPermissionGrant::getPermission)
                .filter(permission -> !SEED_MARKER.equals(permission))
                .collect(Collectors.toSet());
    }

    /**
     * The enforcement-time read used by {@code AdminPermissionAuthoritySource} (and {@code
     * MeController}): returns the user's real stored grants, and when the user has <b>no rows at
     * all</b> but a bundled role, persists the lazy seed ({@link #SEED_MARKER} + {@link
     * DefaultPermissionBundles#unionForRoles}) first - the backfill path for users whose roles
     * predate permission seeding. Read-write on purpose despite being called during authentication:
     * it only ever writes on a user's first post-enforcement request, then every subsequent call is
     * the pure read above. A concurrent first-request race is absorbed by {@code insertIfAbsent}
     * ({@code ON CONFLICT DO NOTHING} on the {@code (keycloak_user_id, permission)} unique
     * constraint) - the loser inserts nothing, re-reads and returns whatever the winner committed.
     *
     * @param roleNames the caller's realm roles (no {@code ROLE_} prefix)
     * @param grantedBy attribution for seeded rows (the caller's own subject)
     */
    @Transactional
    public Set<String> getOrSeedEffectivePermissions(
            String tenantId, String keycloakUserId, Set<String> roleNames, String grantedBy) {
        // Cached per (tenant, user): the seed path writes rows, so a cached
        // answer is only ever returned for a user whose stored state
        // produced it - and every write path below evicts that exact key.
        return effectivePermissions.get(
                TtlCache.key(tenantId, keycloakUserId),
                () ->
                        loadOrSeedEffectivePermissions(
                                tenantId, keycloakUserId, roleNames, grantedBy));
    }

    /**
     * The uncached body of {@link #getOrSeedEffectivePermissions} - the cache only wraps, never
     * rewrites, the decision.
     */
    private Set<String> loadOrSeedEffectivePermissions(
            String tenantId, String keycloakUserId, Set<String> roleNames, String grantedBy) {
        List<UserPermissionGrant> rows =
                repository.findByTenantIdAndKeycloakUserId(tenantId, keycloakUserId);
        if (!rows.isEmpty()) {
            return rows.stream()
                    .map(UserPermissionGrant::getPermission)
                    .filter(permission -> !SEED_MARKER.equals(permission))
                    .collect(Collectors.toSet());
        }
        Set<String> defaults = DefaultPermissionBundles.unionForRoles(roleNames);
        if (defaults.isEmpty()) {
            // No rows AND nothing to seed (unbundled role like delegated-user):
            // writing the marker here would only clutter the table - the
            // empty result is already correct and stays stable forever.
            return Set.of();
        }
        // Concurrent first requests (a page load fires several API calls at once) all get here with
        // no rows. insertIfAbsent makes each insert a no-op for a row another request already
        // wrote,
        // so none of them fails; the loser's statement waits for the winner's transaction. The
        // answer
        // is then re-read, so every caller returns what is actually stored - if that is still empty
        // (rows removed meanwhile) the result is the empty set: fail closed rather than guess.
        insertIfAbsent(tenantId, keycloakUserId, SEED_MARKER, grantedBy);
        for (String permission : defaults) {
            insertIfAbsent(tenantId, keycloakUserId, permission, grantedBy);
        }
        return repository.findByTenantIdAndKeycloakUserId(tenantId, keycloakUserId).stream()
                .map(UserPermissionGrant::getPermission)
                .filter(permission -> !SEED_MARKER.equals(permission))
                .collect(Collectors.toSet());
    }

    private void insertIfAbsent(
            String tenantId, String keycloakUserId, String permission, String grantedBy) {
        repository.insertIfAbsent(
                UUID.randomUUID(), tenantId, keycloakUserId, permission, grantedBy, Instant.now());
    }

    /**
     * The user ids holding one permission in a tenant - backs {@code UserAdminController}'s
     * last-{@code MANAGE_USERS}-holder lockout guard. Marker rows are naturally excluded (the
     * marker is never a queried permission).
     */
    @Transactional(readOnly = true)
    public Set<String> userIdsHolding(String tenantId, String permission) {
        return repository.findByTenantIdAndPermission(tenantId, permission).stream()
                .map(UserPermissionGrant::getKeycloakUserId)
                .collect(Collectors.toSet());
    }

    /**
     * Sets a user's permissions to exactly the given set - adds whatever is missing, removes
     * whatever shouldn't be there anymore. An empty set is valid and revokes everything (a
     * de-escalation, never an escalation). The {@link #SEED_MARKER} row is deliberately left in
     * place: revoking every real grant must not reset the user's seeded state and trigger a lazy
     * reseed on their next request (see this class's javadoc).
     */
    @Transactional
    public void replacePermissions(
            String tenantId,
            String keycloakUserId,
            Set<String> desiredPermissionNames,
            String grantedBy) {
        Set<String> desired = desiredPermissionNames == null ? Set.of() : desiredPermissionNames;
        Set<String> current = getGrantedPermissions(tenantId, keycloakUserId);

        Set<String> toAdd = new HashSet<>(desired);
        toAdd.removeAll(current);
        Set<String> toRemove = new HashSet<>(current);
        toRemove.removeAll(desired);

        if (!toRemove.isEmpty()) {
            repository.deleteByTenantIdAndKeycloakUserIdAndPermissionIn(
                    tenantId, keycloakUserId, toRemove);
        }
        for (String permission : toAdd) {
            repository.save(
                    new UserPermissionGrant(tenantId, keycloakUserId, permission, grantedBy));
        }
        evictEffectivePermissions(tenantId, keycloakUserId);
    }

    /**
     * Additive-only seeding: computes {@link DefaultPermissionBundles#unionForRoles} for {@code
     * roleNames} and inserts whatever permission from that union the user doesn't already hold,
     * plus the {@link #SEED_MARKER} row that records the user as seeded. Never removes an existing
     * grant - see this class's javadoc for why that matters. Idempotent: calling this again with
     * the same roles inserts nothing further.
     */
    @Transactional
    public void seedMissingDefaultPermissions(
            String tenantId, String keycloakUserId, Set<String> roleNames, String grantedBy) {
        Set<String> defaults = DefaultPermissionBundles.unionForRoles(roleNames);
        if (defaults.isEmpty()) {
            return;
        }
        List<UserPermissionGrant> existing =
                repository.findByTenantIdAndKeycloakUserId(tenantId, keycloakUserId);
        Set<String> current =
                existing.stream()
                        .map(UserPermissionGrant::getPermission)
                        .collect(Collectors.toSet());
        List<UserPermissionGrant> toInsert =
                defaults.stream()
                        .filter(permission -> !current.contains(permission))
                        .map(
                                permission ->
                                        new UserPermissionGrant(
                                                tenantId, keycloakUserId, permission, grantedBy))
                        .collect(Collectors.toList());
        if (!current.contains(SEED_MARKER)) {
            toInsert.add(new UserPermissionGrant(tenantId, keycloakUserId, SEED_MARKER, grantedBy));
        }
        if (!toInsert.isEmpty()) {
            repository.saveAll(toInsert);
            evictEffectivePermissions(tenantId, keycloakUserId);
        }
    }

    /**
     * Drops the cached effective set for one (tenant, user) - now, so a concurrent request can't
     * keep a stale answer after flush, and again after commit, so a request that re-reads the
     * still-uncommitted rows between flush and commit can't re-fill the cache (the same
     * double-evict race fix {@code TenantPlanEvictionListener} uses).
     */
    private void evictEffectivePermissions(String tenantId, String keycloakUserId) {
        HotpathCaches.evictUserPermissions(tenantId, keycloakUserId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            HotpathCaches.evictUserPermissions(tenantId, keycloakUserId);
                        }
                    });
        }
    }
}
