package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** {@link ToolRelevanceSelector}: per-turn schema token budget, the no-match budget and count caps. */
class ToolRelevanceSelectorTokenBudgetTest extends ToolRelevanceSelectorTestSupport {

    // --- per-turn schema token budget (Fix A, 2026-08-09 audit) ----------------

    @Test
    void schemaTokenBudgetStopsAdmittingCandidatesEvenWhenCountCapAllowsMore() {
        // maxToolsPerTurn=5 is deliberately NOT the limiting factor here - the point is that a
        // handful of huge-schema tools can exhaust the token budget well before the count cap
        // is reached, which the count-only cap alone could never catch.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 5, 20);
        String bigSchema = "x".repeat(40); // 40 chars / 4 == 10 estimated tokens
        ToolCallback first = toolWithSchema("a", "alpha", bigSchema);
        ToolCallback second = toolWithSchema("b", "beta", bigSchema);
        ToolCallback third = toolWithSchema("c", "gamma", bigSchema);
        List<ToolCallback> candidates =
                List.of(
                        first,
                        second,
                        third,
                        tool("d", "delta"),
                        tool("e", "epsilon"),
                        tool("f", "zeta"));

        List<ToolCallback> result = selector.select("   ", candidates, Set.of(), Set.of());

        // Blank message -> stable order; first and second's ~10-token schemas exactly exhaust
        // the 20-token budget, so third (and everything after it) must be excluded even though
        // the count cap of 5 still has room.
        assertEquals(2, result.size());
        assertTrue(result.contains(first));
        assertTrue(result.contains(second));
        assertFalse(result.contains(third));
    }

    @Test
    void alwaysIncludeToolsSchemaCostReducesTheTokenBudgetToo() {
        // Mirrors how alwaysInclude tools already reduce the count budget (maxToolsPerTurn -
        // alwaysKept.size()) - their schema cost must reduce the token budget the same way,
        // since they're bound to the call regardless of scoring.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 5, 20);
        String bigSchema = "x".repeat(40); // 10 estimated tokens
        ToolCallback rememberFact = toolWithSchema("remember_fact", "Remember a fact", bigSchema);
        ToolCallback first = toolWithSchema("a", "alpha", bigSchema);
        ToolCallback second = toolWithSchema("b", "beta", bigSchema);
        List<ToolCallback> candidates =
                List.of(
                        rememberFact,
                        first,
                        second,
                        tool("c", "gamma"),
                        tool("d", "delta"),
                        tool("e", "epsilon"));

        List<ToolCallback> result =
                selector.select("   ", candidates, Set.of("remember_fact"), Set.of());

        // remember_fact's own 10-token schema is charged against the 20-token budget before any
        // scored candidate is considered, leaving room for exactly one more 10-token tool.
        assertTrue(result.contains(rememberFact));
        assertTrue(result.contains(first));
        assertFalse(result.contains(second));
    }

