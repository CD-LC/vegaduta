package com.agenticai.core.eventbus;

import com.agenticai.core.security.startup.BackgroundStartupTask;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Creates the workflow topics ({@link WorkflowKafkaConfig#workflowEventsTopic()} and the DLT) from
 * a background thread after readiness, instead of on the main thread during context refresh.
 *
 * <p>Only active when {@code app.startup.background-warmup.enabled=true}. That same flag makes
 * {@code StartupWarmupEnvironmentPostProcessor} (core/app-config) set {@code
 * spring.kafka.admin.auto-create=false}, so {@link KafkaAdmin} no longer creates topics in {@code
 * afterSingletonsInstantiated}. With the flag off (the default) this bean does not exist and {@link
 * KafkaAdmin} behaves exactly as before.
 *
 * <p>Why: with the broker unreachable, the synchronous path blocks start-up for the 30 s operation
 * timeout plus the 10 s close timeout (about 40 s of the 131 s measured in the 2026-10-03
 * boot.log), and Redpanda runs on a VM outside the Container Apps environment, so a core restart
 * while Redpanda is down is a realistic failover. Nothing on the request path needs the topic to
 * exist at boot: producers go through {@link WorkflowEventOutboxRelay}, an outbox that already
 * retries until the broker answers.
 *
 * <p>{@link KafkaAdmin#initialize()} returns {@code false} (after logging) when it could not reach
 * the broker, so this retries a bounded number of times with a pause - the synchronous path tried
 * exactly once and never again, which left the topics uncreated until the next restart.
 */
@Component
@Profile("workflow")
@ConditionalOnWorkflowKafka
@ConditionalOnProperty(name = "app.startup.background-warmup.enabled", havingValue = "true")
public class WorkflowKafkaTopicBackgroundInit {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowKafkaTopicBackgroundInit.class);

    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private final int attempts;
    private final long retryDelayMillis;

    public WorkflowKafkaTopicBackgroundInit(
            ObjectProvider<KafkaAdmin> kafkaAdmin,
            @Value("${app.startup.kafka-topic-init.attempts:3}") int attempts,
            @Value("${app.startup.kafka-topic-init.retry-delay-seconds:30}")
                    long retryDelaySeconds) {
        this.kafkaAdmin = kafkaAdmin;
        this.attempts = Math.max(1, attempts);
        this.retryDelayMillis = Math.max(0L, retryDelaySeconds) * 1_000L;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        BackgroundStartupTask.run(
                true,
                "kafka-topic-init",
                () -> createTopics(WorkflowKafkaTopicBackgroundInit::sleep));
    }

    /**
     * Package-private with an injectable sleeper so the retry loop is unit-testable without real
     * waiting.
     */
    void createTopics(LongConsumer sleeper) {
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        if (admin == null) {
            log.debug("No KafkaAdmin bean - nothing to create");
            return;
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (admin.initialize()) {
                log.info(
                        "Workflow Kafka topics checked/created in the background (attempt {} of"
                                + " {})",
                        attempt,
                        attempts);
                return;
            }
            if (attempt < attempts) {
                log.warn(
                        "Workflow Kafka topic creation did not complete (attempt {} of {}) -"
                                + " retrying in {} ms",
                        attempt,
                        attempts,
                        retryDelayMillis);
                sleeper.accept(retryDelayMillis);
                if (Thread.currentThread().isInterrupted()) {
                    return; // shutting down - do not keep poking the broker
                }
            }
        }
        log.warn(
                "Workflow Kafka topics were not created after {} attempt(s). The outbox relay keeps"
                        + " retrying publishes, and the broker may auto-create the topic on first"
                        + " write; otherwise restart core once the broker is reachable.",
                attempts);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
