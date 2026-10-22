package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Locks the {@code personal} profile (application-personal.yml): the shared contract property names
 * other areas read, their personal defaults, that each is env-overridable, and that the file stays
 * additive (no localhost in a value, nothing that turns Kafka or Temporal on). Parses the shipped
 * YAML with duplicate keys rejected, as Spring Boot's own loader does, and resolves placeholders
 * through a real {@link StandardEnvironment}.
 */
class PersonalProfileConfigTest {

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Real environment variables outrank the profile file; model them as the first source.
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        for (PropertySource<?> source :
                new YamlPropertySourceLoader()
                        .load("personal", new ClassPathResource("application-personal.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    void sharedContractDefaultsAreThePersonalOnes() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty("app.auth.mode")).isEqualTo("local");
        assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("enforce");
        assertThat(env.getProperty("app.mcp.stdio.sandbox")).isEqualTo("os");
        assertThat(env.getProperty("app.skills.learning.enabled")).isEqualTo("true");
        assertThat(env.getProperty("app.edition.billing-enabled")).isEqualTo("false");
    }

    @Test
    void embeddingsFallBackToOllamaAndTheSidecarIsOffUnlessAskedFor() throws IOException {
        StandardEnvironment env = environment(Map.of());
        assertThat(env.getProperty("app.embeddings.provider")).isEqualTo("auto");
        assertThat(env.getProperty("app.sidecar.enabled")).isEqualTo("false");

        StandardEnvironment rag =
                environment(
                        Map.of(
                                "APP_SIDECAR_ENABLED",
                                "true",
                                "APP_EMBEDDINGS_PROVIDER",
                                "sidecar"));
        assertThat(rag.getProperty("app.sidecar.enabled")).isEqualTo("true");
        assertThat(rag.getProperty("app.embeddings.provider")).isEqualTo("sidecar");
    }

    @Test
    void theInboundRateLimitStartsOnHereAndIsOffEverywhereElse() throws IOException {
        assertThat(environment(Map.of()).getProperty("app.omnichannel.rate-limit.enabled"))
                .isEqualTo("true");
        assertThat(
                        environment(Map.of("APP_OMNICHANNEL_RATE_LIMIT_ENABLED", "false"))
                                .getProperty("app.omnichannel.rate-limit.enabled"))
                .isEqualTo("false");
    }

    @Test
    void everyContractPropertyCanBeOverriddenFromTheEnvironment() throws IOException {
        StandardEnvironment env =
                environment(
                        Map.of(
                                "APP_AUTH_MODE", "keycloak",
                                "APP_AGENTS_TAINT_MODE", "observe",
                                "APP_MCP_STDIO_SANDBOX", "container",
                                "APP_SKILLS_LEARNING_ENABLED", "false",
                                "APP_EDITION_BILLING_ENABLED", "true"));

        assertThat(env.getProperty("app.auth.mode")).isEqualTo("keycloak");
        assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("observe");
        assertThat(env.getProperty("app.mcp.stdio.sandbox")).isEqualTo("container");
        assertThat(env.getProperty("app.skills.learning.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.edition.billing-enabled")).isEqualTo("true");
    }

    @Test
    void runsOnLocalFilesWithTheInProcessWorkflowEngineAndNoTracing() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty("app.storage.type")).isEqualTo("local");
        assertThat(env.getProperty("app.workflow.execution-engine")).isEqualTo("legacy");
        assertThat(env.getProperty("management.tracing.export.enabled")).isEqualTo("false");
        assertThat(env.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.data.redis.timeout")).isEqualTo("2s");
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("2");
    }

    @Test
    void keepsBestEffortWorkOffTheReadinessPath() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty("app.startup.background-warmup.enabled")).isEqualTo("true");
        assertThat(env.getProperty("app.tenancy.migrate-skip-up-to-date")).isEqualTo("true");
        assertThat(env.getProperty("mcp.registry.warm-on-startup")).isEqualTo("false");
    }

    @Test
    void theStartUpShortcutsCanBeSwitchedBackFromTheEnvironment() throws IOException {
        StandardEnvironment env =
                environment(
                        Map.of(
                                "APP_STARTUP_BACKGROUND_WARMUP_ENABLED", "false",
                                "TENANCY_MIGRATE_SKIP_UP_TO_DATE", "false",
                                "MCP_REGISTRY_WARM_ON_STARTUP", "true"));

        assertThat(env.getProperty("app.startup.background-warmup.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.tenancy.migrate-skip-up-to-date")).isEqualTo("false");
        assertThat(env.getProperty("mcp.registry.warm-on-startup")).isEqualTo("true");
    }

    @Test
    void doesNotSwitchKafkaOrTemporalOn() throws IOException {
        // Both stay governed by the `workflow` / `temporal` profiles (not part of the personal
        // profile set) and by an explicit bootstrap server; this file must not pre-empt that.
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty("spring.kafka.bootstrap-servers")).isNull();
        assertThat(env.getProperty("app.workflow-events.enabled")).isNull();
        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isNull();
        assertThat(env.getProperty("app.temporal.target")).isNull();
        assertThat(env.getProperty("app.workflow.execution-engine")).isNotEqualTo("temporal");
    }

    @Test
    void noValueHardcodesLocalhostAndTheIssuerNeverFallsBackToIt() throws IOException {
        // AGENTS.md rule 4: a token issuer and browser-facing URL never default to localhost.
        StandardEnvironment env = environment(Map.of());
        int checked = 0;
        for (PropertySource<?> source : env.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !source.getName().contains("personal")) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                checked++;
                assertThat(String.valueOf(enumerable.getProperty(name)))
                        .as(name)
                        .doesNotContain("localhost")
                        .doesNotContain("127.0.0.1");
            }
        }
        // Guard against the loop silently matching no source (e.g. a renamed property source).
        assertThat(checked)
                .as("properties scanned from application-personal.yml")
                .isGreaterThan(10);
        assertThat(env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .startsWith("urn:");
        assertThat(
                        environment(
                                        Map.of(
                                                "KEYCLOAK_ISSUER_URI",
                                                "https://auth.example.org/realms/x"))
                                .getProperty(
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .isEqualTo("https://auth.example.org/realms/x");
    }

    @Test
    void yamlHasNoDuplicateKeys() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream in = getClass().getResourceAsStream("/application-personal.yml")) {
            assertThat(in).isNotNull();
            assertThat(new Yaml(options).<Object>load(in)).isNotNull();
        }
    }
}
