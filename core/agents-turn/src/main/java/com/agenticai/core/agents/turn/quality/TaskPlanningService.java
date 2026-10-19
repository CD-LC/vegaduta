package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

    private static final String PLAN_SYSTEM_PROMPT = """
        You are a planning assistant for an AI agent that is about to handle a user's request,
        possibly using tools. Break the request into a short, ordered list of concrete steps the
        agent should take.

        If the request is simple enough to answer or handle in one step, respond with exactly:
        NO_PLAN_NEEDED

        Otherwise respond with ONLY a numbered list of steps, one per line, no other text:
        1. <step>
        2. <step>

        Keep steps concrete and actionable - each one should map to something the agent can
        actually do (call a tool, ask a clarifying question, produce part of the answer), not
        vague advice like "consider the user's needs".
        """;

    public static String toolsHint(java.util.List<String> toolNames) {
        if (toolNames == null) {
            return "";
        }
        if (toolNames.isEmpty()) {
            return "\nThe agent has NO tools: plan only steps it can do by answering directly.\n";
        }
        return "\nThe agent's tools are: " + String.join(", ", toolNames) + ". Plan only steps these tools "
            + "or the agent's own reasoning can do. Do not add steps like \"identify the current date\" "
            + "(the date is already in the agent's prompt). If the request needs a capability none of "
            + "these tools provide, make the step say what is missing.\n";
    }

    private static final String REPLAN_SYSTEM_PROMPT = """
        An AI agent was following a plan to handle a user's request, but a step failed in a way
        that invalidates the rest of the plan. Produce a replacement numbered list of the
        REMAINING steps only, taking the failure into account (work around it, try an
        alternative, or drop steps that depended on it). Same format as before - ONLY a numbered
        list, no other text. If nothing further is needed or possible, respond with exactly:
        NO_PLAN_NEEDED
        """;

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
                .system(PLAN_SYSTEM_PROMPT + toolsHint(toolNames))
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
                .system(REPLAN_SYSTEM_PROMPT)
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
