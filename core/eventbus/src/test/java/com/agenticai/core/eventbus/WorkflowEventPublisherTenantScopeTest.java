package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The explicit-tenant {@code publish} overload exists for callers on a thread with NO ambient
 * {@link TenantContext} (the streaming completion thread). The in-JVM listeners it fans out to
 * (e.g. {@code WebhookDispatchListener}) resolve their schema from the publishing thread's context,
 * so the overload must apply the tenant for the dispatch and put the thread back exactly as it
 * found it.
 */
class WorkflowEventPublisherTenantScopeTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static WorkflowEventPublisher publisher(
            ApplicationEventPublisher events,
            WorkflowEventOutboxRepository outbox,
            boolean enabled) {
        return new WorkflowEventPublisher(
                outbox, com.agenticai.core.json.JsonMappers.withJavaTime(), enabled, events);
    }

    @Test
    void listenerSeesTheExplicitTenantOnAContextlessThreadAndTheThreadIsCleanAfterwards()
            throws Exception {
        List<Optional<String>> seenByListener = new ArrayList<>();
        ApplicationEventPublisher listener =
                e -> seenByListener.add(TenantContext.current().map(TenantContext::getTenantId));
        WorkflowEventPublisher publisher =
                publisher(listener, Mockito.mock(WorkflowEventOutboxRepository.class), false);

        Optional<TenantContext> after =
                CompletableFuture.supplyAsync(
                                () -> {
                                    assertTrue(TenantContext.current().isEmpty());
                                    publisher.publish(
                                            "agent.message.completed", "s1", Map.of(), "tenant_a");
                                    return TenantContext.current();
                                })
                        .get();

        assertEquals(List.of(Optional.of("tenant_a")), seenByListener);
        assertTrue(after.isEmpty(), "the publishing thread must not keep tenant_a's context");
    }

    @Test
    void outboxWriteAlsoRunsUnderTheExplicitTenant() throws Exception {
        WorkflowEventOutboxRepository outbox = Mockito.mock(WorkflowEventOutboxRepository.class);
        List<Optional<String>> seenByOutbox = new ArrayList<>();
        Mockito.when(outbox.save(Mockito.any()))
                .thenAnswer(
                        inv -> {
                            seenByOutbox.add(
                                    TenantContext.current().map(TenantContext::getTenantId));
                            return inv.getArgument(0);
                        });
        WorkflowEventPublisher publisher = publisher(e -> {}, outbox, true);

        CompletableFuture.runAsync(() -> publisher.publish("x.y", "r1", Map.of(), "tenant_a"))
                .get();

        assertEquals(List.of(Optional.of("tenant_a")), seenByOutbox);
    }

    @Test
    void anOuterDifferentTenantIsRestoredNotClobbered() {
        List<Optional<String>> seen = new ArrayList<>();
        WorkflowEventPublisher publisher =
                publisher(
                        e -> seen.add(TenantContext.current().map(TenantContext::getTenantId)),
                        Mockito.mock(WorkflowEventOutboxRepository.class),
                        false);
        TenantContext outer = TenantContext.of("tenant_outer", "u", Set.of());
        TenantContext.set(outer);

        publisher.publish("x.y", "r1", Map.of(), "tenant_a");

        assertEquals(List.of(Optional.of("tenant_a")), seen);
        assertEquals(Optional.of(outer), TenantContext.current());
    }

    @Test
    void aMatchingAmbientContextIsLeftInForce() {
        TenantContext rich = TenantContext.of("tenant_a", "alice", Set.of("ADMIN"));
        List<Optional<TenantContext>> seen = new ArrayList<>();
        WorkflowEventPublisher publisher =
                publisher(
                        e -> seen.add(TenantContext.current()),
                        Mockito.mock(WorkflowEventOutboxRepository.class),
                        false);
        TenantContext.set(rich);

        publisher.publish("x.y", "r1", Map.of(), "tenant_a");

        assertEquals(List.of(Optional.of(rich)), seen);
    }

    @Test
    void aListenerFailureStillRestoresTheThread() {
        WorkflowEventPublisher publisher =
                publisher(
                        e -> {
                            throw new IllegalStateException("boom");
                        },
                        Mockito.mock(WorkflowEventOutboxRepository.class),
                        false);

        publisher.publish("x.y", "r1", Map.of(), "tenant_a");

        assertTrue(TenantContext.current().isEmpty());
    }
}