    @Test
    void maxSchemaTokensPerTurnAccessorReturnsConfiguredValue() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 12, 500);

        assertEquals(500, selector.maxSchemaTokensPerTurn());
    }

    // --- no-match budget (2026-09-28 token-cost fix) ------------------------------
    // A turn where NOTHING scores (a greeting, an acknowledgement, a blank send)
    // used to still fill the entire maxToolsPerTurn budget with zero-score
    // padding - ~12 tool schemas bound to a bare "hello" - and catalogs at or
    // below maxToolsPerTurn bypassed selection entirely so small agents paid
    // their full catalog too. The no-match budget caps those turns tighter;
    // alwaysInclude tools and MCP discovery seeds still apply on top of it.

    @Test
    void zeroScoreTurnIsCappedByTheSmallerNoMatchBudget() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 12, 8000, 4);
        List<ToolCallback> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(tool("tool_" + i, "a generic tool numbered " + i));
        }

        List<ToolCallback> result = selector.select("hello", candidates, Set.of(), Set.of());

        assertEquals(4, result.size());
        assertTrue(
                result.contains(candidates.get(0)),
                "stable-order truncation keeps the first candidates");
    }

    @Test
    void noMatchBudgetAlsoAppliesToCatalogsBelowMaxToolsPerTurn() {
        // Previously candidates.size() <= maxToolsPerTurn bypassed selection
        // entirely, so a small agent's greeting turn still bound every tool
        // it had resolved. The no-match budget now trims those too.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 12, 8000, 4);
        List<ToolCallback> candidates =
                List.of(
                        tool("a", "alpha"),
                        tool("b", "beta"),
                        tool("c", "gamma"),
                        tool("d", "delta"),
                        tool("e", "epsilon"),
                        tool("f", "zeta"),
                        tool("g", "eta"));

        List<ToolCallback> result = selector.select("hello", candidates, Set.of(), Set.of());

        assertEquals(4, result.size());
        assertTrue(result.contains(candidates.get(0)));
    }

    @Test
    void positiveScoreTurnStillUsesTheFullMaxToolsBudgetNotTheNoMatchOne() {
        // noMatch is deliberately 1 here - if it wrongly applied to a turn WITH
        // real matches, only one tool would survive even though two genuinely score.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3, 8000, 1);
        ToolCallback weatherLookup = tool("weather_lookup", "Get current weather forecast");
        ToolCallback weatherRadar = tool("weather_radar", "Show weather radar maps");
        ToolCallback sendEmail = tool("send_email", "Send an email message");
        ToolCallback listFiles = tool("list_files", "List files in a directory");
        List<ToolCallback> candidates = List.of(weatherLookup, weatherRadar, sendEmail, listFiles);

        List<ToolCallback> result =
                selector.select("weather forecast", candidates, Set.of(), Set.of());

        // Both weather tools score > 0; the minimum-score gate still drops the
        // zero-scorers - but the budget applied was 3, not the no-match 1.
        assertEquals(2, result.size());
        assertTrue(result.contains(weatherLookup));
        assertTrue(result.contains(weatherRadar));
    }

    @Test
    void zeroScoreTurnKeepsAlwaysIncludeAndMcpSeedsOnTopOfTheNoMatchBudget() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 12, 8000, 2);
        ToolCallback rememberFact =
                tool("remember_fact", "Remember a fact permanently for future turns");
        ToolCallback first = tool("a", "alpha");
        ToolCallback nav = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback shot = tool("puppeteer_screenshot", "Take a screenshot");
        List<ToolCallback> candidates =
                List.of(
                        rememberFact,
                        first,
                        tool("b", "beta"),
                        tool("c", "gamma"),
                        tool("d", "delta"),
                        tool("e", "epsilon"),
                        nav,
                        shot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");

        List<ToolCallback> result =
                selector.select("hello", candidates, Set.of("remember_fact"), mcpToolNames);

        // countBudget = 2 - 1 alwaysKept = 1 scored slot ("a"), plus remember_fact,
        // plus the puppeteer discovery seed and its rescued sibling = 4 total.
        assertEquals(4, result.size());
        assertTrue(result.contains(rememberFact));
        assertTrue(result.contains(first));
        assertTrue(result.contains(nav));
        assertTrue(result.contains(shot));
    }

    private static final String BIG_SCHEMA = "{\"x\":\"" + "a".repeat(4000) + "\"}";

    private static long countNamed(List<ToolCallback> tools, String prefix) {
        return tools.stream().filter(t -> t.getToolDefinition().name().startsWith(prefix)).count();
    }

    @Test
    void twoArgConstructorKeepsTheOriginalLargeSchemaBudget() {
        // ~4000 estimated tokens in total: over the new production default (3000) would trim, but
        // the 2-arg
        // test constructor stays pinned to 8000 so pre-existing tests keep their original meaning.
        List<ToolCallback> all =
                List.of(
                        toolWithSchema("alpha_tool", "alpha thing", BIG_SCHEMA),
                        toolWithSchema("beta_tool", "beta thing", BIG_SCHEMA),
                        toolWithSchema("gamma_tool", "gamma thing", BIG_SCHEMA),
                        toolWithSchema("delta_tool", "delta thing", BIG_SCHEMA));
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12).select("alpha", all, Set.of(), Set.of());
        assertEquals(all.size(), out.size());
    }

    @Test
    void rescuedSiblingsAreCappedByCount() {
        List<ToolCallback> all = new java.util.ArrayList<>();
        Set<String> mcp = new java.util.HashSet<>();
        all.add(tool("mcp__srv__navigate", "navigate browser page"));
        mcp.add("mcp__srv__navigate");
        for (int i = 0; i < 15; i++) {
            all.add(tool("mcp__srv__zz" + i, "unrelated"));
            mcp.add("mcp__srv__zz" + i);
        }
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12).select("navigate browser", all, Set.of(), mcp);
        assertEquals(1 + ToolRelevanceSelector.MAX_SIBLING_TOOLS, out.size());
    }

    @Test
    void discoverySeedsAreCappedByCount() {
        List<ToolCallback> all = new java.util.ArrayList<>();
        Set<String> mcp = new java.util.HashSet<>();
        all.add(tool("plain_a", "unrelated"));
        all.add(tool("plain_b", "unrelated"));
        for (int i = 0; i < 6; i++) {
            all.add(tool("mcp__s" + i + "__act", "unrelated"));
            mcp.add("mcp__s" + i + "__act");
        }
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 2).select("hello", all, Set.of(), mcp);
        assertEquals(2 + ToolRelevanceSelector.MAX_SEED_TOOLS, out.size());
        assertEquals(ToolRelevanceSelector.MAX_SEED_TOOLS, countNamed(out, "mcp__"));
    }

    @Test
    void firstSeedIsGuaranteedEvenOverBudgetButTheRestAreLimitedByTokenBudget() {
        List<ToolCallback> all = new java.util.ArrayList<>();
        Set<String> mcp = new java.util.HashSet<>();
        all.add(tool("plain_a", "unrelated"));
        all.add(tool("plain_b", "unrelated"));
        for (int i = 0; i < 3; i++) {
            all.add(toolWithSchema("mcp__s" + i + "__act", "unrelated", BIG_SCHEMA));
            mcp.add("mcp__s" + i + "__act");
        }
        // Each seed costs ~1000 tokens against a 500 budget: only the guaranteed first seed gets
        // in, and it
        // is admitted even though scored is non-empty.
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 2, 500, 2).select("hello", all, Set.of(), mcp);
        assertEquals(3, out.size());
        assertTrue(out.contains(all.get(2)));
        assertEquals(1, countNamed(out, "mcp__"));
    }

    @Test
    void firstSiblingOfEveryKeptFamilyIsGuaranteedOverBudget() {
        List<ToolCallback> all = new java.util.ArrayList<>();
        Set<String> mcp = new java.util.HashSet<>();
        ToolCallback nav = tool("mcp__a__navigate", "navigate page");
        ToolCallback search = tool("mcp__b__search", "search index");
        ToolCallback a1 = toolWithSchema("mcp__a__x1", "zzz", BIG_SCHEMA);
        ToolCallback a2 = toolWithSchema("mcp__a__x2", "zzz", BIG_SCHEMA);
        ToolCallback b1 = toolWithSchema("mcp__b__y1", "zzz", BIG_SCHEMA);
        ToolCallback b2 = toolWithSchema("mcp__b__y2", "zzz", BIG_SCHEMA);
        for (ToolCallback t : List.of(nav, a1, a2, search, b1, b2)) {
            all.add(t);
            mcp.add(t.getToolDefinition().name());
        }
        // Budget 1000 tokens, each sibling ~1000: the first sibling of each family is guaranteed,
        // the
        // second of each family does not fit.
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12, 1000, 12)
                        .select("navigate search", all, Set.of(), mcp);
        assertEquals(4, out.size());
        assertTrue(out.contains(nav));
        assertTrue(out.contains(search));
        assertTrue(out.contains(a1));
        assertTrue(out.contains(b1));
        assertFalse(out.contains(a2));
        assertFalse(out.contains(b2));
    }

    @Test
    void seedsDoNotCrowdOutSiblingsOfTheRequestedFamily() {
        List<ToolCallback> all = new java.util.ArrayList<>();
        Set<String> mcp = new java.util.HashSet<>();
        all.add(tool("mcp__a__navigate", "navigate page"));
        all.add(tool("mcp__a__x1", "zzz"));
        all.add(tool("mcp__a__x2", "zzz"));
        for (int i = 0; i < 4; i++) {
            all.add(tool("mcp__b" + i + "__t", "zzz"));
        }
        for (int i = 0; i < 8; i++) {
            all.add(tool("plain_" + i, "zzz"));
        }
        for (ToolCallback t : all) {
            if (t.getToolDefinition().name().startsWith("mcp__")) {
                mcp.add(t.getToolDefinition().name());
            }
        }
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12).select("navigate", all, Set.of(), mcp);
        assertTrue(out.contains(all.get(1)));
        assertTrue(out.contains(all.get(2)));
        assertEquals(ToolRelevanceSelector.MAX_SEED_TOOLS, countNamed(out, "mcp__b"));
    }

    @Test
    void smallCatalogOverTokenBudgetIsStillTrimmed() {
        List<ToolCallback> all =
                List.of(
                        toolWithSchema("alpha_tool", "alpha thing", BIG_SCHEMA),
                        toolWithSchema("beta_tool", "beta thing", BIG_SCHEMA),
                        toolWithSchema("gamma_tool", "gamma thing", BIG_SCHEMA));
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12, 1500, 12)
                        .select("alpha", all, Set.of(), Set.of());
        assertTrue(out.size() < all.size());
        assertFalse(out.isEmpty());
    }

    @Test
    void explicitBudgetOverridesTheDefault() {
        // The same catalog fits whole under a larger explicitly-configured budget.
        List<ToolCallback> all =
                List.of(
                        toolWithSchema("alpha_tool", "alpha thing", BIG_SCHEMA),
                        toolWithSchema("beta_tool", "beta thing", BIG_SCHEMA),
                        toolWithSchema("gamma_tool", "gamma thing", BIG_SCHEMA));
        List<ToolCallback> out =
                new ToolRelevanceSelector(true, 12, 5000, 12)
                        .select("alpha", all, Set.of(), Set.of());
        assertEquals(all.size(), out.size());
    }
}
