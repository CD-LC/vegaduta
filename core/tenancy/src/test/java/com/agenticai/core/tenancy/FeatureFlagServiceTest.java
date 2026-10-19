package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FeatureFlagService#evaluate} runs inside every {@code
 * AgentChatService} chat turn, which is why its two contractual properties
 * are worth a suite: bucketing must be STICKY (a tenant flapping between
 * control and treatment between turns invalidates the experiment AND makes
 * the product feel haunted), and every failure must resolve to CONTROL
 * rather than propagate (a broken experiment must never be able to break
 * live chat for every tenant - the same fail-open posture the class's own
 * javadoc names for its siblings). The 0%/100% boundary tests exist because
 * they are exactly what an off-by-one in {@code bucket < rolloutPercentage}
 * breaks: at 0% a {@code <=} would leak the bucket-0 cohort into treatment,
 * at 100% a {@code <} against a [0,100] bucket range would strand the
 * bucket-100 cohort in control. No test here depends on a specific hash
 * value - that would pin the SHA-256 construction itself, which is an
 * implementation detail the stickiness/boundary contracts don't require.
 */
class FeatureFlagServiceTest {

    private FeatureFlagRepository flagRepository;
    private ExperimentAssignmentRepository assignmentRepository;
    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        flagRepository = mock(FeatureFlagRepository.class);
        assignmentRepository = mock(ExperimentAssignmentRepository.class);
        // Best-effort assignment bookkeeping is not under test in most cases -
        // stub the lookup so the save path behaves like a first evaluation.
        when(assignmentRepository.findByFlagKeyAndTenantId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        service = new FeatureFlagService(flagRepository, assignmentRepository);
    }

    /** Real entity, not a mock - the service reads enabled/rolloutPercentage straight off it. */
    private FeatureFlag flag(String key, boolean enabled, int rolloutPercentage) {
        FeatureFlag flag = new FeatureFlag(key, "test flag", enabled, rolloutPercentage, "test");
        when(flagRepository.findByKey(key)).thenReturn(Optional.of(flag));
        return flag;
    }

