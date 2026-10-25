package com.agenticai.core.agents.tools.scratchpad;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entry for the agent scratchpad ({@code tools.scratchpad.enabled}). The platform
 * value is read from {@link ScratchpadProperties}, the object {@link AgentScratchpadService} and
 * {@code AgentScratchpadToolResolver} read, so the catalog default and the runtime default can never
 * differ. Tenant or agent overrides turn the four {@code scratchpad_*} tools off per scope.
 */
@Component
public class ScratchpadSettings implements SettingsContributor {

    public static final String ENABLED_KEY = "tools.scratchpad.enabled";

    private final ScratchpadProperties properties;

    public ScratchpadSettings(ScratchpadProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(ENABLED_KEY, "tools", SettingType.BOOL)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::isEnabled)
                        .property("app.tools.scratchpad.enabled")
                        .docsAnchor("behaviour-settings")
                        .build());
    }
}
