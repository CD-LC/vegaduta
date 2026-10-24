package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.ResilientToolCallingManagerFactory;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.PatternBasedGuardrailService;
import com.agenticai.core.guardrails.SemanticGuardrailService;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The tool-limit beans come up through Spring's own constructor resolution, bound from the real
 * application.yml: the typed property classes are registered by their modules, the guardrail services
 * take the shared output cap, and the tool-loop factory derives its round cap from the guardrail keys.
 * (Several of these classes have more than one constructor, so "it compiles" is not "it wires".)
 */
class ToolLimitsWiringTest {

    @Configuration
    static class Extras {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static ApplicationContextRunner runner(Class<?>... configurations) throws Exception {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Extras.class)
            .withUserConfiguration(
                Class.forName("com.agenticai.core.guardrails.ToolLimitsConfiguration"),
                Class.forName("com.agenticai.core.guardrails.GuardrailTuningConfiguration"),
                Class.forName("com.agenticai.core.chat.ToolLoopConfiguration"))
            .withUserConfiguration(configurations);
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return runner.withInitializer(ctx -> sources.forEach(s -> ctx.getEnvironment().getPropertySources().addLast(s)));
    }

    @Test
    void thePropertyBeansBindFromTheShippedYml() throws Exception {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolOutputProperties.class).getMaxChars()).isEqualTo(20_000);
            assertThat(context.getBean(ToolStepLimitProperties.class).getGraceCalls()).isEqualTo(10);
            assertThat(context.getBean(ToolLoopProperties.class).getMaxRoundsPerInvocation()).isZero();
        });
    }

    @Test
    void theGuardrailServicesTakeTheSharedOutputCapThroughSpring() throws Exception {
        runner(PatternBasedGuardrailService.class)
            .withPropertyValues("app.guardrails.implementation=pattern", "app.tools.output.max-chars=2500")
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailService service = context.getBean(GuardrailService.class);
                assertThat(service.sanitizeToolOutput("t", "x".repeat(9_000)).length()).isBetween(2_490, 2_500);
            });
        runner(SemanticGuardrailService.class)
            .withPropertyValues("app.tools.output.max-chars=2500")
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailService service = context.getBean(GuardrailService.class);
                assertThat(service.sanitizeToolOutput("t", "y".repeat(9_000)).length()).isBetween(2_490, 2_500);
            });
    }

    @Test
    void theToolLoopFactoryIsBuiltFromTheTypedPropertiesAndTheGuardrailKeys() throws Exception {
        runner(ResilientToolCallingManagerFactory.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ResilientToolCallingManagerFactory.class).create()).isNotNull();
        });
        // The personal profile's ceiling of 150 calls wires the same way (the derived round cap itself is
        // pinned in ToolRoundCapFollowsCallCeilingTest and ToolLimitsDefaultsTest).
        runner(ResilientToolCallingManagerFactory.class)
            .withPropertyValues("app.guardrails.max-tool-calls-per-turn=150")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
