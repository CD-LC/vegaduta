package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@code selectArms} must decide each dimension exactly as {@code selectArm} would, with one policy query. */
@ExtendWith(MockitoExtension.class)
class ContextualBanditServiceBatchTest {

    @Mock
    private AgentPolicyRepository policyRepository;
    @Mock
    private PolicyArmRepository armRepository;

    @Test
    void batchEqualsSequentialForDeterministicPolicies() {
        UUID agentId = UUID.randomUUID();
        // exploration 0 + arm with a single candidate => deterministic
        AgentPolicy depth = new AgentPolicy(agentId, PolicyDimension.REASONING_DEPTH, true, new BigDecimal("0.0"));
        AgentPolicy disabled = new AgentPolicy(agentId, PolicyDimension.GEN_PARAMS, false, new BigDecimal("0.1"));
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "FAST", "{}", null);
        when(policyRepository.findByAgentId(agentId)).thenReturn(List.of(depth, disabled));
        when(armRepository.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(arm));
        var dims = EnumSet.of(PolicyDimension.MODEL_ROUTE, PolicyDimension.REASONING_DEPTH,
            PolicyDimension.GEN_PARAMS, PolicyDimension.TOOL_SUBSET, PolicyDimension.PROMPT_VARIANT);

        ContextualBanditService service = new ContextualBanditService(policyRepository, armRepository);
        Map<PolicyDimension, Optional<PolicyArm>> batch = service.selectArms(agentId, dims, null);

        assertThat(batch.get(PolicyDimension.REASONING_DEPTH)).containsSame(arm);
        assertThat(batch.get(PolicyDimension.GEN_PARAMS)).isEmpty();      // disabled
        assertThat(batch.get(PolicyDimension.MODEL_ROUTE)).isEmpty();     // no policy row
        assertThat(batch.get(PolicyDimension.TOOL_SUBSET)).isEmpty();
        assertThat(batch.get(PolicyDimension.PROMPT_VARIANT)).isEmpty();
        assertThat(batch).hasSize(5);
        // one policy query for the whole batch, none of the per-dimension lookups, arms only for the enabled policy
        verify(policyRepository, times(1)).findByAgentId(agentId);
        verify(policyRepository, never()).findByAgentIdAndDimension(any(), any());
        verify(armRepository, times(1)).findByPolicyIdAndEnabledTrue(any());
    }

    @Test
    void enabledPolicyWithNoArmsIsEmptyLikeSelectArm() {
        UUID agentId = UUID.randomUUID();
        AgentPolicy enabled = new AgentPolicy(agentId, PolicyDimension.TOOL_SUBSET, true, new BigDecimal("0.0"));
        when(policyRepository.findByAgentId(agentId)).thenReturn(List.of(enabled));
        when(armRepository.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of());

        var batch = new ContextualBanditService(policyRepository, armRepository)
            .selectArms(agentId, EnumSet.of(PolicyDimension.TOOL_SUBSET), null);

        assertThat(batch.get(PolicyDimension.TOOL_SUBSET)).isEmpty();
    }
}
