package com.agenticai.core.rl.policy;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for how the learning policies explore ({@code rl.policy.*}). The platform values are
 * read from {@link RlPolicyProperties}, the object {@link ContextualBanditService} and {@link PolicyStore} read, so
 * the catalog default and the runtime default can never differ. A policy's own exploration rate stays on its row
 * (changed per agent and dimension in the console); these are the workspace's and the agent's starting points.
 */
@Component
public class RlSettings implements SettingsContributor {

    public static final String UCB1_EXPLORATION_CONSTANT = "rl.policy.ucb1ExplorationConstant";
    public static final String DEFAULT_EXPLORATION_RATE = "rl.policy.defaultExplorationRate";

    private final RlPolicyProperties properties;

    public RlSettings(RlPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(UCB1_EXPLORATION_CONSTANT, "learning", SettingType.DECIMAL)
                        .range(0, 10).step(0.1).risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::getUcb1ExplorationConstant)
                        .property("rl.policy.ucb1-exploration-constant").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(DEFAULT_EXPLORATION_RATE, "learning", SettingType.DECIMAL)
                        .range(0, 1).step(0.01).risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT)
                        .platform(properties::getDefaultExplorationRate)
                        .property("rl.policy.default-exploration-rate").docsAnchor("behaviour-settings").build());
    }
}
