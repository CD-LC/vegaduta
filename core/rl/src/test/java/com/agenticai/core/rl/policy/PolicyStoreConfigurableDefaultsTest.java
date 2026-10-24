package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code rl.policy.*}: the starting exploration rate, the GEN_PARAMS presets and the REASONING_DEPTH arms a new
 * policy is seeded with, through the real {@link PolicyStore}. Defaults are what the store always seeded.
 */
class PolicyStoreConfigurableDefaultsTest {

    private final AgentPolicyRepository policies = mock(AgentPolicyRepository.class);
    private final PolicyArmRepository arms = mock(PolicyArmRepository.class);
    private final List<PolicyArm> savedArms = new ArrayList<>();
    private final List<AgentPolicy> savedPolicies = new ArrayList<>();

    private PolicyStore store(RlPolicyProperties properties) {
        when(policies.findByAgentIdAndDimension(any(), any())).thenReturn(Optional.empty());
        when(policies.save(any(AgentPolicy.class))).thenAnswer(inv -> {
            savedPolicies.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(arms.findFirstByPolicyIdAndArmKey(any(), any())).thenReturn(Optional.empty());
        when(arms.save(any(PolicyArm.class))).thenAnswer(inv -> {
            savedArms.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return new PolicyStore(policies, arms, properties);
    }

    private static RlPolicyProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("rl.policy", Bindable.of(RlPolicyProperties.class))
            .orElseGet(RlPolicyProperties::new);
    }

    @Test
    void theDefaultsSeedTheSameArmsAndRateAsBefore() {
        PolicyStore store = store(new RlPolicyProperties());

        store.ensurePolicy(UUID.randomUUID(), PolicyDimension.GEN_PARAMS);
        store.ensurePolicy(UUID.randomUUID(), PolicyDimension.REASONING_DEPTH);

        assertThat(savedPolicies).allSatisfy(p -> assertThat(p.getExplorationRate()).isEqualByComparingTo("0.100"));
        assertThat(savedArms).extracting(PolicyArm::getArmKey)
            .containsExactly("PRECISE", "BALANCED", "CREATIVE", "FAST", "CAREFUL", "RIGOROUS");
        assertThat(savedArms.get(0).getArmConfigJson()).isEqualTo("{\"temperature\":0.2,\"topP\":0.8}");
        assertThat(savedArms.get(1).getArmConfigJson()).isEqualTo("{\"temperature\":0.7,\"topP\":0.9}");
        assertThat(savedArms.get(2).getArmConfigJson()).isEqualTo("{\"temperature\":1.0,\"topP\":0.95}");
        assertThat(savedArms.get(3).getArmConfigJson()).isEqualTo("{\"reasoningDepth\":\"FAST\"}");
    }

    @Test
    void aConfiguredStartingExplorationRateIsUsedAndClampedToAProbability() {
        PolicyStore store = store(bind(Map.of("rl.policy.default-exploration-rate", "0.25")));
        store.ensurePolicy(UUID.randomUUID(), PolicyDimension.TOOL_SUBSET);
        assertThat(savedPolicies.get(0).getExplorationRate()).isEqualByComparingTo(new BigDecimal("0.25"));

        assertThat(bind(Map.of("rl.policy.default-exploration-rate", "7")).getDefaultExplorationRate())
            .isEqualByComparingTo("1");
    }

    @Test
    void aConfiguredPresetSetReplacesTheBuiltInOne() {
        PolicyStore store = store(bind(Map.of(
            "rl.policy.gen-param-presets.COLD.temperature", "0.0",
            "rl.policy.gen-param-presets.COLD.top-p", "0.5",
            "rl.policy.gen-param-presets.HOT.temperature", "1.4",
            "rl.policy.gen-param-presets.HOT.top-p", "1.0")));

        store.ensurePolicy(UUID.randomUUID(), PolicyDimension.GEN_PARAMS);

        assertThat(savedArms).extracting(PolicyArm::getArmKey).containsExactlyInAnyOrder("COLD", "HOT");
        assertThat(savedArms).filteredOn(a -> a.getArmKey().equals("HOT"))
            .extracting(PolicyArm::getArmConfigJson).containsExactly("{\"temperature\":1.4,\"topP\":1.0}");
    }

    @Test
    void theReasoningDepthArmsAreConfigurable() {
        PolicyStore store = store(bind(Map.of("rl.policy.reasoning-depth-arms", "FAST,PLANNED")));

        store.ensurePolicy(UUID.randomUUID(), PolicyDimension.REASONING_DEPTH);

        assertThat(savedArms).extracting(PolicyArm::getArmKey).containsExactly("FAST", "PLANNED");
        assertThat(savedArms.get(1).getArmConfigJson()).isEqualTo("{\"reasoningDepth\":\"PLANNED\"}");
    }
}
