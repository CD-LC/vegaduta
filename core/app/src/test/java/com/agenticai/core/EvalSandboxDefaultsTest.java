package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.quality.dataset.EvalMode;
import com.agenticai.core.quality.dataset.EvalSandboxPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The settings of the eval sandbox, the lab's with-tools mode, the judge sample rate and the RL turn reference, read
 * from the real application.yml: evals replay through the full production pipeline in the sandbox (the one deliberate
 * change of default), and every other setting keeps the behaviour that existed before it did (no tenant may
 * auto-approve, no unclassified tool is vouched for, a tool-less lab, a 5% judge sample on a 10 second cache, bandits on
 * tagged turns only); the yml equals the defaults in code.
 */
class EvalSandboxDefaultsTest {

    private static StandardEnvironment fromApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    @Test
    void theYmlMakesTheFullPipelineTheEvalDefaultAndKeepsEverythingElseAsItWas() throws Exception {
        StandardEnvironment env = fromApplicationYml();

        assertThat(env.getProperty("app.quality.eval.default-mode")).isEqualTo("full");
        assertThat(env.containsProperty("app.quality.eval.default-tool-execution"))
            .as("the old per-dataset default is gone").isFalse();
        assertThat(env.getProperty("app.quality.eval.sandbox.read-only-tools")).isNullOrEmpty();
        assertThat(env.getProperty("app.quality.eval.auto-approve.tenant-ids")).isNullOrEmpty();
        assertThat(env.getProperty("app.quality.eval.auto-approve.actor")).isEqualTo("eval-auto-approve");
        assertThat(env.getProperty("app.agent-lab.with-tools.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("app.llm.judge.sample-rate", Double.class)).isEqualTo(0.05);
        assertThat(env.getProperty("app.llm.judge.sample-rate-cache-ms", Long.class)).isEqualTo(10_000L);
        assertThat(env.getProperty("rl.selection.server-turn-ref", Boolean.class)).isFalse();
    }

    @Test
    void thePolicyBuiltFromTheYmlEqualsTheShippedDefaults() throws Exception {
        StandardEnvironment env = fromApplicationYml();
        String excluded = env.getProperty("app.quality.eval.auto-approve.excluded-tools");

        EvalSandboxPolicy fromYml = new EvalSandboxPolicy(
            env.getProperty("app.quality.eval.default-mode"),
            env.getProperty("app.quality.eval.auto-approve.tenant-ids", ""),
            env.getProperty("app.quality.eval.auto-approve.actor"),
            excluded,
            env.getProperty("app.quality.eval.sandbox.read-only-tools", ""));
        EvalSandboxPolicy shipped = EvalSandboxPolicy.defaults();

        assertThat(excluded).isEqualTo(EvalSandboxPolicy.DEFAULT_EXCLUDED_TOOLS);
        assertThat(fromYml.defaultMode()).isEqualTo(shipped.defaultMode()).isEqualTo(EvalMode.FULL);
        assertThat(fromYml.readOnlyTools()).isEqualTo(shipped.readOnlyTools()).isEmpty();
        assertThat(fromYml.actor()).isEqualTo(shipped.actor());
        assertThat(fromYml.excludedTools()).isEqualTo(shipped.excludedTools());
        assertThat(fromYml.autoApproveAllowedFor("any-tenant")).isFalse();
    }

    @Test
    void theExcludedToolsKeepEveryCategoryThatIsNeverAutoApproved() {
        // Security-sensitive default: it may be extended, never silently shortened.
        assertThat(EvalSandboxPolicy.defaults().excludedTools()).contains(
            "local_shell", "coding_task", "schedule_task", "a2a_call_agent", "run_in_code_workspace",
            "apply_code_changes", "*payment*", "*refund*", "*charge*", "*transfer*", "*delete*");
    }
}
