package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * The console surface ({@link A2aConsoleController} + the {@code console*} methods on {@link
 * A2aTaskService}): tenant-wide task visibility for a manager role, where the protocol surface
 * deliberately scopes to the owning subject.
 */
class A2aConsoleSurfaceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Principal ALICE = () -> "alice";

    private AgentChatService chat;
    private AuditLogService audit;
    private A2aTaskService service;
    private A2aConsoleController controller;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        audit = mock(AuditLogService.class);
        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        service =
                new A2aTaskService(
                        chat, new A2aTaskStore(Duration.ofHours(1), 100), audit, properties);
        controller = new A2aConsoleController(service, properties);
        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private A2aTask completedTask() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(r -> r.agentId() != null && r.userMessage() != null)))
                .thenReturn(Flux.just("answer"));
        A2aTask task = service.create(AGENT_ID, "ctx-1", "msg-1", "hello", ALICE);
        service.run(task, null).get(5, TimeUnit.SECONDS);
        return task;
    }

    @Test
    void consoleTasksListEverySubjectInTheTenant() throws Exception {
        A2aTask task = completedTask();
        task.userText();

        Map<String, Object> body = controller.tasks(null, null, null, 100).getBody();
        List<?> rows = (List<?>) body.get("tasks");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals(task.id(), row.get("id"));
        assertEquals("inbound", row.get("direction"));
        assertEquals("TASK_STATE_COMPLETED", row.get("state"));
        assertEquals("answer", row.get("reply"));
        assertEquals(false, row.get("replyTruncated"));
    }

    @Test
    void consoleGetAndCancelFollowTheTenantNotTheSubject() throws Exception {
        A2aTask task = completedTask();
        TenantContext.set(TenantContext.of("tenant-a", "manager", Set.of()));

        // A different subject in the same tenant can read and act on the record;
        // the same task in another tenant answers 404, never 403 (anti-enumeration,
        // same posture as the protocol surface).
        assertEquals(task.id(), controller.task(task.id()).getBody().get("id"));
        TenantContext.set(TenantContext.of("tenant-b", "manager", Set.of()));
        ResponseStatusException miss =
                assertThrows(ResponseStatusException.class, () -> controller.task(task.id()));
        assertEquals(HttpStatus.NOT_FOUND, miss.getStatusCode());
        TenantContext.set(TenantContext.of("tenant-a", "manager", Set.of()));

        ResponseStatusException gone =
                assertThrows(ResponseStatusException.class, () -> controller.cancel(task.id()));
        assertEquals(HttpStatus.CONFLICT, gone.getStatusCode());
    }

    @Test
    void consoleCancelMarksTheTaskAndAudits() {
        A2aTask task =
                service.create(AGENT_ID, "ctx-1", "msg-1", "hello", ALICE); // SUBMITTED, never run

        Map<String, Object> body = controller.cancel(task.id()).getBody();

        assertEquals("TASK_STATE_CANCELED", body.get("state"));
        assertTrue(task.isTerminal());
        verify(audit)
                .record(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        argThat((String d) -> d.contains("console")));
    }

    @Test
    void policyReportsEndpointsAndTheOutboundAllowList() {
        A2aProperties props = new A2aProperties();
        props.getClient().setEnabled(true);
        props.getClient().setAllowedHosts(new java.util.ArrayList<>(List.of("agents.example.com")));
        props.getClient().setMaxCallsPerTurn(3);
        A2aConsoleController c = new A2aConsoleController(service, props);

        Map<String, Object> body = c.policy().getBody();

        Map<?, ?> client = (Map<?, ?>) body.get("client");
        assertEquals(true, client.get("enabled"));
        assertEquals(List.of("agents.example.com"), client.get("allowedHosts"));
        assertEquals(3, client.get("maxCallsPerTurn"));
        Map<?, ?> server = (Map<?, ?>) body.get("server");
        assertEquals("/api/a2a", server.get("jsonRpcPath"));
        assertEquals("/api/a2a/rest", server.get("restPath"));
        assertEquals("/.well-known/agent-card.json", server.get("platformCardPath"));
    }
}
