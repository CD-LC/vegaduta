package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * B6: with the "workflow" profile active, {@code app.workflow-events.enabled=false} used to leave the relay
 * running (only the publisher honoured the flag). It must not even be created when the flag is off.
 */
class WorkflowEventOutboxRelayEnabledFlagTest {

    @Configuration
    @Import(WorkflowEventOutboxRelay.class)
    static class Deps {
        @Bean TenantSchemaIterator iterator() { return mock(TenantSchemaIterator.class); }
        @Bean WorkflowEventOutboxRepository repo() { return mock(WorkflowEventOutboxRepository.class); }
        @SuppressWarnings("unchecked")
        @Bean KafkaTemplate<String, String> kafka() { return mock(KafkaTemplate.class); }
        @Bean PlatformIssueService issues() { return mock(PlatformIssueService.class); }
        @Bean PgAdvisoryJobLock lock() { return mock(PgAdvisoryJobLock.class); }
        @Bean MeterRegistry meters() { return new SimpleMeterRegistry(); }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(Deps.class)
            .withInitializer(ctx -> ctx.getEnvironment().addActiveProfile("workflow"));
    }

    @Test
    void relayIsNotCreatedWhenTheFlagIsOff() {
        runner().withPropertyValues("app.workflow-events.enabled=false")
            .run(ctx -> assertEquals(0, ctx.getBeansOfType(WorkflowEventOutboxRelay.class).size()));
    }

    @Test
    void relayIsNotCreatedWhenTheFlagIsAbsent() {
        runner().run(ctx -> assertEquals(0, ctx.getBeansOfType(WorkflowEventOutboxRelay.class).size()));
    }

    @Test
    void relayIsCreatedWhenTheFlagIsOn() {
        runner().withPropertyValues("app.workflow-events.enabled=true")
            .run(ctx -> assertEquals(1, ctx.getBeansOfType(WorkflowEventOutboxRelay.class).size()));
    }
}
