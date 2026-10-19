package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.decision.DecisionEngine;
import com.agenticai.core.agents.decision.DecisionPolicyView;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Master Roadmap 2026 item #18 fast-follow (#1077) - the router half of
 * RouteLLM/NotDiamond-style cost optimization. Given one turn's user
 * message, decides whether it's simple enough to hand to an agent's cheaper
 * "economy" model instead of its normal (primary) model - see {@link
 * Agent#isCostRoutingEnabled()}'s javadoc for the full per-agent opt-in
 * design this feeds into.
 *
 * <p><b>Deterministic heuristic, not a trained/learned router - a
 * deliberate scope decision, same reasoning {@link LlmResponseCacheService}
 * documents for choosing exact-match over embedding similarity.</b> RouteLLM
 * and NotDiamond both train a classifier (or use an LLM-as-judge call) to
 * predict whether a strong model's answer would meaningfully outperform a
 * weak model's for a given prompt. That requires labeled preference data and
 * either a hosted classifier or an extra LLM round-trip per turn (which
 * would partly defeat the point of a COST-saving feature). A cheap,
 * inspectable heuristic - short, single-part, no code/no structured-output
 * cues - captures the highest-confidence "this is obviously simple" slice
 * with zero extra latency and zero extra spend, at the cost of being more
 * conservative (some borderline-simple turns still route to the primary
 * model). A learned router is a real, natural follow-up once there's usage
 * data to train one from - not a permanently skipped gap.
 *
 * <p><b>Jev is that follow-up, and it arrived.</b> TypeSafe's Jev (reached
 * through {@link DecisionEngine}, the DECISION capability's provider facade)
 * is a dedicated decision model - one cheap calibrated
 * yes/no call, not a frontier-model judge - so the "an extra LLM round-trip
 * per turn would defeat the point" objection above no longer holds: a Jev
 * {@code noul} call costs a fraction of a cent and ~one LAN round trip of
 * latency, against a per-turn saving that is the entire point of cost
 * routing. When Jev is configured ({@code app.jev.complexity.enabled} +
 * a TypeSafe key) it gets a say, but ONLY to rescue the heuristic's known
 * false negatives: the heuristic still runs first, a heuristic "simple" is
 * never second-guessed (it is already the high-precision half of the
 * design), and only a heuristic "not simple" is offered to Jev for a
 * second opinion. Jev unreachable/unsure → heuristic answer stands.
 *
 * <p><b>False negatives (routing a simple turn to the primary model) are
 * the safe failure mode; false positives (routing a complex turn to the
 * economy model) are not.</b> Every threshold below - and the Jev {@code
 * simple-threshold}, deliberately set high - is chosen to fail toward "when
 * in doubt, keep the primary model": this feature only ever REDUCES which
 * turns get the discount, it never risks giving a complex question a
 * degraded answer by being too aggressive.
 */
@Component
public class QueryComplexityClassifier {

    /**
     * The calibrated {@code noul} question Jev answers for every heuristic
     * "not simple" turn. Phrased so YES = "safe to downgrade"; the threshold
     * below then keeps the asymmetric-risk posture - Jev must be quite sure
     * before we hand a turn the heuristic rejected to the economy model.
     */
    static final String JEV_SIMPLE_INSTRUCTIONS =
        "Could a smaller, cheaper AI model answer this user message just as well as a large frontier "
            + "model? Answer YES for short factual questions, greetings, simple lookups and single "
            + "instructions; NO for anything needing multi-step reasoning, code, analysis, long or "
            + "structured output, or careful judgment.";

    /** Rescue-only posture: Jev must be this sure it's economy-safe to override a heuristic "not simple". */
    private final DecisionEngine decisions;
    private final boolean jevComplexityEnabled;
    private final double jevSimpleThreshold;

    /**
     * Turns longer than this are assumed to be doing more than asking one
     * short question - most single-sentence FAQ-style questions ("what are
     * your hours", "how do I reset my password") comfortably fit under this;
     * anything longer is treated as at least possibly requiring real
     * reasoning, multi-step instructions, or provided context to work with.
     */
    private static final int SIMPLE_MAX_LENGTH = 160;

    /** More than one real sentence/question in a single turn reads as a compound, multi-part ask. */
    private static final int SIMPLE_MAX_CLAUSES = 2;

    private static final Pattern CODE_FENCE = Pattern.compile("```|`[^`]+`");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[.?!]+\\s+");

    /**
     * Keywords that signal the turn wants analysis, multi-step work,
     * generation of a substantial artifact, or explicit reasoning - all
     * cases a downgraded model is more likely to visibly underperform on.
     * Deliberately case-insensitive, deliberately broad (a false "complex"
     * classification costs nothing but the primary model's normal price;
     * see the class javadoc's asymmetric-risk note).
     */
    private static final Set<String> COMPLEX_SIGNAL_WORDS = Set.of(
        "compare", "analyze", "analyse", "design", "architecture", "debug", "refactor", "optimize",
        "step by step", "step-by-step", "write a", "generate a", "draft a", "plan", "strategy",
        "summarize the following", "translate the following", "explain in detail", "pros and cons",
        "code", "function", "algorithm", "query", "script", "regex", "sql"
    );

    @Autowired
    public QueryComplexityClassifier(
        ObjectProvider<DecisionEngine> decisions,
        @Value("${app.jev.complexity.enabled:true}") boolean jevComplexityEnabled,
        @Value("${app.jev.complexity.simple-threshold:0.75}") double jevSimpleThreshold
    ) {
        this.decisions = decisions.getIfAvailable();
        this.jevComplexityEnabled = jevComplexityEnabled;
        this.jevSimpleThreshold = jevSimpleThreshold;
    }

    /** Test seam: no Spring, explicit decision-engine wiring. */
    QueryComplexityClassifier(DecisionEngine decisions, boolean jevComplexityEnabled, double jevSimpleThreshold) {
        this.decisions = decisions;
        this.jevComplexityEnabled = jevComplexityEnabled;
        this.jevSimpleThreshold = jevSimpleThreshold;
    }

    /**
     * True when this turn is simple enough to safely route to an agent's
     * configured economy model instead of its primary model. Never throws -
     * a null/blank message (shouldn't happen in practice, {@code
     * AgentChatService} always has a real user message by this point) is
     * simply treated as "not simple", the conservative default.
     *
     * <p>Order of decisions: heuristic first. A heuristic "simple" returns
     * immediately - Jev is never asked to agree, so the common cheap case
     * keeps its zero-latency path. A heuristic "not simple" gets one Jev
     * {@code noul} second opinion when configured; Jev must clear {@code
     * app.jev.complexity.simple-threshold} (default 0.75, deliberately high
     * for the asymmetric-risk reason in the class javadoc) before the turn
     * is rescued to the economy model. Any Jev failure → the heuristic
     * verdict stands unchanged.
     */
    public boolean isSimple(String userMessage) {
        return isSimple(userMessage, null);
    }

    /**
     * {@code agentJevThreshold} is the per-agent override from
     * {@code agent.cost_routing_jev_threshold} (PATCH /cost-routing): when
     * set it wins over the tenant policy's {@code simple_threshold}, which
     * itself wins over the platform default. Whether Jev is consulted at all
     * comes from the tenant policy's {@code costRouting} override falling
     * back to {@code app.jev.complexity.enabled} - a tenant can turn Jev's
     * cost-routing say off entirely and keep the pure-heuristic behavior.
     */
    public boolean isSimple(String userMessage, Double agentJevThreshold) {
        if (!isHeuristicSimple(userMessage)) {
            // The heuristic's known failure mode is false negatives on
            // keyword collisions ("what's your refund plan?"). Jev - a
            // decision model, not a chat model - answers exactly this shape
            // of question cheaply; on any failure or low confidence the
            // conservative heuristic answer stands.
            if (jevComplexityEnabled && decisions != null && userMessage != null) {
                // Tenant decision policy (capability_policy, 'decision' row):
                // costRouting=false keeps the pure-heuristic path even while
                // the platform flag is on. The platform flag stays the hard
                // gate the other way - a tenant override can't resurrect a
                // feature the deployment itself turned off.
                var policy = decisions.tenantPolicy();
                if (Boolean.FALSE.equals(policy.map(DecisionPolicyView::costRouting).orElse(null))) {
                    return false;
                }
                // Precedence: per-agent override > tenant policy > platform default.
                Double tenantThreshold = policy
                    .map(DecisionPolicyView::simpleThreshold)
                    .orElse(null);
                double threshold = agentJevThreshold != null
                    ? agentJevThreshold
                    : (tenantThreshold != null ? tenantThreshold : jevSimpleThreshold);
                return decisions.noul(userMessage, JEV_SIMPLE_INSTRUCTIONS)
                    .map(p -> p >= threshold)
                    .orElse(false);
            }
            return false;
        }
        return true;
    }

    private boolean isHeuristicSimple(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String trimmed = userMessage.trim();
        if (trimmed.isEmpty() || trimmed.length() > SIMPLE_MAX_LENGTH) {
            return false;
        }
        if (CODE_FENCE.matcher(trimmed).find()) {
            return false;
        }
        if (CLAUSE_BOUNDARY.split(trimmed).length > SIMPLE_MAX_CLAUSES) {
            return false;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (String signal : COMPLEX_SIGNAL_WORDS) {
            if (lower.contains(signal)) {
                return false;
            }
        }
        return true;
    }
}
