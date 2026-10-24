package com.agenticai.core.agents.decision;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.WordTokens;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Picks the best-matching agent for a piece of free text, given a tenant's full agent list - the
 * missing half of "quick task should be very perfect in choosing right agents and mcp tools" (#591
 * follow-up: "it is selecting wrong agent for telegram message sending"). {@link
 * com.agenticai.core.agents.tools.ToolRelevanceSelector} already does the equivalent job for TOOLS
 * within a single agent's turn; this is the same "cheap, deterministic lexical overlap, no extra
 * LLM call" design applied one level up, to AGENT selection itself. Quick Task previously had no
 * agent-selection logic at all - just {@code agentService.listAll().get(0)} ("the tenant's first
 * agent, always") - which silently used the wrong agent for any tenant running more than one
 * specialized agent.
 *
 * <p>Deliberately NOT an embeddings/LLM-based reranker, for the identical reason {@link
 * com.agenticai.core.agents.tools.ToolRelevanceSelector}'s javadoc gives: an extra LLM call before
 * every Quick Task run would add real latency and cost to a feature whose whole pitch is "type it
 * and go," and a tenant's agent list is typically a handful of entries, not hundreds - so plain
 * keyword overlap between the task text and each agent's {@code name} + {@code systemPrompt} (the
 * only two fields that describe what an agent is FOR - {@link AgentResponse} has no separate
 * description field) is enough signal to route a task like "translate this to Hindi" to a
 * "Translator" agent over a "Support Triage" one.
 *
 * <p>A single agent, or a task with no lexical overlap against any agent (score 0 across the board
 * - e.g. an empty or very generic message), both fall back to {@code agents.get(0)} - the exact
 * previous default - so a typical single-agent tenant sees no behavior change at all, and an
 * ambiguous task doesn't get routed somewhere confidently wrong.
 *
 * <p><b>Jev upgrades this when configured.</b> Agent selection is a closed-set decision - pick one
 * of N labeled options - which is precisely the shape TypeSafe's Jev {@code choice} question
 * answers with calibrated probabilities (via {@link DecisionEngine}), at a fraction of a frontier
 * model's cost and latency. When a TypeSafe key exists ({@code app.jev.agent-routing.enabled}, on
 * by default), {@link #selectBest} asks Jev instead of counting token overlap - semantic fit ("send
 * the weekly report to Telegram" → the Telegram agent) instead of surface keywords, which is the
 * complaint (#591) this class exists for. {@link #selectAdditionalRelevant} asks Jev one batched
 * call of {@code noul} questions - System One answers every question in a request against the same
 * state, so N candidates still cost one round trip. ANY Jev failure (no key, timeout, malformed
 * answer) falls back to the exact lexical path below - Jev only ever replaces the signal, never the
 * contract.
 */
@Component
public class AgentRelevanceSelector {

    /**
     * Registry keys of the two questions put to the decision engine (default text: {@code
     * prompts/en/router.agent_select.md} and {@code router.agent_additional.md}; the second has the
     * placeholder {@code {agent}}).
     */
    static final String SELECT_KEY = "router.agent_select";
    static final String ADDITIONAL_KEY = "router.agent_additional";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant override of the keys above applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /**
     * Agent description length cap inside Jev criteria/questions - keeps per-call input spend
     * bounded.
     */
    private static final int JEV_AGENT_DESC_CHARS = 300;

    /**
     * Ceiling on candidates sent to Jev in one batched call - token spend stays bounded on big
     * tenants.
     */
    private static final int JEV_MAX_BATCH = 50;

    /** A {@code noul} ≥ this counts as "yes, this extra agent is relevant". */
    private static final double JEV_ADDITIONAL_THRESHOLD = 0.5;

    private final DecisionEngine decisions;
    private final boolean jevRoutingEnabled;

    @Autowired
    public AgentRelevanceSelector(
            ObjectProvider<DecisionEngine> decisions,
            @Value("${app.jev.agent-routing.enabled:true}") boolean jevRoutingEnabled) {
        this.decisions = decisions.getIfAvailable();
        this.jevRoutingEnabled = jevRoutingEnabled;
    }

    /** Test seam: no Spring, explicit decision-engine wiring. */
    AgentRelevanceSelector(DecisionEngine decisions, boolean jevRoutingEnabled) {
        this.decisions = decisions;
        this.jevRoutingEnabled = jevRoutingEnabled;
    }

    private static final Set<String> STOPWORDS =
            Set.of(
                    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "to", "of",
                    "in", "on", "for", "with", "and", "or", "but", "if", "then", "so", "as", "at",
                    "by", "from", "this", "that", "these", "those", "it", "its", "my", "your",
                    "our", "their", "i", "you", "we", "they", "he", "she", "do", "does", "did",
                    "can", "could", "will", "would", "should", "please", "me", "us", "them", "him",
                    "her", "what", "when", "where", "why", "how", "who", "which");

    /**
     * @param agents must be non-empty, already ordered so {@code agents.get(0)} is the intended
     *     fallback (matching {@code AgentService#listAll}'s prior implicit "just use the first one"
     *     contract).
     */
    public AgentResponse selectBest(String taskMessage, List<AgentResponse> agents) {
        if (agents.size() == 1) {
            return agents.get(0);
        }
        if (jevUsableFor(taskMessage)) {
            // Closed-set decision → Jev `choice`. The criteria keys are agent
            // ids so a returned winner maps straight back; a winner that
            // doesn't map, or any Jev failure, drops to the lexical path.
            Map<String, String> criteria = new LinkedHashMap<>();
            for (AgentResponse agent : agents) {
                criteria.put(agent.id().toString(), jevAgentDescription(agent));
            }
            AgentResponse picked =
                    decisions
                            .choose(taskMessage, prompts.text(SELECT_KEY), criteria)
                            .flatMap(
                                    choice ->
                                            agents.stream()
                                                    .filter(
                                                            a ->
                                                                    a.id().toString()
                                                                            .equals(
                                                                                    choice
                                                                                            .choice()))
                                                    .findFirst())
                            .orElse(null);
            if (picked != null) {
                return picked;
            }
        }
        Set<String> queryTokens = tokenize(taskMessage);
        if (queryTokens.isEmpty()) {
            return agents.get(0);
        }
        AgentResponse best = null;
        int bestScore = 0;
        for (AgentResponse agent : agents) {
            int score = score(queryTokens, agent);
            if (score > bestScore) {
                bestScore = score;
                best = agent;
            }
        }
        return best != null ? best : agents.get(0);
    }

    /**
     * #911 follow-up - "if it requires multiple agents, it should use additionally": Quick Task's
     * auto-select path previously stopped at {@link #selectBest}, discarding every other agent's
     * score even when more than one was genuinely relevant to the task text (e.g. "translate this
     * to Hindi and log it in the CRM" plausibly touches both a Translator agent and a CRM agent).
     * This returns the other agents worth offering the primary agent as delegation targets for THIS
     * run - anything with non-zero lexical overlap against the task text, ranked by score,
     * excluding {@code primary} itself, capped at {@code maxExtra}.
     *
     * <p>Deliberately reuses the exact same {@link #score}/{@link #tokenize} signal {@link
     * #selectBest} already uses - no new heuristic, just a second pass over the same scored
     * candidates that keeps more than the single winner. A single-agent tenant, or a task with no
     * overlap against anything but the primary, both return an empty list - the quiet "nothing
     * extra to add" case, not an error.
     */
    public List<AgentResponse> selectAdditionalRelevant(
            String taskMessage, List<AgentResponse> agents, AgentResponse primary, int maxExtra) {
        if (maxExtra <= 0 || agents.size() <= 1) {
            return List.of();
        }
        if (jevUsableFor(taskMessage)) {
            // One Jev call judges every non-primary candidate at once: System
            // One evaluates all questions in a request against the same
            // state, so this is a single round trip regardless of agent
            // count (bounded by JEV_MAX_BATCH). Empty map = Jev gave us
            // nothing usable → lexical path below. A populated map that all
            // falls under the threshold = Jev's actual "no" → empty list,
            // deliberately NOT a lexical fallback (Jev's calibrated no beats
            // a keyword guess).
            List<AgentResponse> candidates =
                    agents.stream()
                            .filter(agent -> primary == null || !agent.id().equals(primary.id()))
                            .limit(JEV_MAX_BATCH)
                            .toList();
            Map<String, String> questions = new LinkedHashMap<>();
            for (AgentResponse agent : candidates) {
                questions.put(
                        agent.id().toString(),
                        prompts.text(
                                ADDITIONAL_KEY, null, Map.of("agent", jevAgentDescription(agent))));
            }
            Map<String, Double> probs = decisions.nouls(taskMessage, questions);
            if (!probs.isEmpty()) {
                return candidates.stream()
                        .map(
                                agent ->
                                        Map.entry(
                                                agent,
                                                probs.getOrDefault(agent.id().toString(), 0.0)))
                        .filter(entry -> entry.getValue() >= JEV_ADDITIONAL_THRESHOLD)
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(maxExtra)
                        .map(Map.Entry::getKey)
                        .toList();
            }
        }
        Set<String> queryTokens = tokenize(taskMessage);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        return agents.stream()
                .filter(agent -> primary == null || !agent.id().equals(primary.id()))
                .map(agent -> Map.entry(agent, score(queryTokens, agent)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(maxExtra)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The decision capability only earns its call when a provider is configured, the platform flag
     * is on (hard gate - a tenant override can't resurrect a feature the deployment disabled), the
     * tenant's policy hasn't opted out (capability_policy 'decision' row: agentRouting=false), AND
     * there's a real task text to judge.
     */
    private boolean jevUsableFor(String taskMessage) {
        if (!jevRoutingEnabled || decisions == null || !StringUtils.hasText(taskMessage)) {
            return false;
        }
        if (Boolean.FALSE.equals(
                decisions.tenantPolicy().map(DecisionPolicyView::agentRouting).orElse(null))) {
            return false;
        }
        return decisions.isAvailable();
    }

    /**
     * The one-line capability description Jev sees for an agent: name + the head of its system
     * prompt.
     */
    private static String jevAgentDescription(AgentResponse agent) {
        String prompt = agent.systemPrompt() == null ? "" : agent.systemPrompt().strip();
        if (prompt.length() > JEV_AGENT_DESC_CHARS) {
            prompt = prompt.substring(0, JEV_AGENT_DESC_CHARS);
        }
        return agent.name() + (prompt.isEmpty() ? "" : " - " + prompt);
    }

    private int score(Set<String> queryTokens, AgentResponse agent) {
        Set<String> agentTokens = tokenize(agent.name() + " " + agent.systemPrompt());
        int overlap = 0;
        for (String token : agentTokens) {
            if (queryTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>(WordTokens.words(text));
        tokens.removeAll(STOPWORDS);
        return tokens;
    }
}
