package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The bounds of the fan-out tools ({@code app.agents.collaboration.*}) are settings, shared by both tools. */
class CollaborationLimitsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final UUID agentId = UUID.randomUUID();

    private DelegateToTeamToolCallback team(CollaborationProperties limits) {
        return new DelegateToTeamToolCallback(
                        chatService,
                        List.of(new DelegateToTeamToolCallback.Member(agentId, "Researcher", "researcher")),
                        null)
                .withLimits(limits);
    }

    private RunSubtasksToolCallback subtasks(CollaborationProperties limits) {
        return new RunSubtasksToolCallback(chatService, agentId, null, null, null, "chat-1").withLimits(limits);
    }

    private static String teamTasks(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"agent\":\"researcher\",\"question\":\"q").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static String subtaskBriefs(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"brief\":\"part ").append(i).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static long count(JsonNode results, String status) {
        long n = 0;
        for (JsonNode r : results) {
            if (status.equals(r.get("status").asText())) {
                n++;
            }
        }
        return n;
    }

    @Test
    void theOfferedSchemaStatesTheConfiguredBranchLimit() throws Exception {
        CollaborationProperties limits = new CollaborationProperties();
        limits.setMaxBranches(3);
        assertEquals(3, maxItems(team(limits).getToolDefinition().inputSchema()));
        assertEquals(3, maxItems(subtasks(limits).getToolDefinition().inputSchema()));
        limits.setMaxBranches(12);
        assertEquals(12, maxItems(team(limits).getToolDefinition().inputSchema()));
        assertEquals(12, maxItems(subtasks(limits).getToolDefinition().inputSchema()));
        assertEquals(8, maxItems(team(new CollaborationProperties()).getToolDefinition().inputSchema()));
        assertEquals(8, maxItems(subtasks(new CollaborationProperties()).getToolDefinition().inputSchema()));
    }

    private static int maxItems(String schema) throws Exception {
        return MAPPER.readTree(schema).get("properties").get("tasks").get("maxItems").asInt();
    }

    @Test
    void theShippedDefaultsAreTheBoundsBothToolsAlwaysHad() {
        CollaborationProperties p = new CollaborationProperties();
        assertEquals(8, p.getMaxBranches());
        assertEquals(240L, p.getBranchTimeoutSeconds());
        assertEquals(4000, p.getMaxAnswerChars());
        assertEquals(80, p.getMaxRoleChars());
    }

    @Test
    void theTeamBranchCapFollowsTheProperty() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any())).thenReturn("x");
        CollaborationProperties limits = new CollaborationProperties();
        limits.setMaxBranches(3);

        JsonNode results = MAPPER.readTree(team(limits).call(teamTasks(5))).get("results");

        assertEquals(3, count(results, "ok"));
        assertEquals(2, count(results, "skipped"));
    }

    @Test
    void theSubtaskWorkerCapFollowsTheProperty() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("x");
        CollaborationProperties limits = new CollaborationProperties();
        limits.setMaxBranches(2);

        JsonNode results = MAPPER.readTree(subtasks(limits).call(subtaskBriefs(4))).get("results");

        assertEquals(2, count(results, "ok"));
        assertEquals(2, results.size() - count(results, "ok"));
    }

    @Test
    void theAnswerCapFollowsThePropertyForBothTools() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any())).thenReturn("y".repeat(100));
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("y".repeat(100));
        CollaborationProperties limits = new CollaborationProperties();
        limits.setMaxAnswerChars(10);

        String teamAnswer =
                MAPPER.readTree(team(limits).call(teamTasks(1))).get("results").get(0).get("answer").asText();
        String subtaskAnswer =
                MAPPER.readTree(subtasks(limits).call(subtaskBriefs(1))).get("results").get(0).get("answer").asText();

        assertEquals("y".repeat(10) + "…", teamAnswer);
        assertEquals("y".repeat(10) + "…", subtaskAnswer);
    }

    @Test
    void theBranchTimeoutFollowsThePropertyForBothTools() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(chatService.delegateCall(any(), any(), any(), any())).thenAnswer(inv -> {
            release.await(30, TimeUnit.SECONDS);
            return "late";
        });
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            release.await(30, TimeUnit.SECONDS);
            return "late";
        });
        CollaborationProperties limits = new CollaborationProperties();
        limits.setBranchTimeoutSeconds(1);

        JsonNode teamResults = MAPPER.readTree(team(limits).call(teamTasks(1))).get("results");
        JsonNode subtaskResults = MAPPER.readTree(subtasks(limits).call(subtaskBriefs(1))).get("results");
        release.countDown();

        assertEquals("timeout", teamResults.get(0).get("status").asText());
        assertTrue(teamResults.get(0).get("error").asText().contains("1s"));
        assertEquals("timeout", subtaskResults.get(0).get("status").asText());
        assertTrue(subtaskResults.get(0).get("error").asText().contains("1s"));
    }
}
