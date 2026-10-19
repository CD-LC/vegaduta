package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** {@link ToolRelevanceSelector}: MCP family namespacing, sibling stickiness, typo tolerance and discovery seeds. */
class ToolRelevanceSelectorMcpFamilyTest extends ToolRelevanceSelectorTestSupport {

    // --- mcpFamily namespacing (2026-08-09) -------------------------------------

    @Test
    void mcpFamilyKeysOnTheNamespaceSegmentNotTheSharedMcpPrefix() {
        // Bug fix: every MCP tool name is now "mcp__<server>__<tool>" (both static and dynamic
        // servers, see NamespacedMcpToolCallback). Splitting on the first underscore alone would
        // land inside "mcp" itself and return "mcp" for every server, conflating unrelated
        // servers into one family - mcpFamily must return the "mcp__<server>" segment instead.
        Set<String> mcpToolNames = Set.of("mcp__puppeteer__navigate", "mcp__filesystem__read_file");

        assertEquals(
                "mcp__puppeteer",
                ToolRelevanceSelector.mcpFamily("mcp__puppeteer__navigate", mcpToolNames));
        assertEquals(
                "mcp__filesystem",
                ToolRelevanceSelector.mcpFamily("mcp__filesystem__read_file", mcpToolNames));
        assertNotEquals(
                ToolRelevanceSelector.mcpFamily("mcp__puppeteer__navigate", mcpToolNames),
                ToolRelevanceSelector.mcpFamily("mcp__filesystem__read_file", mcpToolNames));
    }

    @Test
    void mcpFamilyStillHandlesTheLegacyUnnamespacedShape() {
        // Some MCP servers' own bare tool names already use "<server>_<action>" (e.g. the
        // puppeteer server's real tool names) - this shape must keep working unchanged.
        Set<String> mcpToolNames = Set.of("puppeteer_navigate");
        assertEquals(
                "puppeteer", ToolRelevanceSelector.mcpFamily("puppeteer_navigate", mcpToolNames));
    }

    @Test
    void mcpFamilyReturnsNullForNamesNotInTheMcpSet() {
        assertNull(ToolRelevanceSelector.mcpFamily("mcp__puppeteer__navigate", Set.of()));
        assertNull(ToolRelevanceSelector.mcpFamily(null, Set.of("mcp__puppeteer__navigate")));
    }

    // --- MCP sibling stickiness (#616) -----------------------------------------

