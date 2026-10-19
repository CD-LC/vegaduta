package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.collab.RunBudgetCaps;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** T4: the turn's shared RunBudget is built from the cap of the agent bound to that turn. */
class TurnRunBudgetsAgentBindingTest {

    private static final BigDecimal GLOBAL = new BigDecimal("2.0");

    /**
     * Same shape as the factory AgentToolProvider installs: platform cap lowered by the agent's
     * own.
     */
    private static TurnRunBudgets budgets(Map<UUID, BigDecimal> agentCaps, List<UUID> seen) {
        return new TurnRunBudgets(
                (UUID agentId) -> {
                    seen.add(agentId);
                    BigDecimal cap =
                            RunBudgetCaps.effectiveCap(
                                    GLOBAL,
                                    agentId == null ? null : agentCaps.get(agentId),
                                    true,
                                    new BigDecimal("10"));
                    return new RunBudget(cap, new BigDecimal("3"), new BigDecimal("15"), 8000);
                });
    }

    @Test
    void boundTurnGetsTheAgentsLowerCapAndSharesOneBudget() {
        UUID frugal = UUID.randomUUID();
        List<UUID> seen = new ArrayList<>();
        TurnRunBudgets budgets = budgets(Map.of(frugal, new BigDecimal("0.5")), seen);
        AtomicInteger turn = new AtomicInteger();

        budgets.bindAgent(turn, frugal);
        RunBudget first = budgets.forTurn(turn);

        assertEquals(0, new BigDecimal("0.5").compareTo(first.capUsd()));
        assertSame(first, budgets.forTurn(turn));
        assertEquals(List.of(frugal), seen, "the agent is read once per turn, lazily");
    }

    @Test
    void agentAskingForMoreThanThePlatformCapIsClampedToIt() {
        UUID greedy = UUID.randomUUID();
        TurnRunBudgets budgets = budgets(Map.of(greedy, new BigDecimal("9")), new ArrayList<>());
        AtomicInteger turn = new AtomicInteger();

        budgets.bindAgent(turn, greedy);

        assertEquals(0, GLOBAL.compareTo(budgets.forTurn(turn).capUsd()));
    }

    @Test
    void unboundOrNullTurnFallsBackToThePlatformCap() {
        List<UUID> seen = new ArrayList<>();
        TurnRunBudgets budgets = budgets(Map.of(), seen);

        assertEquals(0, GLOBAL.compareTo(budgets.forTurn(new AtomicInteger()).capUsd()));
        assertEquals(0, GLOBAL.compareTo(budgets.forTurn(null).capUsd()));
        budgets.bindAgent(null, UUID.randomUUID());
        budgets.bindAgent(new AtomicInteger(), null);
        List<UUID> expected = new ArrayList<>();
        expected.add(null);
        expected.add(null);
        assertEquals(expected, seen);
    }

    @Test
    void firstBindingWinsSoADelegateCannotRebindTheParentsTurn() {
        UUID parent = UUID.randomUUID();
        UUID delegate = UUID.randomUUID();
        TurnRunBudgets budgets =
                budgets(
                        Map.of(parent, new BigDecimal("0.5"), delegate, new BigDecimal("1.5")),
                        new ArrayList<>());
        AtomicInteger turn = new AtomicInteger();

        budgets.bindAgent(turn, parent);
        budgets.bindAgent(turn, delegate);

        assertEquals(0, new BigDecimal("0.5").compareTo(budgets.forTurn(turn).capUsd()));
    }
}
