package com.agenticai.core.tenancy;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.security.TtlCache;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The real {@link TenantStatusGuard} implementation - see that interface's
 * javadoc for why it lives in {@code core/security} instead of here (a
 * dependency-inversion seam so {@code core/security} never needs a
 * compile-time dependency on {@code core/tenancy}).
 *
 * <p>Now cached: this runs once per authenticated request (from {@link
 * com.agenticai.core.security.TenantContextFilter}) - the single hottest
 * read path in the system - and the "single cheap lookup" turned out to be
 * two-to-three real DB round-trips per request at scale. The fix is exactly
 * the short-TTL cache this class's own javadoc always prescribed ({@code
 * HotpathCaches#TENANT_STATUS}/{@code PLATFORM_STATUS}, default 10s via
 * {@code app.tenant.status-cache-ttl}; {@code 0s} restores the previous
 * uncached behavior). Correctness is preserved by eviction, not TTL luck:
 * every {@code tenant} row write flushes the cached answer through {@link
 * TenantPlanEvictionListener} (flush + afterCommit), and the platform
 * kill-switch write path evicts its own entry in {@link
 * TenantEmergencyControlService} - so an emergency stop on this pod is
 * effective on the very next request, and other pods lag by at most the
 * TTL.
 *
 * <p><b>{@link #schemaExists} fallback, added 2026-07-11:</b> a {@code
 * tenant_id} with no matching {@link Tenant} row was originally treated as
 * always-usable outright - safe for a tenant that legitimately predates
 * {@code control_plane.tenant} bookkeeping (see {@link
 * TenantProvisioningService}'s own javadoc, which references exactly such a
 * hand-provisioned tenant from the Phase 0 Stage 4 verification walkthrough),
 * but not for one whose schema was silently dropped or never created. The
 * very next real-world report after this guard shipped was exactly that
 * second case: a request routed straight through the original always-true
 * fallback and failed with the same "relation does not exist" error the
 * guard exists to prevent, since the DB simply has no {@code
 * control_plane.tenant} row for whichever schema that request's JWT named.
 * Falling back to a literal {@code information_schema.schemata} check closes
 * that gap without breaking the legitimate case: an untracked tenant whose
 * schema genuinely exists still passes.
 */
@Component
public class TenantStatusGuardImpl implements TenantStatusGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusGuardImpl.class);

    private final TenantRepository tenantRepository;
    private final PlatformKillSwitchRepository platformKillSwitchRepository;
    private final DataSource dataSource;
    private final TtlCache<Boolean> tenantUsable;
    private final TtlCache<Boolean> platformUsable;

    public TenantStatusGuardImpl(
        TenantRepository tenantRepository,
        PlatformKillSwitchRepository platformKillSwitchRepository,
        DataSource dataSource,
        @Value("${app.tenant.status-cache-ttl:10s}") Duration statusCacheTtl
    ) {
        this.tenantRepository = tenantRepository;
        this.platformKillSwitchRepository = platformKillSwitchRepository;
        this.dataSource = dataSource;
        // Both registered under the names TenantPlanEvictionListener /
        // TenantEmergencyControlService evict on write - see class javadoc.
        this.tenantUsable = new TtlCache<>(HotpathCaches.TENANT_STATUS, statusCacheTtl, 10_000);
        this.platformUsable = new TtlCache<>(HotpathCaches.PLATFORM_STATUS, statusCacheTtl, 4);
    }

    /**
     * #815 Governance follow-up (Fail-safe: platform + per-tenant kill
     * switch) - the platform-wide check runs FIRST and short-circuits
     * everything else, since a platform-stopped incident should never even
     * reach a per-tenant lookup. Missing the singleton row (migration V76
     * not run) fails open (not stopped) rather than blocking every request
     * on a config gap - same "never let an infra hiccup lock out the whole
     * platform" posture as {@link #schemaExists}'s SQLException handling
     * below.
     */
    @Override
    public boolean isUsable(String tenantId) {
        // One cached singleton read instead of a findById per request - the
        // platform kill-switch write path evicts this entry the moment the
        // row changes, so "stopped" is never served stale on this pod.
        boolean platformStopped = platformUsable.get(TtlCache.key("platform", "usable"), () ->
            platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)
                .map(PlatformKillSwitch::isStopped)
                .orElse(false));
        if (platformStopped) {
            return false;
        }
        return tenantUsable.get(TtlCache.key(tenantId, "usable"), () -> computeUsable(tenantId));
    }

    /** The uncached body of {@link #isUsable}'s per-tenant half - kept verbatim so the cache only wraps, never rewrites, the decision. */
    private boolean computeUsable(String tenantId) {
        return tenantRepository.findBySchemaName(tenantId)
            .map(tenant -> tenant.getStatus() == TenantStatus.ACTIVE && !tenant.isEmergencyStopped())
            .orElseGet(() -> schemaExists(tenantId));
    }

    /**
     * Fail-open on any SQL error - a transient connectivity blip here should
     * never lock every untracked-tenant request out of the whole platform;
     * worst case, this behaves exactly like the pre-2026-07-11 always-true
     * fallback for the duration of that blip.
     */
    private boolean schemaExists(String schemaName) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schemaName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            log.warn("Could not verify schema existence for untracked tenant '{}' - allowing the request through", schemaName, e);
            return true;
        }
    }
}
