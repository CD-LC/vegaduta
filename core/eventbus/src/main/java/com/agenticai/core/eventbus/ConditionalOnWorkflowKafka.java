package com.agenticai.core.eventbus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean that talks to the Kafka-compatible broker for workflow events (topic creation, the
 * outbox relay, the consumers). The bean exists unless {@code
 * app.workflow-events.kafka.enabled=false}, so with the property absent behaviour is exactly what
 * it was.
 *
 * <p>When {@code WORKFLOW_KAFKA_ENABLED} is not set, core switches it off at start-up if no
 * bootstrap server resolved from any source: the {@code workflow} profile stays on (the workflow
 * engine needs it) but nothing tries to reach a broker that does not exist. See {@link
 * WorkflowKafkaDisabledNotice}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(
        prefix = "app.workflow-events.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public @interface ConditionalOnWorkflowKafka {}