    /** ~50 synthetic tenant ids - enough to make the absolute-boundary loops meaningful. */
    private static List<String> syntheticTenantIds() {
        List<String> tenants = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tenants.add("tenant_" + i);
        }
        return tenants;
    }

    // -- Stickiness --

    @Test
    void sameFlagAndTenantAlwaysYieldTheSameVariantAcrossRepeatedCallsAndFreshInstances() {
        flag("exp.reasoning-depth", true, 37); // an odd mid-range percentage on purpose

        String first = service.evaluate("exp.reasoning-depth", "tenant_sticky");
        String second = service.evaluate("exp.reasoning-depth", "tenant_sticky");

        // A brand-new service instance (fresh construction, same mocked rows)
        // must agree too - stickiness has to come from the deterministic
        // hash, not from any per-instance state, or a core restart / a
        // second replica would re-roll every tenant's cohort.
        FeatureFlagService freshInstance = new FeatureFlagService(flagRepository, assignmentRepository);
        String fromFreshInstance = freshInstance.evaluate("exp.reasoning-depth", "tenant_sticky");

        assertEquals(first, second);
        assertEquals(first, fromFreshInstance);
    }

    @Test
    void differentFlagKeysBucketTheSameTenantsIndependently() {
        // Two 50% flags over the same 50 tenants: if bucketing ignored the
        // flag key (hashing only the tenant), the two assignment sequences
        // would be IDENTICAL and every experiment would share one cohort.
        // SHA-256 is deterministic, so this inequality is stable across runs
        // - not a probabilistic assertion, despite the loop.
        flag("exp.alpha", true, 50);
        flag("exp.beta", true, 50);

        List<String> alphaVariants = new ArrayList<>();
        List<String> betaVariants = new ArrayList<>();
        for (String tenantId : syntheticTenantIds()) {
            alphaVariants.add(service.evaluate("exp.alpha", tenantId));
            betaVariants.add(service.evaluate("exp.beta", tenantId));
        }

        assertNotEquals(alphaVariants, betaVariants);
        // Sanity on the 50% flags themselves: both cohorts must actually be
        // mixed - all-control or all-treatment at 50% over 50 tenants would
        // mean the percentage isn't being applied at all.
        assertTrue(alphaVariants.contains(FeatureFlagService.CONTROL)
            && alphaVariants.contains(FeatureFlagService.TREATMENT));
    }

    // -- The rollout boundaries an off-by-one breaks --

    @Test
    void zeroPercentRolloutYieldsControlForEveryTenantNotJustMost() {
        flag("exp.ramping", true, 0);

        for (String tenantId : syntheticTenantIds()) {
            assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.ramping", tenantId),
                "0% rollout leaked tenant into treatment: " + tenantId);
        }
    }

    @Test
    void hundredPercentRolloutYieldsTreatmentForEveryTenantNotJustMost() {
        flag("exp.fully-ramped", true, 100);

        for (String tenantId : syntheticTenantIds()) {
            assertEquals(FeatureFlagService.TREATMENT, service.evaluate("exp.fully-ramped", tenantId),
                "100% rollout stranded tenant in control: " + tenantId);
        }
    }

    // -- Fail-open: nothing on this path may break the chat turn it rides on --

    @Test
    void nullOrBlankTenantIdResolvesToControlWithoutTouchingTheRepository() {
        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.any", null));
        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.any", ""));
        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.any", "   "));
        assertEquals(FeatureFlagService.CONTROL, service.evaluate(null, "tenant_1"));
        assertEquals(FeatureFlagService.CONTROL, service.evaluate("  ", "tenant_1"));

        // Short-circuit means short-circuit: no lookup, no assignment write.
        verify(flagRepository, never()).findByKey(anyString());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void missingFlagResolvesToControl() {
        when(flagRepository.findByKey("exp.deleted")).thenReturn(Optional.empty());

        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.deleted", "tenant_1"));
    }

    @Test
    void disabledFlagResolvesToControlEvenAtFullRolloutPercentage() {
        // enabled=false is the hard master switch - it must win over whatever
        // the percentage says (the two fields are deliberately separate, see
        // FeatureFlag's javadoc).
        flag("exp.paused", false, 100);

        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.paused", "tenant_1"));
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void flagRepositoryThrowingResolvesToControlInsteadOfPropagating() {
        when(flagRepository.findByKey("exp.db-down")).thenThrow(new RuntimeException("connection pool exhausted"));

        // The whole reason for fail-open: this runs inside every chat turn,
        // so a flag-table hiccup propagating here would take down live chat
        // for every tenant at once.
        assertEquals(FeatureFlagService.CONTROL, service.evaluate("exp.db-down", "tenant_1"));
    }

    @Test
    void assignmentRecordingFailureNeverChangesTheReturnedVariant() {
        flag("exp.fully-ramped", true, 100);
        when(assignmentRepository.findByFlagKeyAndTenantId("exp.fully-ramped", "tenant_1"))
            .thenThrow(new RuntimeException("assignment table locked"));

        // The bookkeeping write is observability, not the source of truth -
        // at 100% the answer is TREATMENT regardless of whether the
        // best-effort record could be written.
        assertEquals(FeatureFlagService.TREATMENT, service.evaluate("exp.fully-ramped", "tenant_1"));
    }

    // -- evaluateActiveFlags: the actual once-per-chat-turn entry point --

    @Test
    void evaluateActiveFlagsAppliesTheSameBoundarySemanticsPerFlag() {
        FeatureFlag atFloor = new FeatureFlag("exp.at-floor", "ramping", true, 0, "test");
        FeatureFlag fullyOn = new FeatureFlag("exp.fully-on", "ramped", true, 100, "test");
        when(flagRepository.findByEnabledTrue()).thenReturn(List.of(atFloor, fullyOn));

        Map<String, String> variants = service.evaluateActiveFlags("tenant_1");

        assertEquals(2, variants.size());
        assertEquals(FeatureFlagService.CONTROL, variants.get("exp.at-floor"));
        assertEquals(FeatureFlagService.TREATMENT, variants.get("exp.fully-on"));
    }

    @Test
    void evaluateActiveFlagsSwallowsARepositoryFailureIntoAnEmptyMap() {
        when(flagRepository.findByEnabledTrue()).thenThrow(new RuntimeException("connection pool exhausted"));

        assertEquals(Map.of(), service.evaluateActiveFlags("tenant_1"));
    }

    @Test
    void evaluateActiveFlagsReturnsAnEmptyMapForABlankTenantWithoutQuerying() {
        assertEquals(Map.of(), service.evaluateActiveFlags(null));
        assertEquals(Map.of(), service.evaluateActiveFlags("  "));

        verify(flagRepository, never()).findByEnabledTrue();
    }
}
