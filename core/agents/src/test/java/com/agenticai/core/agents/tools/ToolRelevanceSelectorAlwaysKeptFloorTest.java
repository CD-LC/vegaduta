package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@link ToolRelevanceSelector}: the always-kept set can fill the whole count cap (a personal install keeps about
 * 15 names against a cap of 12). Before the floor the scored budget was then 0, so a request that plainly named
 * "search the web" was never offered a web tool - on any turn.
 */
class ToolRelevanceSelectorAlwaysKeptFloorTest extends ToolRelevanceSelectorTestSupport {

    private static List<ToolCallback> catalog() {
        return List.of(
                tool("remember_fact", "Remember a fact about the user"),
                tool("recall_related_facts", "Recall facts"),
                tool("request_human", "Ask a person"),
                tool("vegaduta_setup_guide", "Explain how to set the platform up"),
                tool("web_search", "Search the web for current information"),
                tool("fetch_web_page", "Fetch the text of a web page"),
                tool("channel_send_message", "Send a message on a channel"),
                tool("data_table_query", "Query a data table"));
    }

    private static final Set<String> ALWAYS =
            Set.of("remember_fact", "recall_related_facts", "request_human", "vegaduta_setup_guide");

    @Test
    void aRequestThatNamesAToolStillReachesItWhenTheAlwaysKeptSetFillsTheCap() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 4, 8000);

        List<String> names =
                selector.select("search the web for the latest node lts", catalog(), ALWAYS, Set.of()).stream()
                        .map(t -> t.getToolDefinition().name())
                        .toList();

        assertTrue(names.containsAll(ALWAYS), "the always-kept tools are untouched: " + names);
        assertTrue(names.contains("web_search"), "the matching tool is offered: " + names);
        assertTrue(
                names.size() <= ALWAYS.size() + ToolRelevanceSelector.MIN_SCORED_TOOLS_WHEN_ALWAYS_KEPT_FILLS_BUDGET,
                "the floor is small: " + names);
    }

    @Test
    void aTurnWithNoMatchStillGetsNoExtraTools() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 4, 8000);

        List<String> names =
                selector.select("hello there", catalog(), ALWAYS, Set.of()).stream()
                        .map(t -> t.getToolDefinition().name())
                        .toList();

        assertEquals(ALWAYS.size(), names.size(), "no match, no floor: " + names);
        assertFalse(names.contains("web_search"));
    }
}
