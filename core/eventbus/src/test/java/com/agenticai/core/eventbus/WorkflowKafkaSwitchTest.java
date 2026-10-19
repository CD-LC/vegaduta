package com.agenticai.core.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;

/**
 * {@code app.workflow-events.kafka.enabled=false} keeps the "workflow" profile on but starts
 * nothing that talks to a broker (topic admin, consumers); absent or true is the unchanged
 * behaviour.
 */
class WorkflowKafkaSwitchTest {

    @Configuration
    @Import(WorkflowKafkaConfig.class)
    static class ConfigDeps {
        @SuppressWarnings("unchecked")
        @Bean
        KafkaOperations<String, String> kafkaOperations() {
            return mock(KafkaOperations.class);
        }

        @SuppressWarnings("unchecked")
        @Bean
        ConsumerFactory<String, String> consumerFactory() {
            return mock(ConsumerFactory.class);
        }
    }

    @Configuration
    @Import({WorkflowEventLogger.class, WorkflowKafkaDisabledNotice.class})
    static class Beans {}

    private ApplicationContextRunner runner(Class<?> config) {
        return new ApplicationContextRunner()
                .withUserConfiguration(config)
                .withInitializer(ctx -> ctx.getEnvironment().addActiveProfile("workflow"));
    }

    @Test
    void kafkaPiecesStartWhenThePropertyIsAbsent() {
        runner(Beans.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(WorkflowEventLogger.class);
                            assertThat(ctx).doesNotHaveBean(WorkflowKafkaDisabledNotice.class);
                        });
        runner(ConfigDeps.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(WorkflowKafkaConfig.class));
    }

    @Test
    void theBeansThatReachTheBrokerCarryTheSwitch() {
        // The relay and the topic initializer actually connect to the broker. Building them needs
        // a database and Kafka, so lock the annotation they share instead.
        assertThat(WorkflowEventOutboxRelay.class).hasAnnotation(ConditionalOnWorkflowKafka.class);
        assertThat(WorkflowKafkaTopicBackgroundInit.class)
                .hasAnnotation(ConditionalOnWorkflowKafka.class);
        assertThat(WorkflowEventLogger.class).hasAnnotation(ConditionalOnWorkflowKafka.class);
        assertThat(WorkflowKafkaConfig.class).hasAnnotation(ConditionalOnWorkflowKafka.class);
    }

    @Test
    void noticeAlsoStartsWhenEventsAreEnabledAlongsideTheDisabledBroker() {
        runner(Beans.class)
                .withPropertyValues(
                        "app.workflow-events.kafka.enabled=false",
                        "app.workflow-events.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(WorkflowKafkaDisabledNotice.class));
    }

    @Test
    void kafkaPiecesStartWhenThePropertyIsTrue() {
        runner(Beans.class)
                .withPropertyValues("app.workflow-events.kafka.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(WorkflowEventLogger.class));
    }

    @Test
    void nothingTalksToTheBrokerWhenThePropertyIsFalseAndTheNoticeIsLoggedOnce() {
        runner(Beans.class)
                .withPropertyValues("app.workflow-events.kafka.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(WorkflowEventLogger.class);
                            assertThat(ctx).hasSingleBean(WorkflowKafkaDisabledNotice.class);
                        });
        runner(ConfigDeps.class)
                .withPropertyValues("app.workflow-events.kafka.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(WorkflowKafkaConfig.class);
                            assertThat(ctx)
                                    .doesNotHaveBean(org.apache.kafka.clients.admin.NewTopic.class);
                        });
    }
}
