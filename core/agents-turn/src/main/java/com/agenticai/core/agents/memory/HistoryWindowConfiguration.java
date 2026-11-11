package com.agenticai.core.agents.memory;

import com.agenticai.core.settings.SettingsResolver;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands the settings resolver to {@link HistoryWindowSettings} at startup (and takes it back when
 * the context closes), so a workspace's {@code chat.history.*} apply to its conversations. Without
 * a resolver bean every workspace keeps the platform window.
 */
@Component
class HistoryWindowConfiguration {

    private final SettingsResolver resolver;

    HistoryWindowConfiguration(ObjectProvider<SettingsResolver> resolver) {
        this.resolver = resolver.getIfAvailable();
        HistoryWindowSettings.use(this.resolver);
    }

    @PreDestroy
    void release() {
        HistoryWindowSettings.release(resolver);
    }
}
