package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.graphmemory.GraphMemoryProperties;
import com.agenticai.core.graphmemory.GraphMemorySettings;
import com.agenticai.core.knowledge.KnowledgeSearchProperties;
import com.agenticai.core.knowledge.KnowledgeSettings;
import com.agenticai.core.providers.ProviderSettings;
import com.agenticai.core.rl.policy.RlPolicyProperties;
import com.agenticai.core.rl.policy.RlSettings;
import com.agenticai.core.settings.SettingsRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Package B3: the catalog entries of core/knowledge, core/graphmemory, core/rl and core/providers take their platform
 * value from the property objects the runtime reads, bound here from the real application.yml. So the console shows
 * what the deployment really runs, and an unset override changes nothing.
 */
class OwnedModulesSettingsDefaultsTest {

    private static StandardEnvironment applicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    private static <T> T bind(StandardEnvironment env, String prefix, Class<T> type) {
        Binder binder = new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
        return binder.bind(prefix, type).orElseGet(() -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void theCatalogPlatformValuesAreWhatTheYmlBindsAndEqualTheCodeDefaults() throws Exception {
        StandardEnvironment env = applicationYml();
        KnowledgeSearchProperties knowledge = bind(env, "app.knowledge.search", KnowledgeSearchProperties.class);
        GraphMemoryProperties graph = bind(env, "app.graph-memory", GraphMemoryProperties.class);
        RlPolicyProperties rl = bind(env, "rl.policy", RlPolicyProperties.class);
        SettingsRegistry registry = new SettingsRegistry(List.of(
            new KnowledgeSettings(knowledge), new GraphMemorySettings(graph), new RlSettings(rl), new ProviderSettings(env)));

        assertThat(registry.require(KnowledgeSettings.CANDIDATE_POOL_MULTIPLIER).platformValue())
            .isEqualTo((long) new KnowledgeSearchProperties().getCandidatePoolMultiplier()).isEqualTo(4L);
        assertThat(registry.require(KnowledgeSettings.CANDIDATE_POOL_MAX).platformValue())
            .isEqualTo((long) new KnowledgeSearchProperties().getCandidatePoolMax()).isEqualTo(50L);
        assertThat(registry.require(GraphMemorySettings.RECALL_MAX_FACTS).platformValue())
            .isEqualTo((long) new GraphMemoryProperties().getRecall().getMaxFacts()).isEqualTo(20L);
        assertThat(registry.require(GraphMemorySettings.RECALL_MAX_PATHS).platformValue())
            .isEqualTo((long) new GraphMemoryProperties().getRecall().getMaxPaths()).isEqualTo(200L);
        assertThat(registry.require(RlSettings.UCB1_EXPLORATION_CONSTANT).platformValue())
            .isEqualTo(new RlPolicyProperties().getUcb1ExplorationConstant()).isEqualTo(2.0);
        assertThat(registry.require(RlSettings.DEFAULT_EXPLORATION_RATE).platformValue())
            .isEqualTo(new RlPolicyProperties().getDefaultExplorationRate().doubleValue()).isEqualTo(0.1);
        assertThat(registry.require(ProviderSettings.REJECTED_COOLDOWN).platformValue()).isEqualTo(6L * 3600);
    }
}
