package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The terminal audit row of an A2A task is written from the reply stream's completion callback,
 * which runs on whichever Reactor/HTTP thread ends the stream - a thread with no {@code
 * TenantContext}. {@code audit_log_entry} is a tenant table, so the row must be written under the
 * tenant the task was run under, not under whatever the delivering thread holds.
 */
class A2aTaskServiceTenantTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Principal ALICE = () -> "alice";

    private AgentChatService chat;
    private AuditLogService audit;
    private A2aTaskService service;
    private Scheduler delivery;
    private final List<String> tenantSeenByAudit = new CopyOnWriteArrayList<>();
    private CountDownLatch audited;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        audit = mock(AuditLogService.class);
        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        service =
                new A2aTaskService(
                        chat, new A2aTaskStore(Duration.ofHours(1), 100), audit, properties);
        // The thread that delivers the reply stream's terminal signal: not the caller's, no tenant.
        delivery = Schedulers.newSingle("a2a-test-delivery");
        audited = new CountDownLatch(1);
        doAnswer(
                        inv -> {
                            tenantSeenByAudit.add(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("NONE"));
                            audited.countDown();
                            return null;
                        })
                .when(audit)
                .record(anyString(), anyString(), anyString(), anyString(), any(), anyString());
        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        delivery.dispose();
    }

    private void replyWith(Flux<String> tokens) {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(tokens);
    }

    private void runToTheEnd() throws Exception {
        A2aTask task = service.create(AGENT_ID, "ctx-1", "msg-1", "hello", ALICE);
        service.run(task, null).get(5, TimeUnit.SECONDS);
        assertTrue(audited.await(5, TimeUnit.SECONDS), "the terminal audit row was never written");
    }

    @Test
    void theCompletedAuditRowIsWrittenUnderTheTasksTenant() throws Exception {
        replyWith(Flux.just("hi", " there").publishOn(delivery));

        runToTheEnd();

        assertEquals(List.of("tenant-a"), tenantSeenByAudit);
    }

    @Test
    void theFailedAuditRowIsWrittenUnderTheTasksTenant() throws Exception {
        replyWith(
                Flux.<String>error(new IllegalStateException("provider down")).publishOn(delivery));

        runToTheEnd();

        assertEquals(List.of("tenant-a"), tenantSeenByAudit);
    }

    @Test
    void theCallersThreadIsLeftAsItWas() throws Exception {
        replyWith(Flux.just("hi").publishOn(delivery));

        runToTheEnd();

        assertEquals("tenant-a", TenantContext.current().orElseThrow().getTenantId());
    }
}
