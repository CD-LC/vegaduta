package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.graphmemory.GraphMemoryProperties;
import com.agenticai.core.knowledge.KnowledgeSearchProperties;
import com.agenticai.core.providers.ProviderEndpoints;
import com.agenticai.core.providers.ProviderModelDefaultsProperties;
import com.agenticai.core.rl.feedback.RlFeedbackProperties;
import com.agenticai.core.rl.policy.RlPolicyProperties;
import com.agenticai.core.rl.reward.RlRewardProperties;
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
 * Provider endpoints ({@code app.llm.endpoints}), model defaults ({@code app.models}), knowledge retrieval pool
 * ({@code app.knowledge.search}), graph-memory limits ({@code app.graph-memory}) and RL tuning ({@code rl.*})
 * are bound from the real application.yml, and the yml equals the code defaults that a context without the yml
 * falls back to, so the two cannot drift apart.
 */
class ModelRetrievalLearningDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void providerEndpointsEqualTheCodeDefaults() throws Exception {
        ProviderEndpoints fromYml = binderOverApplicationYml().bind("app.llm.endpoints", ProviderEndpoints.class).get();

        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new ProviderEndpoints());
        assertThat(fromYml.getGroq().getBaseUrl()).isEqualTo("https://api.groq.com/openai");
        assertThat(fromYml.getGemini().getCompletionsPath()).isEqualTo("/v1beta/openai/chat/completions");
    }

    @Test
    void modelDefaultsEqualTheCodeDefaults() throws Exception {
        ProviderModelDefaultsProperties fromYml =
            binderOverApplicationYml().bind("app.models", ProviderModelDefaultsProperties.class).get();

        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new ProviderModelDefaultsProperties());
        assertThat(fromYml.getDefaults()).containsEntry("grok", "grok-4-1-fast-non-reasoning");
        assertThat(fromYml.getRetired()).containsEntry("grok-3", "grok-4.3");
        assertThat(fromYml.getContextWindows()).isEmpty();
    }

    @Test
    void knowledgeSearchPoolEqualsTheCodeDefaults() throws Exception {
        KnowledgeSearchProperties fromYml =
            binderOverApplicationYml().bind("app.knowledge.search", KnowledgeSearchProperties.class).get();

        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new KnowledgeSearchProperties());
    }

    @Test
    void graphMemoryLimitsEqualTheCodeDefaults() throws Exception {
        GraphMemoryProperties fromYml = binderOverApplicationYml().bind("app.graph-memory", GraphMemoryProperties.class).get();

        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new GraphMemoryProperties());
    }

    @Test
    void rlPolicyEqualsTheCodeDefaultsAndTheBuiltInPresets() throws Exception {
        RlPolicyProperties fromYml = binderOverApplicationYml().bind("rl.policy", RlPolicyProperties.class).get();
        RlPolicyProperties code = new RlPolicyProperties();

        assertThat(fromYml.getDefaultExplorationRate()).isEqualByComparingTo(code.getDefaultExplorationRate());
        assertThat(fromYml.getReasoningDepthArms()).isEqualTo(code.getReasoningDepthArms());
        assertThat(fromYml.getUcb1ExplorationConstant()).isEqualTo(code.getUcb1ExplorationConstant());
        assertThat(fromYml.getModelRouteArms()).isEqualTo(code.getModelRouteArms());
        assertThat(fromYml.genParamPresets()).usingRecursiveComparison().isEqualTo(code.genParamPresets());
        assertThat(fromYml.getGenParamPresets()).hasSize(3);
    }

    @Test
    void rlRewardAndFeedbackEqualTheCodeDefaults() throws Exception {
        Binder binder = binderOverApplicationYml();

        assertThat(binder.bind("rl.reward", RlRewardProperties.class).get())
            .usingRecursiveComparison().isEqualTo(new RlRewardProperties());
        assertThat(binder.bind("rl.feedback", RlFeedbackProperties.class).get())
            .usingRecursiveComparison().isEqualTo(new RlFeedbackProperties());
    }
}
