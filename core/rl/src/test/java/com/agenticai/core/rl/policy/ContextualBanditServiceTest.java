package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The bandit's selection/update contract plus a statistical sanity check on the
 * Beta sampler underneath Thompson sampling.
 */
@ExtendWith(MockitoExtension.class)
class ContextualBanditServiceTest {

    @Mock
    private AgentPolicyRepository policyRepository;
    @Mock
    private PolicyArmRepository armRepository;

    private ContextualBanditService service() {
        return new ContextualBanditService(policyRepository, armRepository);
    }

    @Test
    void noDecisionWhenNoPolicyExists() {
        UUID agentId = UUID.randomUUID();
        when(policyRepository.findByAgentIdAndDimension(agentId, PolicyDimension.MODEL_ROUTE))
            .thenReturn(Optional.empty());
        assertThat(service().selectArm(agentId, PolicyDimension.MODEL_ROUTE, null)).isEmpty();
    }

    @Test
    void noDecisionWhenPolicyDisabled() {
        UUID agentId = UUID.randomUUID();
        AgentPolicy disabled = new AgentPolicy(agentId, PolicyDimension.REASONING_DEPTH, false, new BigDecimal("0.1"));
        when(policyRepository.findByAgentIdAndDimension(agentId, PolicyDimension.REASONING_DEPTH))
            .thenReturn(Optional.of(disabled));
        assertThat(service().selectArm(agentId, PolicyDimension.REASONING_DEPTH, null)).isEmpty();
    }

    @Test
    void selectsTheOnlyEnabledGlobalArm() {
        UUID agentId = UUID.randomUUID();
        AgentPolicy enabled = new AgentPolicy(agentId, PolicyDimension.REASONING_DEPTH, true, new BigDecimal("0.0"));
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "FAST", "{\"reasoningDepth\":\"FAST\"}", null);
        when(policyRepository.findByAgentIdAndDimension(agentId, PolicyDimension.REASONING_DEPTH))
            .thenReturn(Optional.of(enabled));
        when(armRepository.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(arm));

        Optional<PolicyArm> selected = service().selectArm(agentId, PolicyDimension.REASONING_DEPTH, null);
        assertThat(selected).containsSame(arm);
    }

    @Test
    void updateFoldsRewardIntoPosterior() {
        UUID armId = UUID.randomUUID();
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "FAST", "{}", null);
        when(armRepository.findById(armId)).thenReturn(Optional.of(arm));

        service().update(armId, 1.0);

        // Beta(1,1) + one reward of 1.0 -> Beta(2,1), one pull.
        assertThat(arm.getAlpha()).isEqualTo(2.0);
        assertThat(arm.getBeta()).isEqualTo(1.0);
        assertThat(arm.getPulls()).isEqualTo(1L);
        verify(armRepository).save(arm);
    }

    @Test
    void ucb1AlwaysTriesAnUnpulledArmBeforeFavoringAPulledOne() {
        UUID agentId = UUID.randomUUID();
        AgentPolicy policy = new AgentPolicy(agentId, PolicyDimension.MODEL_ROUTE, true, BigDecimal.ZERO, BanditAlgorithm.UCB1);
        PolicyArm pulled = new PolicyArm(UUID.randomUUID(), "gpt-4o", "{}", null);
        pulled.applyReward(1.0);
        pulled.applyReward(1.0); // avgReward 1.0, pulls 2 - looks great, but never explored below.
        PolicyArm unpulled = new PolicyArm(UUID.randomUUID(), "claude", "{}", null);
        when(policyRepository.findByAgentIdAndDimension(agentId, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        when(armRepository.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(pulled, unpulled));

        Optional<PolicyArm> selected = service().selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);

        assertThat(selected).containsSame(unpulled);
    }

    @Test
    void ucb1FavorsHigherConfidenceBoundOnceEveryArmHasBeenTried() {
        UUID agentId = UUID.randomUUID();
        AgentPolicy policy = new AgentPolicy(agentId, PolicyDimension.MODEL_ROUTE, true, BigDecimal.ZERO, BanditAlgorithm.UCB1);
        // Arm A: 10 pulls, avgReward 0.9 -> score = 0.9 + sqrt(2*ln(12)/10) ~ 1.606
        PolicyArm armA = new PolicyArm(UUID.randomUUID(), "A", "{}", null);
        for (int i = 0; i < 10; i++) {
            armA.applyReward(0.9);
        }
        // Arm B: 2 pulls, avgReward 0.5 -> score = 0.5 + sqrt(2*ln(12)/2) ~ 2.075 (higher - fewer pulls widens its bound)
        PolicyArm armB = new PolicyArm(UUID.randomUUID(), "B", "{}", null);
        for (int i = 0; i < 2; i++) {
            armB.applyReward(0.5);
        }
        when(policyRepository.findByAgentIdAndDimension(agentId, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        when(armRepository.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(armA, armB));

        Optional<PolicyArm> selected = service().selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);

        assertThat(selected).containsSame(armB);
    }

    @Test
    void betaSamplerMeanApproximatesPosteriorMean() {
        // Beta(8,2) has mean 0.8 - the empirical mean of many draws should land near it.
        Random rng = new Random(42);
        int n = 20_000;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += BetaSampler.sampleBeta(8.0, 2.0, rng);
        }
        assertThat(sum / n).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.02));
    }
}
