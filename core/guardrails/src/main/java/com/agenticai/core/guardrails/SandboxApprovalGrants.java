package com.agenticai.core.guardrails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The sandbox auto-approval of an offline eval turn: the one way a gated tool call runs WITHOUT a human
 * deciding it, so that an eval can exercise the real tool pipeline (tools, guardrails, step limits)
 * instead of a text-only stand-in. It is deliberately narrow:
 *
 * <ul>
 *   <li><b>Per turn, by an unguessable key.</b> The grant is registered under the turn's request id,
 *       which the caller of the turn (the eval runner, server-side code) generates itself; a client
 *       cannot name it, so a chat request cannot opt itself in. The turn's tool guards reach it through
 *       {@link TurnApprovalState#sandboxGrant()}, set once when the turn's approval state is built.
 *   <li><b>Only a bypass of the park, nothing else.</b> {@link GuardedToolCallback} still applies the
 *       dispatch gate (DENY), procedure rules, the verifier, the step ceiling, argument policies, output
 *       caps and guardrails. Only the step "this call needs a human, record it and stop" is skipped.
 *   <li><b>Never for the excluded tools.</b> {@link #permits} refuses the tool names and globs the
 *       caller listed (shell, coding, scheduling, agent-to-agent calls and anything that moves money by
 *       default, see {@code app.quality.eval.auto-approve.excluded-tools}); those park as in production.
 *   <li><b>Counted and attributed.</b> Every call the grant let through is counted and named, and logged
 *       with the actor, so a run can say it ran under auto-approval and how many calls that covered.
 * </ul>
 *
 * <p><b>The same registration also isolates the turn</b> when it is made with {@link #registerSandbox}: the grant
 * then carries the {@link ToolEffectSandbox} rule that decides which tools may really run, a call that would wait
 * for a human is held WITHOUT recording an approval request (so nothing reaches an approver's inbox, mail or chat),
 * and the grant counts what was simulated and what was held. A grant made with only {@link #register} keeps the
 * narrow behaviour above and nothing else.
 *
 * Whether a tenant may use this at all is decided by the caller (the eval runner checks the operator's
 * non-production tenant list before it registers a grant); this class only keeps the grants of the turns
 * currently running.
 */
public final class SandboxApprovalGrants {

    private static final ConcurrentMap<String, Grant> BY_TURN = new ConcurrentHashMap<>();

    private SandboxApprovalGrants() {}

    /**
     * Registers a grant for the turn whose request id is {@code turnRequestId}; close the returned
     * registration in a {@code finally} so the grant never outlives the turn.
     *
     * @param actor who the auto-approval is attributed to in logs (never a person)
     * @param excludedToolPatterns tool names or {@code *} globs that are never auto-approved
     */
    public static Registration register(String turnRequestId, String actor, Collection<String> excludedToolPatterns) {
        Objects.requireNonNull(turnRequestId, "turnRequestId");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("A sandbox approval grant needs an actor name.");
        }
        Grant grant = new Grant(
            actor.strip(), true, excludedToolPatterns == null ? List.of() : excludedToolPatterns, null);
        return put(turnRequestId, grant);
    }

    /**
     * Registers the isolation of an eval turn: tools run only as {@code effects} allows (anything else is answered
     * with a simulated result), a call that needs a human is held without recording a request, and, when
     * {@code approvalActor} is not blank, a held call the grant permits is approved by the sandbox instead
     * (attributed to that actor, never for {@code excludedToolPatterns}). With a blank actor nothing is
     * auto-approved: every gated call is held, as it would be in production.
     *
     * @param approvalActor who auto-approvals are attributed to; {@code null} or blank = no auto-approval
     * @param effects which tools may really run; required
     */
    public static Registration registerSandbox(
        String turnRequestId, String approvalActor, Collection<String> excludedToolPatterns, ToolEffectSandbox effects
    ) {
        Objects.requireNonNull(turnRequestId, "turnRequestId");
        Objects.requireNonNull(effects, "an isolated eval turn needs the rule that decides which tools may run");
        boolean approves = approvalActor != null && !approvalActor.isBlank();
        Grant grant = new Grant(
            approves ? approvalActor.strip() : null, approves,
            excludedToolPatterns == null ? List.of() : excludedToolPatterns, effects);
        return put(turnRequestId, grant);
    }

    private static Registration put(String turnRequestId, Grant grant) {
        if (BY_TURN.putIfAbsent(turnRequestId, grant) != null) {
            throw new IllegalStateException("A sandbox approval grant is already registered for turn " + turnRequestId);
        }
        return new Registration(turnRequestId, grant);
    }

    /** The grant of the turn with this request id, or {@code null} when it has none (every ordinary turn). */
    public static Grant forTurn(String turnRequestId) {
        return turnRequestId == null ? null : BY_TURN.get(turnRequestId);
    }

    /** Number of turns that currently hold a grant (for tests and leak checks). */
    static int activeCount() {
        return BY_TURN.size();
    }

    /** A registered grant, removed from the registry on {@link #close}. */
    public static final class Registration implements AutoCloseable {
        private final String turnRequestId;
        private final Grant grant;

        private Registration(String turnRequestId, Grant grant) {
            this.turnRequestId = turnRequestId;
            this.grant = grant;
        }

        public Grant grant() {
            return grant;
        }

        @Override
        public void close() {
            BY_TURN.remove(turnRequestId, grant);
        }
    }

    /** What one turn may auto-approve, and what it did. */
    public static final class Grant {
        private final String actor;
        private final List<Pattern> excluded;
        private final List<String> excludedSource;
        private final AtomicInteger used = new AtomicInteger();
        private final List<String> usedTools = new CopyOnWriteArrayList<>();
        private final boolean approves;
        private final ToolEffectSandbox effects;
        private final List<String> simulatedTools = new CopyOnWriteArrayList<>();
        private final List<String> heldTools = new CopyOnWriteArrayList<>();

        private Grant(String actor, boolean approves, Collection<String> excludedToolPatterns, ToolEffectSandbox effects) {
            this.actor = actor;
            this.approves = approves;
            this.effects = effects;
            List<Pattern> compiled = new ArrayList<>();
            List<String> source = new ArrayList<>();
            for (String raw : excludedToolPatterns) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String glob = raw.strip();
                source.add(glob);
                compiled.add(globToPattern(glob));
            }
            this.excluded = List.copyOf(compiled);
            this.excludedSource = List.copyOf(source);
        }

        /** Who auto-approvals are attributed to; {@code null} for an isolated turn that approves nothing. */
        public String actor() {
            return actor;
        }

        /** True when this grant auto-approves held calls at all (an isolation-only grant does not). */
        public boolean approves() {
            return approves;
        }

        /** True when the turn is isolated: tool effects are decided by {@link #effects()} and no approval is recorded. */
        public boolean isolated() {
            return effects != null;
        }

        /** The rule that decides which tools may really run, or {@code null} for a grant that does not isolate. */
        public ToolEffectSandbox effects() {
            return effects;
        }

        /** Records that a call of {@code toolName} was answered with a simulated result instead of running. */
        public void recordSimulated(String toolName) {
            simulatedTools.add(toolName);
        }

        /** Records that a call of {@code toolName} was held for approval without a request being recorded. */
        public void recordHeld(String toolName) {
            heldTools.add(toolName);
        }

        /** The tool names of the calls that were simulated, in order. */
        public List<String> simulatedTools() {
            return List.copyOf(simulatedTools);
        }

        /** The tool names of the calls that were held for approval (and not recorded), in order. */
        public List<String> heldTools() {
            return List.copyOf(heldTools);
        }

        /** True when {@code toolName} may be auto-approved (the grant approves, and it matches no excluded name or glob). */
        public boolean permits(String toolName) {
            if (toolName == null || !approves) {
                return false;
            }
            String name = toolName.toLowerCase(Locale.ROOT);
            for (Pattern p : excluded) {
                if (p.matcher(name).matches()) {
                    return false;
                }
            }
            return true;
        }

        /** Records that a gated call of {@code toolName} was let through. */
        public void recordUse(String toolName) {
            used.incrementAndGet();
            usedTools.add(toolName);
        }

        /** How many gated calls this grant let through. */
        public int usedCount() {
            return used.get();
        }

        /** The tool names of the calls this grant let through, in order. */
        public List<String> usedTools() {
            return List.copyOf(usedTools);
        }

        /** The excluded names and globs as given. */
        public List<String> excludedPatterns() {
            return excludedSource;
        }
    }

    /** {@code *} matches any run of characters; everything else is literal; case-insensitive. */
    static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString());
    }
}
