package com.agenticai.core.guardrails.leak;

import com.agenticai.core.audit.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PromptLeakProperties} and the {@link PromptLeakGuard}, and makes it the
 * process-wide {@link PromptLeakGuard#shared()} the turn reads (the turn executor is built with
 * {@code new}, not by Spring, so it cannot be injected).
 */
@Configuration
@EnableConfigurationProperties(PromptLeakProperties.class)
class PromptLeakConfiguration {

    @Bean
    PromptLeakGuard promptLeakGuard(
            PromptLeakProperties props,
            ObjectProvider<AuditLogService> audit,
            ObjectProvider<MeterRegistry> meters) {
        PromptLeakGuard guard =
                new PromptLeakGuard(
                        props,
                        new PromptLeakReporter(meters.getIfAvailable(), audit.getIfAvailable()));
        PromptLeakGuard.install(guard);
        return guard;
    }
}
