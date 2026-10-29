package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantPlan;
import org.junit.jupiter.api.Test;

/**
 * Entity coverage for {@link PlanLimits}: the package-private all-args constructor every caller of
 * {@link PlanLimitsServiceTest}'s {@code row(...)} helper already builds, plus all fifteen getters
 * including the V180 live-vision flag and the six V470 billing entitlements.
 */
class PlanLimitsTest {

    private static PlanLimits row(TenantPlan plan) {
        return new PlanLimits(
                plan,
                2 /* maxUsers */,
                5 /* maxAgents */,
                3 /* maxWorkflows */,
                1 /* maxKbCollections */,
                false /* byokAllowed */,
                false /* whatsappAllowed */,
                false /* whiteLabel */,
                true /* liveVisionAllowed */,
                true /* externalChannelsAllowed */,
                true /* developerApiAllowed */,
                true /* ssoAllowed */,
                false /* scimAllowed */,
                false /* workflowExportAllowed */,
                true /* automationAllowed */);
    }

    @Test
    void constructorAndGettersRoundTripEveryColumn() {
        PlanLimits limits = row(TenantPlan.PREMIUM);

        assertEquals(TenantPlan.PREMIUM, limits.getPlan());
        assertEquals(2, limits.getMaxUsers());
        assertEquals(5, limits.getMaxAgents());
        assertEquals(3, limits.getMaxWorkflows());
        assertEquals(1, limits.getMaxKbCollections());
        assertFalse(limits.isByokAllowed());
        assertFalse(limits.isWhatsappAllowed());
        assertFalse(limits.isWhiteLabel());
        assertTrue(limits.isLiveVisionAllowed());
        assertTrue(limits.isExternalChannelsAllowed());
        assertTrue(limits.isDeveloperApiAllowed());
        assertTrue(limits.isSsoAllowed());
        assertFalse(limits.isScimAllowed());
        assertFalse(limits.isWorkflowExportAllowed());
        assertTrue(limits.isAutomationAllowed());
    }

    @Test
    void unlimitedSentinelMinusOneSurvivesVerbatim() {
        PlanLimits limits =
                new PlanLimits(
                        TenantPlan.UNLIMITED,
                        -1,
                        -1,
                        -1,
                        -1,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true);

        // The -1 -> Integer.MAX_VALUE / Optional.empty() translation is
        // TenantLimitsServiceImpl's job; the entity itself stores the raw
        // sentinel, so the getters must hand it back verbatim.
        assertEquals(-1, limits.getMaxUsers());
        assertEquals(-1, limits.getMaxAgents());
        assertEquals(-1, limits.getMaxWorkflows());
        assertEquals(-1, limits.getMaxKbCollections());
    }
}
