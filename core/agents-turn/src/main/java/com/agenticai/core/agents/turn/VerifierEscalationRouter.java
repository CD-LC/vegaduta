package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * L4 - verifier-escalated model router: the second half of a cheap-to-strong cascade.
 *
 * <p>{@link QueryComplexityClassifier} decides, BEFORE the call, whether a turn may start on the
 * agent's economy model. It is a guess. This router checks the ANSWER the cheap model gave and,
 * when a cheap deterministic verifier says the answer is not trustworthy, re-runs the turn once on
 * the agent's primary model. Signals (all computed with no extra LLM call):
 *
 * <ul>
 *   <li>structured-output parse failure (the agent declares an output schema and the reply is not
 *       parseable JSON),
 *   <li>explicit low-confidence marker in the reply ("I'm not sure", "I don't know", ...),
 *   <li>tool-error loop (this turn's tool calls recorded {@code >= max-tool-errors} errors),
 *   <li>empty reply,
 *   <li>guardrail flag (accepted in {@link Signals} and honoured by {@link #shouldEscalate}, but
 *       NOT produced: {@link #inspect} always passes false. The outbound guardrail gate audits and
 *       replaces on every block, so a pre-check on the cheap draft would double-audit; stage 2 -
 *       see docs/plans/adr/verifier-escalated-router.md).
 * </ul>
 *
 * <p>Escalation re-runs the whole turn, so it is skipped when the cheap attempt already made a tool
 * call that is not recognisably read-only ({@link #replaySafe}): repeating a send/create/ delete
 * would double the side effect.
 *
 * <p>Scope of the first slice: the BUFFERED reasoning depths (CAREFUL / RIGOROUS / PLANNED),
 * because those hold the whole answer before emitting it. The streaming FAST path cannot be
 * verified before tokens reach the user and is deliberately untouched.
 *
 * <p>Off by default. {@code app.chat.escalation.enabled=false} (and {@code kill-switch=true}, which
 * wins) makes every method here a no-op, so behaviour is byte-identical to before.
 *
 * <p>Every routing decision is a Micrometer counter ({@value #DECISION_METRIC}); estimated savings
 * are counters in "strong-model output-token equivalents" ({@value #SAVED_METRIC}, {@value
 * #WASTED_METRIC}), derived from reply length and {@code app.chat.escalation.economy-cost-ratio} -
 * an estimate, not billing data.
 */
@Component
public class VerifierEscalationRouter {

    private static final Logger log = LoggerFactory.getLogger(VerifierEscalationRouter.class);
    private static final ObjectMapper JSON = JsonMappers.plain();

    public static final String DECISION_METRIC = "chat.router.decision";
    public static final String SAVED_METRIC = "chat.router.estimated_saved_tokens";
    public static final String WASTED_METRIC = "chat.router.estimated_wasted_tokens";

    /**
     * Metric for escalations the verifier wanted but that were skipped because the turn had side
     * effects.
     */
    public static final String SKIPPED_METRIC = "chat.router.escalation_skipped";

    /**
     * The name lists a replay decision reads (read-only prefixes, mutating words, never-replay tools)
     * live in the prompt registry as {@code detector.replay.*} keys (default text: {@code
     * prompts/en/detector.replay.*.md}), so they are data, can be overridden per tenant or agent with
     * acknowledgement, and are documented in docs/prompts.md. See {@link ReplayNames}.
     */
    static final String READ_ONLY_PREFIXES_KEY = "detector.replay.read_only_prefixes";

    static final String MUTATING_WORDS_KEY = "detector.replay.mutating_words";
    static final String NEVER_REPLAY_KEY = "detector.replay.never_replay_tools";

    /**
     * Registry key of the low-confidence marker list. {@code app.chat.escalation.low-confidence-markers} is a
     * deprecated alias for its platform default ({@link PromptPropertyAliases#lowConfidenceMarkers}); this class
     * reads only the registry.
     */
    static final String LOW_CONFIDENCE_MARKERS_KEY = "detector.escalation.low_confidence_markers";

    private final PromptSlot prompts = new PromptSlot();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /**
     * The three replay name lists, resolved for one decision. The shipped lists are a floor for the
     * mutating words and the never-replay tools: an override can add to them, never remove from them,
     * so a tenant edit cannot make a mutating tool replayable. The read-only prefixes are replaced by
     * the override (removing a prefix only makes the check stricter).
     */
    record ReplayNames(List<String> readOnlyPrefixes, Set<String> mutatingWords, Set<String> neverReplay) {

        private static final class Shipped {
            static final ReplayNames VALUE = build(new PromptSlot(), null, null);
        }

        /** The shipped lists, with no tenant or agent override (hand-built code, tests, static callers). */
        static ReplayNames shipped() {
            return Shipped.VALUE;
        }

        static ReplayNames resolve(PromptSlot slot, UUID agentId) {
            return build(slot, agentId, shipped());
        }

        private static ReplayNames build(PromptSlot slot, UUID agentId, ReplayNames floor) {
            List<String> prefixes = lower(slot.linesAllLocales(READ_ONLY_PREFIXES_KEY, agentId));
            Set<String> mutating = new LinkedHashSet<>(lower(slot.linesAllLocales(MUTATING_WORDS_KEY, agentId)));
            Set<String> never = new LinkedHashSet<>(slot.linesAllLocales(NEVER_REPLAY_KEY, agentId));
            if (floor != null) {
                mutating.addAll(floor.mutatingWords());
                never.addAll(floor.neverReplay());
            }
            return new ReplayNames(List.copyOf(prefixes), Set.copyOf(mutating), Set.copyOf(never));
        }

        private static List<String> lower(List<String> in) {
            return in.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
        }
    }

    /**
     * A low-confidence marker counts only in a SHORT reply ({@code <
     * app.chat.limits.escalation.low-confidence-max-chars}), within its first {@code
     * low-confidence-leading-chars}, and only
     * when the turn used no tool results (a reply built on tool output that says "I don't know" is
     * reporting what the tool returned, and a re-run would not know more).
     */
    // Both bounds are app.chat.limits.escalation.* (ChatLimitsProperties): low-confidence-max-chars and
    // low-confidence-leading-chars.

    /** Why a cheap answer was rejected. */
    public enum Reason {
        PARSE_FAILURE,
        GUARDRAIL_FLAG,
        LOW_CONFIDENCE,
        TOOL_ERROR_LOOP,
        EMPTY_REPLY
    }

    /** Verifier inputs for one cheap-model answer. */
    public record Signals(
            boolean parseFailure,
            boolean guardrailFlag,
            boolean lowConfidence,
            int toolErrors,
            boolean emptyReply) {
        static Signals none() {
            return new Signals(false, false, false, 0, false);
        }
    }

    /** Escalation target registered for a cheap client; weakly holds the client it applies to. */
    private record Registration(
            WeakReference<ChatClient> cheap, Supplier<ChatClient> strong, long at) {}

    /** {@code app.chat.limits.escalation.*}; a hand-built instance keeps the shipped defaults. */
    private volatile ChatLimitsProperties.Escalation limits = new ChatLimitsProperties().getEscalation();

    @Autowired(required = false)
    void useLimits(ChatLimitsProperties properties) {
        this.limits = (properties == null ? new ChatLimitsProperties() : properties).getEscalation();
    }

    private final boolean enabled;
    private final boolean killSwitch;
    private final int maxToolErrors;
    private final double economyCostRatio;
    private final MeterRegistry registry;
    private final Map<UUID, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * Tool-classification replay-safety lookup (see ToolReplaySafety, implemented by
     * AgentToolGovernance in agents-tools); null = name fallback only.
     */
    private volatile java.util.function.Function<String, Optional<Boolean>> classification;

    @Autowired(required = false)
    void setToolGovernance(
            ObjectProvider<com.agenticai.core.agents.tools.ToolReplaySafety> provider) {
        com.agenticai.core.agents.tools.ToolReplaySafety g =
                provider == null ? null : provider.getIfAvailable();
        this.classification = g == null ? null : g::replaySafeVerdict;
    }

    /** Test seam. */
    void useClassification(java.util.function.Function<String, Optional<Boolean>> lookup) {
        this.classification = lookup;
    }

    @Autowired
    public VerifierEscalationRouter(
            ObjectProvider<MeterRegistry> registry,
            @Value("${app.chat.escalation.enabled:false}") boolean enabled,
            @Value("${app.chat.escalation.kill-switch:false}") boolean killSwitch,
            @Value("${app.chat.escalation.max-tool-errors:2}") int maxToolErrors,
            @Value("${app.chat.escalation.economy-cost-ratio:0.2}") double economyCostRatio) {
        this(
                registry.getIfAvailable(),
                enabled,
                killSwitch,
                maxToolErrors,
                economyCostRatio);
    }

    /** Test seam: no Spring. */
    VerifierEscalationRouter(
            MeterRegistry registry,
            boolean enabled,
            boolean killSwitch,
            int maxToolErrors,
            double economyCostRatio) {
        this.registry = registry != null ? registry : Metrics.globalRegistry;
        this.enabled = enabled;
        this.killSwitch = killSwitch;
        this.maxToolErrors = Math.max(1, maxToolErrors);
        this.economyCostRatio = Math.min(1.0, Math.max(0.0, economyCostRatio));
    }

    /** True only when the feature is on and the kill switch is off. */
    public boolean isActive() {
        return enabled && !killSwitch;
    }

    // ---- registration (set when a turn is routed to the economy model) ----

    /**
     * Records that {@code cheapClient} serves this agent's current turn and how to obtain the
     * agent's primary-model client. No-op when inactive. Last writer wins per agent; a lookup that
     * does not match the registered client simply does not escalate (fail-safe).
     */
    void registerCheap(UUID agentId, ChatClient cheapClient, Supplier<ChatClient> strong) {
        if (!isActive() || agentId == null || cheapClient == null || strong == null) {
            return;
        }
        long now = System.currentTimeMillis();
        registrations.values().removeIf(r -> now - r.at() > limits.getRegistrationTtl().toMillis());
        registrations.put(agentId, new Registration(new WeakReference<>(cheapClient), strong, now));
    }

    /**
     * The strong-client supplier when {@code usedClient} is the registered cheap client for this
     * agent.
     */
    Optional<Supplier<ChatClient>> escalationFor(UUID agentId, ChatClient usedClient) {
        if (!isActive() || agentId == null || usedClient == null) {
            return Optional.empty();
        }
        Registration r = registrations.get(agentId);
        if (r == null || r.cheap().get() != usedClient) {
            return Optional.empty();
        }
        return Optional.of(r.strong());
    }

    // ---- verifier ----

    /** Computes the deterministic verifier signals for one answer. */
    Signals inspect(String answer, String outputSchemaJson, int toolErrors) {
        return inspect(answer, outputSchemaJson, toolErrors, 0);
    }

    /**
     * @param outputSchemaJson the schema THIS turn is contractually bound to, or null/blank when
     *     the turn has none (a plain chat reply must never be parse-checked)
     * @param toolCalls tool calls the turn made; any tool result suppresses the low-confidence
     *     signal
     */
    Signals inspect(String answer, String outputSchemaJson, int toolErrors, int toolCalls) {
        return inspect(answer, outputSchemaJson, toolErrors, toolCalls, null);
    }

    /**
     * As {@link #inspect(String, String, int, int)}; {@code agentId} (null = unknown) lets an
     * agent-scope override of the low-confidence marker list apply.
     */
    Signals inspect(String answer, String outputSchemaJson, int toolErrors, int toolCalls, UUID agentId) {
        boolean empty = answer == null || answer.isBlank();
        boolean parseFailure = false;
        if (!empty && outputSchemaJson != null && !outputSchemaJson.isBlank()) {
            parseFailure = !parsesAsJson(answer);
        }
        boolean lowConfidence = false;
        String trimmed = empty ? "" : answer.strip();
        if (!empty && toolCalls <= 0 && trimmed.length() < limits.getLowConfidenceMaxChars()) {
            // A substantive answer that mentions "I don't know" in passing (quoting, a sub-point, a
            // caveat) is not a low-confidence answer: only a SHORT reply, with the marker in its
            // opening, and no tool results behind it, counts.
            String window = trimmed.substring(0, Math.min(trimmed.length(), limits.getLowConfidenceLeadingChars()));
            String lower = window.toLowerCase(Locale.ROOT);
            for (String m : markers(agentId)) {
                if (lower.contains(m)) {
                    lowConfidence = true;
                    break;
                }
            }
        }
        return new Signals(parseFailure, false, lowConfidence, Math.max(0, toolErrors), empty);
    }

    /**
     * True when every tool the cheap attempt called looks read-only by name, so re-running the turn
     * cannot repeat a side effect. Unknown names are treated as mutating (fail-safe: no
     * escalation).
     */
    boolean replaySafe(List<String> toolNames) {
        return replaySafe(toolNames, (UUID) null);
    }

    /**
     * As {@link #replaySafe(List)}, with the name lists resolved for {@code agentId} (null = tenant-wide
     * and shipped lists).
     */
    boolean replaySafe(List<String> toolNames, UUID agentId) {
        return replaySafe(toolNames, classification, ReplayNames.resolve(prompts, agentId));
    }

    /** The low-confidence markers: the registry list for this agent. */
    private List<String> markers(UUID agentId) {
        return prompts.linesAllLocales(LOW_CONFIDENCE_MARKERS_KEY, agentId).stream()
                .map(m -> m.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * As {@link #replaySafe(List)}, against an explicit classification lookup ({@code null} = names
     * only). Shared with {@link FailoverReplayGate}, which asks the same question of the tools a turn ran
     * before it fails over to another provider.
     */
    static boolean replaySafe(
            List<String> toolNames, java.util.function.Function<String, Optional<Boolean>> lookup) {
        return replaySafe(toolNames, lookup, ReplayNames.shipped());
    }

    /** As above, against explicit name lists (see {@link ReplayNames}). */
    static boolean replaySafe(
            List<String> toolNames,
            java.util.function.Function<String, Optional<Boolean>> lookup,
            ReplayNames names) {
        if (toolNames == null) {
            return true;
        }
        for (String name : toolNames) {
            if (name == null) {
                return false;
            }
            if (names.neverReplay().contains(name)) {
                return false;
            }
            // The tool classification is authoritative when the tool has one (browser_click is
            // DURABLE + irreversible there, whatever its name looks like).
            if (lookup != null) {
                Optional<Boolean> verdict;
                try {
                    verdict = lookup.apply(name);
                } catch (RuntimeException e) {
                    verdict = Optional.empty();
                }
                if (verdict != null && verdict.isPresent()) {
                    if (!verdict.get()) {
                        return false;
                    }
                    continue;
                }
            }
            // Unclassified (MCP/custom tools): conservative name heuristic. The verb must be a
            // whole
            // leading word ("browse" matches browse_page, never browser_click).
            String n = name.toLowerCase(Locale.ROOT);
            boolean readOnly = false;
            for (String prefix : names.readOnlyPrefixes()) {
                if (n.startsWith(prefix)
                        && (n.length() == prefix.length()
                                || !Character.isLetter(n.charAt(prefix.length())))) {
                    readOnly = true;
                    break;
                }
            }
            if (readOnly) {
                for (String word : n.split("[^a-z]+")) {
                    if (names.mutatingWords().contains(word)) {
                        readOnly = false;
                        break;
                    }
                }
            }
            if (!readOnly) {
                return false;
            }
        }
        return true;
    }

    /**
     * Counts an escalation the verifier asked for but that was skipped to avoid repeating tool side
     * effects.
     */
    void recordSkippedForSideEffects(UUID agentId, Reason reason) {
        if (!isActive()) {
            return;
        }
        try {
            Counter.builder(SKIPPED_METRIC)
                    .tags("reason", reason.name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
            log.info(
                    "Router skipped escalation agent={} reason={} (turn made non-read-only tool calls)",
                    agentId,
                    reason);
        } catch (RuntimeException e) {
            log.debug("Router metric recording failed (ignored): {}", e.toString());
        }
    }

    private static boolean parsesAsJson(String answer) {
        String s = answer.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        try {
            JSON.readTree(s);
            return s.startsWith("{") || s.startsWith("[");
        } catch (Exception e) {
            return false;
        }
    }

    /** First matching reason in priority order, or empty when the cheap answer stands. */
    Optional<Reason> shouldEscalate(Signals s) {
        if (!isActive() || s == null) {
            return Optional.empty();
        }
        if (s.emptyReply()) {
            return Optional.of(Reason.EMPTY_REPLY);
        }
        if (s.parseFailure()) {
            return Optional.of(Reason.PARSE_FAILURE);
        }
        if (s.guardrailFlag()) {
            return Optional.of(Reason.GUARDRAIL_FLAG);
        }
        if (s.toolErrors() >= maxToolErrors) {
            return Optional.of(Reason.TOOL_ERROR_LOOP);
        }
        if (s.lowConfidence()) {
            return Optional.of(Reason.LOW_CONFIDENCE);
        }
        return Optional.empty();
    }

    // ---- metrics ----

    /**
     * Logs and counts one decision. {@code cheapAnswerChars} sizes the savings estimate: a kept
     * cheap answer saved {@code (1 - ratio)} of a strong answer's cost; an escalated one wasted the
     * {@code ratio} share already spent on the cheap attempt.
     */
    void recordDecision(UUID agentId, Optional<Reason> escalatedFor, int cheapAnswerChars) {
        if (!isActive()) {
            return;
        }
        try {
            double tokens = Math.max(0, cheapAnswerChars) / 4.0;
            String outcome = escalatedFor.isPresent() ? "escalated" : "kept_cheap";
            String reason = escalatedFor.map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("none");
            Counter.builder(DECISION_METRIC)
                    .tags("outcome", outcome, "reason", reason)
                    .register(registry)
                    .increment();
            if (escalatedFor.isPresent()) {
                Counter.builder(WASTED_METRIC)
                        .register(registry)
                        .increment(tokens * economyCostRatio);
            } else {
                Counter.builder(SAVED_METRIC)
                        .register(registry)
                        .increment(tokens * (1.0 - economyCostRatio));
            }
            log.info(
                    "Router decision agent={} outcome={} reason={} cheapAnswerChars={}",
                    agentId,
                    outcome,
                    reason,
                    cheapAnswerChars);
        } catch (RuntimeException e) {
            log.debug("Router metric recording failed (ignored): {}", e.toString());
        }
    }
}
