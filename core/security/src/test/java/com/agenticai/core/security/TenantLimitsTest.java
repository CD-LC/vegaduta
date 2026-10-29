package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link TenantLimits} default methods every implementation inherits: the demo-schema check
 * fails safe to "not demo", {@code storedPlan} mirrors {@code plan} for implementations that never
 * heard of free mode, the single-arg {@code usesPlatformPool} delegates to the two-arg form, and
 * {@code platformPoolAvailable} defaults to hosted-SaaS behaviour.
 */
class TenantLimitsTest {

    /** Minimal implementation: every abstract method answered from one recorded plan. */
    private static final class FakeLimits implements TenantLimits {
        private final TenantPlan plan;
        private boolean twoArgCalled;

        private FakeLimits(TenantPlan plan) {
            this.plan = plan;
        }

        @Override
        public int maxUsers(String tenantId) {
            return 2;
        }

        @Override
        public Optional<Integer> maxAgents(String tenantId) {
            return Optional.of(5);
        }

        @Override
        public Optional<Integer> maxWorkflows(String tenantId) {
            return Optional.empty();
        }

        @Override
        public AccountType accountType(String tenantId) {
            return AccountType.B2C;
        }

        @Override
        public ExperienceMode experienceMode(String tenantId) {
            return ExperienceMode.SIMPLE;
        }

        @Override
        public TenantPlan plan(String tenantId) {
            return plan;
        }

        @Override
        public boolean usesPlatformPool(String tenantId, TenantPlan plan) {
            twoArgCalled = true;
            return true;
        }

        @Override
        public boolean usePlatformLlm(String tenantId) {
            return false;
        }

        @Override
        public boolean byokAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean whatsappAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean whiteLabel(String tenantId) {
            return false;
        }

        @Override
        public Optional<String> brandDisplayName(String tenantId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> brandLogoUrl(String tenantId) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> maxKbCollections(String tenantId) {
            return Optional.empty();
        }

        @Override
        public boolean liveVisionAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean externalChannelsAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean developerApiAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean ssoAllowed(String tenantId) {
            return true;
        }

        @Override
        public boolean scimAllowed(String tenantId) {
            return false;
        }

        @Override
        public boolean workflowExportAllowed(String tenantId) {
            return false;
        }

        @Override
        public boolean automationAllowed(String tenantId) {
            return true;
        }
    }

    @Test
    void isSharedDemoTenantDefaultsToFalseSoPlanGatesStayOn() {
        assertThat(new FakeLimits(TenantPlan.FREE).isSharedDemoTenant("anything")).isFalse();
        assertThat(new FakeLimits(TenantPlan.FREE).isSharedDemoTenant(null)).isFalse();
    }

    @Test
    void storedPlanMirrorsPlanForAnImplementationWithoutFreeMode() {
        assertThat(new FakeLimits(TenantPlan.PREMIUM).storedPlan("tenant_acme"))
                .isEqualTo(TenantPlan.PREMIUM);
    }

    @Test
    void theSingleArgUsesPlatformPoolDelegatesToTheTwoArgForm() {
        FakeLimits limits = new FakeLimits(TenantPlan.FREE);

        assertThat(limits.usesPlatformPool("tenant_acme")).isTrue();
        assertThat(limits.twoArgCalled).isTrue();
    }

    @Test
    void platformPoolAvailableDefaultsToTheHostedSaasAnswer() {
        assertThat(new FakeLimits(TenantPlan.FREE).platformPoolAvailable()).isTrue();
    }
}