    @Test
    void mcpSiblingsAreRescuedEvenWhenTheyDidNotScoreOnTheirOwn() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback evaluate =
                tool("puppeteer_evaluate", "Evaluate JavaScript in the browser context");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current view");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather");
        List<ToolCallback> candidates = List.of(navigate, evaluate, screenshot, weatherTool);
        Set<String> mcpToolNames =
                Set.of("puppeteer_navigate", "puppeteer_evaluate", "puppeteer_screenshot");

        List<ToolCallback> result =
                selector.select("please navigate to the page", candidates, Set.of(), mcpToolNames);

        // navigate wins the single scored slot on lexical relevance; its
        // family members must be rescued back in despite scoring 0
        // themselves and despite maxToolsPerTurn being 1 - this is the
        // documented "never leave a mid-chain tool unavailable" behavior
        // that closed #616/#607's crash.
        assertEquals(3, result.size());
        assertTrue(result.contains(navigate));
        assertTrue(result.contains(evaluate));
        assertTrue(result.contains(screenshot));
        assertFalse(result.contains(weatherTool));
    }

    @Test
    void namespacedMcpSiblingsAreStillRescuedTogetherWithinOneServersFamily() {
        // End-to-end sanity check that sibling-stickiness still works once every MCP tool name is
        // "mcp__<server>__<tool>" (see NamespacedMcpToolCallback) instead of the server's own bare
        // "<server>_<tool>" convention - mirrors
        // mcpSiblingsAreRescuedEvenWhenTheyDidNotScoreOnItsOwn
        // above, just with the namespaced shape.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback navigate = tool("mcp__puppeteer__navigate", "Navigate to a URL");
        ToolCallback evaluate =
                tool("mcp__puppeteer__evaluate", "Evaluate JavaScript in the browser context");
        ToolCallback screenshot =
                tool("mcp__puppeteer__screenshot", "Take a screenshot of the current view");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather");
        List<ToolCallback> candidates = List.of(navigate, evaluate, screenshot, weatherTool);
        Set<String> mcpToolNames =
                Set.of(
                        "mcp__puppeteer__navigate",
                        "mcp__puppeteer__evaluate",
                        "mcp__puppeteer__screenshot");

        List<ToolCallback> result =
                selector.select("please navigate to the page", candidates, Set.of(), mcpToolNames);

        assertEquals(3, result.size());
        assertTrue(result.contains(navigate));
        assertTrue(result.contains(evaluate));
        assertTrue(result.contains(screenshot));
        assertFalse(result.contains(weatherTool));
    }

    @Test
    void builtInToolsSharingAnUnderscorePrefixAreNotTreatedAsAnMcpFamily() {
        // Same underscore-prefix shape as an MCP family (google_gmail,
        // google_drive, google_sheets), but NOT in mcpToolNames - see
        // mcpFamily's javadoc on why grouping these would defeat the
        // filter's purpose for standalone built-in tools.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback gmail = tool("google_gmail", "Send and receive email messages");
        ToolCallback drive = tool("google_drive", "Store and retrieve files in the cloud");
        ToolCallback sheets = tool("google_sheets", "Read and write spreadsheet rows");
        List<ToolCallback> candidates =
                List.of(gmail, drive, sheets, tool("noop", "does nothing at all"));

        List<ToolCallback> result =
                selector.select("send an email now", candidates, Set.of(), Set.of());

        assertEquals(1, result.size());
        assertTrue(result.contains(gmail));
        assertFalse(result.contains(drive));
        assertFalse(result.contains(sheets));
    }

    // --- typo tolerance (#635) -------------------------------------------------

    @Test
    void typoInUserMessageStillMatchesToolViaFuzzyDistance() {
        // The exact real-user scenario #635's javadoc documents: "using
        // pupeeter" (typo for "puppeteer") previously scored zero exact
        // overlap against every puppeteer_* tool and lost the cut entirely.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback navigate =
                tool("puppeteer_navigate", "Navigate using the puppeteer browser automation tool");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather conditions");
        List<ToolCallback> candidates = List.of(weatherTool, navigate);

        List<ToolCallback> result =
                selector.select(
                        "please use pupeeter to open the page", candidates, Set.of(), Set.of());

        assertEquals(1, result.size());
        assertTrue(
                result.contains(navigate),
                "typo-tolerant fuzzy match should have surfaced the puppeteer tool");
    }

    // --- zero-overlap MCP discovery (2026-08-09) --------------------------

    @Test
    void enabledMcpFamilyWithZeroLexicalOverlapStillGetsOneDiscoverySeed() {
        // The exact live report this fix answers: "summarize this webpage
        // <url>" against an enabled puppeteer server, capped tightly enough
        // that puppeteer's tools (which score 0 - no shared token, not even
        // a near-miss typo) would otherwise never be offered at all.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate, screenshot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");

        List<ToolCallback> result =
                selector.select(
                        "summarize this webpage https://example.com/article",
                        candidates,
                        Set.of(),
                        mcpToolNames);

        // weatherTool wins the one scored slot (score 0, but first in
        // original order once nothing scores); puppeteer's family still
        // gets a discovery seed despite scoring 0 - and once navigate is
        // seeded, sibling-stickiness rescues screenshot alongside it.
        assertTrue(
                result.contains(navigate),
                "the enabled MCP family must not be fully invisible to the model");
        assertTrue(
                result.contains(screenshot),
                "sibling-stickiness must still rescue the rest of the seeded family");
    }

    @Test
    void multipleZeroOverlapMcpFamiliesEachGetExactlyOneSeed() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback puppeteerNav = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback puppeteerScreenshot = tool("puppeteer_screenshot", "Take a screenshot");
        ToolCallback fsRead = tool("filesystem_read", "Read a file from disk");
        ToolCallback fsWrite = tool("filesystem_write", "Write a file to disk");
        List<ToolCallback> candidates = List.of(puppeteerNav, puppeteerScreenshot, fsRead, fsWrite);
        Set<String> mcpToolNames =
                Set.of(
                        "puppeteer_navigate",
                        "puppeteer_screenshot",
                        "filesystem_read",
                        "filesystem_write");

        List<ToolCallback> result =
                selector.select(
                        "totally unrelated topic with no matching words",
                        candidates,
                        Set.of(),
                        mcpToolNames);

        // Every family present must be represented - one seed each, plus
        // that seed's rescued siblings - even though nothing scored.
        assertTrue(
                result.contains(puppeteerNav) || result.contains(puppeteerScreenshot),
                "puppeteer family must have a seed");
        assertTrue(
                result.contains(fsRead) || result.contains(fsWrite),
                "filesystem family must have a seed");
        assertEquals(
                4,
                result.size(),
                "both families should be fully rescued via sibling-stickiness once seeded");
    }

    @Test
    void discoverySeedDoesNotDisplaceAnAlreadyScoredMatch() {
        // When a family DOES score on its own merit, the discovery-seed path
        // must be a no-op for it - representedFamilies already covers it, so
        // there's no double-counting or duplicate entries.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 2);
        ToolCallback navigate = tool("puppeteer_navigate", "Navigate to a URL");
        ToolCallback screenshot =
                tool("puppeteer_screenshot", "Take a screenshot of the current page");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        List<ToolCallback> candidates = List.of(weatherTool, navigate, screenshot);
        Set<String> mcpToolNames = Set.of("puppeteer_navigate", "puppeteer_screenshot");

        List<ToolCallback> result =
                selector.select("please navigate to this page", candidates, Set.of(), mcpToolNames);

        assertEquals(2, result.size());
        assertTrue(result.contains(navigate));
        assertTrue(result.contains(screenshot));
        assertFalse(result.contains(weatherTool));
    }

    @Test
    void shortTokensAreNeverFuzzyMatchedEvenWhenCloseByEditDistance() {
        // Documented boundary case from the class's own javadoc: "api" vs
        // "app" is edit-distance 1 but both are below
        // MIN_TOKEN_LENGTH_FOR_FUZZY (5) - must NOT be treated as a match,
        // since a 1-character edit changes a short word's meaning too
        // easily to be safe. Structured so the fuzzy match (if it wrongly
        // fired) would change which tool wins the single scored slot: with
        // every candidate genuinely scoring 0, stable sort keeps the
        // *first* candidate in original list order.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback firstZeroScorer = tool("send_email", "Send an email");
        ToolCallback apiTool = tool("api_gateway", "API gateway configuration");
        List<ToolCallback> candidates =
                List.of(
                        firstZeroScorer,
                        tool("list_files", "List all files"),
                        tool("get_time", "Get current time"),
                        apiTool);

        List<ToolCallback> result =
                selector.select("check the app status now", candidates, Set.of(), Set.of());

        assertEquals(1, result.size());
        assertTrue(
                result.contains(firstZeroScorer),
                "api_gateway must not have been fuzzy-elevated by the short 'api'/'app' pair");
        assertFalse(result.contains(apiTool));
    }
}
