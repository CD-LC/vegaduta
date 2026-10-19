package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code emitAgentMessageCompleted} is called from threads with no {@link TenantContext} (the
 * stream-completion thread, a stream-end pool thread). The publish must run with the explicit
 * tenant bound, and the thread must be left as it was found.
 */
class AgentMessageEventsTenantTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishRunsUnderTheExplicitTenantAndTheThreadIsCleanAfterwards() throws Exception {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        List<Optional<String>> seen = new ArrayList<>();
        doAnswer(
                        inv -> {
                            seen.add(TenantContext.current().map(TenantContext::getTenantId));
                            return null;
                        })
                .when(publisher)
                .publish(anyString(), any(), any(Map.class), anyString());
        AgentMessageEvents events = new AgentMessageEvents();
        events.setPublisher(publisher);

        Optional<TenantContext> after =
                CompletableFuture.supplyAsync(
                                () -> {
                                    assertTrue(TenantContext.current().isEmpty());
                                    events.emitAgentMessageCompleted(
                                            UUID.randomUUID(), "s1", "tenant_a", "hello", "live");
                                    return TenantContext.current();
                                })
                        .get();

        assertEquals(List.of(Optional.of("tenant_a")), seen);
        assertTrue(after.isEmpty());
    }
}
