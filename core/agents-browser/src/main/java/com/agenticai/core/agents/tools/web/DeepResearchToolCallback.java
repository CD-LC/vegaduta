package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code deep_research} built-in: plan-driven, parallel, deduped, span-cited web research. The
 * calling model is the planner: it passes a structured brief; this tool fans out scaled search and
 * read workers (see {@link DeepResearchService}) and returns numbered sources with quoted passages
 * and character offsets. Then the model writes the report and checks it with {@code
 * verify_citations}.
 *
 * <p>Cost: the tool makes no model calls; its spend is HTTP calls bounded by {@link
 * DeepResearchConfig} (workers, sources, and turn-wide ceilings on search queries and fetch
 * attempts held in the {@link DeepResearchSession}). Those ceilings are the real cost control and
 * hold even when no USD cap is configured. Separately, the resolver wraps the tool with the turn's
 * shared {@code RunBudget} in GATE-ONLY mode: once delegation spend (delegate_to_team,
 * delegate_parallel) has exhausted the per-run USD cap the tool refuses to run, but it never
 * charges the budget, because the model-token price model does not describe HTTP calls.
 *
 * <p>Contract, like {@code web_search}: never throws; failures come back as readable JSON.
 */
public class DeepResearchToolCallback implements ToolCallback {

    public static final String TOOL_NAME = "deep_research";

    private static final Logger log = LoggerFactory.getLogger(DeepResearchToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Refusal relayed to the model when the caller's daily search allowance is used up. */
    static final String ALLOWANCE_USED_UP =
            "{\"error\": \"Today's deep research allowance for this account is used up. It resets within 24 hours. "
                    + "Tell the user that plainly and do not call deep_research again in this conversation.\"}";

    /**
     * The caller's daily allowance of platform-paid searches, checked before a call and charged
     * after it (see {@link DeepResearchDailyAllowance}). Implementations never throw.
     */
    public interface SearchAllowance {
        /** Searches the caller may still run today; {@link Integer#MAX_VALUE} when uncapped. */
        int remaining();

        /** Counts the searches a call reserved. */
        void record(int searches);

        /**
         * Atomically reserves up to {@code wanted} searches BEFORE they run and returns how many
         * were granted (0 = used up). The default (check, then count) is for simple fakes; the
         * production allowance overrides it with a single atomic increment so concurrent calls
         * cannot overshoot.
         */
        default int reserve(int wanted) {
            int granted = Math.min(Math.max(0, wanted), Math.max(0, remaining()));
            if (granted > 0) {
                record(granted);
            }
            return granted;
        }

        /** Returns reserved searches that were not used. */
        default void refund(int searches) {}
    }

    private final DeepResearchService service;
    private final DeepResearchSession session;
    private final SearchAllowance allowance;

    public DeepResearchToolCallback(DeepResearchService service, DeepResearchSession session) {
        this(service, session, null);
    }

    /**
     * @param allowance the caller's daily search allowance, or {@code null} for none
     */
    public DeepResearchToolCallback(
            DeepResearchService service, DeepResearchSession session, SearchAllowance allowance) {
        this.service = service;
        this.session = session;
        this.allowance = allowance;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Researches a question on the public web in depth: runs several searches in parallel, removes "
                                + "duplicate sources, reads the best pages, and returns numbered sources (S1, S2...) with "
                                + "quoted passages and character offsets you can cite. Use it for questions that need "
                                + "multiple sources - comparisons, literature or market scans, 'what is the current state of X', "
                                + "fact-checking. Do NOT use it for a single quick fact (use web_search) or a page the user "
                                + "gave you (use fetch_web_page). You are the planner: write the brief yourself - effort "
                                + "'simple' (one angle), 'comparison' (one sub-question per compared item, 2-4), "
                                + "'complex' (5+ independent angles). Then write the report from the returned passages only "
                                + "and run verify_citations on the quotes you use.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "question": {
                            "type": "string",
                            "description": "The research question, stated completely."
                        },
                        "subQuestions": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Independent angles to search in parallel, each a self-contained search query. Omit for a simple question."
                        },
                        "effort": {
                            "type": "string",
                            "enum": ["auto", "simple", "comparison", "complex"],
                            "description": "How much to spend: simple=1 worker, comparison=2-4, complex=5+ (capped). Default auto, inferred from subQuestions."
                        },
                        "maxSources": {
                            "type": "integer",
                            "description": "Optional cap on pages to read (never above the platform ceiling)."
                        },
                        "seedUrls": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Optional public URLs the user named; they are read in addition to the search results."
                        }
                    },
                    "required": ["question"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            ResearchBrief brief = ResearchBrief.parse(args);
            int searchCap = Integer.MAX_VALUE;
            int reserved = 0;
            if (allowance != null) {
                // Reserve the searches this call could use BEFORE any runs (atomic, so concurrent
                // calls
                // cannot all pass a check and overshoot the daily cap); refund what was not used.
                int wanted = service.plan(brief, session.searchesRemaining()).workers();
                if (wanted <= 0) {
                    // This turn's own ceiling is spent: let the service report it, nothing to
                    // reserve.
                    searchCap = 0;
                } else {
                    reserved = allowance.reserve(wanted);
                    if (reserved <= 0) {
                        return ALLOWANCE_USED_UP;
                    }
                    searchCap = reserved;
                }
            }
            // An unexpected failure inside run() keeps the reservation charged: searches may
            // already have run.
            Map<String, Object> result = service.run(brief, session, searchCap);
            if (allowance != null && reserved > 0) {
                // "workers" is exactly the number of search queries this call actually ran.
                int used =
                        result.get("workers") instanceof Integer searches
                                ? Math.max(0, searches)
                                : 0;
                if (used < reserved) {
                    allowance.refund(reserved - used);
                }
            }
            return MAPPER.writeValueAsString(result);
        } catch (ResearchBrief.InvalidBriefException e) {
            return "{\"error\": " + quote(e.getMessage()) + "}";
        } catch (Exception e) {
            log.warn("deep_research failed unexpectedly", e);
            return "{\"error\": \"deep_research failed unexpectedly - fall back to web_search and fetch_web_page\"}";
        }
    }

    private static String quote(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"invalid input\"";
        }
    }
}
