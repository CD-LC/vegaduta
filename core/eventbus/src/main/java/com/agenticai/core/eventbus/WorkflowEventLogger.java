package com.agenticai.core.eventbus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Interactive Workflow Designer PRD, Phase 0's Definition of Done: "prove the pipe end to end
 * before anything depends on it." This is that proof - a consumer with no business logic at all,
 * whose only job is to log every event that makes it through Postgres (outbox) -> {@link
 * WorkflowEventOutboxRelay} -> Kafka -> here.
 *
 * <p>Once Phase 1's WorkflowExecutionEngine ships as a real consumer group on this same topic, this
 * class can either stay (as a permanent audit-style tap on the topic, harmless to run alongside a
 * real consumer since Kafka consumer groups are independent) or be deleted - not a decision Phase 0
 * needs to make.
 *
 * <p>#913/#914: explicitly pinned to {@code containerFactory =
 * "workflowKafkaListenerContainerFactory"} ({@link WorkflowKafkaConfig}) - without this, Spring
 * Boot silently falls back to its own default listener container factory (no retry cap, no DLT
 * recoverer), and this consumer would keep none of the reliability hardening that bean provides. A
 * bad record here now gets one retry then lands on {@code workflow.events.DLT} instead of stalling
 * this consumer group's offset indefinitely.
 */
@Component
@Profile("workflow")
@ConditionalOnWorkflowKafka
public class WorkflowEventLogger {

    /**
     * Public since #916 (Kafka dashboard wiring) - {@code SystemStatusController} reads this same
     * constant for the dashboard's config panel rather than hardcoding a second copy of the group
     * id.
     */
    public static final String CONSUMER_GROUP = "workflow-event-logger";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventLogger.class);

    @KafkaListener(
            topics = WorkflowEventOutboxRelay.TOPIC,
            groupId = CONSUMER_GROUP,
            containerFactory = "workflowKafkaListenerContainerFactory")
    public void onEvent(String envelopeJson) {
        log.info("[workflow.events] {}", envelopeJson);
    }
}
