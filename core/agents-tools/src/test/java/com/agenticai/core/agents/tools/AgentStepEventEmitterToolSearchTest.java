package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import com.agenticai.core.agents.tools.search.ToolSearchService;
import com.agenticai.core.agents.tools.search.ToolSearchState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The {@code tool_search} step event: "searched tools: <query> -> n", emitted when search_tools
 * runs inside a traced turn.
 */
class AgentStepEventEmitterToolSearchTest {

    private static final String MARKER = AgentStepEventEmitter.STEP_EVENT_MARKER;

    private static ToolCallback tool(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    private static List<String> subscribe(AgentStepEventEmitter emitter) {
        List<String> chunks = new CopyOnWriteArrayList<>();
        emitter.asFlux().subscribe(chunks::add);
        return chunks;
    }

    @Test
    void toolSearchEmitsTheSummaryLineTheTraceShows() {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        List<String> chunks = subscribe(emitter);

        emitter.toolSearch("send email", 2, List.of("google_gmail", "google_calendar"));

        assertEquals(1, chunks.size());
        String chunk = chunks.get(0);
        assertTrue(chunk.startsWith(MARKER), chunk);
        assertTrue(chunk.contains("\"type\":\"tool_search\""), chunk);
        assertTrue(chunk.contains("\"summary\":\"searched tools: send email -> 2\""), chunk);
        assertTrue(chunk.contains("\"matched\":2"), chunk);
        assertTrue(chunk.contains("\"tools\":[\"google_gmail\",\"google_calendar\"]"), chunk);
        assertTrue(chunk.indexOf('\n') < 0, "a step event is always a single line");
    }

    @Test
    void aDisabledEmitterEmitsNothing() {
        AgentStepEventEmitter.disabled().toolSearch("anything", 1, List.of("x"));

        assertTrue(subscribe(AgentStepEventEmitter.disabled()).isEmpty());
    }

    @Test
    void searchToolsRunningInsideATracedTurnEmitsTheEventBetweenToolCallAndToolResult() {
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        deferred.put(
                "google_gmail",
                tool("google_gmail", "Reads, searches and sends Gmail email messages"));
        ToolSearchState state =
                new ToolSearchState(Map.of(), deferred, new ToolSearchService(), null, "k", 0);
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        List<String> chunks = subscribe(emitter);
        ToolCallback traced =
                new StepEventToolCallback(new SearchToolsToolCallback(state), emitter);

        traced.call("{\"query\":\"send email\"}");

        assertEquals(3, chunks.size(), chunks.toString());
        assertTrue(chunks.get(0).contains("\"type\":\"tool_call\""), chunks.get(0));
        assertTrue(
                chunks.get(1).contains("\"summary\":\"searched tools: send email -> 1\""),
                chunks.get(1));
        assertTrue(chunks.get(2).contains("\"type\":\"tool_result\""), chunks.get(2));
    }

    @Test
    void searchToolsOutsideATracedTurnStillWorksWithoutAnEmitter() {
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        deferred.put(
                "google_gmail",
                tool("google_gmail", "Reads, searches and sends Gmail email messages"));
        ToolSearchState state =
                new ToolSearchState(Map.of(), deferred, new ToolSearchService(), null, "k", 0);

        String result = new SearchToolsToolCallback(state).call("{\"query\":\"send email\"}");

        assertTrue(result.contains("google_gmail"), result);
    }
}
