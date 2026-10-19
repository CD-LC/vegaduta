package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** {@link ToolRelevanceSelector}: pass-through, relevance ranking and the minimum-score gate. */
class ToolRelevanceSelectorRankingTest extends ToolRelevanceSelectorTestSupport {

    // --- pass-through cases -------------------------------------------------

    @Test
    void disabledSelectorReturnsCandidatesUnchanged() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(false, 1);
        List<ToolCallback> candidates =
                List.of(tool("a", "alpha"), tool("b", "beta"), tool("c", "gamma"));

        List<ToolCallback> result = selector.select("anything", candidates, Set.of(), Set.of());

        assertSame(candidates, result);
    }

    @Test
    void candidateListAtOrBelowBudgetIsReturnedUnchangedEvenWhenEnabled() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3);
        List<ToolCallback> candidates =
                List.of(tool("a", "alpha"), tool("b", "beta"), tool("c", "gamma"));

        List<ToolCallback> result =
                selector.select("completely unrelated message", candidates, Set.of(), Set.of());

        assertSame(candidates, result);
    }

    // A blank/punctuation-only message (no scoreable tokens) used to fall back to returning
    // every candidate completely unfiltered - the exact hole that let an unbounded tool
    // catalog reach the LLM provider whole on a real chat turn (see the live "Maximum tools
    // limit reached. 5415 tools" incident documented on ToolRelevanceSelector's own javadoc).
    // The cap must still apply even with nothing to score against - just via stable-order
    // truncation instead of lexical ranking.

    @Test
    void blankMessageStillRespectsTheCapViaStableOrderTruncation() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback a = tool("a", "alpha");
        ToolCallback b = tool("b", "beta");
        ToolCallback c = tool("c", "gamma");
        ToolCallback d = tool("d", "delta");
        List<ToolCallback> candidates = List.of(a, b, c, d);

        List<ToolCallback> result = selector.select("   ", candidates, Set.of(), Set.of());

        // Every candidate scores 0 with nothing to rank against - the stable sort preserves
        // original resolution order, so the first candidate wins the single slot rather than
        // the whole unbounded list passing through.
        assertEquals(1, result.size());
        assertTrue(result.contains(a));
    }

    @Test
    void punctuationOnlyMessageStillRespectsTheCapViaStableOrderTruncation() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 1);
        ToolCallback a = tool("a", "alpha");
        ToolCallback b = tool("b", "beta");
        ToolCallback c = tool("c", "gamma");
        ToolCallback d = tool("d", "delta");
        List<ToolCallback> candidates = List.of(a, b, c, d);

        List<ToolCallback> result = selector.select("??? !!! ...", candidates, Set.of(), Set.of());

        assertEquals(1, result.size());
        assertTrue(result.contains(a));
    }

    @Test
    void blankMessageWithManyCandidatesNeverReturnsMoreThanTheBudget() {
        // The exact shape of the live incident: thousands of raw candidates, a blank/
        // attachment-only message - this must never return the whole list.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 12);
        List<ToolCallback> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 5415; i++) {
            candidates.add(tool("tool_" + i, "a generic tool numbered " + i));
        }

        List<ToolCallback> result = selector.select("", candidates, Set.of(), Set.of());

        assertEquals(12, result.size());
    }

    @Test
    void blankMessageStillKeepsAlwaysIncludeToolsAheadOfTheStableOrderCut() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 2);
        ToolCallback rememberFact =
                tool("remember_fact", "Remember a fact permanently for future turns");
        ToolCallback first = tool("a", "alpha");
        ToolCallback second = tool("b", "beta");
        ToolCallback third = tool("c", "gamma");
        List<ToolCallback> candidates = List.of(first, rememberFact, second, third);

        List<ToolCallback> result =
                selector.select("", candidates, Set.of("remember_fact"), Set.of());

        assertEquals(2, result.size());
        assertTrue(result.contains(rememberFact));
        assertTrue(result.contains(first));
    }

    // --- relevance ranking ----------------------------------------------------

    @Test
    void relevantToolOutranksAndSurvivesWhileIrrelevantOnesAreDropped() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 2);
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        ToolCallback sendEmail = tool("send_email", "Send an email message");
        ToolCallback listFiles = tool("list_files", "List files in a directory");
        ToolCallback currencyConvert = tool("currency_convert", "Convert currency amounts");
        List<ToolCallback> candidates = List.of(weatherTool, sendEmail, listFiles, currencyConvert);

        List<ToolCallback> result =
                selector.select("what's the weather forecast", candidates, Set.of(), Set.of());

        // Cap is 2, but only weatherTool scores positively - per the minimum-score gate
        // (2026-08-09 fix), the remaining slot is no longer padded with a zero-score tool
        // (sendEmail) just because nothing else scored higher.
        assertEquals(1, result.size());
        assertTrue(
                result.contains(weatherTool),
                "the one lexically relevant tool must survive the cut");
        assertFalse(result.contains(listFiles));
        assertFalse(result.contains(currencyConvert));
    }

    @Test
    void alwaysIncludeToolsSurviveAndReduceTheScoringBudget() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 2);
        ToolCallback rememberFact =
                tool("remember_fact", "Remember a fact permanently for future turns");
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        ToolCallback sendEmail = tool("send_email", "Send an email message");
        ToolCallback listFiles = tool("list_files", "List files in a directory");
        List<ToolCallback> candidates = List.of(rememberFact, weatherTool, sendEmail, listFiles);

        List<ToolCallback> result =
                selector.select(
                        "what's the weather", candidates, Set.of("remember_fact"), Set.of());

        // Budget is 2: remember_fact is always kept (doesn't compete on
        // score), leaving only 1 scored slot - which the lexically relevant
        // weather tool should win.
        assertEquals(2, result.size());
        assertTrue(result.contains(rememberFact));
        assertTrue(result.contains(weatherTool));
        assertFalse(result.contains(sendEmail));
        assertFalse(result.contains(listFiles));
    }

    // --- minimum-score gate (Fix B, 2026-08-09 audit) --------------------------

    @Test
    void positiveScorersAreNotPaddedWithZeroScoreToolsUpToTheCap() {
        // Live failure mode this closes: a code-intel-scoped request calling an unrelated
        // Playwright tool - the cap had open slots, so the old code padded them with whatever
        // scored 0 next, not because it was relevant.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3);
        ToolCallback weatherTool = tool("weather_lookup", "Get current weather forecast");
        ToolCallback sendEmail = tool("send_email", "Send an email message");
        ToolCallback listFiles = tool("list_files", "List files in a directory");
        ToolCallback currencyConvert = tool("currency_convert", "Convert currency amounts");
        List<ToolCallback> candidates = List.of(weatherTool, sendEmail, listFiles, currencyConvert);

        List<ToolCallback> result =
                selector.select("what's the weather forecast", candidates, Set.of(), Set.of());

        // Cap is 3 with 2 slots free after weatherTool - the minimum-score gate must stop right
        // there instead of padding sendEmail/listFiles/currencyConvert in on a score of 0.
        assertEquals(1, result.size());
        assertTrue(result.contains(weatherTool));
    }

    @Test
    void noPositiveScorersAtAllStillFallsBackToStableOrderTruncation() {
        // Distinct from the blank-message cases above: this message has real, non-stopword
        // tokens, they simply don't overlap ANY candidate's name/description. There is no
        // "real match" for zero-score tools to pad on top of, so this is NOT the Fix B bug -
        // the pre-existing "cap always applies even with nothing to score against" behavior
        // must still fire via stable-order truncation.
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 2);
        ToolCallback first = tool("list_files", "List files in a directory");
        ToolCallback second = tool("get_time", "Get the current time");
        ToolCallback third = tool("send_email", "Send an email message");
        List<ToolCallback> candidates = List.of(first, second, third);

        List<ToolCallback> result =
                selector.select("completely unrelated topic today", candidates, Set.of(), Set.of());

        assertEquals(2, result.size());
        assertTrue(result.contains(first));
        assertTrue(result.contains(second));
        assertFalse(result.contains(third));
    }
}
