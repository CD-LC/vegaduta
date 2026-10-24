package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Implements the "Plan-and-Execute" stage.
 *
 * <p>Classical Plan-and-Execute: one upfront, cheap call breaks a genuinely multi-part request
 * into an ordered step list before the normal ReAct tool-calling loop runs - cheaper than
 * re-planning after every single step (which is what plain ReAct implicitly does today), while
 * staying far simpler than a full {@code StateGraph}/backtracking planner (still out of scope
 * unless a real use case needs branch backtracking, per the prior build plan's own call).
 *
 * <p>Same rigid-response-format, fail-open shape as every other verifier/checker in this codebase
 * ({@code ChatClientToolCallVerifierService}, {@code AnswerConsistencyChecker}, {@code
 * ReflexionService}): a malformed, blank, or failed planning call degrades to "no plan" - i.e.
 * today's plain tool-calling behavior for every other {@link ReasoningDepth} - never a hard
 * failure. Only wired in for {@link ReasoningDepth#PLANNED} (see {@code AgentChatService}).
 */
@Service
public class TaskPlanningService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanningService.class);

    /** Registry keys (default text: {@code prompts/en/planner.task*.md}). */
    static final String PLAN_KEY = "planner.task";
    static final String REPLAN_KEY = "planner.task.replan";
    static final String TOOLS_NONE_KEY = "planner.task.tools_none";
    static final String TOOLS_KEY = "planner.task.tools";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of the keys above applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /** The tool hint with the shipped wording (no overrides); see {@link #toolsHint(java.util.List, UUID)}. */
    public static String toolsHint(java.util.List<String> toolNames) {
        return hint(new PromptSlot(), toolNames, null);
    }

    /**
     * The sentence appended to the planning prompt that names the agent's tools ({@code ""} when the
     * list is unknown, {@code null}), through the registry so overrides apply for {@code agentId}.
     */
    String toolsHint(java.util.List<String> toolNames, UUID agentId) {
        return hint(prompts, toolNames, agentId);
    }

    private static String hint(PromptSlot slot, java.util.List<String> toolNames, UUID agentId) {
        if (toolNames == null) {
            return "";
        }
        if (toolNames.isEmpty()) {
            return slot.text(TOOLS_NONE_KEY, agentId);
        }
        return slot.text(TOOLS_KEY, agentId, java.util.Map.of("tools", String.join(", ", toolNames)));
    }

    /**
     * The upfront plan for a {@link ReasoningDepth#PLANNED} turn. Returns {@code null} - never an
     * empty string - when planning is skipped, fails, or the model judged the request too simple
     * to need one; callers treat {@code null} exactly like "no plan produced".
     */
    public String planIfNeeded(ChatClient chatClient, String userMessage) {
        return planIfNeeded(chatClient, userMessage, null, null);
    }

    /**
     * Same as {@link #planIfNeeded(ChatClient, String)}, plus the turn's agent/session so the
     * upfront planning completion PLANNED pays for is attributable in {@code llm_call_log}
     * rather than landing there with a null agent - see {@link LlmMetricsAdvisor#attributeTo}.
     */
    public String planIfNeeded(ChatClient chatClient, String userMessage, UUID agentId, String sessionId) {
        return planIfNeeded(chatClient, userMessage, agentId, sessionId, null);
    }

    /**
     * Same, but tells the planner which tools the agent really has so it plans only steps the
     * agent can perform (a plan step no tool can do - "identify the current date" - just makes
     * the answering model narrate instead of act). {@code toolNames == null} = unknown, no hint.
     */
    public String planIfNeeded(
        ChatClient chatClient, String userMessage, UUID agentId, String sessionId, java.util.List<String> toolNames
    ) {
        if (chatClient == null || userMessage == null || userMessage.isBlank()) {
            return null;
        }
        try {
            String plan = chatClient.prompt()
                .system(prompts.text(PLAN_KEY, agentId) + toolsHint(toolNames, agentId))
                .user(userMessage)
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();
            String usable = usablePlan(plan);
            if (usable != null) {
                log.info("PLANNED turn - upfront plan produced:\n{}", usable);
            }
            return usable;
        } catch (Exception ex) {
            log.warn("Task planning call failed - proceeding without an upfront plan", ex);
            return null;
        }
    }

    /**
     * Re-plans the remaining steps after a tool call failed in a way that invalidates
     * {@code currentPlan}. Bounded to exactly ONE call by {@code AgentChatService} (never invoked
     * recursively from here, and never re-checked after the retry) - the same "bounded, not
     * unbounded" discipline {@code ReflexionService}'s single retry already established for
     * answer-quality revision, applied here to plan revision instead.
     */
    public String replan(ChatClient chatClient, String userMessage, String currentPlan, String failureDescription) {
        return replan(chatClient, userMessage, currentPlan, failureDescription, null, null);
    }

    /** Same as {@link #replan(ChatClient, String, String, String)}, with cost attribution. */
    public String replan(
        ChatClient chatClient, String userMessage, String currentPlan, String failureDescription,
        UUID agentId, String sessionId
    ) {
        if (chatClient == null || currentPlan == null) {
            return null;
        }
        try {
            String plan = chatClient.prompt()
                .system(prompts.text(REPLAN_KEY, agentId))
                .user("Original request: " + userMessage + "\n\nCurrent plan:\n" + currentPlan
                    + "\n\nA step just failed:\n" + failureDescription)
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();
            String usable = usablePlan(plan);
            log.info("PLANNED turn - re-planned after a tool failure:\n{}", usable == null ? "(no further steps needed)" : usable);
            return usable;
        } catch (Exception ex) {
            log.warn("Re-plan call failed - continuing with the original plan", ex);
            return null;
        }
    }

    private static String usablePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return null;
        }
        String trimmed = plan.trim();
        return trimmed.regionMatches(true, 0, "NO_PLAN_NEEDED", 0, "NO_PLAN_NEEDED".length()) ? null : trimmed;
    }
}
