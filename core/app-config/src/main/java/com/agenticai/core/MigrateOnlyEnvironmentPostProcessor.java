package com.agenticai.core;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Quiets the background consumers when {@code app.tenancy.migrate-only=true} (the one-shot
 * tenant-migration Job). The full context starts before the migrate-only runner fires, so anything
 * that polls or consumes would otherwise run against half-migrated tenant schemas and compete with
 * the real API/worker replicas.
 *
 * <p>It also makes the runtime decision for the workflow-event Kafka switch (see {@link
 * #defaultWorkflowKafkaFromBootstrap}).
 *
 * <p>Added at LOWEST precedence, so an explicit env var still wins. The migrate-only part has no
 * effect unless the flag is true. Not covered: {@code @Scheduled} beans (no property switch exists)
 * - see {@code docs/plans/CORE-MIGRATE-JOB.md}.
 */
public class MigrateOnlyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        if ("true".equalsIgnoreCase(environment.getProperty("app.tenancy.migrate-only"))) {
            quietMigrateOnly(environment);
        }
        defaultWorkflowKafkaFromBootstrap(environment);
    }

    /**
     * Runtime decision for the workflow-event broker: when nothing set {@code
     * WORKFLOW_KAFKA_ENABLED}, Kafka is on exactly when a bootstrap resolved to a non-blank value
     * ({@code spring.kafka.bootstrap-servers}, fed by {@code WORKFLOW_KAFKA_BOOTSTRAP_SERVERS} or
     * {@code SPRING_KAFKA_BOOTSTRAP_SERVERS} from env, a mounted Secret or any other source). The
     * deployment tooling cannot see Secret contents, so core decides here instead of the chart.
     */
    private static void defaultWorkflowKafkaFromBootstrap(ConfigurableEnvironment environment) {
        if (environment.getProperty("WORKFLOW_KAFKA_ENABLED") != null) {
            return;
        }
        boolean resolved =
                StringUtils.hasText(environment.getProperty("spring.kafka.bootstrap-servers"));
        environment
                .getPropertySources()
                .addLast(
                        new MapPropertySource(
                                "workflowKafkaRuntimeDefault",
                                Map.of("WORKFLOW_KAFKA_ENABLED", String.valueOf(resolved))));
    }

    private static void quietMigrateOnly(ConfigurableEnvironment environment) {
        environment
                .getPropertySources()
                .addLast(
                        new MapPropertySource(
                                "migrateOnlyDefaults",
                                Map.of(
                                        "app.workflow.temporal.worker-enabled", "false",
                                        "spring.kafka.listener.auto-startup", "false",
                                        // No topic admin / relay / consumers in a process that only
                                        // migrates and exits: topic creation
                                        // alone held start-up for the 30 s Kafka operation timeout
                                        // when the broker was unreachable.
                                        // (Keyed by the env name application-workflow.yml reads,
                                        // because a profile file outranks this
                                        // lowest-precedence source for the property itself; a real
                                        // WORKFLOW_KAFKA_ENABLED still wins.)
                                        "WORKFLOW_KAFKA_ENABLED", "false")));
    }
}
