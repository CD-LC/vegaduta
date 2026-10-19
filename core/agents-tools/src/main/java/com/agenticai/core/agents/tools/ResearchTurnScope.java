package com.agenticai.core.agents.tools;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Makes the deep_research ceilings and shared RunBudget span a whole USER turn instead of one tool
 * resolution. A delegate agent (ask_agent_*, delegate_parallel, a specialist handoff) resolves its
 * OWN tool set with a fresh {@code stepCounter}, so without this every delegate got a fresh
 * search/fetch allowance and a fresh budget.
 *
 * <p>Mechanism: the delegation tools are wrapped with {@link #bind}, which publishes the PARENT
 * turn's key (its stepCounter, the same identity key {@link TurnRunBudgets} uses) on an {@link
 * InheritableThreadLocal} for the duration of the call. The delegate resolves its tools inside that
 * call, on that thread or on a virtual thread the call spawned (swarm branches), and reads the key
 * back with {@link #turnKey}. Everything keyed by the turn key - the {@code DeepResearchSession}
 * and the RunBudget - is then the parent's. The thread-local is set and restored around the call,
 * never leaked.
 *
 * <p>The resolution {@link Mode} is a plain thread-local read only during resolution (same thread).
 */
final class ResearchTurnScope {

    /**
     * What the resolution is for; decides whether research tools are real, stubbed, or listed only.
     */
    enum Mode {
        /**
         * A normal chat turn: tools are offered only when opted in or the message looks like
         * research.
         */
        TURN,
        /**
         * Edge / approval-replay lookup-by-name: tools exist so the name resolves, but fail closed.
         */
        STATELESS,
        /** Tool discovery listing: real definitions, never invoked, never sent to a model. */
        DISCOVERY
    }

    private static final InheritableThreadLocal<AtomicInteger> PARENT_TURN =
            new InheritableThreadLocal<>();
    private static final ThreadLocal<Mode> MODE = new ThreadLocal<>();

    private ResearchTurnScope() {}

    /**
     * The key of the turn this resolution belongs to: the delegating parent's when inside a
     * delegation.
     */
    static AtomicInteger turnKey(AtomicInteger own) {
        AtomicInteger parent = PARENT_TURN.get();
        return parent != null ? parent : own;
    }

    static Mode mode() {
        Mode m = MODE.get();
        return m == null ? Mode.TURN : m;
    }

    static <T> T withMode(Mode mode, Supplier<T> body) {
        Mode prior = MODE.get();
        MODE.set(mode);
        try {
            return body.get();
        } finally {
            if (prior == null) {
                MODE.remove();
            } else {
                MODE.set(prior);
            }
        }
    }

    /**
     * Wraps a delegation tool so everything it resolves or spawns shares {@code parentTurn}'s
     * research state.
     */
    static ToolCallback bind(ToolCallback delegation, AtomicInteger parentTurn) {
        return new Bound(delegation, parentTurn);
    }

    /**
     * Binds a delegation tool at {@code resolverCounter}'s TURN KEY, and only when research is
     * enabled. Using {@link #turnKey} (not the raw counter) keeps A -> B -> C on A's allowance:
     * when B resolves inside A's bound call it re-binds A's key instead of overwriting it with its
     * own. Disabled returns the callback untouched, so the flag-off path adds no wrapper and no
     * thread-local.
     */
    static ToolCallback bindIfEnabled(
            boolean researchEnabled, ToolCallback delegation, AtomicInteger resolverCounter) {
        return researchEnabled ? bind(delegation, turnKey(resolverCounter)) : delegation;
    }

    private static final class Bound implements ToolCallback {
        private final ToolCallback inner;
        private final AtomicInteger parentTurn;

        Bound(ToolCallback inner, AtomicInteger parentTurn) {
            this.inner = inner;
            this.parentTurn = parentTurn;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return inner.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return inner.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return within(() -> inner.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return within(() -> inner.call(toolInput, toolContext));
        }

        private String within(Supplier<String> body) {
            if (parentTurn == null) {
                return body.get();
            }
            AtomicInteger prior = PARENT_TURN.get();
            PARENT_TURN.set(parentTurn);
            try {
                return body.get();
            } finally {
                if (prior == null) {
                    PARENT_TURN.remove();
                } else {
                    PARENT_TURN.set(prior);
                }
            }
        }
    }

    /**
     * Turn-keyed research sessions; weakly keyed so a finished turn's evidence is collected with
     * its counter.
     */
    static final class Sessions<S> {
        private final Map<AtomicInteger, S> perTurn =
                Collections.synchronizedMap(new WeakHashMap<>());

        S forTurn(AtomicInteger turnKey, Supplier<S> factory) {
            if (turnKey == null) {
                return factory.get();
            }
            return perTurn.computeIfAbsent(turnKey, k -> factory.get());
        }
    }
}
