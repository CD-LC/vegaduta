package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parallel fan-out semantics for {@link DelegateToTeamToolCallback}: every branch runs {@code
 * delegateCall} with an isolated session id that shares the swarm run prefix, unknown members fail
 * per-branch without sinking the call, and the branch cap reports skips rather than dropping them.
 */
class DelegateToTeamToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final UUID aId = UUID.randomUUID();
    private final UUID bId = UUID.randomUUID();

    private DelegateToTeamToolCallback team() {
        return new DelegateToTeamToolCallback(
                chatService,
                List.of(
                        new DelegateToTeamToolCallback.Member(aId, "Researcher", "researcher"),
                        new DelegateToTeamToolCallback.Member(bId, "Writer", "writer")),
                null);
    }

    @Test
    void fansOutToBothMembersAndJoinsAnswers() throws Exception {
        when(chatService.delegateCall(eq(aId), any(), any(), any())).thenReturn("answer A");
        when(chatService.delegateCall(eq(bId), any(), any(), any())).thenReturn("answer B");

        String out =
                team().call(
                                "{\"tasks\":["
                                        + "{\"agent\":\"researcher\",\"question\":\"find facts\"},"
                                        + "{\"agent\":\"writer\",\"question\":\"draft it\",\"contextSummary\":\"short\"}]}");

        JsonNode root = MAPPER.readTree(out);
        assertNotNull(root.get("runId"));
        JsonNode results = root.get("results");
        assertEquals(2, results.size());
        for (JsonNode r : results) {
            assertEquals("ok", r.get("status").asString());
        }
    }

    @Test
    void branchSessionsShareTheSwarmRunPrefix() {
        when(chatService.delegateCall(any(), any(), any(), any())).thenReturn("x");

        team().call(
                        "{\"tasks\":["
                                + "{\"agent\":\"researcher\",\"question\":\"q1\"},"
                                + "{\"agent\":\"writer\",\"question\":\"q2\"}]}");

        ArgumentCaptor<String> sessions = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(2)).delegateCall(any(), any(), any(), sessions.capture());
        List<String> ids = sessions.getAllValues();
        assertTrue(ids.get(0).startsWith("delegated:swarm:"));
        assertTrue(ids.get(1).startsWith("delegated:swarm:"));
        // same run id, distinct branch indexes
        String runA =
                ids.get(0).substring("delegated:swarm:".length(), ids.get(0).lastIndexOf(':'));
        String runB =
                ids.get(1).substring("delegated:swarm:".length(), ids.get(1).lastIndexOf(':'));
        assertEquals(runA, runB);
        assertTrue(
                ids.get(0).endsWith(":0") && ids.get(1).endsWith(":1")
                        || ids.get(0).endsWith(":1") && ids.get(1).endsWith(":0"));
    }

    @Test
    void unknownMemberFailsOnlyItsOwnBranch() throws Exception {
        when(chatService.delegateCall(eq(bId), any(), any(), any())).thenReturn("fine");

        String out =
                team().call(
                                "{\"tasks\":["
                                        + "{\"agent\":\"nobody\",\"question\":\"q\"},"
                                        + "{\"agent\":\"writer\",\"question\":\"q\"}]}");

        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals(2, results.size());
        boolean sawError = false;
        boolean sawOk = false;
        for (JsonNode r : results) {
            if ("error".equals(r.get("status").asString())) {
                sawError = true;
                assertEquals("nobody", r.get("agent").asString());
            }
            if ("ok".equals(r.get("status").asString())) {
                sawOk = true;
            }
        }
        assertTrue(sawError && sawOk);
    }

    @Test
    void overLimitBranchesAreReportedSkipped() throws Exception {
        when(chatService.delegateCall(any(), any(), any(), any())).thenReturn("x");

        StringBuilder tasks = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < 10; i++) {
            tasks.append(i == 0 ? "" : ",")
                    .append("{\"agent\":\"researcher\",\"question\":\"q")
                    .append(i)
                    .append("\"}");
        }
        String out = team().call(tasks + "]}");

        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals(10, results.size());
        long skipped = 0;
        long ok = 0;
        for (JsonNode r : results) {
            if ("skipped".equals(r.get("status").asString())) skipped++;
            if ("ok".equals(r.get("status").asString())) ok++;
        }
        assertEquals(8, ok);
        assertEquals(2, skipped);
    }

    @Test
    void aFailingBranchIsAnErrorEntryNotAThrownCall() throws Exception {
        when(chatService.delegateCall(eq(aId), any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));
        when(chatService.delegateCall(eq(bId), any(), any(), any())).thenReturn("fine");

        String out =
                team().call(
                                "{\"tasks\":["
                                        + "{\"agent\":\"researcher\",\"question\":\"q\"},"
                                        + "{\"agent\":\"writer\",\"question\":\"q\"}]}");

        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals(2, results.size());
        boolean sawError = false;
        for (JsonNode r : results) {
            if ("error".equals(r.get("status").asString())) {
                sawError = true;
                assertTrue(r.get("error").asString().contains("provider down"));
            }
        }
        assertTrue(sawError);
    }

    @Test
    void branchesActuallyRunInParallel() throws Exception {
        // Both branches block on a latch the other releases - sequential
        // execution would deadlock; parallel completes.
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        when(chatService.delegateCall(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            order.add("start");
                            both.countDown();
                            both.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            return "done";
                        });

        String out =
                team().call(
                                "{\"tasks\":["
                                        + "{\"agent\":\"researcher\",\"question\":\"q\"},"
                                        + "{\"agent\":\"writer\",\"question\":\"q\"}]}");

        assertEquals(2, order.size());
        JsonNode results = MAPPER.readTree(out).get("results");
        assertEquals(2, results.size());
    }

    @Test
    void emptyTasksIsAnError() throws Exception {
        JsonNode root = MAPPER.readTree(team().call("{\"tasks\":[]}"));
        assertTrue(root.has("error"));
    }
}
