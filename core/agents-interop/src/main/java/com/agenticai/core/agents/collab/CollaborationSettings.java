package com.agenticai.core.agents.collab;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for delegation and sub-task fan-out ({@code delegation.*}); the platform values are read
 * from {@link CollaborationProperties}, the object the collaboration tools use. The tools read the resolved values through
 * {@link ResolvedCollaborationLimits}, for the calling agent and its workspace.
 */
@Component
public class CollaborationSettings implements SettingsContributor {

    public static final String MAX_BRANCHES = "delegation.maxBranches";
    public static final String BRANCH_TIMEOUT_SECONDS = "delegation.branchTimeoutSeconds";

    private final CollaborationProperties properties;

    public CollaborationSettings(CollaborationProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(MAX_BRANCHES, "delegation", SettingType.INT)
                        .unit(SettingUnit.COUNT).range(1, 32).risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::getMaxBranches)
                        .property("app.agents.collaboration.max-branches").docsAnchor("behaviour-settings").build(),
                SettingDefinition.builder(BRANCH_TIMEOUT_SECONDS, "delegation", SettingType.DURATION)
                        .unit(SettingUnit.S).range(10, 3_600).risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::getBranchTimeoutSeconds)
                        .property("app.agents.collaboration.branch-timeout-seconds").docsAnchor("behaviour-settings").build());
    }
}
