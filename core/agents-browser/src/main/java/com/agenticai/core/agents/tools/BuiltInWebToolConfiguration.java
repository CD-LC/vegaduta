package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.web.DeepResearchConfig;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.providers.PlatformToolCredentialService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link WebToolPort} implementation and its deep_research settings. These beans used to
 * be declared in {@code ToolResolverConfiguration} and applied to {@code
 * AgentIntegrationToolResolver}; they moved here with {@code tools.web} (ADR-0023) under the same
 * bean names and configuration keys.
 */
@Configuration(proxyBeanMethods = false)
class BuiltInWebToolConfiguration {

    /** deep_research tuning that is not part of {@code DeepResearchConfig}'s short constructor. */
    record ResearchTuning(int totalDeadlineSeconds, int minQuoteChars, boolean autoOfferOnIntent) {}

    @Bean
    DeepResearchConfig deepResearchConfig(
            @Value("${app.research.deep.enabled:false}") boolean enabled,
            @Value("${app.research.deep.max-workers:6}") int maxWorkers,
            @Value("${app.research.deep.max-sources:8}") int maxSources,
            @Value("${app.research.deep.fetch-chars:12000}") int fetchChars,
            @Value("${app.research.deep.max-fetches-per-run:12}") int maxFetchesPerRun,
            @Value("${app.research.deep.passages-per-source:2}") int passagesPerSource,
            @Value("${app.research.deep.results-per-search:6}") int resultsPerSearch,
            @Value("${app.research.deep.worker-timeout-seconds:45}") int workerTimeoutSeconds,
            @Value("${app.research.deep.max-searches-per-run:12}") int maxSearchesPerRun) {
        return new DeepResearchConfig(
                enabled,
                maxWorkers,
                maxSources,
                fetchChars,
                maxFetchesPerRun,
                passagesPerSource,
                resultsPerSearch,
                workerTimeoutSeconds,
                maxSearchesPerRun);
    }

    @Bean
    ResearchTuning researchTuning(
            @Value("${app.research.deep.total-deadline-seconds:120}") int totalDeadlineSeconds,
            @Value("${app.research.deep.min-quote-chars:20}") int minQuoteChars,
            @Value("${app.research.deep.auto-offer-on-intent:true}") boolean autoOfferOnIntent) {
        return new ResearchTuning(totalDeadlineSeconds, minQuoteChars, autoOfferOnIntent);
    }

    @Bean
    BuiltInWebTools builtInWebTools(
            AgentService agentService,
            ObjectProvider<PlatformToolCredentialService> platformToolCredentialService,
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            DeepResearchConfig deepResearchConfig,
            ResearchTuning researchTuning,
            ObjectProvider<ResearchToolWiring> researchToolWiring) {
        BuiltInWebTools tools =
                new BuiltInWebTools(
                        agentService,
                        platformToolCredentialService,
                        httpRequestExecutor,
                        deepResearchConfig);
        tools.applyResearchTuning(
                researchTuning.totalDeadlineSeconds(),
                researchTuning.minQuoteChars(),
                researchTuning.autoOfferOnIntent());
        ResearchToolWiring wiring = researchToolWiring.getIfAvailable();
        if (wiring != null) {
            wiring.attach(tools);
        }
        return tools;
    }
}
