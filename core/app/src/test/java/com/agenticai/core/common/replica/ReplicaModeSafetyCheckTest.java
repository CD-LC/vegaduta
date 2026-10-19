package com.agenticai.core.common.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.tenancy.replica.ReplicaMode;
import com.agenticai.core.tenancy.replica.ReplicaModeSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReplicaModeSafetyCheckTest {

    @Test
    void parsesTheDocumentedValuesAndTreatsLegacyHintsAsMulti() {
        assertThat(ReplicaMode.parse(null)).isEqualTo(ReplicaMode.SINGLE);
        assertThat(ReplicaMode.parse("  ")).isEqualTo(ReplicaMode.SINGLE);
        assertThat(ReplicaMode.parse("Single")).isEqualTo(ReplicaMode.SINGLE);
        assertThat(ReplicaMode.parse("multi")).isEqualTo(ReplicaMode.MULTI);
        assertThat(ReplicaMode.parse("api")).isEqualTo(ReplicaMode.MULTI);
        assertThat(ReplicaMode.isDocumentedValue("api")).isFalse();
        assertThat(ReplicaMode.isDocumentedValue("multi")).isTrue();
    }

    @Test
    void singleModeEvaluatesNothingAndNeverThrows() {
        MockEnvironment env = new MockEnvironment();
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("single", true));
        assertThat(check.findings()).isEmpty();
        check.afterPropertiesSet();
    }

    @Test
    void strictFlagIsIgnoredInSingleMode() {
        assertThat(ReplicaModeSettings.of("single", true).isStrict()).isFalse();
        assertThat(ReplicaModeSettings.of("multi", true).isStrict()).isTrue();
        assertThat(ReplicaModeSettings.of("multi", false).isStrict()).isFalse();
    }

    @Test
    void multiModeWithDefaultsReportsTheRegisterBlockersAndWarnsWithoutThrowing() {
        MockEnvironment env = new MockEnvironment();
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", false));
        var messages =
                check.findings().stream().map(ReplicaModeSafetyCheck.Finding::message).toList();
        assertThat(messages).anyMatch(m -> m.contains("private key"));
        assertThat(messages).anyMatch(m -> m.contains("app.a2a.task-store"));
        assertThat(messages).anyMatch(m -> m.contains("app.abuse.throttle-store"));
        assertThat(messages).anyMatch(m -> m.contains("app.mcp.registry-store"));
        // Features that are off by default are not reported.
        assertThat(messages).noneMatch(m -> m.contains("data-analysis"));
        assertThat(messages).noneMatch(m -> m.contains("E2B"));
        check.afterPropertiesSet(); // warn only: must not throw
    }

    @Test
    void strictModeRefusesToBootAndListsEveryFatalFinding() {
        MockEnvironment env = new MockEnvironment();
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.a2a.task-store")
                .hasMessageContaining("app.abuse.throttle-store")
                .hasMessageContaining("app.mcp.registry-store")
                .hasMessageContaining("private key");
    }

    @Test
    void fullyRedisBackedMultiModeIsClean() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.signing.private-key-pem", "pem")
                        .withProperty("app.a2a.task-store", "redis")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.demo.studio.approval-store", "redis")
                        .withProperty("app.tools.effect-journal.store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        assertThat(check.findings()).isEmpty();
        check.afterPropertiesSet();
    }

    @Test
    void anEnabledInMemoryMcpOAuthStoreIsFatalInStrictMulti() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.enabled", "false")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale")
                        .withProperty("app.demo.studio.approval-store", "redis")
                        .withProperty("app.tools.effect-journal.store", "redis")
                        .withProperty("app.mcp.oauth.enabled", "true");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.oauth.enabled");
    }

    @Test
    void mcpOAuthFindingsClearWhenTheSharedStoreIsRedis() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.enabled", "false")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale")
                        .withProperty("app.demo.studio.approval-store", "redis")
                        .withProperty("app.tools.effect-journal.store", "redis")
                        .withProperty("app.mcp.oauth.enabled", "true")
                        .withProperty("app.mcp.oauth.providers-enabled", "true");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        // Memory default: the remote-MCP finding is fatal, the provider one is not.
        var findings = check.findings();
        assertThat(findings)
                .anySatisfy(
                        f -> {
                            assertThat(f.message()).contains("app.mcp.oauth.store");
                            assertThat(f.fatalWhenStrict()).isTrue();
                        })
                .anySatisfy(
                        f -> {
                            assertThat(f.message()).contains("providers-enabled");
                            assertThat(f.fatalWhenStrict()).isFalse();
                        });
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.oauth.store");

        env.setProperty("app.mcp.oauth.store", "redis");
        assertThat(check.findings()).isEmpty();
        check.afterPropertiesSet();
    }

    @Test
    void enabledSandboxFeaturesAreFatalUntilTheirStoreIsRedis() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.enabled", "false")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale")
                        .withProperty("app.demo.studio.approval-store", "redis")
                        .withProperty("app.tools.effect-journal.store", "redis")
                        .withProperty("app.sandbox.data-analysis.enabled", "true")
                        .withProperty("app.sandbox.platform-e2b.enabled", "true");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data-analysis")
                .hasMessageContaining("E2B");
        env.setProperty("app.sandbox.data-analysis.store", "redis");
        env.setProperty("app.sandbox.platform-e2b.quota-store", "redis");
        assertThat(check.findings()).isEmpty();
    }

    @Test
    void resumableStreamsAreReportedOnlyWhenEnabledWithAnInMemoryStoreAndNeverFatal() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.enabled", "false")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale")
                        .withProperty("app.tools.effect-journal.store", "redis")
                        .withProperty("app.demo.studio.approval-store", "redis");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));
        // Off by default: nothing to report.
        assertThat(check.findings()).isEmpty();

        env.setProperty("app.chat.resumable-streams.enabled", "true");
        assertThat(check.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.message()).contains("app.chat.resumable-streams.store");
                            assertThat(f.fatalWhenStrict()).isFalse();
                        });
        check.afterPropertiesSet(); // a non-fatal finding must not stop a strict boot

        env.setProperty("app.chat.resumable-streams.store", "redis");
        assertThat(check.findings()).isEmpty();
    }

    @Test
    void theEffectJournalIsReportedWhileItIsOnAndInMemoryAndNeverFatal() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.a2a.enabled", "false")
                        .withProperty("app.abuse.throttle-store", "redis")
                        .withProperty("app.mcp.registry-store", "redis")
                        .withProperty("app.guardrails.procedures.session-calls-store", "redis")
                        .withProperty("app.quality.boot-orphan-sweep", "stale")
                        .withProperty("app.demo.studio.approval-store", "redis");
        ReplicaModeSafetyCheck check =
                new ReplicaModeSafetyCheck(env, ReplicaModeSettings.of("multi", true));

        // On by default (TOOL_EFFECT_JOURNAL_ENABLED=true) with the memory store.
        assertThat(check.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.message()).contains("effect journal");
                            assertThat(f.fatalWhenStrict()).isFalse();
                        });
        check.afterPropertiesSet(); // non-fatal: a strict boot continues

        env.setProperty("app.tools.effect-journal.enabled", "false");
        assertThat(check.findings()).isEmpty();
        env.setProperty("app.tools.effect-journal.enabled", "true");
        env.setProperty("app.tools.effect-journal.store", "redis");
        assertThat(check.findings()).isEmpty();
    }
}
