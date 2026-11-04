package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the unified-memory pieces. Nothing here touches a database or the chat prompt: the
 * composer is a bean that no turn code calls yet, and every flag defaults off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MemoryUnifiedProperties.class, DecayProperties.class})
class MemoryStoreConfiguration {

    @Bean
    MemoryUnifiedGate memoryUnifiedGate(
            MemoryUnifiedProperties properties, ObjectProvider<MemoryFlagOverride> overrides) {
        return new MemoryUnifiedGate(properties, overrides);
    }

    @Bean
    MemoryRecallComposer memoryRecallComposer(
            MemoryUnifiedProperties properties, MemoryUnifiedGate gate, ObjectProvider<MemoryRecallSource> sources) {
        return new MemoryRecallComposer(properties, gate, sources);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryProposalSink.class)
    MemoryProposalSink memoryProposalSink() {
        return new DisabledMemoryProposalSink();
    }
}
