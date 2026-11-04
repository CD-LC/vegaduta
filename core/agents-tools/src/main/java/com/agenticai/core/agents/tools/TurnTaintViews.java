package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.TurnTaintView;
import com.agenticai.core.guardrails.taint.TaintMode;
import com.agenticai.core.guardrails.taint.TurnTaint;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Gives a persistent-write tool the taint of the turn it was built for. The memory and knowledge write tools
 * quarantine a write made on a tainted turn and, when the taint state cannot be read, quarantine every write (fail
 * closed). Nothing published a {@link TurnTaintView} bean, so without this every write would have been quarantined.
 *
 * <p>The turn's taint is the {@link TurnTaint} that {@link AgentTaintPolicy#bind} adopted for the turn's step counter:
 * the same record the tool guard consults and the entries (channel, share page, conversation flag) mark. It is read
 * lazily, when the write happens, because marks made during the turn must count.
 *
 * <p>With {@code app.agents.taint.mode=off} the operator has switched the taint layer off, so there is nothing to
 * quarantine on: writes are treated as clean. Without a policy bean at all (a slim context) the supplied fallback is
 * used, and with none writes stay quarantined.
 */
final class TurnTaintViews {

    private static final TurnTaintView CLEAN =
            new TurnTaintView() {
                @Override
                public boolean isTainted() {
                    return false;
                }

                @Override
                public List<String> sources() {
                    return List.of();
                }
            };

    private TurnTaintViews() {}

    /**
     * What tainted the turn behind {@code view}, or null when it is clean, for write tools whose packages
     * must not depend on the memory package. A missing or throwing taint state is reported as tainted (the
     * same fail-closed reading as {@code MemoryWriteGate}).
     */
    static java.util.function.Supplier<List<String>> sources(ObjectProvider<TurnTaintView> view) {
        return () -> {
            com.agenticai.core.agents.memory.MemoryProvenance p =
                    com.agenticai.core.agents.memory.MemoryWriteGate.classify(view);
            return p.quarantined() ? p.sourceTaint() : null;
        };
    }

    static ObjectProvider<TurnTaintView> forTurn(
            ObjectProvider<AgentTaintPolicy> policies,
            AtomicInteger turnKey,
            ObjectProvider<TurnTaintView> fallback) {
        AgentTaintPolicy policy = policies == null ? null : policies.getIfAvailable();
        if (policy == null || turnKey == null) {
            return fallback;
        }
        TurnTaintView view = view(policy, turnKey);
        return new ObjectProvider<>() {
            @Override
            public TurnTaintView getObject(Object... args) {
                return view;
            }

            @Override
            public TurnTaintView getObject() {
                return view;
            }

            @Override
            public TurnTaintView getIfAvailable() {
                return view;
            }

            @Override
            public TurnTaintView getIfUnique() {
                return view;
            }
        };
    }

    private static TurnTaintView view(AgentTaintPolicy policy, AtomicInteger turnKey) {
        if (policy.mode() == TaintMode.OFF) {
            return CLEAN;
        }
        return new TurnTaintView() {
            @Override
            public boolean isTainted() {
                TurnTaint turn = policy.turnTaint(turnKey);
                // No record for this turn means the taint state cannot be read: quarantine.
                return turn == null || turn.isTainted();
            }

            @Override
            public List<String> sources() {
                TurnTaint turn = policy.turnTaint(turnKey);
                return turn == null ? List.of("taint-state-unavailable") : turn.sources();
            }
        };
    }
}
