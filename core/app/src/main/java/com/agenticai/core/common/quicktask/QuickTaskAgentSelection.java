package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.collab.DelegateToAgentToolCallback;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.decision.AgentRelevanceSelector;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent choice for a Quick Task run: the one primary agent that owns the answer (explicit pick, or
 * {@link AgentRelevanceSelector}'s best match), plus up to {@link #MAX_ADDITIONAL_AGENTS} other
 * agents offered to it as one-off {@code ask_agent_<slug>} delegation tools (#911). Created and
 * owned by {@link QuickTaskService}; not a bean.
 */
final class QuickTaskAgentSelection {

    private final AgentService agentService;
    private final AgentChatService agentChatService;
    private final AgentRelevanceSelector agentRelevanceSelector;

    QuickTaskAgentSelection(
            AgentService agentService,
            AgentChatService agentChatService,
            AgentRelevanceSelector agentRelevanceSelector) {
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.agentRelevanceSelector = agentRelevanceSelector;
    }

    /**
     * #591 follow-up ("quick task should be very perfect in choosing right agents and mcp tools") -
     * was {@code firstAgentOrThrow()}, always {@code agents.get(0)} regardless of the task's own
     * text. Now routes through {@link AgentRelevanceSelector} so a tenant running more than one
     * specialized agent gets the one that actually matches what was asked, not whichever agent
     * happens to be first. See that class's javadoc for the selection strategy and its deliberate
     * fallbacks.
     *
     * <p>"customers should be able to select the agents" - {@code explicitAgentId} skips
     * auto-selection entirely when the caller already knows which agent it wants. {@link
     * AgentService#get} does the real work here (tenant-scoped lookup, 404s via {@code
     * AgentNotFoundException} for an unknown/cross-tenant id) - this method doesn't re-implement
     * that check, just chooses which path to take.
     */
    AgentResponse selectAgentOrThrow(String message, UUID explicitAgentId) {
        if (explicitAgentId != null) {
            return agentService.get(explicitAgentId);
        }
        List<AgentResponse> agents = agentService.listAll();
        if (agents.isEmpty()) {
            throw new QuickTaskNotReadyException(
                    "No agent is set up yet for this workspace - create one first, then Quick Task will use it automatically from now on.");
        }
        return agentRelevanceSelector.selectBest(message, agents);
    }

    /**
     * Caps how many extra specialist agents one Quick Task run offers the primary agent - see this
     * method's own javadoc below for why a small, fixed cap.
     */
    private static final int MAX_ADDITIONAL_AGENTS = 2;

    /**
     * #911 ("in quick tasks, if it requires multiple agents, it should use additionally") - {@link
     * #selectAgentOrThrow} picks exactly ONE primary agent to actually run the turn (unchanged -
     * Quick Task still needs a single owner for the response/dispatch), but a task's text can
     * genuinely span more than one agent's specialty ("translate this and log it in the CRM"
     * touches both a Translator and a CRM agent). This builds ad-hoc {@code ask_agent_<slug>}
     * delegation tools - the exact same {@link DelegateToAgentToolCallback} class {@code
     * AgentToolProvider#resolveCollaboratorTools} already uses for permanently-configured
     * collaborators - for whichever OTHER agents {@link
     * AgentRelevanceSelector#selectAdditionalRelevant} finds lexically relevant to this one task,
     * and hands them to {@link AgentChatService}'s {@code extraTools} overload for this run only.
     * Nothing is persisted: the tenant's saved {@code collaboratorAgentIds} on any agent is
     * untouched, so this has zero effect outside the single Quick Task turn it was built for.
     *
     * <p>Capped at {@link #MAX_ADDITIONAL_AGENTS} - offering every other agent in the tenant as a
     * delegation tool would bloat the prompt for tenants with many agents and dilute the model's
     * ability to tell which delegate is actually relevant; two extra candidates is enough headroom
     * for the common "this task touches one other specialty" case without turning every Quick Task
     * into an every-agent free-for-all. Returns an empty list (not an error) for a single-agent
     * tenant or a task with no lexical overlap against anything but the primary - the common case,
     * left byte-for-byte unchanged from before this feature existed.
     */
    List<ToolCallback> additionalAgentTools(AgentResponse primary, String message) {
        List<AgentResponse> agents = agentService.listAll();
        List<AgentResponse> additional =
                agentRelevanceSelector.selectAdditionalRelevant(
                        message, agents, primary, MAX_ADDITIONAL_AGENTS);
        if (additional.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> tools = new ArrayList<>();
        for (AgentResponse collaborator : additional) {
            String toolName = "ask_agent_" + slugifyAgentName(collaborator.name());
            String prompt = collaborator.systemPrompt();
            String shortDescription =
                    prompt == null ? "" : prompt.substring(0, Math.min(200, prompt.length()));
            // AgentJobExecution#runOneTurn documents the repo invariant for this exact
            // extraTools seam: "the wrap is mandatory, not stylistic" - extraTools are passed
            // RAW past guardAndLog and the delegation executes later on a reactive thread that
            // does NOT inherit this plain-ThreadLocal TenantContext. Unwrapped, delegateCall's
            // tenant-scoped agent lookup silently resolved against the empty public schema (or
            // a stale wrong-tenant ThreadLocal). The context is read here on the calling
            // thread - correct for both entry points: run() calls execute() on the request
            // thread, and runStream()'s execute() re-applies its captured context before this
            // method runs (2026-08-19 MCP-blackout follow-up sweep).
            tools.add(
                    new TenantContextPropagatingToolCallback(
                            new DelegateToAgentToolCallback(
                                    agentChatService,
                                    collaborator.id(),
                                    toolName,
                                    collaborator.name(),
                                    shortDescription),
                            TenantContext.current().orElse(null)));
        }
        return tools;
    }

    /**
     * Mirrors {@code AgentToolProvider#slugify} exactly - same {@code ask_agent_<slug>} naming
     * convention for a delegation tool, wherever it's built.
     */
    private static String slugifyAgentName(String name) {
        if (name == null) {
            return "agent";
        }
        String slug =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "agent" : slug;
    }
}
