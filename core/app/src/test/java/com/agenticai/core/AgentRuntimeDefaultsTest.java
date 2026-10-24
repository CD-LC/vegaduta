package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.limits.AgentRuntimeProperties;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.limits.AgentRuntimeSettingsResolver;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.limits.TenantRuntimeLimitsService;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The agent-runtime settings ({@code app.agent-runtime.*}) bind from the real application.yml, equal the code
 * defaults a context without the yml falls back to, and the one resolver comes up through Spring's own
 * constructor resolution with the platform layer read from the keys the limits always had: with no tenant or
 * agent override, every agent's effective limits are exactly what they were before the resolver existed.
 */
class AgentRuntimeDefaultsTest {

    private static StandardEnvironment applicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    private static <T> T bind(StandardEnvironment env, String prefix, Class<T> type) {
        Binder binder = new Binder(
            ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
        return binder.bind(prefix, type).get();
    }

    @Test
    void thePropertiesBindAndEqualTheCodeDefaults() throws Exception {
        AgentRuntimeProperties fromYml = bind(applicationYml(), "app.agent-runtime", AgentRuntimeProperties.class);
        AgentRuntimeProperties codeDefaults = new AgentRuntimeProperties();

        assertThat(fromYml.getCacheTtl()).isEqualTo(codeDefaults.getCacheTtl());
        assertThat(fromYml.getToolTimeoutMessage()).isEqualTo(codeDefaults.getToolTimeoutMessage());
        assertThat(fromYml.getDefaults().getToolTimeoutSeconds())
            .isEqualTo(codeDefaults.getDefaults().getToolTimeoutSeconds()).isZero();
        assertThat(fromYml.getDefaults().getRetrievalTopK())
            .isEqualTo(codeDefaults.getDefaults().getRetrievalTopK()).isEqualTo(3);
        assertThat(fromYml.getDefaults().getRetrievalMinScore())
            .isEqualTo(codeDefaults.getDefaults().getRetrievalMinScore()).isZero();
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            assertThat(fromYml.getBounds().of(setting).getMin()).as(setting + " min")
                .isEqualTo(codeDefaults.getBounds().of(setting).getMin());
            assertThat(fromYml.getBounds().of(setting).getMax()).as(setting + " max")
                .isEqualTo(codeDefaults.getBounds().of(setting).getMax());
        }
    }

    @Configuration
    static class Collaborators {
        @Bean
        AgentService agentService() {
            return mock(AgentService.class);
        }

        @Bean
        TenantRuntimeLimitsService tenantRuntimeLimitsService() {
            return mock(TenantRuntimeLimitsService.class);
        }

        @Bean
        ToolStepLimitProperties stepLimits() {
            return new ToolStepLimitProperties();
        }

        @Bean
        ToolOutputProperties toolOutput() {
            return new ToolOutputProperties();
        }
    }

    @Test
    void theResolverWiresAndTheUnoverriddenPlatformValuesAreTheOnesTheKeysAlwaysHad() throws Exception {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class)
            .withUserConfiguration(Class.forName("com.agenticai.core.agents.limits.AgentRuntimeConfiguration"))
            // A Spring Boot application registers this; a bare test context does not, and "240s" needs it.
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(
                org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withInitializer(ctx -> sources.forEach(s -> ctx.getEnvironment().getPropertySources().addLast(s)))
            .run(context -> {
                assertThat(context).hasNotFailed();
                AgentRuntimeSettingsResolver resolver = context.getBean(AgentRuntimeSettingsResolver.class);

                AgentRuntimeSettings semi = resolver.platform(AgentAutonomyTier.SEMI_AUTONOMOUS);
                assertThat(semi.maxToolSteps()).as("app.guardrails.max-tool-calls-per-turn").isEqualTo(15);
                assertThat(resolver.platform(AgentAutonomyTier.SUPERVISED).maxToolSteps()).isEqualTo(7);
                assertThat(resolver.platform(AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps()).isEqualTo(30);
                assertThat(semi.turnTimeout()).as("app.llm.http.buffered-turn-timeout").isEqualTo(Duration.ofSeconds(240));
                assertThat(semi.toolTimeout()).as("no per-call limit unless configured").isEqualTo(Duration.ZERO);
                assertThat(semi.toolOutputMaxChars()).as("app.tools.output.max-chars").isEqualTo(20_000);
                assertThat(semi.retrievalTopK()).as("the knowledge tool's historic 3").isEqualTo(3);
                assertThat(semi.retrievalMinScore()).isZero();
                assertThat(semi.continuationPasses()).as("app.chat.continuation.max-passes").isEqualTo(8);
            });
    }
}
