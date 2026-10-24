package com.agenticai.core.agents.skills;

import com.agenticai.core.settings.SettingsResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SkillPromptProperties} and publishes the bound instance to the static {@link
 * SkillPromptBlock}: there is no application-wide {@code @ConfigurationPropertiesScan}, so each module
 * registers the properties class it owns. Also installs the tenant overlay of {@link SkillPromptSettings}, which asks
 * the settings catalog lazily (the catalog reads the bound properties for its platform values).
 */
@Configuration
@EnableConfigurationProperties(SkillPromptProperties.class)
class SkillPromptConfiguration {

    SkillPromptConfiguration(SkillPromptProperties properties, ObjectProvider<SettingsResolver> settings) {
        SkillPromptProperties.activate(properties);
        SkillPromptProperties.useTenantOverlay(bound -> SkillPromptSettings.effective(bound, settings.getIfAvailable()));
    }
}
