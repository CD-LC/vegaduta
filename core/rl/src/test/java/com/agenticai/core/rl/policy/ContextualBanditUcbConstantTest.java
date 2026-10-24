package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.BanditAlgorithm;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UCB1's exploration constant ({@code rl.policy.ucb1-exploration-constant}) decides exploit versus explore. */
class ContextualBanditUcbConstantTest {

    private final AgentPolicyRepository policies = mock(AgentPolicyRepository.class);
    private final PolicyArmRepository arms = mock(PolicyArmRepository.class);
    private final PolicyArm proven = new PolicyArm(UUID.randomUUID(), "proven", "{}", null);
    private final PolicyArm lessTried = new PolicyArm(UUID.randomUUID(), "less-tried", "{}", null);
    private final UUID agentId = UUID.randomUUID();

    private ContextualBanditService service(double constant) {
        for (int i = 0; i < 10; i++) {
            proven.applyReward(0.9);
        }
        for (int i = 0; i < 2; i++) {
            lessTried.applyReward(0.5);
        }
        AgentPolicy policy = new AgentPolicy(agentId, PolicyDimension.MODEL_ROUTE, true, BigDecimal.ZERO, BanditAlgorithm.UCB1);
        when(policies.findByAgentIdAndDimension(agentId, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        when(arms.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(proven, lessTried));
        ContextualBanditService service = new ContextualBanditService(policies, arms);
        RlPolicyProperties properties = new RlPolicyProperties();
        properties.setUcb1ExplorationConstant(constant);
        service.setProperties(properties);
        return service;
    }

    @Test
    void theTextbookConstantStillExploresTheLessTriedArm() {
        assertThat(service(2.0).selectArm(agentId, PolicyDimension.MODEL_ROUTE, null)).containsSame(lessTried);
    }

    @Test
    void aZeroConstantExploitsTheBestAverage() {
        assertThat(service(0.0).selectArm(agentId, PolicyDimension.MODEL_ROUTE, null)).containsSame(proven);
    }
}
