package com.agenticai.core.eventbus;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Logs, once at start-up, that the workflow-event broker is switched off ({@code
 * app.workflow-events.kafka.enabled=false}). It replaces a stream of "connection to localhost:9092
 * could not be established" warnings from the topic admin, the outbox relay and the consumers, none
 * of which are created in this state (see {@link ConditionalOnWorkflowKafka}).
 *
 * <p>Only the literal {@code false} switches the broker pieces off ({@code ConditionalOnProperty}
 * matching); values such as {@code no} or {@code 0} are not treated as false.
 */
@Component
@Profile("workflow")
@ConditionalOnProperty(
        prefix = "app.workflow-events.kafka",
        name = "enabled",
        havingValue = "false")
public class WorkflowKafkaDisabledNotice {

    private static final Logger log = LoggerFactory.getLogger(WorkflowKafkaDisabledNotice.class);

    private final boolean eventsEnabled;

    public WorkflowKafkaDisabledNotice(
            @Value("${app.workflow-events.enabled:false}") boolean eventsEnabled) {
        this.eventsEnabled = eventsEnabled;
    }

    @PostConstruct
    void announce() {
        log.info(
                "Workflow-event Kafka is OFF (app.workflow-events.kafka.enabled=false: either"
                    + " WORKFLOW_KAFKA_ENABLED=false or no bootstrap server resolved): no topic"
                    + " admin, outbox relay or consumers are started. Set"
                    + " WORKFLOW_KAFKA_BOOTSTRAP_SERVERS (Helm: global.kafka.bootstrapServers, or a"
                    + " key in the app Secret) to turn it on.");
        if (eventsEnabled) {
            log.warn(
                    "app.workflow-events.enabled=true while workflow-event Kafka is OFF: workflow"
                            + " events are written to the outbox but nothing drains it, and the"
                            + " status/health checks probe a broker that is not in use. Unset"
                            + " WORKFLOW_EVENTS_ENABLED or set WORKFLOW_KAFKA_ENABLED=true.");
        }
    }
}
