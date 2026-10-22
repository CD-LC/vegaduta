package com.agenticai.core.startup;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * When {@code app.startup.background-warmup.enabled=true}, stops Spring Kafka's
 * {@code KafkaAdmin} from creating topics on the main thread during context
 * refresh ({@code spring.kafka.admin.auto-create=false}).
 *
 * <p>Why: with the broker unreachable, {@code KafkaAdmin.afterSingletonsInstantiated}
 * blocks start-up for the 30 s operation timeout plus the 10 s close timeout
 * (measured: 06:48:58 to 06:49:39 in the 2026-10-03 boot.log, about 31 percent of
 * that 131 s boot). Redpanda runs on a VM outside the Container Apps
 * environment, so "core restarts while Redpanda is down" is a real failover case.
 * The topics are then created by {@code WorkflowKafkaTopicBackgroundInit} (in
 * core/eventbus) from a background thread after readiness, with errors contained.
 *
 * <p>Added at LOWEST precedence, so an operator's explicit
 * {@code SPRING_KAFKA_ADMIN_AUTO_CREATE} still wins. No effect at all unless the
 * flag is true, so default behaviour is unchanged. Same pattern as
 * {@code MigrateOnlyEnvironmentPostProcessor}.
 */
public class StartupWarmupEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String FLAG = "app.startup.background-warmup.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!"true".equalsIgnoreCase(environment.getProperty(FLAG))) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("startupWarmupDefaults", Map.of(
            "spring.kafka.admin.auto-create", "false")));
    }
}
