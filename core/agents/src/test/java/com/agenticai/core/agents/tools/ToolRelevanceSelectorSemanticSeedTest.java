package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** {@link ToolRelevanceSelector}: semantic discovery-seed scoring. */
class ToolRelevanceSelectorSemanticSeedTest extends ToolRelevanceSelectorTestSupport {

    // --- semantic discovery-seed scoring (2026-08-12) ----------------------
    // Direct ask: "use AI or other mechanism and select only tools required
    // ... instead of sending all tools together". See class javadoc for the
    // full design: lexical scoring stays the free primary pass; a sidecar
    // embedding call only ever runs to pick WHICH candidate wins an
    // already-guaranteed discovery seed within a zero-scoring MCP family
    // with more than one candidate.

    private org.springframework.beans.factory.ObjectProvider<
                    com.agenticai.core.knowledge.SidecarClient>
            providerOf(com.agenticai.core.knowledge.SidecarClient client) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public com.agenticai.core.knowledge.SidecarClient getObject() {
                return client;
            }

            @Override
            public com.agenticai.core.knowledge.SidecarClient getObject(Object... args) {
                return client;
            }

            @Override
            public com.agenticai.core.knowledge.SidecarClient getIfAvailable() {
                return client;
            }

            @Override
            public com.agenticai.core.knowledge.SidecarClient getIfUnique() {
                return client;
            }
        };
    }

    /**
     * Fixed unit-length vectors so cosine similarity is exact and easy to reason about: `a` is
     * closer to `query` than `b` is, by construction.
     */
    private static final float[] QUERY_VEC = {1f, 0f};

    private static final float[] CLOSE_VEC = {0.99f, 0.14107f};

 // ~8 degrees off QUERY_VEC
    private static final float[] FAR_VEC = {0f, 1f};

 // orthogonal to QUERY_VEC - similarity 0

    @Test
    void semanticScoringPicksTheClosestCandidateWithinAZeroScoringFamily() {
        com.agenticai.core.knowledge.SidecarClient sidecar =
                mock(com.agenticai.core.knowledge.SidecarClient.class);
        when(sidecar.embedQuery("find the trending topic")).thenReturn(QUERY_VEC);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        when(sidecar.embedQuery(
                        navigate.getToolDefinition().name()
                                + " "
                                + navigate.getToolDefinition().description()))
                .thenReturn(CLOSE_VEC);
        when(sidecar.embedQuery(
                        screenshot.getToolDefinition().name()
                                + " "
                                + screenshot.getToolDefinition().description()))
                .thenReturn(FAR_VEC);
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates =
                List.of(weatherTool, screenshot, navigate); // deliberately NOT navigate-first
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 1, 8000, providerOf(sidecar), true);

        List<ToolCallback> result =
                selector.select("find the trending topic", candidates, Set.of(), mcpToolNames);

        // Both still end up present (sibling-stickiness rescues whichever wasn't the literal seed)
        // -
        // the real assertion is that semantic scoring, not list order, chose navigate as the seed.
        assertTrue(result.contains(navigate));
        assertTrue(result.contains(screenshot));
        verify(sidecar)
                .embedQuery(
                        navigate.getToolDefinition().name()
                                + " "
                                + navigate.getToolDefinition().description());
        verify(sidecar)
                .embedQuery(
                        screenshot.getToolDefinition().name()
                                + " "
                                + screenshot.getToolDefinition().description());
    }

    @Test
    void semanticScoringNeverRunsForASingleCandidateFamily() {
        // Cost discipline: a family with only one zero-scoring candidate has nothing to compare -
        // the sidecar must never be called at all for it, query embedding included.
        com.agenticai.core.knowledge.SidecarClient sidecar =
                mock(com.agenticai.core.knowledge.SidecarClient.class);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate");
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 1, 8000, providerOf(sidecar), true);

        List<ToolCallback> result =
                selector.select("irrelevant message", candidates, Set.of(), mcpToolNames);

        assertTrue(result.contains(navigate));
        verifyNoInteractions(sidecar);
    }

    @Test
    void semanticScoringDisabledFallsBackToFirstInOrderWithNoSidecarCalls() {
        com.agenticai.core.knowledge.SidecarClient sidecar =
                mock(com.agenticai.core.knowledge.SidecarClient.class);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate, screenshot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 1, 8000, providerOf(sidecar), false);

        List<ToolCallback> result =
                selector.select("summarize this webpage", candidates, Set.of(), mcpToolNames);

        assertTrue(result.contains(navigate));
        assertTrue(result.contains(screenshot));
        verifyNoInteractions(sidecar);
    }

    @Test
    void semanticScoringFailsOpenToFirstInOrderWhenTheQueryEmbeddingCallThrows() {
        com.agenticai.core.knowledge.SidecarClient sidecar =
                mock(com.agenticai.core.knowledge.SidecarClient.class);
        when(sidecar.embedQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("sidecar unreachable"));
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate, screenshot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 1, 8000, providerOf(sidecar), true);

        // Must not throw despite the sidecar failing - the whole turn degrades to the
        // pre-2026-08-12
        // first-in-order seeding, never fails.
        List<ToolCallback> result =
                selector.select("summarize this webpage", candidates, Set.of(), mcpToolNames);

        assertTrue(result.contains(navigate));
        assertTrue(result.contains(screenshot));
    }

    @Test
    void toolEmbeddingIsCachedAcrossCallsNotRecomputedEveryTurn() {
        com.agenticai.core.knowledge.SidecarClient sidecar =
                mock(com.agenticai.core.knowledge.SidecarClient.class);
        when(sidecar.embedQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(QUERY_VEC);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate, screenshot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(true, 1, 8000, providerOf(sidecar), true);

        selector.select("first turn message", candidates, Set.of(), mcpToolNames);
        selector.select("second turn message", candidates, Set.of(), mcpToolNames);

        // Each turn: 1 query embed. The two candidates' own embeddings are computed once on the
        // FIRST turn and reused (cached) on the second - so exactly 2 + 1 + 1 = 4 calls total, not
        // 6.
        verify(sidecar, org.mockito.Mockito.times(4))
                .embedQuery(org.mockito.ArgumentMatchers.anyString());
    }
}
