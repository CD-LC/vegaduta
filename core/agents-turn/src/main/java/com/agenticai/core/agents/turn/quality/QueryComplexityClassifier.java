package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.decision.DecisionEngine;
import com.agenticai.core.agents.decision.DecisionPolicyView;
import com.agenticai.core.agents.tools.WordTokens;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.List;
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
    static final String COMPLEXITY_KEY = "router.complexity";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of {@link #COMPLEXITY_KEY} applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /** Rescue-only posture: Jev must be this sure it's economy-safe to override a heuristic "not simple". */
    private final DecisionEngine decisions;
    private final boolean jevComplexityEnabled;
    private final double jevSimpleThreshold;

    private volatile SettingsResolver settings;

    /**
     * The settings catalog, so a tenant's or an agent's {@code routing.simple.maxChars} / {@code routing.simple.maxClauses}
     * decide what is simple for their turns. Absent (hand-built instances): the {@code app.routing.simple.*} values.
     */
    @Autowired(required = false)
    public void setSettingsResolver(SettingsResolver resolver) {
        this.settings = resolver;
    }

    static final String MAX_CHARS_KEY = "routing.simple.maxChars";
    static final String MAX_CLAUSES_KEY = "routing.simple.maxClauses";

    private final RoutingSimpleProperties simple;
    private final Pattern clauseBoundary;
    private final List<String> signalPhrases;

    private static final Pattern CODE_FENCE = Pattern.compile("```|`[^`]+`");

    @Autowired
    public QueryComplexityClassifier(
        ObjectProvider<DecisionEngine> decisions,
        @Value("${app.jev.complexity.enabled:true}") boolean jevComplexityEnabled,
        @Value("${app.jev.complexity.simple-threshold:0.75}") double jevSimpleThreshold,
        ObjectProvider<RoutingSimpleProperties> simple
    ) {
        this(decisions.getIfAvailable(), jevComplexityEnabled, jevSimpleThreshold, simple.getIfAvailable(RoutingSimpleProperties::new));
    }

    /** Test seam: no Spring, explicit decision-engine wiring, the shipped {@code app.routing.simple.*} defaults. */
    QueryComplexityClassifier(DecisionEngine decisions, boolean jevComplexityEnabled, double jevSimpleThreshold) {
        this(decisions, jevComplexityEnabled, jevSimpleThreshold, new RoutingSimpleProperties());
    }

    /** Test seam: explicit decision-engine wiring and routing thresholds. */
    QueryComplexityClassifier(
        DecisionEngine decisions, boolean jevComplexityEnabled, double jevSimpleThreshold, RoutingSimpleProperties simple
    ) {
        this.decisions = decisions;
        this.jevComplexityEnabled = jevComplexityEnabled;
        this.jevSimpleThreshold = jevSimpleThreshold;
        this.simple = simple;
        this.clauseBoundary = Pattern.compile(simple.getClauseBoundary());
        this.signalPhrases = simple.getSignalWords().values().stream()
            .flatMap(list -> java.util.Arrays.stream(list.split(",")))
            .map(phrase -> phrase.strip().toLowerCase(java.util.Locale.ROOT))
            .filter(phrase -> !phrase.isEmpty())
            .toList();
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
        return isSimple(userMessage, agentJevThreshold, null);
    }

    /**
     * As {@link #isSimple(String, Double)}; {@code agentId} (null = unknown) lets an agent-scope
     * override of the {@value #COMPLEXITY_KEY} question apply to this agent's routing.
     */
    public boolean isSimple(String userMessage, Double agentJevThreshold, java.util.UUID agentId) {
        if (!isHeuristicSimple(userMessage, agentId)) {
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
                return decisions.noul(userMessage, prompts.text(COMPLEXITY_KEY, agentId))
                    .map(p -> p >= threshold)
                    .orElse(false);
            }
            return false;
        }
        return true;
    }

    private boolean isHeuristicSimple(String userMessage, java.util.UUID agentId) {
        if (userMessage == null) {
            return false;
        }
        String trimmed = userMessage.trim();
        if (trimmed.isEmpty() || trimmed.length() > limit(MAX_CHARS_KEY, agentId, simple.getMaxChars())) {
            return false;
        }
        if (CODE_FENCE.matcher(trimmed).find()) {
            return false;
        }
        if (clauseBoundary.split(trimmed).length > limit(MAX_CLAUSES_KEY, agentId, simple.getMaxClauses())) {
            return false;
        }
        // The signal words cannot say whether a message written in another script is a hard question.
        if (!simple.isNonLatinSimple() && WordTokens.hasLettersOutsideLatin(trimmed)) {
            return false;
        }
        // Same for accented Latin text: the shipped signal words are English.
        if (!simple.isAccentedLatinSimple() && hasAccentedLatinLetters(trimmed)) {
            return false;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (String signal : signalPhrases) {
            if (lower.contains(signal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The limit for {@code agentId} of the calling thread's tenant: the catalog's value (their override, else the platform
     * value) or {@code platform} when there is no catalog or it cannot answer. The routing decision runs on the turn's
     * own thread, where the tenant is set (the Jev call below needs it too).
     */
    private int limit(String key, java.util.UUID agentId, int platform) {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return platform;
        }
        try {
            return catalog.getInt(key, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            return platform;
        }
    }

    private static boolean hasAccentedLatinLetters(String text) {
        return text.codePoints()
            .anyMatch(cp -> cp > 127 && Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
    }
}
