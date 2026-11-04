package com.agenticai.core.agents.memorystore;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;

/**
 * Settings-catalog entry {@code memory.unified.enabled} (ADR-0028 section 11). The platform value is
 * {@code app.memory.unified.enabled} (default false); a tenant or an agent can only switch unified recall off for
 * itself, because {@link MemoryUnifiedGate} ANDs every level.
 */
public class MemoryUnifiedSettings implements SettingsContributor {

    public static final String ENABLED_KEY = "memory.unified.enabled";

    private final MemoryUnifiedProperties properties;

    public MemoryUnifiedSettings(MemoryUnifiedProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(ENABLED_KEY, "memory", SettingType.BOOL)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(properties::isEnabled)
                        .property("app.memory.unified.enabled")
                        .docsAnchor("behaviour-settings")
                        .build());
    }
}
