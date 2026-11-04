package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the unified-memory pieces. Nothing here is called from the chat prompt: the composer is a
 * bean that no turn code calls yet, every flag defaults off, and every database-touching bean takes
 * its {@code JdbcTemplate} and embedder as an {@link ObjectProvider}, so the api, jobs and worker
 * shapes start whether or not a database or an embedder is present (the call then fails closed).
 *
 * <p>The proposal sink is the disabled placeholder until {@code app.memory.unified.enabled=true};
 * then it is the store-backed sink. It still checks the per-agent gate on every call.
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
    @ConditionalOnMissingBean(MemoryItemStorage.class)
    MemoryItemStorage memoryItemStorage(ObjectProvider<JdbcTemplate> jdbc) {
        return new JdbcMemoryItemStorage(jdbc);
    }

    @Bean
    MemoryContentScreen basicMemoryContentScreen(MemoryUnifiedProperties properties) {
        return new BasicMemoryContentScreen(properties);
    }

    @Bean
    MemoryItemStore memoryItemStore(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder) {
        return new MemoryItemStore(properties, gate, storage, embedder, Clock.systemUTC());
    }

    @Bean
    MemoryItemReviewService memoryItemReviewService(
            MemoryUnifiedProperties properties,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder,
            ObjectProvider<MemoryContentScreen> screens) {
        return new MemoryItemReviewService(
                properties, storage, embedder, screens.orderedStream().toList(), Clock.systemUTC());
    }

    /** The store-backed sink replaces the disabled placeholder when the platform flag is on. */
    @Bean
    @ConditionalOnProperty(prefix = "app.memory.unified", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(MemoryProposalSink.class)
    MemoryProposalSink memoryItemProposalSink(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder,
            ObjectProvider<MemoryContentScreen> screens,
            ObjectProvider<MeterRegistry> meters) {
        return new StoreMemoryProposalSink(
                properties, gate, storage, embedder, screens.orderedStream().toList(), meters, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(MemoryProposalSink.class)
    MemoryProposalSink memoryProposalSink() {
        return new DisabledMemoryProposalSink();
    }
}
