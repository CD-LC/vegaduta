package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/**
 * Test support for the ADR-0022 stage 4 caller tests: stubs {@link AgentChatService#chat} on a mock
 * to answer one chunk, and records the {@link TurnContext} the adapter passed together with the
 * {@link TenantContext} that was on the calling thread at that moment, so a test can assert the two
 * are the same instance.
 */
public final class TurnContextProbe {

    private final AtomicReference<TurnContext> context = new AtomicReference<>();
    private final AtomicReference<ChatTurnRequest> request = new AtomicReference<>();
    private final AtomicReference<TenantContext> threadLocal = new AtomicReference<>();
    private volatile int calls;

    /** Stubs {@code chat(TurnContext, ChatTurnRequest)} on the mock and returns the probe. */
    public static TurnContextProbe on(AgentChatService chat) {
        TurnContextProbe probe = new TurnContextProbe();
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            probe.context.set(inv.getArgument(0));
                            probe.request.set(inv.getArgument(1));
                            probe.threadLocal.set(TenantContext.current().orElse(null));
                            probe.calls++;
                            return Flux.just("hi");
                        });
        return probe;
    }

    public TurnContext context() {
        assertEquals(1, calls, "expected exactly one chat turn");
        return context.get();
    }

    public ChatTurnRequest request() {
        return request.get();
    }

    /**
     * Asserts the turn context carries the tenant the adapter resolved, that this is the very
     * instance on the calling thread's ThreadLocal at the chat call, and that the principal is the
     * adapter's.
     */
    public void assertTenant(String tenantSchema, String subject) {
        TurnContext turn = context();
        assertNotNull(turn, "the adapter passed no TurnContext");
        assertEquals(tenantSchema, turn.tenantId());
        assertEquals(subject, turn.subject());
        TenantContext onThread = threadLocal.get();
        assertNotNull(onThread, "no TenantContext on the calling thread at the chat call");
        assertSame(
                onThread,
                turn.tenantContext().orElse(null),
                "the context must be the ThreadLocal instance");
    }

    /** The adapter must leave the calling thread without a tenant. */
    public static void assertThreadClean() {
        assertTrue(TenantContext.current().isEmpty(), "the calling thread was left with a tenant");
    }
}
