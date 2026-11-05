package com.agenticai.core.agents.memorystore;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Platform AND (tenant, agent via the settings resolver): competence counting is on for an agent
 * only if every level says so. No resolver bean means the platform flag decides; an unreadable
 * level counts as off (fail closed).
 */
public class CompetenceGate {

    private static final Logger log = LoggerFactory.getLogger(CompetenceGate.class);

    private final CompetenceProperties properties;
    private final ObjectProvider<SettingsResolver> settings;

    public CompetenceGate(
            CompetenceProperties properties, ObjectProvider<SettingsResolver> settings) {
        this.properties = properties;
        this.settings = settings;
    }

    public boolean enabled(UUID agentId) {
        if (agentId == null || !properties.isEnabled()) {
            return false;
        }
        try {
            SettingsResolver resolver = settings.getIfAvailable();
            return resolver == null
                    || (resolver.getBool(CompetenceSettings.ENABLED_KEY, SettingContext.tenant())
                            && resolver.getBool(
                                    CompetenceSettings.ENABLED_KEY,
                                    SettingContext.forAgent(agentId)));
        } catch (RuntimeException e) {
            log.warn("Competence flag level unreadable; treating as off", e);
            return false;
        }
    }
}
