package com.agenticai.core.rl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultRlPolicyPortBatchTest {

    @Mock
    private ContextualBanditService banditService;
    @Mock
    private TenantLimits tenantLimits;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void freeTenantGetsEmptyForEveryDimensionWithoutTouchingTheBandit() {
        TenantContext.set(TenantContext.of("free", "u", Set.of()));
        when(tenantLimits.plan("free")).thenReturn(TenantPlan.FREE);
        var dims = EnumSet.of(PolicyDimension.MODEL_ROUTE, PolicyDimension.TOOL_SUBSET);

        var out = new DefaultRlPolicyPort(banditService, tenantLimits).selectArms(UUID.randomUUID(), dims, null);

        assertThat(out).containsOnlyKeys(dims).allSatisfy((d, a) -> assertThat(a).isEmpty());
        verifyNoInteractions(banditService);
    }

    @Test
    void paidTenantDoesOnePlanReadAndOneBatchedBanditCall() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        UUID agentId = UUID.randomUUID();
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "FAST", "{\"reasoningDepth\":\"FAST\"}", null);
        var dims = EnumSet.of(PolicyDimension.REASONING_DEPTH, PolicyDimension.TOOL_SUBSET);
        when(banditService.selectArms(agentId, dims, null)).thenReturn(Map.of(
            PolicyDimension.REASONING_DEPTH, Optional.of(arm), PolicyDimension.TOOL_SUBSET, Optional.empty()));

        var out = new DefaultRlPolicyPort(banditService, tenantLimits).selectArms(agentId, dims, null);

        assertThat(out.get(PolicyDimension.REASONING_DEPTH)).get().extracting(RlPolicyPort.ArmSelection::armKey)
            .isEqualTo("FAST");
        assertThat(out.get(PolicyDimension.TOOL_SUBSET)).isEmpty();
        verify(tenantLimits, times(1)).plan("acme");
        verify(banditService, times(1)).selectArms(agentId, dims, null);
    }

    @Test
    void defaultInterfaceMethodFallsBackToSequentialSelectArm() {
        RlPolicyPort port = org.mockito.Mockito.mock(RlPolicyPort.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        UUID agentId = UUID.randomUUID();
        org.mockito.Mockito.doReturn(Optional.empty()).when(port).selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);
        org.mockito.Mockito.doReturn(Optional.empty()).when(port).selectArm(agentId, PolicyDimension.TOOL_SUBSET, null);

        var out = port.selectArms(agentId, EnumSet.of(PolicyDimension.MODEL_ROUTE, PolicyDimension.TOOL_SUBSET), null);

        assertThat(out).hasSize(2);
        verify(port).selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);
        verify(port).selectArm(agentId, PolicyDimension.TOOL_SUBSET, null);
    }
}
