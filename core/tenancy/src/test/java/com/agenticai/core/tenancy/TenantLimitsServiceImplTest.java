package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A2-PR1 acceptance: "grep proves no remaining hardcoded 2/5/3 limit
 * constants in tenancy" (verified by inspection - this class no longer
 * declares FREE_MAX_USERS/etc.) plus behavioral proof that the public
 * {@code TenantLimits} contract every existing caller (AgentChatService,
 * AgentService, WorkflowService, UserAdminController, ...) depends on is
 * unchanged by the {@link PlanLimitsService} refactor: same return types,
 * same {@code -1 -> Integer.MAX_VALUE}/{@code Optional.empty()}
 * translation the pre-refactor hardcoded UNLIMITED case already used.
 */
class TenantLimitsServiceImplTest {

    private TenantRepository tenantRepository;
    private PlanLimitsService planLimitsService;
    private FreeModeService freeModeService;
    // Typed as the interface, not the impl: the one-argument {@code
    // usesPlatformPool} overload is a default method on TenantLimits, and
    // every caller in the product reaches this class through the interface.
    private TenantLimits service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        planLimitsService = mock(PlanLimitsService.class);
        // Mockito's default boolean stub is false - free mode OFF for every
        // pre-existing test; the free-mode tests stub it on explicitly.
        freeModeService = mock(FreeModeService.class);
        service = new TenantLimitsServiceImpl(tenantRepository, planLimitsService, freeModeService);
    }

    private static PlanLimitsService.ResolvedPlanLimits limits(
        TenantPlan plan, int users, int agents, int workflows
    ) {
        // live_vision_allowed (V180) + the six V470 entitlements mirror their
        // migration seeds - see PlanLimitsServiceTest's row() helper for the
        // per-plan breakdown.
        boolean paid = plan != TenantPlan.FREE;
        boolean unlimited = plan == TenantPlan.UNLIMITED;
        return new PlanLimitsService.ResolvedPlanLimits(
            plan, users, agents, workflows, 1, false, false, false,
            paid, paid, paid, paid, unlimited, unlimited, paid);
    }

    @Test
    void freeUsesPlanLimitsRowValuesDirectly() {
        when(planLimitsService.getLimits("acme")).thenReturn(limits(TenantPlan.FREE, 2, 5, 3));

        assertEquals(2, service.maxUsers("acme"));
        assertEquals(Optional.of(5), service.maxAgents("acme"));
        assertEquals(Optional.of(3), service.maxWorkflows("acme"));
    }

    @Test
    void premiumUsesPlanLimitsRowValuesDirectly() {
        when(planLimitsService.getLimits("acme")).thenReturn(limits(TenantPlan.PREMIUM, 10, 25, 15));

        assertEquals(10, service.maxUsers("acme"));
        assertEquals(Optional.of(25), service.maxAgents("acme"));
        assertEquals(Optional.of(15), service.maxWorkflows("acme"));
    }

    @Test
    void unlimitedTranslatesNegativeOneToMaxValueAndEmptyOptional() {
        when(planLimitsService.getLimits("acme")).thenReturn(limits(TenantPlan.UNLIMITED, -1, -1, -1));

        assertEquals(Integer.MAX_VALUE, service.maxUsers("acme"));
        assertEquals(Optional.empty(), service.maxAgents("acme"));
        assertEquals(Optional.empty(), service.maxWorkflows("acme"));
    }

    @Test
    void accountTypeExperienceModePlanAndUsePlatformLlmReadStraightOffTheTenantRowUnchanged() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getAccountType()).thenReturn(AccountType.B2C);
        when(tenant.getExperienceMode()).thenReturn(ExperienceMode.SIMPLE);
        when(tenant.getPlan()).thenReturn(TenantPlan.PREMIUM);
        when(tenant.isUsePlatformLlm()).thenReturn(true);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        assertEquals(AccountType.B2C, service.accountType("acme"));
        assertEquals(ExperienceMode.SIMPLE, service.experienceMode("acme"));
        assertEquals(TenantPlan.PREMIUM, service.plan("acme"));
        assertTrue(service.usePlatformLlm("acme"));
    }

    @Test
    void liveVisionAllowedReadsThePlanLimitsRow() {
        when(planLimitsService.getLimits("acme")).thenReturn(limits(TenantPlan.PREMIUM, 10, 25, 15));
        when(planLimitsService.getLimits("shoestring")).thenReturn(limits(TenantPlan.FREE, 2, 5, 3));

        assertTrue(service.liveVisionAllowed("acme"));
        assertFalse(service.liveVisionAllowed("shoestring"));
    }

    /**
     * The hosted-pool routing rule - see {@link
     * com.agenticai.core.security.TenantLimits#usesPlatformPool(String,
     * com.agenticai.core.security.TenantPlan)}. The case that forced this
     * method to exist is {@link #complimentaryGrantTenantIsOnTheHostedPool}:
     * every self-service signup is granted a three-month complimentary
     * UNLIMITED plan, and under the old {@code plan == FREE ||
     * usePlatformLlm} rule that grant took the tenant OFF the pool for
     * exactly the window the product promises a hosted model is included.
     * The three tests either side of it are the ones that must NOT move.
     */
    @Nested
    class UsesPlatformPool {

        private Tenant tenantRow(TenantPlan plan, boolean optedIn, Instant compExpiresAt) {
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(plan);
            when(tenant.isUsePlatformLlm()).thenReturn(optedIn);
            when(tenant.getCompPlanExpiresAt()).thenReturn(compExpiresAt);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));
            return tenant;
        }

        @Test
        void complimentaryGrantTenantIsOnTheHostedPool() {
            // A brand-new signup: TenantProvisioningRecorder granted UNLIMITED
            // for three months and never touched usePlatformLlm.
            tenantRow(TenantPlan.UNLIMITED, false, Instant.now().plus(80, ChronoUnit.DAYS));

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
            assertTrue(service.usesPlatformPool("acme"));
        }

        @Test
        void complimentaryGrantTenantWhoExplicitlyChoseByokLeavesThePool() {
            // V236: the grant only stands in for a choice not yet made. Without
            // this, "Your own provider" was unselectable for the whole grant.
            Tenant tenant = tenantRow(TenantPlan.UNLIMITED, false, Instant.now().plus(80, ChronoUnit.DAYS));
            when(tenant.isLlmSourceExplicit()).thenReturn(true);

            assertFalse(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
            assertFalse(service.usesPlatformPool("acme"));
        }

        @Test
        void paidUnlimitedTenantWithNoExplicitChoiceIsOnThePlatformPoolByDefault() {
            // Platform pool is the default for everyone; only an explicit choice
            // (llm_source_explicit) takes a tenant off it.
            tenantRow(TenantPlan.UNLIMITED, false, null);

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
            assertTrue(service.usesPlatformPool("acme"));
        }

        @Test
        void paidTenantWhoExplicitlyChoseByokStaysOnByok() {
            Tenant tenant = tenantRow(TenantPlan.UNLIMITED, false, null);
            when(tenant.isLlmSourceExplicit()).thenReturn(true);

            assertFalse(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
            assertFalse(service.usesPlatformPool("acme"));
        }

        @Test
        void expiredComplimentaryGrantTenantIsStillOnThePoolByDefault() {
            // CompGrantExpiryReverter nulls this column when it reverts, but it
            // is a scheduled sweep - the grant must lapse on the clock, not on
            // the job.
            tenantRow(TenantPlan.UNLIMITED, false, Instant.now().minus(1, ChronoUnit.DAYS));

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
        }

        @Test
        void freeTenantIsStillOnTheHostedPool() {
            tenantRow(TenantPlan.FREE, false, null);

            assertTrue(service.usesPlatformPool("acme", TenantPlan.FREE));
            assertTrue(service.usesPlatformPool("acme"));
        }

        @Test
        void explicitOptInIsStillOnTheHostedPool() {
            tenantRow(TenantPlan.PREMIUM, true, null);

            assertTrue(service.usesPlatformPool("acme", TenantPlan.PREMIUM));
            assertTrue(service.usesPlatformPool("acme"));
        }

        @Test
        void aFreePlanPassedInCostsNoDatabaseLookup() {
            // The per-turn chat path hands us the plan it already has. Answering
            // FREE from it is what keeps this off the hot path's query budget -
            // note this test deliberately stubs NO repository response.
            assertTrue(service.usesPlatformPool("acme", TenantPlan.FREE));

            verify(tenantRepository, never()).findBySchemaName("acme");
        }

        @Test
        void noTenantContextIsByok() {
            assertFalse(service.usesPlatformPool(null, null));
            assertFalse(service.usesPlatformPool(null));
        }

        @Test
        void theGrantIsNotWrittenBackOntoTheOptInFlag() {
            // the maintainer chose the computed form over writing usePlatformLlm=true at
            // grant time, precisely so the expiry revert has nothing to unwind
            // and can never clobber a tenant's own toggle. Pool: yes. Stored
            // opt-in: untouched.
            tenantRow(TenantPlan.UNLIMITED, false, Instant.now().plus(80, ChronoUnit.DAYS));

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
            assertFalse(service.usePlatformLlm("acme"));
        }
    }

    @Test
    void unknownTenantSchemaThrowsIllegalStateForRowBackedMethods() {
        when(tenantRepository.findBySchemaName("ghost")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.plan("ghost"));
    }

    /**
     * Free mode (platform.free-mode.enabled, V237): the effective plan for
     * feature gating is UNLIMITED for every tenant, and stored-FREE tenants
     * keep the hosted pool forever - without the second clause, flipping the
     * flag would silently demand a BYOK key from every expired-grant FREE
     * tenant. The stored {@code Tenant.plan} is still the reporting truth.
     */
    @Nested
    class FreeMode {

        @Test
        void planResolvesUnlimitedWithoutReadingTheTenantRow() {
            when(freeModeService.isEnabled()).thenReturn(true);

            assertEquals(TenantPlan.UNLIMITED, service.plan("acme"));
            verify(tenantRepository, never()).findBySchemaName("acme");
        }

        @Test
        void storedPlanIgnoresFreeModeAndReadsTheRow() {
            // The Agent Lab's knockout/consistency gate reads this so free mode
            // does not open a 120-call fan-out to every tenant.
            when(freeModeService.isEnabled()).thenReturn(true);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertEquals(TenantPlan.UNLIMITED, service.plan("acme"));
            assertEquals(TenantPlan.FREE, service.storedPlan("acme"));
        }

        @Test
        void storedFreeTenantWhoExplicitlyChoseByokUnderFreeModeLeavesThePool() {
            // Prod 2026-09-24: "Switch to BYOK" saved (usePlatformLlm=false,
            // llmSourceExplicit=true) but GET /api/providers/availability (plan
            // null) still said "pool", so the UI never showed BYOK as selected.
            when(freeModeService.isEnabled()).thenReturn(true);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
            when(tenant.isUsePlatformLlm()).thenReturn(false);
            when(tenant.isLlmSourceExplicit()).thenReturn(true);
            when(tenant.getCompPlanExpiresAt()).thenReturn(null);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertFalse(service.usesPlatformPool("acme"));
            assertFalse(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
        }

        @Test
        void storedFreeTenantWithFreeModeOffStillShortCircuitsToThePool() {
            when(freeModeService.isEnabled()).thenReturn(false);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
            when(tenant.isLlmSourceExplicit()).thenReturn(true);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertTrue(service.usesPlatformPool("acme"));
        }

        @Test
        void storedFreeTenantWithExpiredGrantStaysOnTheHostedPool() {
            when(freeModeService.isEnabled()).thenReturn(true);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
            when(tenant.isUsePlatformLlm()).thenReturn(false);
            when(tenant.getCompPlanExpiresAt()).thenReturn(Instant.now().minus(1, ChronoUnit.DAYS));
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
        }

        @Test
        void paidPlanTenantWithNoExplicitChoiceIsOnThePoolUnderFreeModeToo() {
            // Default is the pool; a paying tenant that already runs its own key
            // is marked explicit by V366 rather than left to this fall-through.
            when(freeModeService.isEnabled()).thenReturn(true);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.UNLIMITED);
            when(tenant.isUsePlatformLlm()).thenReturn(false);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertTrue(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
        }

        @Test
        void explicitByokChoiceStillLeavesThePool() {
            // Even a stored-FREE tenant who explicitly chose "Your own
            // provider" is honoured - free mode unlocks, it does not force.
            when(freeModeService.isEnabled()).thenReturn(true);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getPlan()).thenReturn(TenantPlan.FREE);
            when(tenant.isUsePlatformLlm()).thenReturn(false);
            when(tenant.isLlmSourceExplicit()).thenReturn(true);
            when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

            assertFalse(service.usesPlatformPool("acme", TenantPlan.UNLIMITED));
        }
    }
}
