package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.agents.a2a.A2aTaskService;
import com.agenticai.core.agents.a2a.A2aTaskStore;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lifecycle of the task-style tools over the REAL {@link A2aTaskService} and {@link A2aTaskStore}
 * (only the chat turn is mocked): submitted/working -> completed | failed | cancelled, plus
 * per-grant ownership and the exposed-agent gate.
 */
class McpAgentTaskToolCallbacksTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private AgentChatService chat;
    private A2aTaskService service;
    private ToolCallback start;
    private ToolCallback get;
    private ToolCallback cancel;

    private final UUID grantId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        A2aProperties props = new A2aProperties();
        A2aTaskStore store =
                new A2aTaskStore(
                        props,
                        (ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate>)
                                mock(ObjectProvider.class));
        service = new A2aTaskService(chat, store, mock(AuditLogService.class), props);
        List<ToolCallback> tools =
                McpAgentTaskToolCallbacks.create(service, mock(AuditLogService.class));
        start = tools.get(0);
        get = tools.get(1);
        cancel = tools.get(2);
        bind(grantId);
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
        TenantContext.clear();
    }

    private void bind(UUID grant) {
        McpCallerContextTestAccess.set(grant, Set.of(agentId), Set.of(), Set.of());
        TenantContext.set(
                TenantContext.of("tenant_a", "mcp-external:" + grant, Set.of("ROLE_mcp-external")));
    }

    private JsonNode json(ToolCallback tool, String input) throws Exception {
        return MAPPER.readTree(tool.call(input));
    }

    private JsonNode startTask() throws Exception {
        return json(start, "{\"agent_id\":\"" + agentId + "\",\"message\":\"hi\"}");
    }

    private void awaitTerminal(String taskId) throws Exception {
        service.get(taskId, null).completion().get(5, TimeUnit.SECONDS);
    }

    @Test
    void toolNamesAreStable() {
        assertEquals("start_agent_task", start.getToolDefinition().name());
        assertEquals("get_task", get.getToolDefinition().name());
        assertEquals("cancel_task", cancel.getToolDefinition().name());
    }

    @Test
    void completedLifecycleReturnsReply() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Hel", "lo"));
        JsonNode started = startTask();
        String taskId = started.get("task_id").asString();
        assertTrue(
                Set.of("submitted", "working", "completed")
                        .contains(started.get("state").asString()));
        assertTrue(started.get("session_id").asString().startsWith("mcp-" + grantId));

        awaitTerminal(taskId);
        JsonNode done = json(get, "{\"task_id\":\"" + taskId + "\"}");
        assertEquals("completed", done.get("state").asString());
        assertEquals("Hello", done.get("reply").asString());
        assertFalse(done.has("error"));
    }

    @Test
    void failedLifecycleCarriesError() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.error(new RuntimeException("boom")));
        String taskId = startTask().get("task_id").asString();
        awaitTerminal(taskId);
        JsonNode failed = json(get, "{\"task_id\":\"" + taskId + "\"}");
        assertEquals("failed", failed.get("state").asString());
        assertNotNull(failed.get("error"));
    }

    @Test
    void cancelStopsWorkingTaskThenIsNotCancelableAgain() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        String taskId = startTask().get("task_id").asString();
        // Wait until the virtual thread has actually moved it to working.
        long deadline = System.currentTimeMillis() + 5000;
        String state = "";
        while (System.currentTimeMillis() < deadline) {
            state = json(get, "{\"task_id\":\"" + taskId + "\"}").get("state").asString();
            if ("working".equals(state)) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals("working", state);

        JsonNode canceled = json(cancel, "{\"task_id\":\"" + taskId + "\"}");
        assertEquals("cancelled", canceled.get("state").asString());
        assertEquals(
                "cancelled", json(get, "{\"task_id\":\"" + taskId + "\"}").get("state").asString());
        JsonNode again = json(cancel, "{\"task_id\":\"" + taskId + "\"}");
        assertTrue(again.get("error").asString().contains("cannot be cancelled"));
    }

    @Test
    void anotherGrantCannotReadOrCancelTheTask() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        String taskId = startTask().get("task_id").asString();

        bind(UUID.randomUUID());
        assertTrue(
                json(get, "{\"task_id\":\"" + taskId + "\"}")
                        .get("error")
                        .asString()
                        .contains("not found"));
        assertTrue(
                json(cancel, "{\"task_id\":\"" + taskId + "\"}")
                        .get("error")
                        .asString()
                        .contains("not found"));
    }

    @Test
    void unknownTaskIsNotFound() throws Exception {
        assertTrue(
                json(get, "{\"task_id\":\"nope\"}").get("error").asString().contains("not found"));
    }

    @Test
    void agentOutsideExposedSetIsRejectedBeforeAnyTurnStarts() throws Exception {
        JsonNode res =
                json(start, "{\"agent_id\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\"}");
        assertTrue(res.get("error").asString().contains("not authorized"));
        verify(chat, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void missingFieldsAndMissingCallerAreHandled() throws Exception {
        assertTrue(json(start, "{\"agent_id\":\"" + agentId + "\"}").has("error"));
        assertTrue(json(get, "{}").has("error"));
        assertTrue(json(cancel, "{}").has("error"));
        McpCallerContextTestAccess.clear();
        assertThrows(IllegalStateException.class, () -> get.call("{\"task_id\":\"x\"}"));
    }

    private JsonNode startTaskWith(ToolCallback startTool) throws Exception {
        return json(startTool, "{\"agent_id\":\"" + agentId + "\",\"message\":\"hi\"}");
    }

    @Test
    void perTenantCapRejectsTheNextTaskWithAClearErrorAndFreesTheSlotOnCancel() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        ToolCallback cappedStart =
                McpAgentTaskToolCallbacks.create(service, mock(AuditLogService.class), 1).get(0);

        JsonNode first = startTaskWith(cappedStart);
        assertFalse(first.has("error"));

        JsonNode second = startTaskWith(cappedStart);
        assertTrue(second.get("error").asString().contains("limit 1"), second.toString());

        json(cancel, "{\"task_id\":\"" + first.get("task_id").asString() + "\"}");
        assertFalse(
                startTaskWith(cappedStart).has("error"),
                "the slot is released once the task is terminal");
    }

    @Test
    void capIsPerTenant() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        ToolCallback cappedStart =
                McpAgentTaskToolCallbacks.create(service, mock(AuditLogService.class), 1).get(0);
        assertFalse(startTaskWith(cappedStart).has("error"));

        TenantContext.set(
                TenantContext.of(
                        "tenant_b", "mcp-external:" + grantId, Set.of("ROLE_mcp-external")));

        assertFalse(
                startTaskWith(cappedStart).has("error"), "another tenant has its own allowance");
    }

    @Test
    void zeroCapDisablesTheLimit() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        ToolCallback uncapped =
                McpAgentTaskToolCallbacks.create(service, mock(AuditLogService.class), 0).get(0);
        for (int i = 0; i < 6; i++) {
            assertFalse(startTaskWith(uncapped).has("error"));
        }
    }

    @Test
    void runningTaskCarriesAPollHintAndFinishedTaskDoesNot() throws Exception {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.never());
        JsonNode started = startTask();
        assertEquals(
                McpAgentTaskToolCallbacks.POLL_AFTER_SECONDS,
                started.get("poll_after_seconds").asInt());
        String taskId = started.get("task_id").asString();

        JsonNode canceled = json(cancel, "{\"task_id\":\"" + taskId + "\"}");
        assertFalse(canceled.has("poll_after_seconds"));
    }

    @Test
    void tenantCapForgetsTenantsWithNoRunningTasks() {
        McpAgentTaskToolCallbacks.TenantTaskCap cap =
                new McpAgentTaskToolCallbacks.TenantTaskCap(1);
        Runnable r1 = cap.tryAcquire("t1");
        assertNotNull(r1);
        assertEquals(null, cap.tryAcquire("t1"), "second slot for the same tenant is refused");
        assertEquals(1, cap.size());
        r1.run();
        r1.run();
        assertEquals(
                0, cap.size(), "released tenants are evicted so the map cannot grow without bound");
        assertNotNull(cap.tryAcquire("t1"));
    }
}
