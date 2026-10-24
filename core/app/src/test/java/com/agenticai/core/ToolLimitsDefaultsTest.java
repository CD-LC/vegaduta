package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.ArgumentSanitizingToolCallingManager;
import com.agenticai.core.chat.ResilientToolCallingManagerFactory;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
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
 * The tool-loop limits and wordings ({@code app.tools.output.*}, {@code app.guardrails.step-limit.*},
 * {@code app.chat.tools.*}) are bound from the real application.yml, equal the code defaults that a
 * context without the yml falls back to, and agree with each other across the module boundaries that
 * cannot share a class: chat cannot see guardrails, so the round cap's inputs and the budget-exhausted
 * marker are repeated there and pinned here.
 */
class ToolLimitsDefaultsTest {

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
    void theToolOutputCapBindsAndEqualsTheCodeDefaults() throws Exception {
        ToolOutputProperties fromYml = bind(applicationYml(), "app.tools.output", ToolOutputProperties.class);
        ToolOutputProperties codeDefaults = new ToolOutputProperties();

        assertThat(fromYml.getMaxChars()).isEqualTo(codeDefaults.getMaxChars()).isEqualTo(20_000);
        assertThat(fromYml.getTruncationMarker()).isEqualTo(codeDefaults.getTruncationMarker());
        assertThat(fromYml.getPerTool()).isEmpty();
    }

    @Test
    void theStepLimitBindsAndEqualsTheCodeDefaults() throws Exception {
        ToolStepLimitProperties fromYml =
            bind(applicationYml(), "app.guardrails.step-limit", ToolStepLimitProperties.class);
        ToolStepLimitProperties codeDefaults = new ToolStepLimitProperties();

        assertThat(fromYml.getGraceCalls()).isEqualTo(codeDefaults.getGraceCalls());
        assertThat(fromYml.getSupervisedDivisor()).isEqualTo(codeDefaults.getSupervisedDivisor());
        assertThat(fromYml.getSupervisedMinSteps()).isEqualTo(codeDefaults.getSupervisedMinSteps());
        assertThat(fromYml.getAutonomousMultiplier()).isEqualTo(codeDefaults.getAutonomousMultiplier());
        assertThat(fromYml.getExhaustedMessage()).isEqualTo(codeDefaults.getExhaustedMessage());
        // The shipped behaviour the audit and the docs describe: half (floored at 3), as configured, double.
        assertThat(fromYml.supervisedCeiling(15)).isEqualTo(7);
        assertThat(fromYml.autonomousCeiling(15)).isEqualTo(30);
    }

    @Test
    void theToolLoopBindsAndEqualsTheCodeDefaults() throws Exception {
        ToolLoopProperties fromYml = bind(applicationYml(), "app.chat.tools", ToolLoopProperties.class);
        ToolLoopProperties codeDefaults = new ToolLoopProperties();

        assertThat(fromYml.getMaxRoundsPerInvocation())
            .isEqualTo(codeDefaults.getMaxRoundsPerInvocation())
            .as("0 = derived from the call ceiling, not a hidden number")
            .isZero();
        assertThat(fromYml.getMaxUnknownCallsPerInvocation()).isEqualTo(codeDefaults.getMaxUnknownCallsPerInvocation());
        assertThat(fromYml.getBudgetExhaustedMessage()).isEqualTo(codeDefaults.getBudgetExhaustedMessage());
        assertThat(fromYml.getHardStopReply()).isEqualTo(codeDefaults.getHardStopReply());
        ToolLoopProperties.ResultClearing clearing = fromYml.getResultClearing();
        ToolLoopProperties.ResultClearing clearingDefaults = codeDefaults.getResultClearing();
        assertThat(clearing.isEnabled()).isEqualTo(clearingDefaults.isEnabled());
        assertThat(clearing.getThresholdChars()).isEqualTo(clearingDefaults.getThresholdChars()).isEqualTo(24_000);
        assertThat(clearing.getKeepLast()).isEqualTo(clearingDefaults.getKeepLast()).isEqualTo(3);
        assertThat(clearing.getRestoreHint()).isEqualTo(clearingDefaults.getRestoreHint());
    }

