package com.agenticai.core.providers;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Settings-catalog entries for how a workspace's connected providers are used ({@code providers.*}). The platform
 * value is the {@code app.providers.rejected-cooldown-hours} property {@link ProviderCredentialResolver} and
 * {@link ProviderService} read with {@code @Value}, expressed in seconds like every duration entry.
 */
@Component
public class ProviderSettings implements SettingsContributor {

    public static final String REJECTED_COOLDOWN = "providers.credential.rejectedCooldown";
    static final long DEFAULT_REJECTED_COOLDOWN_HOURS = 6;

    private static final Logger log = LoggerFactory.getLogger(ProviderSettings.class);

    private final Environment env;

    public ProviderSettings(Environment env) {
        this.env = env;
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(REJECTED_COOLDOWN, "providers", SettingType.DURATION)
                        .unit(SettingUnit.S).range(0, 604_800).step(3_600).risk(SettingRisk.MEDIUM)
                        .scopes(SettingScope.TENANT)
                        .platform(() -> Math.max(0,
                                env.getProperty("app.providers.rejected-cooldown-hours", Long.class, DEFAULT_REJECTED_COOLDOWN_HOURS)) * 3_600)
                        .property("app.providers.rejected-cooldown-hours").docsAnchor("behaviour-settings").build());
    }

    /**
     * How long a credential the provider refused stays off-limits to background callers for the calling thread's
     * workspace: its catalog value, or {@code configuredHours} (the property) when there is no resolver or it cannot
     * answer.
     */
    static Duration rejectedCooldown(SettingsResolver settings, long configuredHours) {
        if (settings != null) {
            try {
                return settings.getDuration(REJECTED_COOLDOWN, SettingContext.tenant());
            } catch (RuntimeException e) {
                // A credential lookup never fails because a setting cannot be read: the configured cooldown applies.
                log.warn("Could not read the provider cooldown setting, using the configured cooldown: {}", e.toString());
            }
        }
        return Duration.ofHours(Math.max(0, configuredHours));
    }
}
