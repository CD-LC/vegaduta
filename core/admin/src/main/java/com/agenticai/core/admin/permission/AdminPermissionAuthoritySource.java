package com.agenticai.core.admin.permission;

import com.agenticai.core.security.PermissionAuthoritySource;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The {@link PermissionAuthoritySource} implementation {@code
 * GranularPermissionConverter} calls once per authenticated request:
 * resolves the caller's effective {@link Permission} names from their
 * stored {@code user_permission_grant} rows, via {@link
 * UserPermissionService#getOrSeedEffectivePermissions} (which also lazy-seeds
 * legacy bundled-role users - see that method's javadoc).
 *
 * <p><b>Grants bind everyone, including tenant-admin.</b> There is no
 * role-based bypass here: a {@code tenant-admin} whose grants were trimmed
 * holds exactly what's left. That was a deliberate product decision
 * (2026-09-23) - the role is a bootstrap that seeds the full bundle, not an
 * ongoing superuser flag. The companion safeguard against trimming everyone
 * out of user administration lives in {@code UserAdminController}'s
 * last-{@code MANAGE_USERS}-holder guard.
 *
 * <p><b>Caching.</b> A lookup hits {@code control_plane} once per request
 * uncached, which would put a JPA query on literally every API call - so
 * results are held in a short-TTL in-memory cache keyed by
 * {@code tenantId:subject}. Staleness is bounded two ways: writes through
 * {@code UserAdminController} call {@link #evict} for the user they just
 * changed, and anything else ages out inside {@link #TTL_SECONDS} (a fixed
 * window from load, not sliding).
 *
 * <p><b>Two-replica behaviour (analysed 2026-09-23, deliberately NOT changed):</b>
 * {@link #evict} only clears the replica that served the admin write; the other
 * replica keeps its entry until the TTL lapses, so a grant change (including a
 * REVOCATION) can take up to {@link #TTL_SECONDS} seconds to bind on it.
 * Cross-replica invalidation is not "trivial and safe" here: a Redis version
 * key would add a Redis read to every authenticated request (the very hop this
 * cache exists to avoid), and pub/sub needs a listener container this module
 * does not have (and no Redis dependency today). The bound is small, hard
 * (never sliding), affects only the admin plane's own permission edits, and the
 * knob is the constant below - shorten it if a tighter revocation SLA is ever
 * required. See docs/plans/CORE-TWO-REPLICA-READINESS.md.
 */
@Component
public class AdminPermissionAuthoritySource implements PermissionAuthoritySource {

    private static final long TTL_SECONDS = 30;

    private record CacheEntry(Set<String> permissions, Instant loadedAt) {
        boolean fresh() {
            return Instant.now().isBefore(loadedAt.plusSeconds(TTL_SECONDS));
        }
    }

    private final UserPermissionService userPermissionService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AdminPermissionAuthoritySource(UserPermissionService userPermissionService) {
        this.userPermissionService = userPermissionService;
    }

    @Override
    public Set<String> effectivePermissions(String tenantId, String subject, Set<String> roleNames) {
        if (tenantId == null || subject == null) {
            return Set.of();
        }
        String key = tenantId + ":" + subject;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.fresh()) {
            return cached.permissions();
        }
        // This runs inside the bearer-token authentication step, BEFORE
        // TenantContextFilter has set a TenantContext for the request. The grant
        // table is control_plane-qualified so the query is correct either way,
        // but with no context Hibernate's schema resolver counted every cache
        // miss as "authenticated_no_context" and logged the TenantContext
        // propagation-bug WARN (146 in 6 h on prod). Setting the context
        // explicitly for just this lookup makes it a normal tenant-scoped read,
        // then restores whatever was there.
        Optional<TenantContext> previous = TenantContext.current();
        TenantContext.set(TenantContext.of(tenantId, subject, Set.of()));
        Set<String> permissions;
        try {
            permissions = userPermissionService.getOrSeedEffectivePermissions(tenantId, subject, roleNames, subject);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
        cache.put(key, new CacheEntry(permissions, Instant.now()));
        return permissions;
    }

    /**
     * Drops one user's cached set so the next request re-reads fresh grants.
     * Called by {@code UserAdminController} after every grant mutation - the
     * TTL alone would already bound staleness, this just makes the admin's
     * own "I just changed it" expectation hold within the same session.
     */
    public void evict(String tenantId, String keycloakUserId) {
        cache.remove(tenantId + ":" + keycloakUserId);
    }
}
