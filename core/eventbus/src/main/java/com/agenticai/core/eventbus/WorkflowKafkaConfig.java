package com.agenticai.core.eventbus;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * {@code @EnableKafka} is required for {@code @KafkaListener} methods (see {@code
 * WorkflowEventLogger}) to actually be registered - Spring Boot's autoconfiguration wires the
 * {@code KafkaTemplate} and listener container factory on its own once spring-kafka is on the
 * classpath, but listener *processing* still needs this annotation present somewhere.
 *
 * <p>{@code @Profile("workflow")}: with no profile active, this whole module's Kafka-facing pieces
 * (this config, the outbox relay, the logger consumer) simply never start - a core started with the
 * default "security" profile alone never attempts to reach Redpanda/Kafka at all, matching every
 * other per-feature profile in this codebase (mcp, knowledge, e2e).
 *
 * <p>#913/#914 (Kafka/queue reliability + scaling hardening):
 *
 * <ul>
 *   <li>{@link #workflowEventsTopic()} - explicit {@link NewTopic} bean for {@value
 *       WorkflowEventOutboxRelay#TOPIC} with {@value #PARTITION_COUNT} partitions. Before this, the
 *       topic auto-created with Redpanda's default (1 partition) on first publish, meaning every
 *       consumer group was structurally capped at a single active consumer regardless of {@code
 *       concurrency} - a genuine scaling ceiling, not just a config nicety.
 *   <li>{@link #workflowEventsDlt()} - the dead-letter topic {@link DeadLetterPublishingRecoverer}
 *       publishes to below.
 *   <li>{@link #workflowKafkaListenerContainerFactory} - replaces Spring Boot's default listener
 *       container factory with one wired for bounded retry-then-DLT ({@link DefaultErrorHandler} +
 *       {@link FixedBackOff} + {@link DeadLetterPublishingRecoverer}) and {@value
 *       #CONSUMER_CONCURRENCY} concurrent consumer threads (matching the new partition count) -
 *       previously a {@code @KafkaListener} like {@code WorkflowEventLogger} had no error handling
 *       at all: an exception thrown from listener code would just loop-retry the same record via
 *       the container's own default behavior, or in the worst case silently stall that partition's
 *       offset progress, with no visibility and no path to "give up and move on".
 * </ul>
 */
@Configuration
@Profile("workflow")
@ConditionalOnWorkflowKafka
@EnableKafka
public class WorkflowKafkaConfig {

    /**
     * Matches {@link #CONSUMER_CONCURRENCY} - no value in more partitions than a single consumer
     * group instance can ever concurrently consume at Phase 0's deployment topology (one core
     * instance per tenant-pool, not yet horizontally scaled). Raise both together if/when core is
     * deployed with multiple replicas sharing the "agentic-ai-core" consumer group.
     *
     * <p>Public since #916 (Kafka dashboard wiring) - {@code SystemStatusController} reads these
     * same constants for the dashboard's "how is Kafka configured" panel rather than hardcoding a
     * second copy.
     */
    public static final int PARTITION_COUNT = 3;

    public static final short REPLICATION_FACTOR = 1;
    private static final int CONSUMER_CONCURRENCY = 3;

    /**
     * Name of the dead-letter topic {@link DeadLetterPublishingRecoverer} below publishes to - also
     * read by {@code SystemStatusController} to look up the DLQ's live message count for the
     * dashboard.
     */
    public static final String DLT_TOPIC = WorkflowEventOutboxRelay.TOPIC + ".DLT";

    /**
     * One retry (2 total attempts) with a 2s gap, then DLT - short enough to not stall the
     * partition on a transient blip, bounded so a permanently broken record (bad JSON, a bug in
     * listener code) doesn't loop forever the way it silently could before this change.
     */
    public static final long RETRY_BACKOFF_MS = 2_000L;

    private static final long RETRY_ATTEMPTS = 1L;

    @Bean
    public NewTopic workflowEventsTopic() {
        return TopicBuilder.name(WorkflowEventOutboxRelay.TOPIC)
                .partitions(PARTITION_COUNT)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    @Bean
    public NewTopic workflowEventsDlt() {
        return TopicBuilder.name(DLT_TOPIC)
                .partitions(PARTITION_COUNT)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    @Bean
    public DefaultErrorHandler workflowKafkaErrorHandler(
            KafkaOperations<String, String> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaOperations,
                        (record, exception) ->
                                new TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        recoverer, new FixedBackOff(RETRY_BACKOFF_MS, RETRY_ATTEMPTS));
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            workflowKafkaListenerContainerFactory(
                    ConsumerFactory<String, String> consumerFactory,
                    DefaultErrorHandler workflowKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(CONSUMER_CONCURRENCY);
        factory.setCommonErrorHandler(workflowKafkaErrorHandler);
        return factory;
    }
}
