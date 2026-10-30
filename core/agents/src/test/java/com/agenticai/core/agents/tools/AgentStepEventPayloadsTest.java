package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentStepEventPayloads} builds the single-line JSON step events the emitter puts on the
 * wire. These pin each event's {@code type} and machine-value fields, the optional-field rules
 * (absent keys, not nulls), and the framing requirement the class javadoc calls out: a payload is
 * always ONE line, because Spring's SSE writer splits a chunk on {@code \n}.
 */
class AgentStepEventPayloadsTest {

    @Test
    void heartbeatCarriesOnlyElapsedTimeWhenTheStartIsKnown() {
        Map<String, Object> e = AgentStepEventPayloads.heartbeat(null);
        assertEquals("heartbeat", e.get("type"));
        assertFalse(e.containsKey("ms"));

        Map<String, Object> timed = AgentStepEventPayloads.heartbeat(Instant.now().minusMillis(50));
        assertEquals("heartbeat", timed.get("type"));
        assertTrue(timed.get("ms") instanceof Long);
        assertTrue((Long) timed.get("ms") >= 0);
    }

    @Test
    void phaseNamesTheStageAndTheElapsedTime() {
        Map<String, Object> e = AgentStepEventPayloads.phase("guardrails", 123L);

        assertEquals("phase", e.get("type"));
        assertEquals("guardrails", e.get("phase"));
        assertEquals(123L, e.get("ms"));
    }

    @Test
    void toolEventsCarryIdToolAndRedactedText() {
        Map<String, Object> call =
                AgentStepEventPayloads.toolCall(7, "web_search", "{\"q\":\"x\"}");
        assertEquals("tool_call", call.get("type"));
        assertEquals(7, call.get("id"));
        assertEquals("web_search", call.get("tool"));
        assertTrue(call.containsKey("args"));

        Map<String, Object> result =
                AgentStepEventPayloads.toolResult(7, "web_search", "some output", 42L, null);
        assertEquals("tool_result", result.get("type"));
        assertTrue(result.containsKey("preview"));
        assertFalse(result.containsKey("app"), "no app metadata - the key is absent, not null");

        Map<String, Object> withApp =
                AgentStepEventPayloads.toolResult(7, "t", "o", 0L, Map.of("name", "gmail"));
        assertEquals(Map.of("name", "gmail"), withApp.get("app"));

        Map<String, Object> error = AgentStepEventPayloads.toolError(7, "t", "boom", 5L);
        assertEquals("error", error.get("type"));
        assertTrue(error.containsKey("message"));
    }

    @Test
    void runStartedAndHandoffRenderIdsAsStrings() {
        UUID agent = UUID.randomUUID();
        Map<String, Object> started = AgentStepEventPayloads.runStarted("s-1", agent);

        assertEquals("run_started", started.get("type"));
        assertEquals("s-1", started.get("sessionId"));
        assertEquals(agent.toString(), started.get("agentId"));
        assertNull(
                AgentStepEventPayloads.runStarted("s", null).get("agentId"),
                "a null agent id stays a JSON null, never the string \"null\"");

        UUID to = UUID.randomUUID();
        Map<String, Object> handoff = AgentStepEventPayloads.handoff(agent, to);
        assertEquals("handoff", handoff.get("type"));
        assertEquals(agent.toString(), handoff.get("fromAgent"));
        assertEquals(to.toString(), handoff.get("toAgent"));
    }

    @Test
    void memoryRecallCopiesLabelsAndFiltersNullFactIds() {
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
        UUID real = UUID.randomUUID();
        ids.add(real);
        ids.add(null);

        Map<String, Object> e =
                AgentStepEventPayloads.memoryRecall(2, List.of("private", "shared"), 3, 1, ids);

        assertEquals("memory", e.get("type"));
        assertEquals(2, e.get("blocks"));
        assertEquals(List.of("private", "shared"), e.get("blockLabels"));
        assertEquals(3, e.get("privateFacts"));
        assertEquals(1, e.get("sharedFacts"));
        assertEquals(List.of(real.toString()), e.get("factIds"));

        Map<String, Object> none = AgentStepEventPayloads.memoryRecall(0, null, 0, 0, null);
        assertEquals(List.of(), none.get("blockLabels"));
        assertEquals(List.of(), none.get("factIds"));
    }

