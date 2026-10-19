package com.agenticai.core.tenancy;

import com.agenticai.core.security.HotpathCaches;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts the chat hot path's per-tenant plan caches ({@link HotpathCaches#TENANT_PLAN}/{@code TENANT_POOL})
 * AND the filter chain's per-tenant status cache ({@link HotpathCaches#TENANT_STATUS}) on ANY write to the
 * {@code tenant} row (plan change, comp grant/revert, usePlatformLlm, status flip, emergency stop/resume,
 * ...), so a write on this pod is visible to the very next request on this pod. Evicts at flush and again
 * after commit: between the two a concurrent read could re-read the still-uncommitted old row and re-fill
 * the cache. Other pods only see the change after the cache TTL (see docs/plans/CORE-HOTPATH-FLAGS.md). The
 * plan caches are disabled by default, in which case their half of this is a cheap no-op. Raw-SQL updates
 * bypass JPA listeners; none of them write plan or status (checked 2026-09-23: only subscription_status,
 * billing profile, is_demo).
 */
public class TenantPlanEvictionListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    void onWrite(Tenant tenant) {
        String tenantId = tenant.getSchemaName();
        HotpathCaches.evictTenantPlan(tenantId);
        // Same write, same visibility contract for the isUsable cache - an
        // emergency stop must not sit behind a stale "usable" answer for up
        // to the TTL on the pod that issued it.
        HotpathCaches.evictTenantStatus(tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    HotpathCaches.evictTenantPlan(tenantId);
                    HotpathCaches.evictTenantStatus(tenantId);
                }
            });
        }
    }
}
