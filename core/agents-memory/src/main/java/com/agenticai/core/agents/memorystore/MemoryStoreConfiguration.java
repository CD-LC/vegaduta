package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import com.agenticai.core.settings.SettingsResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the unified-memory pieces. Nothing here touches a database; the chat prompt path calls the
 * composer only while the gate is on, and every flag defaults off.
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
    MemoryUnifiedSettings memoryUnifiedSettings(MemoryUnifiedProperties properties) {
        return new MemoryUnifiedSettings(properties);
    }

    @Bean
    MemoryFlagOverride memoryTenantFlagOverride(ObjectProvider<SettingsResolver> settings) {
        return SettingsMemoryFlagOverride.tenantLevel(settings);
    }

    @Bean
    MemoryFlagOverride memoryAgentFlagOverride(ObjectProvider<SettingsResolver> settings) {
        return SettingsMemoryFlagOverride.agentLevel(settings);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryProposalSink.class)
    MemoryProposalSink memoryProposalSink() {
        return new DisabledMemoryProposalSink();
    }
}
