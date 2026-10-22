package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.json.JsonMappers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import tools.jackson.databind.ObjectMapper;

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
     * Tool-name prefixes treated as read-only (safe to replay). Anything else is assumed mutating.
     */
    private static final List<String> READ_ONLY_PREFIXES =
            List.of(
                    "get",
                    "list",
                    "search",
                    "read",
                    "fetch",
                    "find",
                    "lookup",
                    "look_up",
                    "query",
                    "retrieve",
                    "describe",
                    "check",
                    "view",
                    "recall",
                    "load_skill",
                    "web_search",
                    "browse",
                    "calculate",
                    "count");

    /**
     * Whole words that mark an unclassified tool as mutating even when it starts with a read verb
     * ("get_and_delete").
     */
    private static final java.util.Set<String> MUTATING_WORDS =
            java.util.Set.of(
                    "delete",
                    "remove",
                    "send",
                    "create",
                    "update",
                    "write",
                    "post",
                    "put",
                    "patch",
                    "drop",
                    "click",
                    "type",
                    "submit",
                    "cancel",
                    "pay",
                    "book",
                    "execute",
                    "exec",
                    "run",
                    "set",
                    "add",
                    "insert",
                    "upload",
                    "publish",
                    "reply",
                    "forward",
                    "approve",
                    "transfer",
                    "charge",
                    "purchase",
                    "buy",
                    "sell",
                    "merge",
                    "commit",
                    "push",
                    "kill",
                    "stop",
                    "start",
                    "reset",
                    "modify",
                    "edit",
                    "save",
                    "store",
                    "invoke",
                    "call",
                    "trigger",
                    "email",
                    "message",
                    "sms",
                    "notify");

    /**
     * A low-confidence marker counts only in a SHORT reply ({@code <
     * MAX_LOW_CONFIDENCE_REPLY_CHARS}), within its first {@code LEADING_WINDOW_CHARS}, and only
     * when the turn used no tool results (a reply built on tool output that says "I don't know" is
     * reporting what the tool returned, and a re-run would not know more).
     */
    static final int MAX_LOW_CONFIDENCE_REPLY_CHARS = 400;

    static final int LEADING_WINDOW_CHARS = 240;

    /**
     * Tools never treated as replay-safe even when classified non-durable (http_request can POST).
     */
    private static final java.util.Set<String> NEVER_REPLAY =
            java.util.Set.of("http_request", "http_request_write");

    static final List<String> DEFAULT_LOW_CONFIDENCE_MARKERS =
            List.of(
                    "i'm not sure",
                    "i am not sure",
                    "i don't know",
                    "i do not know",
                    "i'm not certain",
                    "i cannot determine",
                    "i can't determine",
                    "low confidence",
                    "[low-confidence]");

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

    private static final long REGISTRATION_TTL_MS = 10 * 60_000L;

    private final boolean enabled;
    private final boolean killSwitch;
    private final List<String> markers;
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
            @Value("${app.chat.escalation.low-confidence-markers:}") String markersCsv,
            @Value("${app.chat.escalation.max-tool-errors:2}") int maxToolErrors,
            @Value("${app.chat.escalation.economy-cost-ratio:0.2}") double economyCostRatio) {
        this(
                registry.getIfAvailable(),
                enabled,
                killSwitch,
                markersCsv,
                maxToolErrors,
                economyCostRatio);
    }

    /** Test seam: no Spring. */
    VerifierEscalationRouter(
            MeterRegistry registry,
            boolean enabled,
            boolean killSwitch,
            String markersCsv,
            int maxToolErrors,
            double economyCostRatio) {
        this.registry = registry != null ? registry : Metrics.globalRegistry;
        this.enabled = enabled;
        this.killSwitch = killSwitch;
        this.markers = parseMarkers(markersCsv);
        this.maxToolErrors = Math.max(1, maxToolErrors);
        this.economyCostRatio = Math.min(1.0, Math.max(0.0, economyCostRatio));
    }

    private static List<String> parseMarkers(String csv) {
        if (csv == null || csv.isBlank()) {
            return DEFAULT_LOW_CONFIDENCE_MARKERS;
        }
        List<String> out = new ArrayList<>();
        for (String m : csv.split(",")) {
            String t = m.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? DEFAULT_LOW_CONFIDENCE_MARKERS : out;
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
        registrations.values().removeIf(r -> now - r.at() > REGISTRATION_TTL_MS);
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
        boolean empty = answer == null || answer.isBlank();
        boolean parseFailure = false;
        if (!empty && outputSchemaJson != null && !outputSchemaJson.isBlank()) {
            parseFailure = !parsesAsJson(answer);
        }
        boolean lowConfidence = false;
        String trimmed = empty ? "" : answer.strip();
        if (!empty && toolCalls <= 0 && trimmed.length() < MAX_LOW_CONFIDENCE_REPLY_CHARS) {
            // A substantive answer that mentions "I don't know" in passing (quoting, a sub-point, a
            // caveat) is not a low-confidence answer: only a SHORT reply, with the marker in its
            // opening, and no tool results behind it, counts.
            String window = trimmed.substring(0, Math.min(trimmed.length(), LEADING_WINDOW_CHARS));
            String lower = window.toLowerCase(Locale.ROOT);
            for (String m : markers) {
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
        if (toolNames == null) {
            return true;
        }
        for (String name : toolNames) {
            if (name == null) {
                return false;
            }
            if (NEVER_REPLAY.contains(name)) {
                return false;
            }
            // The tool classification is authoritative when the tool has one (browser_click is
            // DURABLE + irreversible there, whatever its name looks like).
            java.util.function.Function<String, Optional<Boolean>> lookup = classification;
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
            for (String prefix : READ_ONLY_PREFIXES) {
                if (n.startsWith(prefix)
                        && (n.length() == prefix.length()
                                || !Character.isLetter(n.charAt(prefix.length())))) {
                    readOnly = true;
                    break;
                }
            }
            if (readOnly) {
                for (String word : n.split("[^a-z]+")) {
                    if (MUTATING_WORDS.contains(word)) {
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
