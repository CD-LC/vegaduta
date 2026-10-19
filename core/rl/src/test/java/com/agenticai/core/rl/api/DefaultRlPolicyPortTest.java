package com.agenticai.core.rl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 2026-07-27 billing audit gate: a FREE tenant must always get fixed/default
 * routing - {@link DefaultRlPolicyPort#selectArm} must short-circuit to empty
 * for them without ever consulting {@link ContextualBanditService}, even
 * when an admin has enabled an {@code AgentPolicy} for the agent. PREMIUM/
 * UNLIMITED tenants, and callers with no tenant in context at all (internal/
 * offline callers), keep the pre-gate behavior of delegating straight
 * through to the bandit.
 */
@ExtendWith(MockitoExtension.class)
class DefaultRlPolicyPortTest {

    @Mock
    private ContextualBanditService banditService;
    @Mock
    private TenantLimits tenantLimits;

    private DefaultRlPolicyPort port() {
        return new DefaultRlPolicyPort(banditService, tenantLimits);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void freeTenantNeverConsultsTheBanditRegardlessOfDimension() {
        String tenantId = "free-tenant";
        TenantContext.set(TenantContext.of(tenantId, "user-1", Set.of()));
        when(tenantLimits.plan(tenantId)).thenReturn(TenantPlan.FREE);

        Optional<RlPolicyPort.ArmSelection> selected =
            port().selectArm(UUID.randomUUID(), PolicyDimension.MODEL_ROUTE, null);

        assertThat(selected).isEmpty();
        verifyNoInteractions(banditService);
    }

    @Test
    void premiumTenantDelegatesToTheBandit() {
        String tenantId = "premium-tenant";
        TenantContext.set(TenantContext.of(tenantId, "user-1", Set.of()));
        when(tenantLimits.plan(tenantId)).thenReturn(TenantPlan.PREMIUM);
        UUID agentId = UUID.randomUUID();
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "gpt-4o", "{\"model\":\"gpt-4o\"}", null);
        when(banditService.selectArm(agentId, PolicyDimension.MODEL_ROUTE, null)).thenReturn(Optional.of(arm));

        Optional<RlPolicyPort.ArmSelection> selected = port().selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);

        assertThat(selected).isPresent();
        assertThat(selected.get().armKey()).isEqualTo("gpt-4o");
    }

    @Test
    void unlimitedTenantDelegatesToTheBandit() {
        String tenantId = "unlimited-tenant";
        TenantContext.set(TenantContext.of(tenantId, "user-1", Set.of()));
        when(tenantLimits.plan(tenantId)).thenReturn(TenantPlan.UNLIMITED);
        UUID agentId = UUID.randomUUID();
        when(banditService.selectArm(agentId, PolicyDimension.REASONING_DEPTH, null)).thenReturn(Optional.empty());

        Optional<RlPolicyPort.ArmSelection> selected = port().selectArm(agentId, PolicyDimension.REASONING_DEPTH, null);

        assertThat(selected).isEmpty();
    }

    @Test
    void noTenantInContextIsTreatedPermissivelyAndDelegates() {
        // No TenantContext.set(...) - mirrors an internal/offline caller with
        // no HTTP request in flight (same permissive default AgentService uses).
        UUID agentId = UUID.randomUUID();
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "claude", "{\"model\":\"claude\"}", null);
        when(banditService.selectArm(agentId, PolicyDimension.MODEL_ROUTE, null)).thenReturn(Optional.of(arm));

        Optional<RlPolicyPort.ArmSelection> selected = port().selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);

        assertThat(selected).isPresent();
        assertThat(selected.get().armKey()).isEqualTo("claude");
    }

    @Test
    void rewardAlwaysDelegatesRegardlessOfPlan() {
        UUID armId = UUID.randomUUID();

        port().reward(armId, 0.75);

        org.mockito.Mockito.verify(banditService).update(armId, 0.75);
    }
}
