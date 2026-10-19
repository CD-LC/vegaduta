package com.agenticai.core.tenancy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A self-service signup that never completes
 * Razorpay's checkout/authorization step should not sit around fully
 * provisioned (real Keycloak login, real Postgres schema) forever. This job
 * finds those tenants and rolls them back via
 * {@link TenantProvisioningService#rollbackAbandonedCheckout}, reusing the
 * exact same compensating-rollback mechanism {@code signup()} already uses
 * for a mid-provisioning failure - deliberately not a second cleanup
 * mechanism, per the Billing Build Plan's Stage 3 task list.
 *
 * <p>{@link #ABANDON_WINDOW_HOURS} intentionally matches {@code
 * RazorpayGatewayClient}'s own {@code AUTHORIZATION_WINDOW_HOURS}
 * (Razorpay's {@code expire_by}) - see that field's javadoc for why they're
 * two separate constants that just happen to agree today rather than one
 * shared config value.
 *
 * <p>Multi-replica: each sweep runs under {@link PgAdvisoryJobLock}. Two
 * replicas racing the same abandoned tenant would both drive {@code
 * rollbackAbandonedCheckout}'s multi-step compensation (Keycloak user
 * delete, schema drop, row cleanup) concurrently - each step tolerates
 * "already gone", but interleaving them is exactly the kind of half-rolled-
 * back state the compensation was built to avoid, so the lock is
 * load-bearing here, not just insurance.
 */
@Component
@Profile("security")
public class AbandonedSignupCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AbandonedSignupCleanupJob.class);

    static final long ABANDON_WINDOW_HOURS = 48;

    private static final String JOB_LOCK_NAME = "abandoned-signup-cleanup";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final TenantProvisioningService provisioningService;
    private final PgAdvisoryJobLock jobLock;

    public AbandonedSignupCleanupJob(
        TenantRepository tenantRepository,
        AppUserRepository appUserRepository,
        TenantProvisioningService provisioningService,
        PgAdvisoryJobLock jobLock
    ) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.provisioningService = provisioningService;
        this.jobLock = jobLock;
    }

    // Hourly, with a short startup delay so this never races the very first
    // requests against a cold-starting app - same reasoning any other
    // periodic background job in this codebase would apply, there just
    // hasn't been one until this one.
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void cleanupAbandonedCheckouts() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica cleaning up is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::cleanupAbandonedCheckoutsExclusively);
    }

    private void cleanupAbandonedCheckoutsExclusively() {
        Instant cutoff = Instant.now().minus(ABANDON_WINDOW_HOURS, ChronoUnit.HOURS);
        List<Tenant> abandoned = tenantRepository.findAbandonedCheckouts(cutoff);
        for (Tenant tenant : abandoned) {
            String keycloakUserId = appUserRepository.findByTenantId(tenant.getId()).stream()
                .findFirst()
                .map(AppUser::getKeycloakUserId)
                .orElse(null);
            try {
                provisioningService.rollbackAbandonedCheckout(tenant, keycloakUserId);
            } catch (RuntimeException e) {
                // Best-effort, same posture as compensate()'s own cleanup
                // failures - logged, not rethrown, so one bad tenant in the
                // batch never blocks the rest from being cleaned up this run.
                log.error("Failed to roll back abandoned checkout for tenant {}", tenant.getId(), e);
            }
        }
    }
}