    @Test
    void swarmSummarisesResultsToStatusesOnly() {
        UUID member = UUID.randomUUID();
        Map<String, Object> e =
                AgentStepEventPayloads.swarm(
                        "done",
                        "run-1",
                        List.of("a", "b"),
                        List.of(
                                Map.of(
                                        "agent",
                                        member.toString(),
                                        "status",
                                        "ok",
                                        "ms",
                                        12L,
                                        "answer",
                                        "SECRET")));

        assertEquals("swarm", e.get("type"));
        assertEquals("done", e.get("phase"));
        assertEquals("run-1", e.get("runId"));
        assertEquals(List.of("a", "b"), e.get("members"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) e.get("results");
        assertEquals(1, results.size());
        assertEquals(member.toString(), results.get(0).get("agent"));
        assertEquals("ok", results.get(0).get("status"));
        assertEquals(12L, results.get(0).get("ms"));
        assertFalse(results.get(0).containsKey("answer"), "the full answers stay out of the trace");
    }

    @Test
    void lifecycleEventsCarryMachineValuesAndDropBlankOptionals() {
        Map<String, Object> finish = AgentStepEventPayloads.finish("complete", null);
        assertEquals("finish", finish.get("type"));
        assertEquals("complete", finish.get("finishReason"));
        assertFalse(finish.containsKey("detail"));

        Map<String, Object> finishDetail =
                AgentStepEventPayloads.finish("refused", "content_filter");
        assertTrue(finishDetail.containsKey("detail"));

        Map<String, Object> retry = AgentStepEventPayloads.retry(2, 3, "timeout");
        assertEquals("retry", retry.get("type"));
        assertEquals(2, retry.get("attempt"));
        assertEquals(3, retry.get("maxAttempts"));
        assertEquals("timeout", retry.get("reason"));

        Map<String, Object> failover = AgentStepEventPayloads.failover(null, "dead");
        assertEquals("failover", failover.get("type"));
        assertFalse(failover.containsKey("toModel"));

        Map<String, Object> cont = AgentStepEventPayloads.continuation(1, 3, " ");
        assertEquals("continuation", cont.get("type"));
        assertFalse(cont.containsKey("reason"), "a blank reason is no reason");

        Map<String, Object> approval =
                AgentStepEventPayloads.approvalRequired("send_email", "req-9");
        assertEquals("approval_required", approval.get("type"));
        assertEquals("send_email", approval.get("tool"));
        assertEquals("req-9", approval.get("approvalId"));
    }

    @Test
    void contextCompactedRendersItsOwnSummary() {
        Map<String, Object> e = AgentStepEventPayloads.contextCompacted(9000, 3000, 4, 2);

        assertEquals("context_compacted", e.get("type"));
        assertEquals(9000, e.get("tokensBefore"));
        assertEquals(3000, e.get("tokensAfter"));
        assertEquals(4, e.get("cleared"));
        assertEquals(2, e.get("compacted"));
        assertEquals(
                "context 9000 -> 3000 tokens; cleared 4 older tool result(s); summarised 2 older message(s)",
                e.get("summary"));

        Map<String, Object> quiet = AgentStepEventPayloads.contextCompacted(100, 50, 0, 0);
        assertEquals("context 100 -> 50 tokens", quiet.get("summary"));
    }

    @Test
    void everyPayloadSerialisesToASingleLine() throws Exception {
        UUID id = UUID.randomUUID();
        List<Map<String, Object>> events =
                List.of(
                        AgentStepEventPayloads.heartbeat(Instant.now()),
                        AgentStepEventPayloads.phase("model", 1L),
                        AgentStepEventPayloads.toolCall(1, "t\nwith-newline", "{}"),
                        AgentStepEventPayloads.toolResult(1, "t", "o\nu\nt", 0L, Map.of("a", 1)),
                        AgentStepEventPayloads.toolError(1, "t", "m\n", 0L),
                        AgentStepEventPayloads.runStarted("s", id),
                        AgentStepEventPayloads.memoryRecall(1, List.of("l"), 1, 1, List.of(id)),
                        AgentStepEventPayloads.runFinished(9L),
                        AgentStepEventPayloads.plan("p"),
                        AgentStepEventPayloads.thinking("t"),
                        AgentStepEventPayloads.guardrail("t", "r"),
                        AgentStepEventPayloads.hook("pre", "h", "a", "d", "t", "r"),
                        AgentStepEventPayloads.handoff(id, id),
                        AgentStepEventPayloads.swarm(
                                "p",
                                "r",
                                List.of("m"),
                                List.of(Map.of("agent", "a", "status", "ok"))),
                        AgentStepEventPayloads.toolSearch("q", 2, List.of("t")),
                        AgentStepEventPayloads.finish("complete", "d"),
                        AgentStepEventPayloads.retry(1, 2, "r"),
                        AgentStepEventPayloads.failover("m", "r"),
                        AgentStepEventPayloads.continuation(1, 2, "r"),
                        AgentStepEventPayloads.approvalRequired("t", "a"),
                        AgentStepEventPayloads.contextCompacted(1, 2, 3, 4));

        for (Map<String, Object> event : events) {
            String json = AgentStepEventPayloads.toJson(event);
            assertFalse(
                    json.contains("\n"),
                    "an SSE chunk split on \\n breaks the marker prefix: " + json);
            assertTrue(json.contains("\"type\""), json);
        }
    }
}
