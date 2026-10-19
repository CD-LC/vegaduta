package com.agenticai.core.rl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A Playground turn selects up to five RL dimensions and each selection used to read the tenant's
 * plan. With the plan the caller already resolved for the turn, none of them reads it.
 */
class DefaultRlPolicyPortKnownPlanTest {

    private final ContextualBanditService bandit = mock(ContextualBanditService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final DefaultRlPolicyPort port = new DefaultRlPolicyPort(bandit, limits);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void fiveSelectionsWithAKnownPlanReadThePlanZeroTimes() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        when(bandit.selectArm(any(), any(), any())).thenReturn(Optional.empty());
        UUID agent = UUID.randomUUID();

        for (PolicyDimension d : EnumSet.allOf(PolicyDimension.class)) {
            assertThat(port.selectArm(agent, d, null, TenantPlan.PREMIUM)).isEmpty();
        }

        verifyNoInteractions(limits);
        verify(bandit, times(PolicyDimension.values().length)).selectArm(any(), any(), any());
    }

    @Test
    void fiveSelectionsWithoutAPlanReadItFiveTimes() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(bandit.selectArm(any(), any(), any())).thenReturn(Optional.empty());
        UUID agent = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            port.selectArm(agent, PolicyDimension.MODEL_ROUTE, null);
        }

        verify(limits, times(5)).plan("acme");
    }

    @Test
    void aKnownFreePlanGatesAdaptiveRoutingOffWithoutReadingAnything() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        UUID agent = UUID.randomUUID();

        assertThat(port.selectArm(agent, PolicyDimension.MODEL_ROUTE, null, TenantPlan.FREE))
                .isEmpty();
        assertThat(
                        port.selectArms(
                                agent,
                                EnumSet.of(PolicyDimension.MODEL_ROUTE),
                                null,
                                TenantPlan.FREE))
                .containsEntry(PolicyDimension.MODEL_ROUTE, Optional.empty());

        verifyNoInteractions(limits, bandit);
    }
}
