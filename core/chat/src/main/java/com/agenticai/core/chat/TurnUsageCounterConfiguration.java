package com.agenticai.core.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates and installs the {@link TurnUsageCounter} when {@code app.metrics.turn-usage.enabled=true} (default off). */
@Configuration
class TurnUsageCounterConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.metrics.turn-usage.enabled", havingValue = "true")
    TurnUsageCounter turnUsageCounter() {
        TurnUsageCounter counter = new TurnUsageCounter();
        TurnUsageCounter.install(counter);
        return counter;
    }
}
