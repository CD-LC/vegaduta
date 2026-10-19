package com.agenticai.core.common.systemstatus;

import java.util.List;

/**
 * "System health like a spaceship data center" - HUD Dashboard rebuild.
 *
 * <p>Every value here is either read straight off a live Spring Boot
 * actuator {@code HealthContributor}, a Hikari connection-pool MXBean, the
 * JVM's own {@code MemoryMXBean}/{@code RuntimeMXBean}, or the existing
 * {@code McpRegistryService} - nothing in this response is a placeholder or
 * an invented number, matching this project's standing rule (see
 * page.js/dashboard.css comments: "never invent a number to fill the
 * space"). Where a real value genuinely can't be determined (e.g. Hikari's
 * pool MXBean is null before the pool has initialized its first
 * connection), the field is {@code null} and the frontend is expected to
 * render an explicit "unavailable" state, not a fabricated zero.
 *
 * <p>{@code kafka} (#912/#916) runs a real, live {@code
 * AdminClient.describeCluster()} probe (see {@code
 * SystemStatusController#kafkaStatus} for why Spring Boot Actuator's own
 * Kafka indicator can't be used - it was removed from core Actuator years
 * ago) against whatever broker {@code spring.kafka.bootstrap-servers}
 * points at, independent of whether this platform's own {@code
 * app.workflow-events.enabled} feature flag happens to be on. That means it
 * can genuinely read DOWN even while the workflow event feature is
 * administratively disabled - still an accurate, real reading of "can this
 * process reach the broker," not a fabricated one. #916 additionally
 * attaches static config (topic names, partitions, consumer group, retry
 * policy) and live queue/DLQ depth - see {@link KafkaStatus}'s own javadoc.
 */
public record SystemStatusResponse(
    String overallStatus, // NOMINAL | DEGRADED | CRITICAL
    DatabaseStatus database,
    ComponentStatus redis,
    KafkaStatus kafka,
    ComponentStatus diskSpace,
    JvmStatus jvm,
    McpStatus mcp,
    LlmStatus llm, // real call-count/latency captured by LlmMetricsAdvisor since this process started
    ComponentStatus sidecar // Python embeddings/extraction microservice - "NOT_CONFIGURED" when app.sidecar.base-url is unset
) {

    public record DatabaseStatus(
        String status,
        String product,
        Integer activeConnections,
        Integer idleConnections,
        Integer totalConnections,
        Integer maxPoolSize,
        Integer threadsAwaitingConnection
    ) {
    }

    public record ComponentStatus(String status) {
    }

    // Direct feedback: "capture any metrics like latency, how many LLM
    // calls were made" - real, in-process totals from LlmCallMetricsService,
    // fed by every ChatClient call this app makes (see LlmMetricsAdvisor).
    // avgLatencyMs is null (not zero) until at least one call has completed,
    // matching this response's own "never fabricate a value" convention.
    public record LlmStatus(long totalCalls, long errorCount, Double avgLatencyMs) {
    }

    public record JvmStatus(
        long heapUsedMb,
        Long heapMaxMb, // null when the JVM reports no defined max (-1)
        long uptimeSeconds,
        int availableProcessors
    ) {
    }

    public record McpStatus(int totalServers, int enabledServers, List<McpServerSummary> servers) {
    }

    public record McpServerSummary(String name, String type, boolean enabled) {
    }

    /**
     * #916 (Kafka dashboard wiring: "show how kafka is configured and
     * queues, DLQ") - unlike the plain {@link ComponentStatus} used for
     * redis/diskSpace/sidecar (a single up/down/unknown reading
     * with nothing else to show), Kafka has three genuinely different things
     * worth surfacing: how it's WIRED ({@link KafkaConfig} - topic names,
     * partition count, consumer group, retry policy; static, doesn't change
     * unless someone redeploys), how full the QUEUE currently is ({@link
     * KafkaQueue} - outbox rows in THIS tenant's own {@code
     * workflow_event_outbox} table that are still waiting to be published,
     * vs. ones that have exhausted their retries - see {@code
     * WorkflowEventOutboxRelay}'s javadoc), and the DLQ itself ({@link
     * KafkaDlq} - the real {@code workflow.events.DLT} Kafka topic's current
     * message count, which is a different thing from the dead-lettered
     * outbox rows: a {@code DefaultErrorHandler} on the *consumer* side can
     * also route a bad record there independent of the outbox relay).
     */
    public record KafkaStatus(
        String status, // UP | DOWN | NOT_CONFIGURED | UNKNOWN - unchanged shape/meaning from the ComponentStatus field this replaces
        KafkaConfig config,
        KafkaQueue queue,
        KafkaDlq dlq
    ) {

        public record KafkaConfig(
            String bootstrapServers, // null when KafkaAdmin isn't configured at all (feature never turned on)
            String topic,
            String dltTopic,
            int partitions,
            short replicationFactor,
            String consumerGroup,
            int maxRetries,
            long retryBackoffMs
        ) {
        }

        // Read from THIS tenant's own workflow_event_outbox table -
        // schema-per-tenant multitenancy routes the query automatically
        // (see TenantContext), so this is never a cross-tenant aggregate;
        // this dashboard is already scoped to one tenant per request.
        public record KafkaQueue(long pendingCount, long deadLetteredCount) {
        }

        // messageCount is a best-effort reading (latest-offset minus
        // earliest-offset, summed across partitions) of the real Kafka
        // workflow.events.DLT topic - bound by that topic's own retention
        // window, not a lifetime total. Null when it can't be determined
        // (broker unreachable, or the topic hasn't been created yet).
        public record KafkaDlq(String topic, Long messageCount) {
        }
    }
}
