package com.agenticai.core.agents.tools;

import static com.agenticai.core.agents.tools.ToolRelevanceScoring.admitExtras;
import static com.agenticai.core.agents.tools.ToolRelevanceScoring.cosineSimilarity;
import static com.agenticai.core.agents.tools.ToolRelevanceScoring.estimateSchemaTokens;
import static com.agenticai.core.agents.tools.ToolRelevanceScoring.score;
import static com.agenticai.core.agents.tools.ToolRelevanceScoring.tokenize;
import static com.agenticai.core.agents.tools.ToolRelevanceScoring.totalSchemaTokens;

import com.agenticai.core.knowledge.SidecarClient;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Automatic tool/MCP selection (no manual picking)" - the PRD's Progressive Disclosure requirement
 * ("Agents should query the registry to discover available tools only when needed to save context
 * window tokens"), finally implemented as real per-turn behavior rather than just an
 * always-bind-everything default.
 *
 * <p>{@code AgentToolProvider#resolveToolsFor} already had a form of "automatic" tool access - an
 * agent with no {@code allowedToolNames} entry gets every currently-resolvable tool (MCP +
 * knowledge + memory + Google) with zero admin configuration required. What was still missing is
 * the part of Progressive Disclosure that actually matters once a tenant's MCP catalog grows past a
 * handful of servers: binding every single tool's full schema to every chat call regardless of
 * relevance wastes context tokens and gives the model more irrelevant options to potentially
 * mis-call. This class is the fix - a lightweight, dependency-free relevance filter run on every
 * turn, keyed off the user's own message, so the model only ever sees the tools actually worth
 * considering for what was just asked.
 *
 * <p><b>Primary scoring is lexical, deliberately NOT embeddings/LLM-based</b>: a live embedding
 * call (or worse, an extra LLM call) to rank EVERY candidate before every real chat call would add
 * latency and cost to every single turn, for a problem ("filter an at-most-a-few-dozen-item list")
 * that plain lexical overlap solves well enough most of the time - the same "cheap, deterministic,
 * good enough" tradeoff {@code GuardrailService}'s keyword-based sanitization already makes
 * elsewhere in this codebase, rather than reaching for an LLM/ML solution to a filtering problem
 * with only a few dozen candidates.
 *
 * <p><b>2026-08-12 - narrow, targeted semantic layer added on top (direct ask: "use AI or other
 * mechanism and select only tools required... instead of sending all tools together").</b> Lexical
 * overlap has one structural blind spot no amount of tuning fixes: a message that is genuinely
 * relevant to a tool but shares zero vocabulary with its name/ description (the confirmed live
 * incident below). Rather than embedding every candidate every turn - the cost/latency tradeoff
 * rejected above, which still holds for the PRIMARY ranking - this adds semantic scoring ONLY at
 * the one place lexical scoring already admits it cannot help: the {@code select()}-local
 * discovery-seed rescue for an MCP family with zero lexical representation. That rescue already
 * guaranteed each such family exactly one seed (see its own javadoc for why - an enabled server
 * must never go fully invisible); this only changes WHICH candidate within a zero-scoring family
 * gets seeded, from "first in resolution order" to "the one this turn's message is actually
 * semantically closest to" - same guarantee, better precision. Bounded cost: one query embedding
 * per {@code select()} call that reaches this path, plus one embedding per distinct candidate tool
 * the FIRST time it's ever seen (cached forever after - tool descriptions essentially never change
 * turn to turn). Fails open to the exact pre-2026-08-12 first-in-order behavior on any error,
 * timeout, or when {@link #semanticEnabled} is off / no {@link SidecarClient} is wired (matches the
 * {@code knowledge} profile's own optionality) - a filtering feature must never itself become a
 * reason a turn fails.
 *
 * <p>Small candidate lists are left untouched: if there are fewer tools than {@link
 * #maxToolsPerTurn} to begin with, there's nothing to trim - this only ever activates once a
 * tenant's enabled tool catalog is genuinely large enough for it to matter, so a typical
 * single-MCP-server tenant sees no behavior change at all.
 *
 * <p><b>The cap always applies once enabled, even with nothing to score against.</b> A message with
 * no scoreable tokens (blank, punctuation-only, an attachment-only send with no caption) used to
 * fall back to returning every candidate completely unfiltered - reasoned at the time as "if we
 * can't tell what's relevant, don't guess-drop tools." That reasoning broke catastrophically once a
 * tenant's real resolved tool count grew into the thousands (many MCP connectors, many custom-code
 * tools): a single blank- message turn would bind the ENTIRE unbounded catalog to the provider
 * call, which is exactly how a live incident saw {@code POST https://api.x.ai/v1/chat/completions}
 * 400 with "Maximum tools limit reached. 5415 tools have been provided but the maximum is 350" -
 * every OTHER turn for that tenant was capped at {@link #maxToolsPerTurn} as designed; only the
 * blank-message turns slipped the whole catalog through. {@link #score} degrades gracefully when
 * {@code queryTokens} is empty (every candidate scores 0, so the stable sort below just preserves
 * each tool's original resolution order) - so the fix is simply to stop special-casing "nothing to
 * score against" and let that same scoring path (still budget-capped, still always-include-aware,
 * still MCP-sibling- sticky) run unconditionally.
 *
 * <p><b>2026-09-28 - the no-match budget ({@link #noMatchMaxToolsPerTurn}).</b> A zero-score turn
 * (a greeting, an acknowledgement, a blank send, or a message whose tokens simply don't overlap ANY
 * candidate) used to still spend the entire {@link #maxToolsPerTurn} budget on zero-score padding -
 * a bare "hello" bound ~12 full tool schemas, and catalogs at or below {@code maxToolsPerTurn}
 * bypassed selection entirely so small agents paid their whole catalog too. Prompt tokens for tools
 * the model was never going to call are pure per-turn cost, so a no-positive-score turn now gets
 * the smaller {@code noMatchMaxToolsPerTurn} budget instead: the same stable-order truncation, a
 * tighter ceiling. {@code alwaysInclude} tools and the per-family MCP discovery seeds are
 * unaffected, so standing capabilities and every enabled MCP server stay reachable - only the "fill
 * the cap with arbitrary zero-scorers" padding went away. Set the property {@code =
 * max-tools-per-turn} (or higher) to restore the old shape exactly.
 */
@Component
public class ToolRelevanceSelector {

    private static final Logger log = LoggerFactory.getLogger(ToolRelevanceSelector.class);

    private final boolean enabled;
    private final int maxToolsPerTurn;
    /** Scored slots reserved for a request that matches something when the always-kept set already fills the count cap. */
    static final int MIN_SCORED_TOOLS_WHEN_ALWAYS_KEPT_FILLS_BUDGET = 4;

    /** The matching schema-token floor (about four ordinary tool schemas). */
    static final int MIN_SCORED_SCHEMA_TOKENS_WHEN_ALWAYS_KEPT_FILLS_BUDGET = 1600;

    private final int maxSchemaTokensPerTurn;
    // 2026-09-28 token-cost fix - see the class javadoc's "no-match budget"
    // paragraph. A turn where NO candidate scores above zero gets this
    // (deliberately smaller) tool cap instead of the full maxToolsPerTurn:
    // zero-score padding is the dominant per-turn schema cost on low-intent
    // messages like greetings, and trimming it harder costs nothing real -
    // alwaysInclude tools and MCP discovery seeds apply on top either way.
    private final int noMatchMaxToolsPerTurn;
    // 2026-08-12 semantic layer - see class javadoc. ObjectProvider (not a
    // hard SidecarClient dependency) so this class stays instantiable with
    // no profile requirement of its own, matching AgentChatService's own
    // extensive ObjectProvider use for every optional-profile collaborator -
    // absent exactly when the `knowledge` profile isn't active, in which
    // case this degrades to the pre-2026-08-12 lexical-only behavior.
    private final ObjectProvider<SidecarClient> sidecarClientProvider;
    private final boolean semanticEnabled;
    // Tool description text rarely changes turn to turn (a tool's name/
    // description is effectively static for the lifetime of its
    // registration), so its embedding is cached indefinitely rather than
    // re-computed every turn - the entire point of only paying the
    // embedding cost once per distinct tool ever seen, not once per turn.
    // Bounded (not unbounded) so a platform accumulating many distinct
    // tools/MCP servers over a long uptime can't grow this without limit;
    // eviction is simple least-recently-USED (accessOrder=true), matching
    // the "keep what's actually being asked about" intent of the cache
    // itself. synchronizedMap because Spring beans are singletons shared
    // across every tenant's concurrent turns.
    private static final int TOOL_EMBEDDING_CACHE_MAX_ENTRIES = 4000;
    private final Map<String, float[]> toolEmbeddingCache =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                            return size() > TOOL_EMBEDDING_CACHE_MAX_ENTRIES;
                        }
                    });

    @Autowired
    public ToolRelevanceSelector(
            @Value("${app.tools.auto-selection.enabled:true}") boolean enabled,
            @Value("${app.tools.auto-selection.max-tools-per-turn:12}") int maxToolsPerTurn,
            @Value("${app.tools.auto-selection.max-schema-tokens-per-turn:3000}")
                    int maxSchemaTokensPerTurn,
            @Value("${app.tools.auto-selection.no-match-max-tools:4}") int noMatchMaxToolsPerTurn,
            ObjectProvider<SidecarClient> sidecarClientProvider,
            @Value("${app.tools.auto-selection.semantic-enabled:true}") boolean semanticEnabled) {
        this.enabled = enabled;
        this.maxToolsPerTurn = maxToolsPerTurn;
        this.maxSchemaTokensPerTurn = maxSchemaTokensPerTurn;
        this.noMatchMaxToolsPerTurn = noMatchMaxToolsPerTurn;
        this.sidecarClientProvider = sidecarClientProvider;
        this.semanticEnabled = semanticEnabled;
    }

    /**
     * Test convenience: predates the token-budget dimension added alongside {@link
     * #maxSchemaTokensPerTurn} - pinned to the pre-token-diet 8000 so every pre-existing test using
     * this overload keeps its original meaning (the production {@code @Value} default is lower).
     * Package-private, same pattern as {@code DisposableEmailDomainService}'s test-only overload.
     * Semantic layer defaults OFF here (null provider - see {@link #semanticBestSeedPerFamily}'s
     * null-check) so every pre-existing test exercising this overload keeps testing pure lexical
     * behavior unchanged. {@code noMatchMaxToolsPerTurn} defaults to {@code maxToolsPerTurn} so
     * these pre-existing tests exercise the pre-2026-09-28 behavior (the full budget on zero-score
     * turns) they were written against.
     */
    ToolRelevanceSelector(boolean enabled, int maxToolsPerTurn) {
        this(enabled, maxToolsPerTurn, 8000, maxToolsPerTurn, null, false);
    }

    /**
     * Test convenience: the pre-2026-08-12 (enabled, maxToolsPerTurn, maxSchemaTokensPerTurn)
     * shape, semantic layer defaulted OFF (null provider) and {@code noMatchMaxToolsPerTurn} pinned
     * to {@code maxToolsPerTurn} for the same reason as the two-arg overload above - every
     * pre-existing test using this overload keeps testing unchanged lexical behavior.
     */
    ToolRelevanceSelector(boolean enabled, int maxToolsPerTurn, int maxSchemaTokensPerTurn) {
        this(enabled, maxToolsPerTurn, maxSchemaTokensPerTurn, maxToolsPerTurn, null, false);
    }

    /**
     * Test seam for the 2026-09-28 no-match budget: lets a test set {@code noMatchMaxToolsPerTurn}
     * independently of {@code maxToolsPerTurn}, semantic layer still defaulted OFF.
     */
    ToolRelevanceSelector(
            boolean enabled,
            int maxToolsPerTurn,
            int maxSchemaTokensPerTurn,
            int noMatchMaxToolsPerTurn) {
        this(enabled, maxToolsPerTurn, maxSchemaTokensPerTurn, noMatchMaxToolsPerTurn, null, false);
    }

    /**
     * Test convenience: the 2026-08-12 semantic-layer shape, {@code noMatchMaxToolsPerTurn} pinned
     * to {@code maxToolsPerTurn} so the pre-existing semantic tests keep exercising the same budget
     * they were written against.
     */
    ToolRelevanceSelector(
            boolean enabled,
            int maxToolsPerTurn,
            int maxSchemaTokensPerTurn,
            ObjectProvider<SidecarClient> sidecarClientProvider,
            boolean semanticEnabled) {
        this(
                enabled,
                maxToolsPerTurn,
                maxSchemaTokensPerTurn,
                maxToolsPerTurn,
                sidecarClientProvider,
                semanticEnabled);
    }

    /** Cost-transparency panel: whether per-turn tool-schema minimization is currently active. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Cost-transparency panel: the configured cap this selector trims candidate tool schemas down
     * to, once active.
     */
    public int maxToolsPerTurn() {
        return maxToolsPerTurn;
    }

    /**
     * Cost-transparency panel: the configured per-turn schema-token budget (see {@link
     * #estimateSchemaTokens}).
     */
    public int maxSchemaTokensPerTurn() {
        return maxSchemaTokensPerTurn;
    }

    /**
     * Cost-transparency panel: the tighter tool cap a turn gets when NO candidate scores above
     * zero.
     */
    public int noMatchMaxToolsPerTurn() {
        return noMatchMaxToolsPerTurn;
    }

    /**
     * @param alwaysInclude tool names that skip relevance scoring entirely and are always kept
     *     (e.g. {@code remember_fact} - a standing capability every agent should retain regardless
     *     of what this specific message is about, not something to gate behind "does this message
     *     mention memory").
     * @param mcpToolNames the subset of {@code candidates}' names that actually came from an MCP
     *     server (see {@code AgentToolProvider#resolveMcpTools}) - used only for {@link
     *     #mcpFamily}'s sibling-stickiness grouping below, never scored differently otherwise.
     */
    public List<ToolCallback> select(
            String userMessage,
            List<ToolCallback> candidates,
            Set<String> alwaysInclude,
            Set<String> mcpToolNames) {
        // Fast path that needs no scoring at all: a catalog that already fits
        // inside the smaller of the two caps can never be trimmed further, so
        // tiny agents skip the whole selection pass exactly as before.
        if (!enabled) {
            return candidates;
        }
        if (candidates.size() <= Math.min(maxToolsPerTurn, noMatchMaxToolsPerTurn)
                && totalSchemaTokens(candidates) <= maxSchemaTokensPerTurn) {
            return candidates;
        }

        Set<String> queryTokens = tokenize(userMessage);
        // Deliberately NOT an early "return candidates" here - see the class
        // javadoc's "the cap always applies" note. An empty queryTokens set
        // (blank/punctuation-only message) makes every candidate score 0 in the
        // ranking below, which lands on the no-match budget's stable-order
        // truncation instead of lexical ranking.
        if (queryTokens.isEmpty()) {
            log.debug(
                    "No scoreable tokens in this turn's message ({} candidate tools) - the no-match "
                            + "budget applies ({} tools) instead of lexical ranking.",
                    candidates.size(),
                    Math.min(maxToolsPerTurn, noMatchMaxToolsPerTurn));
        }

        // Each candidate is scored exactly once and memoized. Previously the
        // sort comparator below re-ran score() (tokenize + fuzzy Levenshtein)
        // per comparison - O(n log n) scorings over the whole catalog, exactly
        // the costliest possible shape for the large tenants the cap exists
        // for. IdentityHashMap because ToolCallback implementations are not
        // required to define equals/hashCode; identity is what the list holds.
        Map<ToolCallback, Integer> scores = new java.util.IdentityHashMap<>();
        for (ToolCallback t : candidates) {
            scores.put(t, score(queryTokens, t));
        }

        List<ToolCallback> alwaysKept =
                candidates.stream()
                        .filter(t -> alwaysInclude.contains(t.getToolDefinition().name()))
                        .toList();
        List<ToolCallback> rankedRest =
                candidates.stream()
                        .filter(t -> !alwaysInclude.contains(t.getToolDefinition().name()))
                        .sorted(
                                Comparator.comparingInt((ToolCallback t) -> scores.get(t))
                                        .reversed())
                        .toList();

        // Minimum-score gate (2026-08-09 audit fix, live failure mode: a code-intel-scoped
        // request calling an unrelated Playwright tool). The OLD `rankedRest.stream().limit(...)`
        // above filled every remaining cap slot regardless of score, so once real matches ran
        // out it padded with whatever zero-score tools happened to sort next - purely because
        // nothing scored higher, not because they were remotely relevant. Fix: once a real
        // (positive-score) match exists for this turn, only positive scorers may fill `scored`;
        // stop as soon as the ranked (descending) stream drops to 0 rather than continuing to
        // pad. The one case this must NOT touch is "nothing scored at all" (blank/punctuation-
        // only message, or a message whose tokens simply don't overlap ANY candidate) - that is
        // the pre-existing, intentional "cap always applies even with nothing to score against"
        // behavior from the class javadoc above, so it still falls through to stable-order
        // truncation exactly as before.
        boolean anyPositiveScore = !rankedRest.isEmpty() && scores.get(rankedRest.get(0)) > 0;

        // 2026-09-28 no-match budget (see the class javadoc's paragraph): a turn
        // with NO positive scorer gets the deliberately-smaller
        // noMatchMaxToolsPerTurn ceiling instead of the full maxToolsPerTurn, and
        // it also applies to catalogs at-or-below maxToolsPerTurn that used to
        // bypass selection entirely - "hello" no longer pays the full tool
        // catalog. Positive-score turns keep the pre-existing budget exactly.
        int effectiveMaxTools =
                anyPositiveScore
                        ? maxToolsPerTurn
                        : Math.min(maxToolsPerTurn, noMatchMaxToolsPerTurn);
        if (candidates.size() <= effectiveMaxTools
                && totalSchemaTokens(candidates) <= maxSchemaTokensPerTurn) {
            return candidates;
        }

        // Per-turn token budget (2026-08-09 audit fix): maxToolsPerTurn is a raw tool COUNT, so
        // a handful of huge-schema tools can dominate the actual token cost of a turn while a
        // dozen tiny ones sail under the same count cap. estimateSchemaTokens is a cheap proxy
        // (schema JSON length / 4) - deliberately not a real tokenizer, same "cheap, good enough"
        // tradeoff as this class's lexical scoring (see class javadoc). alwaysKept tools reduce
        // the remaining budget the same way they already reduce the remaining count budget below.
        int countBudget = Math.max(0, effectiveMaxTools - alwaysKept.size());
        int tokenBudget =
                Math.max(
                        0,
                        maxSchemaTokensPerTurn
                                - alwaysKept.stream()
                                        .mapToInt(ToolRelevanceScoring::estimateSchemaTokens)
                                        .sum());
        // A request that names something (a URL, "search the web", a calendar) must be able to reach
        // the tool that does it. When the always-kept set alone fills the budget (a personal install
        // keeps about 15 names against a cap of 12) the scored budget was 0, so web, channel and
        // browser tools were never offered on any turn. Reserve a small floor for positive matches;
        // the always-kept set is untouched and a turn with no match still gets none.
        if (anyPositiveScore && countBudget == 0) {
            countBudget = MIN_SCORED_TOOLS_WHEN_ALWAYS_KEPT_FILLS_BUDGET;
        }
        if (anyPositiveScore && tokenBudget < MIN_SCORED_SCHEMA_TOKENS_WHEN_ALWAYS_KEPT_FILLS_BUDGET && alwaysKept.size() > 0
                && effectiveMaxTools - alwaysKept.size() <= 0) {
            tokenBudget = MIN_SCORED_SCHEMA_TOKENS_WHEN_ALWAYS_KEPT_FILLS_BUDGET;
        }

        List<ToolCallback> scoredMutable = new java.util.ArrayList<>();
        int tokensUsed = 0;
        for (ToolCallback t : rankedRest) {
            if (scoredMutable.size() >= countBudget) {
                break;
            }
            if (anyPositiveScore && scores.get(t) <= 0) {
                break;
            }
            int toolTokens = estimateSchemaTokens(t);
            // Always admit at least one candidate even if its own schema alone exceeds the
            // remaining budget - a single oversized tool must not zero out the whole turn.
            if (!scoredMutable.isEmpty() && tokensUsed + toolTokens > tokenBudget) {
                break;
            }
            scoredMutable.add(t);
            tokensUsed += toolTokens;
        }
        List<ToolCallback> scored = List.copyOf(scoredMutable);

        // Zero-overlap MCP discovery (2026-08-09, live report: a "summarize
        // this webpage <url>" turn against an enabled puppeteer server never
        // called it - 0 tool calls, no error - because "summarize"/"webpage"
        // share literally no token, fuzzy or exact, with
        // puppeteer_navigate/_screenshot/_evaluate's names or descriptions.
        // #635's fuzzy match only rescues near-miss typos ("pupeeter"); it
        // can't rescue content that's topically unrelated to a tool's own
        // wording, which is most real messages relative to most MCP tools.
        // Left alone, an enabled MCP server a tenant explicitly turned on
        // could go permanently unreachable through auto-select any time its
        // own vocabulary doesn't happen to appear in the user's message -
        // effectively invisible, indistinguishable from never being enabled
        // at all. So: guarantee each MCP family with zero representation in
        // alwaysKept/scored gets exactly one seed - its own best (possibly
        // 0-scoring) candidate - so the model at least learns the
        // capability exists. Bounded by distinct enabled MCP families, not
        // total tool count (families are always small, per the sibling-
        // stickiness reasoning below), so this can't regress back into the
        // unbounded-catalog shape of the x.ai 5415-tools incident.
        Set<String> representedFamilies =
                java.util.stream.Stream.concat(alwaysKept.stream(), scored.stream())
                        .map(t -> mcpFamily(t.getToolDefinition().name(), mcpToolNames))
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        // 2026-08-12 semantic layer (see class javadoc): every zero-scoring
        // candidate per unrepresented family is collected first (not just
        // the first one seen) so a semantic pass below can choose the best
        // one instead of an arbitrary one. rankedRest is already score-
        // sorted, so this list's own order is still "first = lexically-best
        // (here, equally zero) representative" - the exact fallback order
        // used when semantic scoring isn't available.
        Map<String, List<ToolCallback>> zeroFamilyCandidates = new LinkedHashMap<>();
        for (ToolCallback t : rankedRest) {
            if (scored.contains(t)) {
                continue;
            }
            String family = mcpFamily(t.getToolDefinition().name(), mcpToolNames);
            if (family == null || representedFamilies.contains(family)) {
                continue;
            }
            zeroFamilyCandidates.computeIfAbsent(family, f -> new java.util.ArrayList<>()).add(t);
        }
        Map<String, ToolCallback> discoverySeedByFamily =
                semanticBestSeedPerFamily(userMessage, zeroFamilyCandidates);
        List<ToolCallback> discoverySeeds = List.copyOf(discoverySeedByFamily.values());

        // Sibling stickiness (#607 follow-up: recurring "No ToolCallback found
        // for tool name: puppeteer_evaluate" after puppeteer_navigate had
        // already run successfully in the SAME turn). A message phrased
        // around one step of a multi-step MCP tool chain ("go to this page")
        // lexically favors that step's tool (navigate) and starves its
        // siblings (evaluate, screenshot, click, ...) even though the model
        // will legitimately need to call them next in the very same
        // tool-calling loop - and unlike a tool that was never offered at
        // all, the model doesn't know evaluate is unavailable until it tries
        // and the call fails outright, crashing the turn. Once ANY tool from
        // an MCP server's family made the relevance cut on its own merit -
        // including via a discovery seed above - every other same-family MCP
        // tool that got trimmed is rescued back in - a real server's tool
        // family is always small (a handful of actions), so this never
        // balloons back toward "bind the whole catalog" the way skipping
        // relevance filtering entirely would.
        Set<String> keptFamilies =
                java.util.stream.Stream.concat(scored.stream(), discoverySeeds.stream())
                        .map(t -> siblingFamily(t.getToolDefinition().name(), mcpToolNames))
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        List<ToolCallback> rescuedSiblings =
                keptFamilies.isEmpty()
                        ? List.of()
                        : rankedRest.stream()
                                .filter(t -> !scored.contains(t) && !discoverySeeds.contains(t))
                                .filter(
                                        t ->
                                                keptFamilies.contains(
                                                        siblingFamily(
                                                                t.getToolDefinition().name(),
                                                                mcpToolNames)))
                                .toList();

        // Extras are bounded by separate count caps (seeds and siblings never compete for one pool,
        // so
        // unrelated-family seeds cannot starve the siblings of the tool the user actually asked
        // for) and by
        // the schema-token budget left after alwaysKept + scored. Each pool also has a small
        // guaranteed
        // allowance: one seed overall and the first sibling of every kept MCP family are admitted
        // even if
        // over budget, so the rescue mechanisms still work when the budget is tight.
        int[] extraTokens = {tokensUsed};
        List<ToolCallback> admittedSeeds =
                admitExtras(discoverySeeds, MAX_SEED_TOOLS, tokenBudget, extraTokens, t -> "seed");
        List<ToolCallback> admittedSiblings =
                admitExtras(
                        rescuedSiblings,
                        MAX_SIBLING_TOOLS,
                        tokenBudget,
                        extraTokens,
                        t -> siblingFamily(t.getToolDefinition().name(), mcpToolNames));

        return java.util.stream.Stream.of(alwaysKept, scored, admittedSeeds, admittedSiblings)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    /**
     * MCP tool names are namespaced {@code "<server>_<action>"} by the server itself (e.g.
     * {@code @modelcontextprotocol/server-puppeteer} exposes {@code puppeteer_navigate}, {@code
     * puppeteer_evaluate}, {@code puppeteer_screenshot}, ...) - the segment before the first
     * underscore is the family every sibling shares. Deliberately scoped to {@code mcpToolNames}
     * only: several BUILT-IN tools also happen to share an underscore-delimited prefix (e.g. {@code
     * google_gmail}, {@code google_sheets}, {@code google_drive}) but each is one standalone
     * capability, not steps of one server's chain - grouping those the same way would defeat the
     * relevance filter's whole point for the Google tool set. Returns {@code null} (no family) for
     * anything not in {@code mcpToolNames} or with no underscore to split on.
     *
     * <p>Bug fix (2026-08-09, static-MCP-namespacing follow-up): every MCP tool - static and
     * dynamic alike, see {@code AgentToolProvider#resolveStaticMcpTools} - now arrives here already
     * prefixed {@code mcp__<server>__<original-name>} by {@link NamespacedMcpToolCallback}. Naively
     * splitting on the FIRST underscore, as before, would land on the one inside {@code "mcp"}'s
     * own prefix and collapse every server's tools into one shared family literally named {@code
     * "mcp"} - the opposite of this method's purpose, since two unrelated servers' tools would then
     * look like siblings of each other while a server's own multi-tool chain (the case this method
     * exists to keep grouped) would be indistinguishable from any other server's. The {@code
     * mcp__<server>__} shape is checked first and the family is cut after the SECOND {@code __}, so
     * the family key is the namespace segment (unique per connection) rather than the shared {@code
     * "mcp"} prefix.
     *
     * <p>Package-private (not {@code private}) so this is directly unit-testable without needing a
     * full {@code select} call to exercise it, matching {@code AgentToolProvider#capToHardLimit}'s
     * documented rationale for the same visibility choice.
     */
    /**
     * 2026-08-12 semantic layer: picks one seed per family, preferring the candidate this turn's
     * message is semantically closest to over an arbitrary first-in-order pick - see class javadoc
     * for the full reasoning. Cheap paths are checked before ever touching the sidecar: an empty
     * map does nothing, and a family with only one candidate needs no comparison at all. Fails open
     * to first-in-order for every remaining family the instant anything about the semantic path
     * doesn't work (disabled, no client wired, the query embedding call itself throws) - a single
     * point of failure rather than a per-family retry, so a flaky sidecar call degrades predictably
     * to the old behavior rather than mixing semantic and non-semantic picks within one turn.
     */
    private Map<String, ToolCallback> semanticBestSeedPerFamily(
            String userMessage, Map<String, List<ToolCallback>> zeroFamilyCandidates) {
        Map<String, ToolCallback> result = new LinkedHashMap<>();
        if (zeroFamilyCandidates.isEmpty()) {
            return result;
        }
        boolean needsComparison =
                zeroFamilyCandidates.values().stream().anyMatch(list -> list.size() > 1);
        SidecarClient sidecarClient =
                semanticEnabled && needsComparison && sidecarClientProvider != null
                        ? sidecarClientProvider.getIfAvailable()
                        : null;
        float[] queryEmbedding = null;
        if (sidecarClient != null) {
            try {
                queryEmbedding = sidecarClient.embedQuery(userMessage);
            } catch (RuntimeException e) {
                log.debug(
                        "Semantic discovery-seed scoring unavailable this turn (sidecar embed failed) - "
                                + "falling back to first-in-order seeding for every zero-scoring MCP family",
                        e);
                queryEmbedding = null;
            }
        }
        for (Map.Entry<String, List<ToolCallback>> entry : zeroFamilyCandidates.entrySet()) {
            List<ToolCallback> candidates = entry.getValue();
            ToolCallback seed =
                    candidates.get(
                            0); // first-in-order fallback, used whenever semantic scoring can't run
            if (queryEmbedding != null && candidates.size() > 1) {
                ToolCallback best = null;
                double bestSimilarity = Double.NEGATIVE_INFINITY;
                for (ToolCallback candidate : candidates) {
                    float[] toolEmbedding = cachedToolEmbedding(sidecarClient, candidate);
                    if (toolEmbedding == null) {
                        // This one candidate's embedding failed/unavailable - it simply can't win
                        // the
                        // comparison (stays out of contention) rather than aborting the whole
                        // family's
                        // semantic pass over one bad candidate.
                        continue;
                    }
                    double similarity = cosineSimilarity(queryEmbedding, toolEmbedding);
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity;
                        best = candidate;
                    }
                }
                if (best != null) {
                    seed = best;
                }
            }
            result.put(entry.getKey(), seed);
        }
        return result;
    }

    /**
     * Tool description text is effectively static, so its embedding is cached indefinitely (see
     * {@link #toolEmbeddingCache}'s own javadoc) - this is the only place that reads or writes that
     * cache. Keyed on the exact same "name + description" text {@link #score} already tokenizes for
     * lexical matching, so both scoring paths judge a tool by the same text. Returns {@code null}
     * (never throws) on any embedding failure - the caller already treats a null embedding as "this
     * candidate can't win the comparison," not as a reason to fail the whole turn.
     */
    private float[] cachedToolEmbedding(SidecarClient sidecarClient, ToolCallback tool) {
        String key = tool.getToolDefinition().name() + " " + tool.getToolDefinition().description();
        float[] cached = toolEmbeddingCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            float[] embedding = sidecarClient.embedQuery(key);
            toolEmbeddingCache.put(key, embedding);
            return embedding;
        } catch (RuntimeException e) {
            log.debug(
                    "Could not embed tool '{}' for semantic discovery-seed scoring - it will not win "
                            + "this family's comparison this turn",
                    tool.getToolDefinition().name(),
                    e);
            return null;
        }
    }

    static String mcpFamily(String toolName, Set<String> mcpToolNames) {
        if (toolName == null || !mcpToolNames.contains(toolName)) {
            return null;
        }
        if (toolName.startsWith("mcp__")) {
            int nextDelimiter = toolName.indexOf("__", "mcp__".length());
            return nextDelimiter > 0 ? toolName.substring(0, nextDelimiter) : toolName;
        }
        int idx = toolName.indexOf('_');
        return idx > 0 ? toolName.substring(0, idx) : null;
    }

    /**
     * Max discovery seeds (one per zero-overlap MCP family) added on top of the scored tools in one
     * turn.
     */
    static final int MAX_SEED_TOOLS = 3;

    /**
     * The family whose members are rescued together once one of them makes the cut: an MCP server's tools
     * ({@link #mcpFamily}), plus the built-in {@code browser_*} tools, which only work as a set (navigate,
     * then read, click, type). Lexical scoring favors the step the words name ("open ...") and trimmed
     * the rest, so a request to drive a page got {@code browser_navigate} and nothing to read or click with.
     * Used for the sibling rescue only: the zero-overlap discovery seeds still use {@link #mcpFamily}, so an
     * unrelated turn is not offered a browser tool just because the browser is on.
     */
    static String siblingFamily(String toolName, Set<String> mcpToolNames) {
        String family = mcpFamily(toolName, mcpToolNames);
        if (family != null) {
            return family;
        }
        return toolName != null && toolName.startsWith("browser_") ? "browser_" : null;
    }

    /** Max rescued same-family MCP siblings added on top of the scored tools in one turn. */
    static final int MAX_SIBLING_TOOLS = 6;
}
