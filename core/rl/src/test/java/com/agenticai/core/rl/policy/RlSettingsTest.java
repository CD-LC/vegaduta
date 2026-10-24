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
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The workspace's and the agent's {@code rl.policy.*} settings reach the real bandit and the real policy store:
 * UCB1's exploration constant decides exploit versus explore per agent, and a new policy starts at the workspace's
 * exploration rate. With nothing stored both behave exactly as the properties say.
 */
class RlSettingsTest {

    private final AgentPolicyRepository policies = mock(AgentPolicyRepository.class);
    private final PolicyArmRepository arms = mock(PolicyArmRepository.class);
    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final RlPolicyProperties properties = new RlPolicyProperties();
    private final DefaultSettingsResolver resolver = new DefaultSettingsResolver(
            new SettingsRegistry(List.of(new RlSettings(properties))), store, List.of(), new SettingsProperties(), null);
    private final PolicyArm proven = new PolicyArm(UUID.randomUUID(), "proven", "{}", null);
    private final PolicyArm lessTried = new PolicyArm(UUID.randomUUID(), "less-tried", "{}", null);
    private final UUID agent = UUID.randomUUID();
    private final UUID otherAgent = UUID.randomUUID();
    private final List<AgentPolicy> savedPolicies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("acme", "alice", Set.of("ROLE_tenant-admin")));
        for (int i = 0; i < 10; i++) {
            proven.applyReward(0.9);
        }
        for (int i = 0; i < 2; i++) {
            lessTried.applyReward(0.5);
        }
        for (UUID a : List.of(agent, otherAgent)) {
            when(policies.findByAgentIdAndDimension(a, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(
                    new AgentPolicy(a, PolicyDimension.MODEL_ROUTE, true, BigDecimal.ZERO, BanditAlgorithm.UCB1)));
        }
        when(arms.findByPolicyIdAndEnabledTrue(any())).thenReturn(List.of(proven, lessTried));
        when(policies.save(any(AgentPolicy.class))).thenAnswer(inv -> {
            savedPolicies.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(arms.findFirstByPolicyIdAndArmKey(any(), any())).thenReturn(Optional.empty());
        when(arms.save(any(PolicyArm.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ContextualBanditService bandit() {
        ContextualBanditService service = new ContextualBanditService(policies, arms);
        service.setProperties(properties);
        service.setSettings(resolver);
        return service;
    }

    private void override(SettingScope scope, String scopeId, String key, double value) {
        store.saveCurrent(new SettingOverride(scope, scopeId, key, value, 1, "alice", Instant.now()));
    }

    @Test
    void theCatalogDefaultsAreTheValuesThePropertiesCarry() {
        assertThat(resolver.getDecimal(RlSettings.UCB1_EXPLORATION_CONSTANT, SettingContext.tenant())).isEqualTo(2.0);
        assertThat(resolver.getDecimal(RlSettings.DEFAULT_EXPLORATION_RATE, SettingContext.tenant())).isEqualTo(0.1);
    }

    @Test
    void withNothingOverriddenTheTextbookConstantStillExploresTheLessTriedArm() {
        assertThat(bandit().selectArm(agent, PolicyDimension.MODEL_ROUTE, null)).containsSame(lessTried);
    }

    @Test
    void aWorkspaceConstantOfZeroMakesTheBanditExploitTheBestAverage() {
        override(SettingScope.TENANT, "", RlSettings.UCB1_EXPLORATION_CONSTANT, 0.0);
        assertThat(bandit().selectArm(agent, PolicyDimension.MODEL_ROUTE, null)).containsSame(proven);
    }

    @Test
    void anAgentOverrideAppliesToThatAgentOnly() {
        override(SettingScope.AGENT, agent.toString(), RlSettings.UCB1_EXPLORATION_CONSTANT, 0.0);
        ContextualBanditService bandit = bandit();
        assertThat(bandit.selectArm(agent, PolicyDimension.MODEL_ROUTE, null)).containsSame(proven);
        assertThat(bandit.selectArm(otherAgent, PolicyDimension.MODEL_ROUTE, null)).containsSame(lessTried);
    }

    @Test
    void anotherWorkspacesOverrideNeverReachesThisOne() {
        override(SettingScope.TENANT, "", RlSettings.UCB1_EXPLORATION_CONSTANT, 0.0);
        TenantContext.set(TenantContext.of("globex", "bob", Set.of()));
        assertThat(bandit().selectArm(agent, PolicyDimension.MODEL_ROUTE, null)).containsSame(lessTried);
    }

    @Test
    void theBatchedSelectionFollowsTheSameOverride() {
        override(SettingScope.AGENT, agent.toString(), RlSettings.UCB1_EXPLORATION_CONSTANT, 0.0);
        when(policies.findByAgentId(agent)).thenReturn(List.of(
                new AgentPolicy(agent, PolicyDimension.MODEL_ROUTE, true, BigDecimal.ZERO, BanditAlgorithm.UCB1)));
        var picked = bandit().selectArms(agent, Set.of(PolicyDimension.MODEL_ROUTE), null);
        assertThat(picked.get(PolicyDimension.MODEL_ROUTE)).containsSame(proven);
    }

    @Test
    void withoutAResolverTheBanditKeepsUsingTheProperties() {
        override(SettingScope.TENANT, "", RlSettings.UCB1_EXPLORATION_CONSTANT, 0.0);
        ContextualBanditService bare = new ContextualBanditService(policies, arms);
        bare.setProperties(properties);
        assertThat(bare.selectArm(agent, PolicyDimension.MODEL_ROUTE, null)).containsSame(lessTried);
    }

    @Test
    void aNewPolicyStartsAtTheWorkspacesExplorationRate() {
        override(SettingScope.TENANT, "", RlSettings.DEFAULT_EXPLORATION_RATE, 0.25);
        PolicyStore policyStore = new PolicyStore(policies, arms, properties);
        policyStore.setSettings(resolver);
        when(policies.findByAgentIdAndDimension(any(), any())).thenReturn(Optional.empty());

        policyStore.ensurePolicy(UUID.randomUUID(), PolicyDimension.GEN_PARAMS);

        assertThat(savedPolicies).hasSize(1);
        assertThat(savedPolicies.get(0).getExplorationRate()).isEqualByComparingTo("0.25");
    }

    @Test
    void aNewPolicyWithNothingOverriddenStartsAtTheConfiguredRate() {
        PolicyStore policyStore = new PolicyStore(policies, arms, properties);
        policyStore.setSettings(resolver);
        when(policies.findByAgentIdAndDimension(any(), any())).thenReturn(Optional.empty());

        policyStore.ensurePolicy(UUID.randomUUID(), PolicyDimension.GEN_PARAMS);

        assertThat(savedPolicies.get(0).getExplorationRate()).isEqualByComparingTo("0.100");
    }

    @Test
    void aNewPolicyWithoutAResolverStartsAtTheConfiguredRate() {
        override(SettingScope.TENANT, "", RlSettings.DEFAULT_EXPLORATION_RATE, 0.25);
        PolicyStore policyStore = new PolicyStore(policies, arms, properties);
        when(policies.findByAgentIdAndDimension(any(), any())).thenReturn(Optional.empty());

        policyStore.ensurePolicy(UUID.randomUUID(), PolicyDimension.GEN_PARAMS);

        assertThat(savedPolicies.get(0).getExplorationRate()).isEqualByComparingTo("0.100");
    }
}
