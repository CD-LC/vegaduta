package com.agenticai.core.agents.memorystore;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;

/**
 * Settings-catalog entry {@code memory.competence.enabled} (ADR-0028 WP9). The platform value is
 * {@code app.memory.competence.enabled} (default false); a tenant or an agent can only switch it
 * off for itself, because {@link CompetenceGate} ANDs the levels.
 */
public class CompetenceSettings implements SettingsContributor {

    public static final String ENABLED_KEY = "memory.competence.enabled";

    private final CompetenceProperties properties;

    public CompetenceSettings(CompetenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(ENABLED_KEY, "memory", SettingType.BOOL)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::isEnabled)
                        .property("app.memory.competence.enabled")
                        .docsAnchor("behaviour-settings")
                        .build());
    }
}
