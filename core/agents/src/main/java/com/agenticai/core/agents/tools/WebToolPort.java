package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023): the built-in web tools that {@link AgentIntegrationToolResolver} offers.
 * Implemented in core/agents-browser ({@code BuiltInWebTools}), which owns {@code tools.web}; this
 * module never imports that package. Everything returned is RAW (unguarded): the resolver wraps
 * each tool in the tool guard and the turn's run budget, exactly as it did when the tools were
 * built in place. No bean (a slim context) = no web tools offered.
 */
public interface WebToolPort {

    /** The two tool names the "Deep research" capability stands for. */
    Set<String> RESEARCH_TOOL_NAMES = Set.of("deep_research", "verify_citations");

    /**
     * {@code fetch_web_page} followed by the {@code http_request} tools configured for this agent
     * ({@code app.tools.http-request.*}), in offer order.
     */
    List<ToolCallback> pageAndHttpTools(UUID agentId);

    /**
     * {@code web_search}, the deep_research pair and {@code web_task}, each null when not offered.
     * {@code turnKey} is the user turn's key ({@code ResearchTurnScope.turnKey}), so a delegate
     * shares its parent's research session. Under {@code ResearchTurnScope.Mode.STATELESS} the
     * research pair are fail-closed stubs that must be offered as they are (no guard, no budget).
     */
    SearchTools searchTools(UUID agentId, String sessionId, AtomicInteger turnKey, String userMessage);

    /** Whether the agent's "Deep research" capability switch and the deployment flag are on. */
    boolean researchCapabilityEnabled(UUID agentId);

    /** The search-side tools of one resolution; any member may be null. */
    record SearchTools(
            ToolCallback webSearch,
            ToolCallback deepResearch,
            ToolCallback verifyCitations,
            ToolCallback webTask) {

        public static final SearchTools NONE = new SearchTools(null, null, null, null);
    }
}
