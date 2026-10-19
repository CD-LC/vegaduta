package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A prompt's chat turn is subscribed on a pooled Reactor scheduler thread with the call's tenant
 * applied, and that thread must be left exactly as it was found. The terminal signal of a streamed
 * reply is delivered on a different thread, so clearing the tenant from {@code doFinally} (which is
 * what this used to do) never cleaned the scheduler thread - it kept the tenant and ran the next,
 * unrelated, task under it.
 */
class VoiceRelayWebSocketHandlerTenantScopeTest {

    private static final String TENANT_SCHEMA = "tenant_voice_a";
    private static final UUID AGENT = UUID.randomUUID();

    private AgentChatService chat;
    private VoiceRelayWebSocketHandler handler;
    private WebSocketSession session;
    private Map<String, Object> attributes;
    private Scheduler promptThread;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        handler =
                new VoiceRelayWebSocketHandler(
                        mock(TenantResolutionService.class),
                        mock(ChannelSessionService.class),
                        chat,
                        mock(VoiceDtmfMappingService.class),
                        mock(VoiceNumberAgentService.class),
                        mock(VoiceRelayTicketService.class));
        // One dedicated thread standing in for the bounded-elastic pool, so the test can ask that
        // very thread afterwards what it is holding.
        promptThread = Schedulers.newSingle("voice-relay-prompt-test");
        handler.promptScheduler = promptThread;

        attributes = new HashMap<>();
        attributes.put("voiceRelay.tenantSchema", TENANT_SCHEMA);
        attributes.put("voiceRelay.agentId", AGENT);
        attributes.put("voiceRelay.memorySessionId", "memory-1");
        attributes.put("voiceRelay.from", "+919876543210");
        attributes.put("voiceRelay.callSid", "CA1");
        session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        promptThread.dispose();
        TenantContext.clear();
    }

    @Test
    void theChatTurnIsStartedUnderTheCallsTenantAndTheSchedulerThreadIsLeftClean()
            throws Exception {
        AtomicReference<String> tenantAtAssembly = new AtomicReference<>();
        AtomicReference<String> tenantAtSubscription = new AtomicReference<>();
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "memory-1")
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenAnswer(
                        inv -> {
                            tenantAtAssembly.set(currentTenant());
                            return Flux.defer(
                                            () -> {
                                                tenantAtSubscription.set(currentTenant());
                                                return Flux.just("Hello there. ");
                                            })
                                    // The reply ends on another thread, like a streamed provider
                                    // response.
                                    .delayElements(Duration.ofMillis(20));
                        });
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(
                        inv -> {
                            TextMessage message = inv.getArgument(0);
                            if (message.getPayload().contains("\"last\":true")) {
                                finished.countDown();
                            }
                            return null;
                        })
                .when(session)
                .sendMessage(any(TextMessage.class));

        handler.handleTextMessage(
                session,
                new TextMessage("{\"type\":\"prompt\",\"voicePrompt\":\"hi\",\"last\":true}"));

        assertTrue(finished.await(5, TimeUnit.SECONDS), "the turn never finished");
        assertEquals(TENANT_SCHEMA, tenantAtAssembly.get());
        assertEquals(TENANT_SCHEMA, tenantAtSubscription.get());
        assertEquals(
                "NONE",
                currentTenantOn(promptThread),
                "the scheduler thread must not keep the tenant after the subscribe");
        assertTrue(TenantContext.current().isEmpty(), "the websocket thread is left as it was");
    }

    /**
     * ADR-0022 stage 4: the adapter builds the turn context itself, and on the thread that calls
     * the entry the context is exactly the ThreadLocal value (same instance), so the context and
     * the JDBC boundary can never disagree about the tenant.
     */
    @Test
    void theAdapterBuildsTheTurnContextEqualToTheThreadLocalOnTheCallingThread() throws Exception {
        AtomicReference<TurnContext> passed = new AtomicReference<>();
        AtomicReference<TenantContext> threadLocalAtEntry = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            passed.set(inv.getArgument(0));
                            threadLocalAtEntry.set(TenantContext.current().orElse(null));
                            called.countDown();
                            return Flux.just("Hello there. ");
                        });

        handler.handleTextMessage(
                session,
                new TextMessage("{\"type\":\"prompt\",\"voicePrompt\":\"hi\",\"last\":true}"));

        assertTrue(called.await(5, TimeUnit.SECONDS), "the turn never reached the entry");
        TurnContext context = passed.get();
        assertEquals(TENANT_SCHEMA, context.tenantId());
        assertTrue(context.subject().endsWith("+919876543210"), context.subject());
        assertTrue(
                context.tenant() == threadLocalAtEntry.get(),
                "the context must be the very TenantContext the calling thread holds");
        assertEquals(AGENT, context.requestedAgentId());
        assertEquals("memory-1", context.sessionId());
    }

    private static String currentTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("NONE");
    }

    private static String currentTenantOn(Scheduler scheduler) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        scheduler.schedule(
                () -> {
                    seen.set(currentTenant());
                    done.countDown();
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return Optional.ofNullable(seen.get()).orElse("?");
    }
}
