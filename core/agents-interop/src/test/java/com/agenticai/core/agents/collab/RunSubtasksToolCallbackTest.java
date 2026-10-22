package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.DelegateOptions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RunSubtasksToolCallback}: every brief runs as a delegated turn of the same agent, in
 * parallel, with an isolated session id and a worker tool filter; the cap, timeout and failure
 * cases come back as entries, not as a thrown call.
 */
class RunSubtasksToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final UUID agentId = UUID.randomUUID();

    private RunSubtasksToolCallback tool() {
        return new RunSubtasksToolCallback(chatService, agentId, null, null, null, "chat-1");
    }

    private static String tasks(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"brief\":\"do part ").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void runsEveryBriefAsADelegatedTurnOfTheSameAgent() throws Exception {
        when(chatService.delegateCall(eq(agentId), any(), any(), any(), any())).thenReturn("done");

        String out =
                tool().call(
                                "{\"tasks\":[{\"brief\":\"find prices\",\"role\":\"researcher\"},"
                                        + "{\"brief\":\"draft email\"}]}");

        JsonNode root = MAPPER.readTree(out);
        assertNotNull(root.get("runId"));
        JsonNode results = root.get("results");
        assertEquals(2, results.size());
        assertEquals("ok", results.get(0).get("status").asString());
        assertEquals("researcher", results.get(0).get("role").asString());
        assertEquals("done", results.get(0).get("answer").asString());
        assertFalse(results.get(1).has("role"));

        ArgumentCaptor<String> questions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessions = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(2))
                .delegateCall(eq(agentId), questions.capture(), any(), sessions.capture(), any());
        assertTrue(questions.getAllValues().containsAll(List.of("find prices", "draft email")));
        for (String session : sessions.getAllValues()) {
            assertTrue(session.startsWith("delegated:subtasks:"), session);
        }
        assertEquals(2, sessions.getAllValues().stream().distinct().count());
    }

    @Test
    void branchesRunInParallel() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(chatService.delegateCall(eq(agentId), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            bothStarted.countDown();
                            // Would deadlock into the assertion below if branches ran one by one.
                            assertTrue(bothStarted.await(10, TimeUnit.SECONDS));
                            return "ok";
                        });

        JsonNode results = MAPPER.readTree(tool().call(tasks(2))).get("results");

        assertEquals("ok", results.get(0).get("status").asString());
        assertEquals("ok", results.get(1).get("status").asString());
    }

    @Test
    void workersGetADirectiveAndNoDelegationOrShellWriteTools() {
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("x");

        tool().call("{\"tasks\":[{\"brief\":\"b\",\"role\":\"analyst\"}]}");

        ArgumentCaptor<DelegateOptions> options = ArgumentCaptor.forClass(DelegateOptions.class);
        verify(chatService).delegateCall(any(), any(), any(), any(), options.capture());
        assertTrue(options.getValue().systemAddendum().contains("Your role: analyst."));
        var filter = options.getValue().toolFilter();
        for (String banned :
                List.of(
                        "run_subtasks",
                        "delegate_parallel",
                        "delegate_to_specialist",
                        "ask_agent_billing",
                        "schedule_task",
                        "request_human",
                        "coding_task",
                        "local_shell")) {
            assertFalse(filter.test(banned), banned);
        }
        for (String allowed :
                List.of("web_search", "fetch_web_page", "local_shell_read", "run_code")) {
            assertTrue(filter.test(allowed), allowed);
        }
    }

    @Test
    void overTheCapBriefsAreReportedSkipped() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("x");

        JsonNode results = MAPPER.readTree(tool().call(tasks(10))).get("results");

        assertEquals(10, results.size());
        long ok = 0;
        long skipped = 0;
        for (JsonNode r : results) {
            if ("ok".equals(r.get("status").asString())) ok++;
            if ("skipped".equals(r.get("status").asString())) skipped++;
        }
        assertEquals(8, ok);
        assertEquals(2, skipped);
        verify(chatService, times(8)).delegateCall(any(), any(), any(), any(), any());
    }

    @Test
    void aSlowWorkerBecomesATimeoutEntry() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(chatService.delegateCall(eq(agentId), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            if ("do part 0".equals(inv.getArgument(1))) {
                                release.await(30, TimeUnit.SECONDS);
                            }
                            return "fast";
                        });
        RunSubtasksToolCallback slow =
                new RunSubtasksToolCallback(chatService, agentId, null, null, null, "chat-1", 1);

        JsonNode results = MAPPER.readTree(slow.call(tasks(2))).get("results");
        release.countDown();

        boolean sawTimeout = false;
        for (JsonNode r : results) {
            if ("timeout".equals(r.get("status").asString())) {
                sawTimeout = true;
                assertTrue(r.get("error").asString().contains("1s"));
            }
        }
        assertTrue(sawTimeout);
    }

    @Test
    void aFailingWorkerIsAnErrorEntryAndTheOthersStillReturn() throws Exception {
        when(chatService.delegateCall(eq(agentId), eq("bad"), any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));
        when(chatService.delegateCall(eq(agentId), eq("good"), any(), any(), any()))
                .thenReturn("fine");

        String out =
                tool().call(
                                "{\"tasks\":[{\"brief\":\"bad\"},{\"brief\":\"good\"},{\"brief\":\" \"}]}");

        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals("error", results.get(0).get("status").asString());
        assertTrue(results.get(0).get("error").asString().contains("provider down"));
        assertEquals("ok", results.get(1).get("status").asString());
        assertEquals("error", results.get(2).get("status").asString());
        assertEquals(
                RunSubtasksToolCallback.ERR_BRIEF_REQUIRED, results.get(2).get("error").asString());
    }

    @Test
    void longAnswersAreCapped() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any(), any()))
                .thenReturn("y".repeat(RunSubtasksToolCallback.MAX_ANSWER_CHARS * 2));

        JsonNode results = MAPPER.readTree(tool().call(tasks(1))).get("results");

        assertTrue(
                results.get(0).get("answer").asString().length()
                        <= RunSubtasksToolCallback.MAX_ANSWER_CHARS + 100);
    }

    @Test
    void emptyOrMalformedInputIsAnErrorEnvelope() throws Exception {
        for (String input : List.of("{}", "{\"tasks\":[]}", "not json")) {
            JsonNode root = MAPPER.readTree(tool().call(input));
            assertTrue(root.has("error"), input);
        }
        verify(chatService, times(0)).delegateCall(any(), any(), any(), any(), any());
    }

    @Test
    void theToolIsNamedAndCappedInItsSchema() {
        var definition = tool().getToolDefinition();
        assertEquals("run_subtasks", definition.name());
        assertTrue(definition.inputSchema().contains("\"maxItems\": 8"));
    }
}
