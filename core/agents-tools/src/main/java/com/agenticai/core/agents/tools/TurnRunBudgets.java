package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.collab.RunBudget;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Hands every tool resolver of one chat turn the SAME {@link RunBudget}. A turn is identified by
 * the {@code stepCounter} that {@code AgentToolProvider#resolveToolsFor} creates once per turn and
 * passes to every resolver (identity key; {@code AtomicInteger} does not override equals). Entries
 * are weakly keyed, so a finished turn's budget is collected with its counter.
 *
 * <p>Without this, each resolver would call the raw factory and get a private budget, and the
 * per-run cap would not actually span delegate_to_team, delegate_parallel and deep_research.
 *
 * <p>Per-agent cap (T4): {@link #bindAgent} records which agent a turn belongs to, so the budget is
 * built lazily from that agent's own cap the first time a resolver asks for it. A turn that was
 * never bound gets the factory's {@code null}-agent (platform default) budget.
 */
final class TurnRunBudgets {

    private final Function<UUID, RunBudget> factory;
    private final Map<AtomicInteger, RunBudget> perTurn =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<AtomicInteger, UUID> turnAgents =
            Collections.synchronizedMap(new WeakHashMap<>());

    TurnRunBudgets(Supplier<RunBudget> factory) {
        this.factory = agentId -> factory.get();
    }

    /**
     * {@code factory} receives the agent bound to the turn, or null when the turn was never bound.
     */
    TurnRunBudgets(Function<UUID, RunBudget> factory) {
        this.factory = factory;
    }

    /** Remembers which agent owns the turn identified by {@code turnKey}. First binding wins. */
    void bindAgent(AtomicInteger turnKey, UUID agentId) {
        if (turnKey != null && agentId != null) {
            turnAgents.putIfAbsent(turnKey, agentId);
        }
    }

    /** The budget shared by all resolvers of the turn identified by {@code turnKey}. */
    RunBudget forTurn(AtomicInteger turnKey) {
        if (turnKey == null) {
            return factory.apply(null);
        }
        // Read the binding before computeIfAbsent: both maps are synchronized on themselves and a
        // nested lookup inside the mapping function would hold two monitors at once.
        UUID agentId = turnAgents.get(turnKey);
        return perTurn.computeIfAbsent(turnKey, k -> factory.apply(agentId));
    }
}
