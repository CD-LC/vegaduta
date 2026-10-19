package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.turn.AgentChatService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for the browser-use fallback tools and the "Crew"-style specialist handoff
 * tool, split out of {@link AgentToolProvider} to keep that class under its file-size ratchet. Not
 * a Spring bean: constructed by AgentToolProvider.
 */
final class AgentCrewBrowserToolResolver {

    /**
     * Mirrors app.research.deep.enabled; false = the specialist tool is not wrapped (flag-off
     * parity).
     */
    private volatile boolean researchEnabled;

    void setResearchEnabled(boolean researchEnabled) {
        this.researchEnabled = researchEnabled;
    }

    /**
     * The in-flight "Crew" handoff chain state, visible only for the duration of one synchronous
     * resolveToolsFor call made via {@link #withHandoffChain} - same thread-scoped hand-off shape
     * as {@link AgentToolGuard#STEP_EVENT_SCOPE}, and for the same reason: threading a {@link
     * HandoffChain} parameter through the ~20 resolveXxxTools helpers and every guardAndLog call
     * site would touch dozens of signatures for a value only {@link
     * #resolveSpecialistDelegationTools} reads. Unset (null) for every ordinary chat turn, which
     * {@link #resolveSpecialistDelegationTools} reads as "this is the ROOT of a new chain" and
     * defaults to {@link HandoffChain#root}.
     */
    private static final ThreadLocal<HandoffChain> HANDOFF_CHAIN_SCOPE = new ThreadLocal<>();

    private final AgentToolGuard toolGuard;
    // Browser-use fallback tools (browser_navigate/click/type/read_page_text/screenshot,
    // 2026-09-23), built in core/agents-browser behind this port (ADR-0023). ObjectProvider so a
    // slim test context (no bean registered) stays constructible; no browser tools then.
    private final ObjectProvider<BrowserToolPort> browserTools;
    // "Crew"-style multi-agent handoff (chat-time, capability/role-based - see HandoffChain's
    // javadoc
    // for how this differs from the collaboratorAgentIds mechanism). No circular dependency on
    // SpecialistHandoffService (it depends only on SpecialistRegistry/AgentHandoffAuditService/
    // AgentService), but ObjectProvider anyway so a slim test context without the feature's
    // JPA-backed beans stays constructible: absent means "no specialist delegation tool offered".
    private final ObjectProvider<SpecialistHandoffService> specialistHandoffService;
    // Same lazy ObjectProvider as AgentCustomToolResolver's - AgentChatService depends on
    // AgentToolProvider, so a direct field would be a bean cycle. See
    // resolveSpecialistDelegationTools.
    private final ObjectProvider<AgentChatService> agentChatService;

    AgentCrewBrowserToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<BrowserToolPort> browserTools,
            ObjectProvider<SpecialistHandoffService> specialistHandoffService,
            ObjectProvider<AgentChatService> agentChatService) {
        this.toolGuard = toolGuard;
        this.browserTools = browserTools;
        this.specialistHandoffService = specialistHandoffService;
        this.agentChatService = agentChatService;
    }

    private ToolCallback guardAndLog(
            ToolCallback raw,
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return toolGuard.guardAndLog(
                raw, agentId, sessionId, stepCounter, userMessage, chatClient, rigorous);
    }

    /**
     * Runs {@code resolve} with {@code chain} bound as the in-flight handoff chain (see {@link
     * #HANDOFF_CHAIN_SCOPE}), always clearing it afterwards. remove(), not set(null) - same
     * carrier-thread-reuse reasoning as STEP_EVENT_SCOPE's own finally block.
     */
    List<ToolCallback> withHandoffChain(HandoffChain chain, Supplier<List<ToolCallback>> resolve) {
        HANDOFF_CHAIN_SCOPE.set(chain);
        try {
            return resolve.get();
        } finally {
            HANDOFF_CHAIN_SCOPE.remove();
        }
    }

    /**
     * Browser-use fallback tool (2026-09-23, the "Lindy Computer Use" equivalent - see {@code
     * com.agenticai.core.agents.tools.browseruse}'s package): {@code browser_navigate}/{@code
     * browser_click}/{@code browser_type}/{@code browser_read_page_text}/{@code
     * browser_screenshot}. Two gates, both cheap and non-throwing - the same "offer-time gate, real
     * enforcement at call-time" split every other optional built-in uses (see resolveDeviceTools
     * for the closest sibling: same ObjectProvider-absent-in-slim-tests shape):
     *
     * <ol>
     *   <li>{@code BrowserUseSessionManager} bean must be present (always true in the real app;
     *       absent only in a slim test context).
     *   <li>{@code BrowserUseSessionManager#isEnabled()} - off by default (see that class's
     *       javadoc's "Off by default" section: Chromium must be provisioned in the image first).
     *       This is a platform-wide switch, not a per-tenant/per-agent one - unlike Sarvam/E2B's
     *       explicit per-agent attach, there is no {@code Agent.browserUseEnabled} column, because
     *       this is a fallback capability meant for every agent once an operator has decided the
     *       platform can run it, the same posture resolveWebTools' {@code fetch_web_page} takes
     *       once its own (no-gate) precondition is met.
     * </ol>
     *
     * All five tools resolve together or not at all - unlike resolveWebTools, there is no reason to
     * offer read-only browsing without click/type, since the whole point of this family over {@code
     * fetch_web_page} is the interactive half.
     */
    List<ToolCallback> resolveBrowserUseTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        BrowserToolPort port = browserTools.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        List<ToolCallback> raw = port.browserUseTools(agentId, sessionId);
        return raw.stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * "Crew"-style specialist handoff - the capability/role-tag-based sibling of
     * resolveCollaboratorTools. See {@code com.agenticai.core.agents.crew.HandoffChain}'s javadoc
     * for the full comparison; in short, this offers ONE dynamically-targeted tool ({@code
     * delegate_to_specialist}) instead of one tool per pre-wired collaborator, and allows a bounded
     * multi-hop CHAIN instead of a single hop.
     *
     * <p>{@code allowDelegation=false} short-circuits this to empty for exactly the same reason it
     * does for resolveCollaboratorTools: a turn already running INSIDE a legacy delegated call (see
     * AgentToolProvider#resolveToolsForDelegatedCall) must not be able to start a brand new crew
     * chain either - that single shared flag is what keeps the two delegation mechanisms from being
     * usable to bypass each other's depth cap.
     *
     * <p>The effective {@link HandoffChain} is read from {@link #HANDOFF_CHAIN_SCOPE}: unset (the
     * common case - an ordinary user-facing chat turn) defaults to {@link HandoffChain#root}, depth
     * 0; set (by AgentToolProvider#resolveToolsForSpecialistHandoff, when THIS turn is itself
     * running as a specialist mid-chain) reflects however many hops already happened. Either way,
     * once {@code chain.isAtMaxDepth()}, the tool is not offered at all - the depth cap is enforced
     * here (no tool to call) AND again, defensively, inside {@code
     * AgentChatService#delegateToSpecialist} itself in case a model attempts the call off a stale
     * tool list.
     *
     * <p>Offered only when at least one capability tag is registered anywhere in this tenant (an
     * empty {@code agent_specialist} table is today's unchanged "no such tool" behavior) and both
     * {@link SpecialistHandoffService} and {@link AgentChatService} are actually available - same
     * fail-open posture resolveCollaboratorTools applies to its own two dependencies.
     */
    List<ToolCallback> resolveSpecialistDelegationTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous,
            boolean allowDelegation) {
        if (!allowDelegation) {
            return List.of();
        }
        SpecialistHandoffService handoff = specialistHandoffService.getIfAvailable();
        if (handoff == null) {
            return List.of();
        }
        AgentChatService chatService = agentChatService.getIfAvailable();
        if (chatService == null) {
            return List.of();
        }
        HandoffChain chain = HANDOFF_CHAIN_SCOPE.get();
        if (chain == null) {
            chain = HandoffChain.root(sessionId, agentId);
        }
        if (chain.isAtMaxDepth()) {
            return List.of();
        }
        List<String> capabilityTags = handoff.listCapabilityTags();
        if (capabilityTags.isEmpty()) {
            return List.of();
        }
        // bind(): the specialist's deep_research ceilings resolve to THIS turn's, not a fresh set.
        ToolCallback delegate =
                ResearchTurnScope.bindIfEnabled(
                        researchEnabled,
                        handoff.delegationTool(chatService, capabilityTags, chain),
                        stepCounter);
        return List.of(
                guardAndLog(
                        delegate,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * {@code browser_totp} (app.browser.totp.enabled) and {@code run_browser_recipe}
     * (app.browser.recipes.enabled), both default OFF. Same two offer-time gates as {@link
     * #resolveBrowserUseTools} (manager bean present and enabled) plus {@code
     * BrowserAutomationTools}, which decides the per-feature flags; with everything off this
     * returns an empty list, so the tool list is unchanged. Called from BOTH
     * AgentToolProvider.resolveToolsFor and listAvailableToolsFor so the tools also appear in the
     * per-agent tool picker.
     */
    List<ToolCallback> resolveBrowserAutomationTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        BrowserToolPort port = browserTools.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        return port.browserAutomationTools(agentId, sessionId).stream()
                .map(
                        callback ->
                                guardAndLog(
                                        callback,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }
}
