package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.HostedPoolAvailability;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code app.llm.default-source}: what a tenant that never made an explicit
 * LLM-source choice resolves to. Hosted SaaS = platform-pool; a self-hosted
 * install with no pool configured must degrade to the tenant's own key.
 */
class TenantLimitsDefaultSourceTest {

    private TenantRepository tenantRepository;
    private HostedPoolAvailability availability;
    private ObjectProvider<HostedPoolAvailability> provider;
    private Tenant tenant;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        availability = mock(HostedPoolAvailability.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(availability);
        tenant = mock(Tenant.class);
        when(tenant.getPlan()).thenReturn(TenantPlan.UNLIMITED);
        when(tenant.isUsePlatformLlm()).thenReturn(false);
        when(tenant.isLlmSourceExplicit()).thenReturn(false);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));
    }

    private TenantLimits service(String mode) {
        return new TenantLimitsServiceImpl(
            tenantRepository, mock(PlanLimitsService.class), mock(FreeModeService.class), provider, mode);
    }

    @Test
    void autoUsesThePoolWhenOneIsConfigured() {
        when(availability.isPoolConfigured()).thenReturn(true);
        assertTrue(service("auto").usesPlatformPool("acme", TenantPlan.UNLIMITED));
    }

    @Test
    void autoFallsBackToByokWhenNoPoolIsConfigured() {
        when(availability.isPoolConfigured()).thenReturn(false);
        assertFalse(service("auto").usesPlatformPool("acme", TenantPlan.UNLIMITED));
    }

    @Test
    void platformPoolModeIgnoresAvailability() {
        when(availability.isPoolConfigured()).thenReturn(false);
        assertTrue(service("platform-pool").usesPlatformPool("acme", TenantPlan.UNLIMITED));
    }

    @Test
    void byokModeNeverDefaultsToThePool() {
        when(availability.isPoolConfigured()).thenReturn(true);
        assertFalse(service("BYOK").usesPlatformPool("acme", TenantPlan.UNLIMITED));
    }

    @Test
    void explicitPoolChoiceStillWinsInEveryMode() {
        when(tenant.isUsePlatformLlm()).thenReturn(true);
        when(tenant.isLlmSourceExplicit()).thenReturn(true);
        when(availability.isPoolConfigured()).thenReturn(false);
        assertTrue(service("byok").usesPlatformPool("acme", TenantPlan.UNLIMITED));
    }

    // --- FREE plan x mode x pool present/absent -------------------------------------------

    private boolean free(String mode, boolean poolPresent) {
        when(availability.isPoolConfigured()).thenReturn(poolPresent);
        return service(mode).usesPlatformPool("acme", TenantPlan.FREE);
    }

    @Test
    void freePlanAutoFollowsPoolAvailability() {
        assertTrue(free("auto", true));
        assertFalse(free("auto", false));
    }

    @Test
    void freePlanPlatformPoolModeAlwaysUsesThePool() {
        assertTrue(free("platform-pool", true));
        assertTrue(free("platform-pool", false));
    }

    @Test
    void freePlanByokModeNeverUsesThePool() {
        assertFalse(free("byok", true));
        assertFalse(free("byok", false));
    }

    @Test
    void storedFreeTenantWithNullPlanFollowsTheSameRule() {
        when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
        when(availability.isPoolConfigured()).thenReturn(false);
        assertFalse(service("auto").usesPlatformPool("acme"));
        when(availability.isPoolConfigured()).thenReturn(true);
        assertTrue(service("auto").usesPlatformPool("acme"));
    }

    @Test
    void byokIsUnlockedForFreeOnlyWhenThereIsNoPool() {
        PlanLimitsService plans = mock(PlanLimitsService.class);
        when(plans.getLimits("acme")).thenReturn(new PlanLimitsService.ResolvedPlanLimits(
            TenantPlan.FREE, 2, 5, 3, 1, false, false, false, false, false, false, false, false, false, false));
        TenantLimits noPool = new TenantLimitsServiceImpl(
            tenantRepository, plans, mock(FreeModeService.class), provider, "byok");
        TenantLimits hosted = new TenantLimitsServiceImpl(
            tenantRepository, plans, mock(FreeModeService.class), provider, "platform-pool");
        assertTrue(noPool.byokAllowed("acme"));
        assertFalse(hosted.byokAllowed("acme"));
        assertFalse(noPool.platformPoolAvailable());
        assertTrue(hosted.platformPoolAvailable());
    }
}
