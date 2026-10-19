package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantPlanEvictionListenerTest {

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Tenant tenant(String schema) {
        Tenant t = mock(Tenant.class);
        when(t.getSchemaName()).thenReturn(schema);
        return t;
    }

    @Test
    void aTenantRowWriteEvictsThatTenantsPlanAndPoolEntriesOnly() {
        TtlCache<String> plans = new TtlCache<>(HotpathCaches.TENANT_PLAN, Duration.ofSeconds(10), 10);
        TtlCache<String> pools = new TtlCache<>(HotpathCaches.TENANT_POOL, Duration.ofSeconds(10), 10);
        plans.get(TtlCache.key("acme", "plan"), () -> "PREMIUM");
        plans.get(TtlCache.key("globex", "plan"), () -> "FREE");
        pools.get(TtlCache.key("acme", "PREMIUM"), () -> "false");

        new TenantPlanEvictionListener().onWrite(tenant("acme"));

        assertThat(plans.size()).isEqualTo(1);
        assertThat(pools.size()).isZero();
    }

    @Test
    void evictsAgainAfterCommitSoAConcurrentReadOfTheUncommittedRowCannotRefillIt() {
        TtlCache<String> plans = new TtlCache<>(HotpathCaches.TENANT_PLAN, Duration.ofSeconds(10), 10);
        TransactionSynchronizationManager.initSynchronization();

        new TenantPlanEvictionListener().onWrite(tenant("acme"));
        // a concurrent turn re-reads the still-uncommitted OLD plan between flush and commit
        plans.get(TtlCache.key("acme", "plan"), () -> "PREMIUM");
        assertThat(plans.size()).isEqualTo(1);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.forEach(TransactionSynchronization::afterCommit);

        assertThat(plans.size()).isZero();
    }
}
