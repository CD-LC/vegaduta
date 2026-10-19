package com.agenticai.core.agents.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The loop/recursion guard for a chat-time "Crew"-style multi-agent handoff chain - the state
 * threaded through {@code AgentChatService#delegateToSpecialist} and {@code
 * AgentToolProvider#resolveToolsForSpecialistHandoff} as one hop follows another in a single
 * user-facing turn (A delegates to B, B delegates to C, ...).
 *
 * <p>Distinct from {@code AgentChatService#delegateCall}'s existing collaborator mechanism, which
 * caps recursion structurally at depth 1 (a collaborator invoked that way is never itself offered
 * a delegation tool - see {@code AgentToolProvider#resolveToolsForDelegatedCall}). This feature
 * allows a bounded CHAIN of hops instead of a single hop, so the guard has to be a real value
 * (depth counter + visited set) rather than a single boolean flag.
 *
 * <h2>Two independent guarantees</h2>
 * <ul>
 *   <li><b>Depth bound</b> ({@link #MAX_DEPTH}) - {@link #depth()} counts hops already taken;
 *       once it reaches {@code MAX_DEPTH}, {@code AgentToolProvider#resolveSpecialistDelegationTools}
 *       stops offering the delegation tool at all, and {@code AgentChatService#delegateToSpecialist}
 *       independently re-checks this before ever dispatching a call, in case a model attempts the
 *       tool call anyway off a stale tool list.</li>
 *   <li><b>Cycle detection</b> ({@link #wouldCycle}) - {@link #visitedAgentIds()} is the exact
 *       ordered path taken so far (root first); an agent already on that path can never be
 *       re-selected as the next hop, which is what makes "A delegates to B delegates back to A"
 *       impossible even within the depth bound (not just discouraged by prompt wording).</li>
 * </ul>
 *
 * <p>Immutable - {@link #next(UUID)} returns a new instance with one more hop appended, exactly
 * like every other small value-holder in this codebase's tool-resolution path.
 *
 * @param turnSessionId the OUTERMOST (root) chat session id this chain belongs to - constant
 *     across every hop, and the correlation key {@code agent_handoff_audit} rows are queried by.
 * @param rootAgentId the agent the end user was originally talking to when the chain started.
 * @param visitedAgentIds the ordered path taken so far, root first, current agent last. Never
 *     empty - {@link #root} seeds it with exactly {@code [rootAgentId]}.
 * @param depth the number of hops already taken (0 for the root turn, before any handoff).
 */
public record HandoffChain(String turnSessionId, UUID rootAgentId, List<UUID> visitedAgentIds, int depth) {

    /**
     * Maximum number of hops a single chain may take (A -&gt; B -&gt; C is depth 3: three hops,
     * four agents total including the root). Chosen as a small, generous-enough-for-one-turn
     * bound - see the build task's own spec ("max handoff depth, e.g. 3"). Raising this is a
     * behavior change, not a config knob, since a longer chain means more provider calls (and
     * more cost/latency) inside one user-facing turn; it is intentionally a compile-time constant
     * rather than a per-tenant setting until real usage shows a need for one.
     */
    public static final int MAX_DEPTH = 3;

    public HandoffChain {
        visitedAgentIds = visitedAgentIds == null ? List.of() : List.copyOf(visitedAgentIds);
    }

    /** The chain at the very start of a turn, before any handoff - depth 0, path = [rootAgentId]. */
    public static HandoffChain root(String turnSessionId, UUID rootAgentId) {
        return new HandoffChain(turnSessionId, rootAgentId, List.of(rootAgentId), 0);
    }

    /** The agent whose turn is currently executing (the delegating side of the NEXT hop, if any). */
    public UUID currentAgentId() {
        return visitedAgentIds.isEmpty() ? rootAgentId : visitedAgentIds.get(visitedAgentIds.size() - 1);
    }

    /** True once {@link #depth()} has reached {@link #MAX_DEPTH} - no further hop may be offered or dispatched. */
    public boolean isAtMaxDepth() {
        return depth >= MAX_DEPTH;
    }

    /** True if {@code candidateAgentId} already appears anywhere on this chain's path - delegating to it would cycle. */
    public boolean wouldCycle(UUID candidateAgentId) {
        return candidateAgentId != null && visitedAgentIds.contains(candidateAgentId);
    }

    /** The chain after one more hop to {@code nextAgentId} - depth+1, path + nextAgentId. Caller must have already checked {@link #isAtMaxDepth()} and {@link #wouldCycle}. */
    public HandoffChain next(UUID nextAgentId) {
        List<UUID> extended = new ArrayList<>(visitedAgentIds);
        extended.add(nextAgentId);
        return new HandoffChain(turnSessionId, rootAgentId, List.copyOf(extended), depth + 1);
    }

    /** Human-readable path, for refusal messages shown back to the delegating model ("a -> b -> c"). */
    public String describeVisited() {
        return visitedAgentIds.stream().map(UUID::toString).collect(Collectors.joining(" -> "));
    }
}
