package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.MigrateOnlyEnvironmentPostProcessor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

/**
 * Locks how the workflow-event Kafka switch is decided at runtime: on iff a bootstrap server
 * resolved (WORKFLOW_KAFKA_BOOTSTRAP_SERVERS or SPRING_KAFKA_BOOTSTRAP_SERVERS, wherever it comes
 * from, e.g. a mounted Secret), off when none did, and off in the one-shot migrate process. An
 * explicit WORKFLOW_EVENTS_ENABLED / WORKFLOW_KAFKA_ENABLED always wins.
 */
class WorkflowKafkaDefaultsTest {

    private static StandardEnvironment environment(Map<String, Object> env, boolean migrateOnly)
            throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Real environment variables outrank everything; model them as the first source.
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        if (migrateOnly) {
            environment
                    .getPropertySources()
                    .addLast(
                            new MapPropertySource(
                                    "flag", Map.of("app.tenancy.migrate-only", "true")));
        }
        for (PropertySource<?> source :
                new YamlPropertySourceLoader()
                        .load("workflow", new ClassPathResource("application-workflow.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        new MigrateOnlyEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void noBootstrapResolvedMeansNoKafka() throws IOException {
        StandardEnvironment env = environment(Map.of(), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.workflow-events.enabled")).isEqualTo("false");
    }

    @Test
    void blankBootstrapMeansNoKafka() throws IOException {
        StandardEnvironment env =
                environment(Map.of("WORKFLOW_KAFKA_BOOTSTRAP_SERVERS", "  "), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("false");
    }

    @Test
    void workflowBootstrapKeepsKafkaOn() throws IOException {
        StandardEnvironment env =
                environment(Map.of("WORKFLOW_KAFKA_BOOTSTRAP_SERVERS", "b:9092"), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("true");
        assertThat(env.getProperty("app.workflow-events.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("b:9092");
    }

    @Test
    void springBootstrapFromASecretBackedEnvKeepsKafkaOn() throws IOException {
        // SPRING_KAFKA_BOOTSTRAP_SERVERS relaxed-binds to spring.kafka.bootstrap-servers and
        // outranks the profile file; it is what an envFrom'd Secret key looks like to core.
        StandardEnvironment env =
                environment(Map.of("spring.kafka.bootstrap-servers", "secret-broker:9093"), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("true");
    }

    @Test
    void explicitEnabledTrueWithoutBootstrapIsRespected() throws IOException {
        StandardEnvironment env = environment(Map.of("WORKFLOW_KAFKA_ENABLED", "true"), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("true");
    }

    @Test
    void explicitEnabledFalseBeatsAResolvedBootstrap() throws IOException {
        StandardEnvironment env =
                environment(
                        Map.of(
                                "WORKFLOW_KAFKA_ENABLED",
                                "false",
                                "WORKFLOW_KAFKA_BOOTSTRAP_SERVERS",
                                "b:9092"),
                        false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("false");
    }

    @Test
    void kafkaOffTurnsOffTheBrokerPiecesAndTheOutboxWrites() throws IOException {
        StandardEnvironment env = environment(Map.of("WORKFLOW_KAFKA_ENABLED", "false"), false);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.workflow-events.enabled")).isEqualTo("false");
    }

    @Test
    void explicitEventsFlagStillWins() throws IOException {
        StandardEnvironment env =
                environment(
                        Map.of(
                                "WORKFLOW_KAFKA_ENABLED",
                                "false",
                                "WORKFLOW_EVENTS_ENABLED",
                                "true"),
                        false);

        assertThat(env.getProperty("app.workflow-events.enabled")).isEqualTo("true");
    }

    @Test
    void migrateOnlyProcessNeverStartsTheBrokerPieces() throws IOException {
        StandardEnvironment env =
                environment(Map.of("WORKFLOW_KAFKA_BOOTSTRAP_SERVERS", "b:9092"), true);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("false");
    }

    @Test
    void migrateOnlyStillYieldsToAnExplicitEnvironmentVariable() throws IOException {
        StandardEnvironment env = environment(Map.of("WORKFLOW_KAFKA_ENABLED", "true"), true);

        assertThat(env.getProperty("app.workflow-events.kafka.enabled")).isEqualTo("true");
    }
}
