package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.turn.AgentChatStreamController;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The edit / regenerate controller around the ordinary chat call: a turn that ends without appending anything
 * (refusal, upstream failure, client cancel) must hand the conversation back to its previous branch, and do it
 * before the end of the stream reaches the client.
 */
class ChatBranchControllerTest {

    private static final String SESSION = "sess-1";

    private final UUID agentId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final UUID previousLeaf = UUID.randomUUID();
    private final ChatBranchService service = mock(ChatBranchService.class);
    private final AgentChatStreamController agentController = mock(AgentChatStreamController.class);
    private ChatBranchController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatBranchController(service, agentController);
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("tenant_user")));
        when(service.prepareRegenerate(agentId, SESSION, messageId))
            .thenReturn(new ChatBranchService.PreparedTurn(SESSION, "q", previousLeaf));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void chatReturns(Flux<String> body) {
        when(agentController.chat(eq(agentId), any(ChatRequest.class), any(), any())).thenReturn(ResponseEntity.ok(body));
    }

    private Flux<String> regenerate() {
        ResponseEntity<Flux<String>> response = controller.regenerate(agentId, SESSION, messageId, null, null);
        assertNotNull(response.getBody());
        return response.getBody();
    }

    @Test
    void aRefusalThatAppendsNothingIsReconciledBeforeTheStreamEndsForTheClient() {
        chatReturns(Flux.just("I can't help with that."));
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<String> tenantSeen = new AtomicReference<>();
        doAnswer(inv -> {
            order.add("restore");
            tenantSeen.set(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            return null;
        }).when(service).restoreIfNothingAppended(agentId, SESSION, previousLeaf);

        Flux<String> body = regenerate();
        // Nothing happens until the client subscribes.
        verify(service, never()).restoreIfNothingAppended(any(), any(), any());

        // The terminal signal arrives on a thread that has no TenantContext of its own.
        List<String> received = body
            .subscribeOn(Schedulers.boundedElastic())
            .doOnComplete(() -> order.add("client-sees-end"))
            .collectList()
            .block();

        assertEquals(List.of("I can't help with that."), received);
        assertEquals(List.of("restore", "client-sees-end"), order);
        assertEquals("t1", tenantSeen.get(), "the captured tenant context is re-applied for the database work");
        verify(service, never()).restoreLeaf(any(), any(), any());
    }

    @Test
    void aStreamThatFailsAfterSubscriptionIsReconciledOnceAndTheErrorStillReachesTheClient() {
        chatReturns(Flux.error(new IllegalStateException("upstream down")));

        List<String> received = regenerate().onErrorReturn("failed").collectList().block();

        assertEquals(List.of("failed"), received);
        verify(service, times(1)).restoreIfNothingAppended(agentId, SESSION, previousLeaf);
    }

    @Test
    void aClientThatCancelsTheStreamIsReconciledToo() {
        chatReturns(Flux.never());

        Disposable subscription = regenerate().subscribe();
        verify(service, never()).restoreIfNothingAppended(any(), any(), any());
        subscription.dispose();

        verify(service, times(1)).restoreIfNothingAppended(agentId, SESSION, previousLeaf);
    }

    @Test
    void aStreamThatCompletesNormallyIsReconciledExactlyOnce() {
        chatReturns(Flux.just("a", "b"));

        assertEquals(List.of("a", "b"), regenerate().collectList().block());

        verify(service, times(1)).restoreIfNothingAppended(agentId, SESSION, previousLeaf);
    }

    @Test
    void aTurnRefusedBeforeItStartsStillUsesTheSynchronousUndo() {
        when(agentController.chat(eq(agentId), any(ChatRequest.class), any(), any()))
            .thenThrow(new IllegalArgumentException("quota"));

        assertThrows(IllegalArgumentException.class,
            () -> controller.regenerate(agentId, SESSION, messageId, null, null));

        verify(service).restoreLeaf(agentId, SESSION, previousLeaf);
        verify(service, never()).restoreIfNothingAppended(any(), any(), any());
        assertTrue(TenantContext.current().isPresent());
    }
}
