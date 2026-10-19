package com.agenticai.core.tenancy;

import com.agenticai.core.security.HotpathCaches;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch) -
 * the write side of both controls {@link TenantStatusGuardImpl#isUsable}
 * reads. Deliberately platform-admin-only actions (wired to {@code
 * ROLE_platform-admin} in {@code SecurityConfig}, same tier as {@code
 * PlatformTenantPlanService}'s plan override) - an emergency stop is a
 * platform-level incident-response lever, not a tenant self-service setting.
 */
@Service
public class TenantEmergencyControlService {

    private final TenantRepository tenantRepository;
    private final PlatformKillSwitchRepository platformKillSwitchRepository;

    public TenantEmergencyControlService(
        TenantRepository tenantRepository,
        PlatformKillSwitchRepository platformKillSwitchRepository
    ) {
        this.tenantRepository = tenantRepository;
        this.platformKillSwitchRepository = platformKillSwitchRepository;
    }

    @Transactional
    public void stopTenant(UUID tenantId, String reason, String updatedBy) {
        requireTenant(tenantId).emergencyStop(reason == null || reason.isBlank() ? "No reason given" : reason);
    }

    @Transactional
    public void resumeTenant(UUID tenantId) {
        requireTenant(tenantId).emergencyResume();
    }

    @Transactional
    public void stopPlatform(String reason, String updatedBy) {
        killSwitch().stop(reason == null || reason.isBlank() ? "No reason given" : reason, updatedBy);
        evictPlatformStatus();
    }

    @Transactional
    public void resumePlatform(String updatedBy) {
        killSwitch().resume(updatedBy);
        evictPlatformStatus();
    }

    @Transactional(readOnly = true)
    public boolean isPlatformStopped() {
        return killSwitch().isStopped();
    }

    @Transactional(readOnly = true)
    public PlatformKillSwitchStatus getStatus() {
        PlatformKillSwitch killSwitch = killSwitch();
        return new PlatformKillSwitchStatus(
            killSwitch.isStopped(), killSwitch.getReason(), killSwitch.getStoppedAt(), killSwitch.getUpdatedBy()
        );
    }

    /**
     * Flushes the cached "platform is stopped" answer {@link
     * TenantStatusGuardImpl#isUsable} serves every authenticated request -
     * now (so a concurrent read can't keep a stale "running" answer after
     * flush) and again after commit (so a read that re-fills between flush
     * and commit with the still-uncommitted old row is dropped too - the
     * exact double-evict pattern {@link TenantPlanEvictionListener}
     * established for the same race).
     */
    private void evictPlatformStatus() {
        HotpathCaches.evictPlatformStatus();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    HotpathCaches.evictPlatformStatus();
                }
            });
        }
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("No tenant with id " + tenantId));
    }

    /**
     * The singleton row is seeded by migration V76 - {@code orElseThrow}
     * rather than lazily creating it here, so a missing row surfaces as a
     * loud startup-adjacent failure (a migration that didn't run) instead of
     * silently behaving as "never stopped" the first time this is read.
     */
    private PlatformKillSwitch killSwitch() {
        return platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)
            .orElseThrow(() -> new IllegalStateException(
                "control_plane.platform_kill_switch has no singleton row - migration V76 did not run correctly."));
    }
}