    @Test
    void theChatModuleRepeatsTheGuardrailKeysItDerivesTheRoundCapFromWithTheSameDefaults() throws Exception {
        StandardEnvironment env = applicationYml();

        assertThat(env.getProperty("app.guardrails.max-tool-calls-per-turn", Integer.class))
            .isEqualTo(ResilientToolCallingManagerFactory.DEFAULT_CALLS_PER_TURN);
        assertThat(env.getProperty("app.guardrails.step-limit.autonomous-multiplier", Integer.class))
            .isEqualTo(ResilientToolCallingManagerFactory.DEFAULT_AUTONOMOUS_MULTIPLIER);
        assertThat(env.getProperty("app.guardrails.step-limit.grace-calls", Integer.class))
            .isEqualTo(ResilientToolCallingManagerFactory.DEFAULT_GRACE_CALLS);
        ToolStepLimitProperties guardrails = new ToolStepLimitProperties();
        assertThat(guardrails.getAutonomousMultiplier())
            .isEqualTo(ResilientToolCallingManagerFactory.DEFAULT_AUTONOMOUS_MULTIPLIER);
        assertThat(guardrails.getGraceCalls()).isEqualTo(ResilientToolCallingManagerFactory.DEFAULT_GRACE_CALLS);
    }

    @Test
    void theDefaultRoundCapIsNeverBelowTheHighestCallCeilingAcrossTheShippedProfiles() throws Exception {
        StandardEnvironment env = applicationYml();
        ToolLoopProperties loop = bind(env, "app.chat.tools", ToolLoopProperties.class);
        int calls = env.getProperty("app.guardrails.max-tool-calls-per-turn", Integer.class);
        ToolStepLimitProperties limits = bind(env, "app.guardrails.step-limit", ToolStepLimitProperties.class);

        int rounds = loop.effectiveMaxRounds(calls, limits.getAutonomousMultiplier(), limits.getGraceCalls());

        assertThat(rounds).isGreaterThan(limits.autonomousCeiling(calls) + limits.getGraceCalls());
        // The personal profile raises the ceiling to 150: the round cap follows it instead of staying at 25.
        assertThat(loop.effectiveMaxRounds(150, limits.getAutonomousMultiplier(), limits.getGraceCalls()))
            .isGreaterThan(limits.autonomousCeiling(150));
    }

    @Test
    void anOperatorCanSetThePerToolCapAndTheOtherLimitsFromConfiguration() {
        java.util.Map<String, Object> operator = new java.util.HashMap<>();
        operator.put("app.tools.output.max-chars", "5000");
        operator.put("app.tools.output.per-tool.workspace_read", "60000");
        operator.put("app.guardrails.step-limit.grace-calls", "3");
        operator.put("app.guardrails.step-limit.autonomous-multiplier", "4");
        operator.put("app.chat.tools.max-rounds-per-invocation", "12");
        operator.put("app.chat.tools.result-clearing.restore-hint", "ask again");
        Binder binder = new Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(operator));

        ToolOutputProperties output = binder.bind("app.tools.output", ToolOutputProperties.class).get();
        ToolStepLimitProperties steps = binder.bind("app.guardrails.step-limit", ToolStepLimitProperties.class).get();
        ToolLoopProperties loop = binder.bind("app.chat.tools", ToolLoopProperties.class).get();

        assertThat(output.limitFor("workspace_read")).isEqualTo(60_000);
        assertThat(output.limitFor("anything_else")).isEqualTo(5_000);
        assertThat(steps.getGraceCalls()).isEqualTo(3);
        assertThat(steps.autonomousCeiling(15)).isEqualTo(60);
        assertThat(loop.effectiveMaxRounds(15, 2, 10)).isEqualTo(12);
        assertThat(loop.getResultClearing().getRestoreHint()).isEqualTo("ask again");
    }

    @Test
    void theBudgetExhaustedMarkerIsTheSameStringInTheToolLoopAndInTheGuard() {
        assertThat(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED).isEqualTo(ToolBudgetExhausted.MARKER);
        assertThat(ToolBudgetExhausted.isBudgetExhausted(new ToolStepLimitProperties().exhaustedResult(15))).isTrue();
    }
}
