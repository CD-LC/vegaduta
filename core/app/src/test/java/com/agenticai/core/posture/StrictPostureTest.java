package com.agenticai.core.posture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * CI guard against posture drift: under the {@code strict} profile every {@link PostureChecks} key resolves to
 * a safe value; the base profile reports PERMISSIVE (so nobody flips a default by accident, or forgets to add a
 * new fail-open knob to the strict profile). DB-free: loads the real yml files into a bare environment.
 */
class StrictPostureTest {

    private static StandardEnvironment env(boolean strict) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // addLast in precedence order: the profile file first so it wins over application.yml, as Spring does.
        if (strict) {
            add(env, "application-strict.yml");
        }
        add(env, "application.yml");
        return env;
    }

    private static void add(StandardEnvironment env, String file) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        sources.forEach(s -> env.getPropertySources().addLast(s));
    }

    @Test
    void strictProfileResolvesEveryKeyToItsSafeValue() throws Exception {
        StandardEnvironment env = env(true);
        for (PostureChecks.Check c : PostureChecks.ALL) {
            String value = env.getProperty(c.key());
            assertThat(c.safe().test(value)).as("%s=%s must be safe under strict", c.key(), value).isTrue();
        }
        assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("enforce");
        assertThat(env.getProperty("app.agents.taint.get-query-is-sink")).isEqualTo("true");
        assertThat(env.getProperty("app.mcp.tool-pinning.mode")).isEqualTo("enforce");
        assertThat(env.getProperty("app.tools.classification.unclassified-action")).isEqualTo("require-approval");
        assertThat(env.getProperty("app.tools.classification.confirmation-gate-exempt-tools")).isEmpty();
        assertThat(env.getProperty("app.tools.classification.trifecta-enforcement")).isEqualTo("block");
        assertThat(env.getProperty("app.approvals.expiry.ttl")).isEqualTo("PT24H");

        PostureReport report = new PostureReport(env);
        assertThat(report.posture()).isEqualTo(Posture.STRICT);
        assertThat(report.permissiveSettings()).isEmpty();
    }

    @Test
    void baseProfileReportsPermissiveAndListsTheCoreKeys() throws Exception {
        PostureReport report = new PostureReport(env(false));
        assertThat(report.posture()).isEqualTo(Posture.PERMISSIVE);
        long coreKeys = PostureChecks.ALL.stream().filter(PostureChecks.Check::core).count();
        assertThat(report.permissiveSettings().stream()
            .filter(s -> PostureChecks.ALL.stream().anyMatch(c -> c.core() && s.startsWith(c.key() + "="))))
            .hasSize((int) coreKeys);
    }

    @Test
    void oneOpenCoreKeyUnderStrictIsMixed() throws Exception {
        StandardEnvironment env = env(true);
        env.getPropertySources().addFirst(
            new org.springframework.core.env.MapPropertySource("override", java.util.Map.of("app.agents.taint.mode", "observe")));
        PostureReport report = new PostureReport(env);
        assertThat(report.posture()).isEqualTo(Posture.MIXED);
        assertThat(report.permissiveSettings()).hasSize(1);
    }

    @Test
    void zeroTtlIsPermissiveAndIso8601IsNot() {
        PostureChecks.Check ttl = PostureChecks.ALL.stream()
            .filter(c -> c.key().equals("app.approvals.expiry.ttl")).findFirst().orElseThrow();
        assertThat(ttl.safe().test("0")).isFalse();
        assertThat(ttl.safe().test("PT0S")).isFalse();
        assertThat(ttl.safe().test("PT24H")).isTrue();
        assertThat(ttl.safe().test("30m")).isTrue();
    }
}
