package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link AgentToolSelection#capToHardLimit} is the defense-in-depth backstop behind {@link
 * ToolRelevanceSelector} - see the live incident documented on both classes: {@code POST
 * https://api.x.ai/v1/chat/completions} 400ing with "Maximum tools limit reached. 5415 tools have
 * been provided but the maximum is 350" because the selector's own empty-query bypass let an
 * unbounded tool catalog reach the provider whole on a blank-message turn. This is the second,
 * independent line of defense - it must hold even if the selector is disabled or misconfigured.
 * Tested directly against the static method (package-private, takes the cap as a parameter) rather
 * than standing up the full {@code AgentToolProvider} bean, which needs ~40 constructor
 * dependencies - same rationale {@code DynamicMcpConnectionManager#redactSecrets}/ {@code
 * #filterUsable} already document for testing their own pure helpers this way.
 *
 * <p>The family-atomic tests below cover a second live incident (2026-08-19, prod tenant with a
 * ~5660-tool resolved catalog): a blind {@code subList(0, hardCap)} truncation split an MCP
 * server's own multi-step tool family apart mid-turn (kept {@code puppeteer_screenshot}, dropped
 * {@code puppeteer_navigate}), even though {@link ToolRelevanceSelector}'s sibling-stickiness had
 * already guaranteed the whole family together one stage earlier. {@code mcpToolNames} (empty
 * {@link Set} in the pre-existing tests above, which predate this behavior and don't exercise it)
 * is what lets {@code capToHardLimit} tell an MCP family apart from an unrelated built-in tool that
 * happens to share an underscore-delimited name prefix.
 */
class AgentToolProviderCapTest {

    private static final Set<String> NO_MCP_TOOLS = Set.of();

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test tool " + name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    @Test
    void listAtOrBelowCapIsReturnedUnchanged() {
        List<ToolCallback> tools = List.of(tool("a"), tool("b"), tool("c"));

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 3, NO_MCP_TOOLS, UUID.randomUUID());

        assertSame(tools, result);
    }

    @Test
    void listOverCapIsTruncatedToExactlyTheCap() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 5415; i++) {
            tools.add(tool("tool_" + i));
        }

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 350, NO_MCP_TOOLS, UUID.randomUUID());

        assertEquals(350, result.size());
    }

    @Test
    void truncationKeepsTheFirstToolsInOriginalOrder() {
        ToolCallback first = tool("first");
        ToolCallback second = tool("second");
        ToolCallback third = tool("third");
        List<ToolCallback> tools = List.of(first, second, third);

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 2, NO_MCP_TOOLS, UUID.randomUUID());

        assertEquals(2, result.size());
        assertTrue(result.contains(first));
        assertTrue(result.contains(second));
    }

    @Test
    void emptyListIsUnaffected() {
        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(List.of(), 100, NO_MCP_TOOLS, UUID.randomUUID());

        assertTrue(result.isEmpty());
    }

    // --- family-atomic truncation (2026-08-19 fix) ----------------------------------------

    @Test
    void truncationNeverSplitsAnMcpFamilyEvenWhenThatDropsTheWholeFamily() {
        // Reproduces the live incident: puppeteer_screenshot lands right after soloA (within
        // budget on its own), its sibling puppeteer_navigate lands later in the list (out of
        // budget on its own) - the old subList(0, hardCap) would have kept screenshot but dropped
        // navigate, splitting the family apart mid-turn. The fix groups a family at the position
        // of its FIRST member and only admits it as one unit, so once the family (screenshot +
        // navigate together) no longer fits in the remaining budget, BOTH are dropped rather than
        // keeping screenshot alone - soloB, which sorts after the family, correctly never gets a
        // chance to leapfrog ahead of it either, preserving the original relevance-order semantics
        // at family granularity instead of individual-tool granularity.
        ToolCallback soloA = tool("solo_a");
        ToolCallback screenshot = tool("mcp__puppeteer__puppeteer_screenshot");
        ToolCallback soloB = tool("solo_b");
        ToolCallback navigate = tool("mcp__puppeteer__puppeteer_navigate");
        List<ToolCallback> tools = List.of(soloA, screenshot, soloB, navigate);
        Set<String> mcpToolNames =
                Set.of(
                        "mcp__puppeteer__puppeteer_screenshot",
                        "mcp__puppeteer__puppeteer_navigate");

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 2, mcpToolNames, UUID.randomUUID());

        assertEquals(
                1,
                result.size(),
                "the two-member puppeteer family doesn't fit in the remaining budget of 1, "
                        + "so it must be dropped whole rather than split");
        assertTrue(result.contains(soloA));
        assertFalse(result.contains(screenshot));
        assertFalse(result.contains(navigate));
        assertFalse(
                result.contains(soloB),
                "soloB must not leapfrog ahead of the family it sorts after");
    }

    @Test
    void aFamilyThatFitsWithinTheCapIsKeptWhole() {
        ToolCallback navigate = tool("mcp__puppeteer__puppeteer_navigate");
        ToolCallback screenshot = tool("mcp__puppeteer__puppeteer_screenshot");
        ToolCallback soloA = tool("solo_a");
        List<ToolCallback> tools = List.of(navigate, screenshot, soloA);
        Set<String> mcpToolNames =
                Set.of(
                        "mcp__puppeteer__puppeteer_navigate",
                        "mcp__puppeteer__puppeteer_screenshot");

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 2, mcpToolNames, UUID.randomUUID());

        assertEquals(2, result.size());
        assertTrue(result.contains(navigate));
        assertTrue(result.contains(screenshot));
        assertFalse(result.contains(soloA));
    }

    @Test
    void aSingleFamilyLargerThanTheWholeCapIsTruncatedInternallyRatherThanReturningNothing() {
        List<ToolCallback> tools = new ArrayList<>();
        Set<String> mcpToolNames = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            String name = "mcp__huge__tool_" + i;
            tools.add(tool(name));
            mcpToolNames.add(name);
        }

        List<ToolCallback> result =
                AgentToolSelection.capToHardLimit(tools, 3, mcpToolNames, UUID.randomUUID());

        assertEquals(
                3,
                result.size(),
                "an oversized single family must still be truncated, not dropped entirely");
    }

    // --- mergeFavoringBuiltins collision reporting (2026-08-09 misdiagnosis fix) ----------

    private record Collision(String name, String message) {}

    @Test
    void mcpToolCollidingWithABuiltinIsDroppedAndReportedAsABuiltinCollision() {
        ToolCallback builtin = tool("search_code");
        ToolCallback mcpTool = tool("search_code");
        List<Collision> recorded = new ArrayList<>();

        List<ToolCallback> result =
                AgentToolSelection.mergeFavoringBuiltins(
                        List.of(builtin),
                        List.of(mcpTool),
                        UUID.randomUUID(),
                        (name, message) -> recorded.add(new Collision(name, message)));

        assertEquals(1, result.size());
        assertSame(builtin, result.get(0));
        assertEquals(1, recorded.size());
        assertTrue(
                recorded.get(0)
                        .message()
                        .contains("a built-in platform tool already uses that name"),
                "expected the built-in-collision message, got: " + recorded.get(0).message());
    }

    @Test
    void twoMcpToolsCollidingWithEachOtherAreReportedAsAnMcpCollisionNotABuiltinOne() {
        // The bug this fixes: two MCP-sourced tools sharing a name (e.g. two static servers that
        // both ship a "search" tool) used to be misreported as "a built-in platform tool already
        // uses that name" even though no built-in was involved at all.
        ToolCallback firstMcpTool = tool("search");
        ToolCallback secondMcpTool = tool("search");
        List<Collision> recorded = new ArrayList<>();

        List<ToolCallback> result =
                AgentToolSelection.mergeFavoringBuiltins(
                        List.of(),
                        List.of(firstMcpTool, secondMcpTool),
                        UUID.randomUUID(),
                        (name, message) -> recorded.add(new Collision(name, message)));

        assertEquals(1, result.size());
        assertSame(firstMcpTool, result.get(0));
        assertEquals(1, recorded.size());
        assertFalse(
                recorded.get(0)
                        .message()
                        .contains("a built-in platform tool already uses that name"),
                "must not blame a built-in for an MCP-vs-MCP collision: "
                        + recorded.get(0).message());
        assertTrue(
                recorded.get(0).message().contains("another MCP-registered tool"),
                "expected the MCP-vs-MCP collision message, got: " + recorded.get(0).message());
    }

    @Test
    void nonCollidingToolsAreAllKeptWithNoCollisionReported() {
        ToolCallback builtin = tool("remember_fact");
        ToolCallback mcpTool = tool("search");
        List<Collision> recorded = new ArrayList<>();

        List<ToolCallback> result =
                AgentToolSelection.mergeFavoringBuiltins(
                        List.of(builtin),
                        List.of(mcpTool),
                        UUID.randomUUID(),
                        (name, message) -> recorded.add(new Collision(name, message)));

        assertEquals(2, result.size());
        assertTrue(result.contains(builtin));
        assertTrue(result.contains(mcpTool));
        assertTrue(recorded.isEmpty());
    }

    // --- ALWAYS_RELEVANT_TOOL_NAMES: shared-memory write/read pairing (2026-08-19 sweep) ---

    @Test
    void alwaysRelevantToolNamesKeepTheSharedMemoryWriteAndReadPairTogether() {
        // remember_shared_fact (shared WRITE) was silently missing while its READ sibling
        // recall_shared_graph_facts was present - a builtin has no MCP family, so no sibling
        // rescue can save it on a trimmed turn; asserting BOTH locks the pairing so the write
        // half can't be dropped again.
        assertTrue(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES.contains("remember_shared_fact"));
        assertTrue(
                AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES.contains(
                        "recall_shared_graph_facts"));
    }

    @Test
    void alwaysRelevantToolNamesKeepRequestHumanFromBeingTrimmedByRelevanceFiltering() {
        // request_human is a safety/escalation path, not a lexically-triggered tool - the user
        // does not always say "get me a human" before an agent should recognize it needs to
        // escalate, so ToolRelevanceSelector must never be the reason it's missing from a
        // crowded turn.
        assertTrue(AgentToolSelection.ALWAYS_RELEVANT_TOOL_NAMES.contains("request_human"));
    }

    // --- computeCached: MCP discovery cache serialization/TTL contract (2026-08-19 sweep) ---

    private static java.util.function.Supplier<List<ToolCallback>> countingLoader(
            AtomicInteger calls, List<ToolCallback> value) {
        return () -> {
            calls.incrementAndGet();
            return value;
        };
    }

    @Test
    void computeCachedInvokesTheLoaderOnceAndServesTheCachedListForTheSameUnexpiredKey() {
        Map<String, AgentToolSelection.CachedMcpTools> cache = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        List<ToolCallback> loaded = List.of(tool("a"));

        List<ToolCallback> first =
                AgentToolSelection.computeCached(
                        cache, "__static__", Duration.ofMinutes(5), countingLoader(calls, loaded));
        List<ToolCallback> second =
                AgentToolSelection.computeCached(
                        cache,
                        "__static__",
                        Duration.ofMinutes(5),
                        countingLoader(calls, List.of(tool("b"))));

        assertEquals(1, calls.get(), "an unexpired same-key entry must never re-run the loader");
        assertSame(loaded, first);
        assertSame(loaded, second, "the second caller must get the first caller's cached list");
    }

    @Test
    void computeCachedKeepsDistinctKeysIndependent() {
        Map<String, AgentToolSelection.CachedMcpTools> cache = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();

        AgentToolSelection.computeCached(
                cache,
                "tenant_a",
                Duration.ofMinutes(5),
                countingLoader(calls, List.of(tool("a"))));
        AgentToolSelection.computeCached(
                cache,
                "tenant_b",
                Duration.ofMinutes(5),
                countingLoader(calls, List.of(tool("b"))));

        assertEquals(2, calls.get(), "two distinct keys must each run their own loader");
    }

    @Test
    void computeCachedReRunsTheLoaderForAnExpiredEntry() {
        Map<String, AgentToolSelection.CachedMcpTools> cache = new ConcurrentHashMap<>();
        cache.put(
                "__static__",
                new AgentToolSelection.CachedMcpTools(
                        List.of(tool("stale")), Instant.now().minusSeconds(60)));
        AtomicInteger calls = new AtomicInteger();
        List<ToolCallback> fresh = List.of(tool("fresh"));

        List<ToolCallback> result =
                AgentToolSelection.computeCached(
                        cache, "__static__", Duration.ofMinutes(5), countingLoader(calls, fresh));

        assertEquals(1, calls.get());
        assertSame(fresh, result);
    }

    // --- wrapStaticCallbacks: per-TOOL wrap isolation (2026-08-19 sweep) --------------------

    /** A callback whose definition materialization itself throws - the wrap-time failure class. */
    private static ToolCallback poisonedTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                throw new IllegalStateException("definition materialization failed");
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    @Test
    void wrapStaticCallbacksSkipsOnlyTheOneToolWhoseDefinitionThrows() {
        // Archetype smell #1 in a narrower seam: before the fix, the middle tool's throw escaped
        // to resolveStaticMcpTools' OUTER catch, discarding (and TTL-caching away) every healthy
        // server's already-discovered tools. Now it costs exactly the one tool, in order.
        ToolCallback[] fetched = {tool("tool_a"), poisonedTool(), tool("tool_b")};

        List<ToolCallback> result = AgentToolSelection.wrapStaticCallbacks(fetched, "puppeteer");

        assertEquals(2, result.size());
        assertEquals("mcp__puppeteer__tool_a", result.get(0).getToolDefinition().name());
        assertEquals("mcp__puppeteer__tool_b", result.get(1).getToolDefinition().name());
    }

    @Test
    void wrapStaticCallbacksWrapsEveryHealthyToolInOrder() {
        ToolCallback[] fetched = {tool("navigate"), tool("screenshot")};

        List<ToolCallback> result = AgentToolSelection.wrapStaticCallbacks(fetched, "puppeteer");

        assertEquals(2, result.size());
        assertEquals("mcp__puppeteer__navigate", result.get(0).getToolDefinition().name());
        assertEquals("mcp__puppeteer__screenshot", result.get(1).getToolDefinition().name());
    }

    @Test
    void hardCapPerTurnDefaultsTo40AndIsConfigurable() {
        // The constructor's @Value expression carries both the default (40) and the property key
        // that
        // overrides it (app.tools.hard-cap-per-turn, env TOOL_HARD_CAP_PER_TURN in
        // application.yml).
        List<String> expressions = new ArrayList<>();
        for (java.lang.reflect.Constructor<?> c :
                ToolResolver.class.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter p : c.getParameters()) {
                org.springframework.beans.factory.annotation.Value v =
                        p.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
                if (v != null && v.value().contains("app.tools.hard-cap-per-turn")) {
                    expressions.add(v.value());
                }
            }
        }
        assertEquals(List.of("${app.tools.hard-cap-per-turn:40}"), expressions);
    }
}
