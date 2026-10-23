package com.agenticai.core.guardrails;

import com.agenticai.core.settings.SettingsResolver;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands the settings resolver to {@link GuardrailSettings} at startup (and takes it back when the context closes), the
 * way {@link GuardrailFailModeConfiguration} hands over the fail mode. Without a resolver bean the guardrails keep the
 * platform values.
 */
@Component
class GuardrailSettingsConfiguration {

    private final SettingsResolver resolver;

    GuardrailSettingsConfiguration(ObjectProvider<SettingsResolver> resolver) {
        this.resolver = resolver.getIfAvailable();
        GuardrailSettings.use(this.resolver);
    }

    @PreDestroy
    void release() {
        GuardrailSettings.release(resolver);
    }
}
