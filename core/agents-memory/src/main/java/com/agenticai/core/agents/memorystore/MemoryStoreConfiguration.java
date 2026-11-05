package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memorystore.decay.DecayProperties;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
@EnableConfigurationProperties({
    MemoryUnifiedProperties.class,
    MemoryRecallProperties.class,
    DecayProperties.class,
    CompetenceProperties.class
})
class MemoryStoreConfiguration {

    @Bean
    MemoryUnifiedGate memoryUnifiedGate(
            MemoryUnifiedProperties properties, ObjectProvider<MemoryFlagOverride> overrides) {
        return new MemoryUnifiedGate(properties, overrides);
    }

    @Bean
    MemoryRecallComposer memoryRecallComposer(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            ObjectProvider<MemoryRecallSource> sources,
            MemoryRecallProperties relevance) {
        return new MemoryRecallComposer(properties, gate, sources, relevance);
    }

    @Bean
    MemoryUnifiedSettings memoryUnifiedSettings(MemoryUnifiedProperties properties) {
        return new MemoryUnifiedSettings(properties);
    }

    @Bean
    CompetenceSettings competenceSettings(CompetenceProperties properties) {
        return new CompetenceSettings(properties);
    }

    @Bean
    CompetenceGate competenceGate(CompetenceProperties properties, ObjectProvider<SettingsResolver> settings) {
        return new CompetenceGate(properties, settings);
    }

    @Bean
    @ConditionalOnMissingBean(CompetenceStorage.class)
    CompetenceStorage competenceStorage(ObjectProvider<JdbcTemplate> jdbc) {
        return new JdbcCompetenceStorage(jdbc);
    }

    /** The competence writer; a no-op until {@code app.memory.competence.enabled} and the tenant/agent setting allow it. */
    @Bean
    CompetenceTracker competenceTracker(
            CompetenceProperties properties,
            CompetenceGate gate,
            CompetenceStorage storage,
            ObjectProvider<MeterRegistry> meters) {
        return new CompetenceTracker(properties, gate, storage, meters, Clock.systemUTC());
    }

    /** SELF_MODEL recall from the competence counters; empty unless unified recall, the kind and competence are all on. */
    @Bean
    SelfModelRecallSource selfModelRecallSource(
            MemoryUnifiedProperties unified,
            MemoryUnifiedGate gate,
            CompetenceProperties properties,
            CompetenceTracker tracker) {
        return new SelfModelRecallSource(unified, gate, properties, tracker);
    }

    @Bean
    MemoryFlagOverride memoryTenantFlagOverride(ObjectProvider<SettingsResolver> settings) {
        return SettingsMemoryFlagOverride.tenantLevel(settings);
    }

    @Bean
    MemoryFlagOverride memoryAgentFlagOverride(ObjectProvider<SettingsResolver> settings) {
        return SettingsMemoryFlagOverride.agentLevel(settings);
    }

    /**
     * Makes a tombstone mean forgotten: recall leaves out rows whose content hash is tombstoned. On when
     * {@code app.memory.tombstone.enforce=true}; unset it follows {@code app.memory.consolidation.enabled}, the only
     * writer of tombstones, so with every memory flag off no recall statement changes.
     */
    @Bean
    MemoryTombstoneGuard memoryTombstoneGuard(
            @Value("${app.memory.tombstone.enforce:${app.memory.consolidation.enabled:false}}") boolean enforce,
            ObjectProvider<JdbcTemplate> jdbc) {
        return new MemoryTombstoneGuard(enforce, jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryItemStorage.class)
    MemoryItemStorage memoryItemStorage(ObjectProvider<JdbcTemplate> jdbc, MemoryTombstoneGuard tombstones) {
        return new JdbcMemoryItemStorage(jdbc, tombstones);
    }

    /** Read-only view of the unified store for the nightly consolidation (it holds no storage or review reference). */
    @Bean
    MemoryItemConsolidationReader memoryItemConsolidationReader(ObjectProvider<JdbcTemplate> jdbc) {
        return new JdbcMemoryItemConsolidationReader(jdbc);
    }

    /** Subject erasure over the store; never flag-gated (erasure works with the feature off). */
    @Bean
    MemoryItemErasure memoryItemErasure(ObjectProvider<MemoryItemStorage> storage) {
        return new MemoryItemErasure(storage);
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
            ObjectProvider<MeterRegistry> meters,
            MemoryTombstoneGuard tombstones) {
        return new StoreMemoryProposalSink(
                properties, gate, storage, embedder, screens.orderedStream().toList(), meters, Clock.systemUTC(), tombstones);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryProposalSink.class)
    MemoryProposalSink memoryProposalSink() {
        return new DisabledMemoryProposalSink();
    }
}
