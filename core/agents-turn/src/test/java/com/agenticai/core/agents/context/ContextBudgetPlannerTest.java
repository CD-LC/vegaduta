package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.context.ContextBudgetPlanner.Clear;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Compact;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Item;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Kind;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Plan;
import com.agenticai.core.agents.context.ContextBudgetPlanner.Settings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBudgetPlannerTest {

    /** window 10_000: clear above 6_000, compact above 8_500, keep the newest 2 tool results, 100-token summary. */
    private static final Settings SETTINGS = new Settings(10_000, 0.6, 0.85, 2, 100, 48);

    private static Item tool(int tokens) {
        return Item.of(Kind.TOOL_RESULT, tokens);
    }

    private static List<Clear> clears(Plan plan) {
        List<Clear> out = new ArrayList<>();
        plan.actions().forEach(a -> {
            if (a instanceof Clear c) {
                out.add(c);
            }
        });
        return out;
    }

    // ---- thresholds ----

    @Test
    void nothingHappensBelowTheClearThreshold() {
        List<Item> items = List.of(
            Item.of(Kind.SYSTEM, 500).withLocked(true), Item.of(Kind.USER, 100).withLocked(true),
            tool(2_000), tool(2_000), tool(1_000));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS); // 5_600 <= 6_000

        assertTrue(plan.isEmpty());
        assertEquals(5_600, plan.tokensBefore());
        assertEquals(5_600, plan.tokensAfter());
    }

    @Test
    void exactlyAtTheThresholdIsStillQuiet() {
        Plan plan = ContextBudgetPlanner.plan(List.of(tool(3_000), tool(3_000)), 0, SETTINGS); // == 6_000

        assertTrue(plan.isEmpty());
    }

    @Test
    void fixedOverheadCountsTowardsTheThreshold() {
        List<Item> items = List.of(tool(1_000), tool(1_000), tool(1_000));

        assertTrue(ContextBudgetPlanner.plan(items, 2_000, SETTINGS).isEmpty()); // 5_000
        assertFalse(ContextBudgetPlanner.plan(items, 3_500, SETTINGS).isEmpty()); // 6_500 -> clears one
    }

    @Test
    void aWindowOfZeroDisablesPlanning() {
        Settings off = new Settings(0, 0.6, 0.85, 2, 100, 48);

        assertTrue(ContextBudgetPlanner.plan(List.of(tool(500_000)), 0, off).isEmpty());
    }

    @Test
    void anEmptyListPlansNothing() {
        assertTrue(ContextBudgetPlanner.plan(List.of(), 9_999, SETTINGS).isEmpty());
    }

    // ---- clearing ----

    @Test
    void clearsEveryOldToolResultAndKeepsTheNewestN() {
        List<Item> items = List.of(tool(2_000), tool(2_000), tool(2_000), tool(2_000), tool(2_000));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS); // 10_000 > 6_000; keep 2 => clear 3

        assertEquals(List.of(0, 1, 2), clears(plan).stream().map(Clear::index).toList());
        assertEquals(10_000 - 3 * (2_000 - 48), plan.tokensAfter());
        assertNull(plan.compaction());
    }

    @Test
    void keepRecentCountsToolResultsOnlyNotOtherItems() {
        List<Item> items = List.of(
            tool(2_500), Item.of(Kind.ASSISTANT, 100), tool(2_500), Item.of(Kind.ASSISTANT, 100), tool(2_500));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS); // keep the last 2 tool results

        assertEquals(List.of(0), clears(plan).stream().map(Clear::index).toList());
    }

    @Test
    void keepRecentOfZeroClearsEveryEligibleResult() {
        Settings keepNone = new Settings(10_000, 0.6, 0.85, 0, 100, 48);

        Plan plan = ContextBudgetPlanner.plan(List.of(tool(4_000), tool(4_000)), 0, keepNone);

        assertEquals(2, plan.clearCount());
    }

    @Test
    void lockedPinnedAndAlreadyClearedResultsAreNeverCleared() {
        List<Item> items = List.of(
            tool(2_000).withLocked(true),
            tool(2_000).withPinned(true),
            tool(2_000).withCleared(true),
            tool(2_000),
            tool(2_000),
            tool(2_000));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS); // 12_000; keep the last 2 (indices 4, 5)

        assertEquals(List.of(3), clears(plan).stream().map(Clear::index).toList());
    }

    @Test
    void aTinyResultIsNotWorthReplacingWithAPlaceholder() {
        List<Item> items = List.of(tool(60), tool(3_000), tool(3_000), tool(3_000));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS); // 9_060; keep last 2 => candidates 0 (tiny), 1

        assertEquals(List.of(1), clears(plan).stream().map(Clear::index).toList());
    }

    @Test
    void theNewestResultsStayEvenWhenTheyAreEnormous() {
        List<Item> items = List.of(tool(80), tool(80), tool(9_000), tool(9_000));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS);

        assertTrue(clears(plan).isEmpty(), "only the two 80-token results are older than the newest two, and they are too small to be worth replacing");
    }

    // ---- compaction ----

    private static List<Item> longConversation() {
        // [system, (u,a) x4 old, (u,a) recent, latest user] - 7_000 tokens of old chat + a big recent tail
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.SYSTEM, 300).withLocked(true));
        for (int i = 0; i < 4; i++) {
            items.add(Item.of(Kind.USER, 1_000));
            items.add(Item.of(Kind.ASSISTANT, 1_000));
        }
        items.add(Item.of(Kind.USER, 500).withRecent(true));
        items.add(Item.of(Kind.ASSISTANT, 500).withRecent(true));
        items.add(Item.of(Kind.USER, 200).withLocked(true));
        return items;
    }

    @Test
    void compactsTheShortestOldestRunThatGetsBackUnderTheClearLine() {
        List<Item> items = longConversation(); // 300 + 8_000 + 1_000 + 200 = 9_500 > 8_500

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS);

        Compact compact = plan.compaction();
        assertNotNull(compact);
        // target 6_000: dropping n pairs leaves 9_500 - 2000n + 100 -> n=2 gives 5_600 <= 6_000
        assertEquals(List.of(1, 2, 3, 4), compact.indices());
        assertEquals(4_000, compact.tokensRemoved());
        assertEquals(5_600, plan.tokensAfter());
    }

    @Test
    void doesNotCompactBetweenTheClearAndCompactLines() {
        List<Item> items = longConversation();
        items.set(1, Item.of(Kind.USER, 200)); // the four old items shrink from 1_000 to 200 tokens each
        items.set(2, Item.of(Kind.ASSISTANT, 200));
        items.set(3, Item.of(Kind.USER, 200));
        items.set(4, Item.of(Kind.ASSISTANT, 200));
        // total now 9_500 - 3_200 = 6_300: above clear-at (nothing to clear), below compact-at

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS);

        assertTrue(plan.isEmpty());
    }

    @Test
    void theProtectedTailAndTheLatestUserAreNeverDropped() {
        List<Item> items = longConversation();

        Compact compact = ContextBudgetPlanner.plan(items, 0, SETTINGS).compaction();

        assertNotNull(compact);
        int tailStart = items.size() - 3;
        assertTrue(compact.indices().stream().allMatch(i -> i < tailStart));
    }

    @Test
    void compactionNeverLeavesAnOrphanedAssistantReply() {
        // Same chat but the tail starts with an assistant item: a cut right before it would orphan it.
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.USER, 3_000));
        items.add(Item.of(Kind.ASSISTANT, 3_000));
        items.add(Item.of(Kind.USER, 3_000));
        items.add(Item.of(Kind.ASSISTANT, 400).withRecent(true));
        items.add(Item.of(Kind.USER, 100).withLocked(true));

        Compact compact = ContextBudgetPlanner.plan(items, 0, SETTINGS).compaction();

        assertNotNull(compact);
        assertEquals(List.of(0, 1), compact.indices(), "only the first exchange ends right before a user turn");
    }

    @Test
    void pinnedConversationItemsSurviveCompaction() {
        List<Item> items = longConversation();
        items.set(1, items.get(1).withPinned(true)); // the oldest user turn is pinned

        Compact compact = ContextBudgetPlanner.plan(items, 0, SETTINGS).compaction();

        assertNotNull(compact);
        assertFalse(compact.indices().contains(1), "a pinned item is never dropped");
        assertTrue(compact.indices().contains(2));
    }

    @Test
    void systemItemsAndToolResultsAreNeverDropped() {
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.SYSTEM, 4_000));
        items.add(tool(4_000));
        items.add(tool(1_000));
        items.add(Item.of(Kind.USER, 100).withLocked(true));

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS);

        assertNull(plan.compaction());
    }

    @Test
    void anExistingSummaryIsRolledIntoTheNextOneButNeverCompactedAlone() {
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.SUMMARY, 400));
        items.add(Item.of(Kind.USER, 4_500));
        items.add(Item.of(Kind.ASSISTANT, 4_500));
        items.add(Item.of(Kind.USER, 100).withLocked(true));

        Compact rolled = ContextBudgetPlanner.plan(items, 0, SETTINGS).compaction();
        assertNotNull(rolled);
        assertTrue(rolled.indices().contains(0), "the old summary is folded into the new one");

        List<Item> onlySummary = List.of(Item.of(Kind.SUMMARY, 9_000), Item.of(Kind.USER, 100).withLocked(true));
        assertNull(ContextBudgetPlanner.plan(onlySummary, 0, SETTINGS).compaction());
    }

    @Test
    void aRunThatCannotReachTheTargetStillCompactsAsMuchAsAllowed() {
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.USER, 1_000));
        items.add(Item.of(Kind.ASSISTANT, 1_000));
        items.add(Item.of(Kind.USER, 9_000).withLocked(true)); // the latest user turn alone busts the budget

        Compact compact = ContextBudgetPlanner.plan(items, 0, SETTINGS).compaction();

        assertNotNull(compact);
        assertEquals(List.of(0, 1), compact.indices());
    }

    @Test
    void compactionIsSkippedWhenItWouldNotSaveMoreThanTheSummaryCosts() {
        Settings bigSummary = new Settings(10_000, 0.6, 0.85, 2, 3_000, 48);
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.USER, 1_000));
        items.add(Item.of(Kind.ASSISTANT, 1_000));
        items.add(Item.of(Kind.USER, 8_000).withLocked(true));

        assertNull(ContextBudgetPlanner.plan(items, 0, bigSummary).compaction());
    }

    @Test
    void clearingRunsBeforeCompactionAndCanMakeCompactionUnnecessary() {
        List<Item> items = new ArrayList<>();
        items.add(Item.of(Kind.USER, 500));
        items.add(Item.of(Kind.ASSISTANT, 500));
        items.add(tool(4_000));
        items.add(tool(4_000));
        items.add(tool(500));
        items.add(tool(500));
        items.add(Item.of(Kind.USER, 100).withLocked(true));
        // 10_100 > 8_500; clearing the two old results saves 2 * (4_000 - 48) -> 2_196, under every line

        Plan plan = ContextBudgetPlanner.plan(items, 0, SETTINGS);

        assertEquals(2, plan.clearCount());
        assertNull(plan.compaction());
        assertTrue(plan.tokensAfter() < 8_500);
    }

    // ---- determinism ----

    @Test
    void planningTwiceOnTheSameInputGivesTheSamePlan() {
        List<Item> items = longConversation();
        items.add(1, tool(2_500));
        items.add(2, tool(2_500));
        items.add(3, tool(2_500));

        assertEquals(ContextBudgetPlanner.plan(items, 123, SETTINGS), ContextBudgetPlanner.plan(items, 123, SETTINGS));
    }

    @Test
    void settingsAreNormalised() {
        Settings s = new Settings(-5, Double.NaN, 0.1, -2, 0, 0);

        assertEquals(0, s.windowTokens());
        assertEquals(Settings.DEFAULT_CLEAR_AT, s.clearAt());
        assertTrue(s.compactAt() >= s.clearAt(), "compact-at can never be below clear-at");
        assertEquals(0, s.keepRecentToolResults());
        assertEquals(1, s.summaryTokens());
        assertEquals(1, s.placeholderTokens());
    }
}
