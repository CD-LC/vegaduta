package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.a2a.A2aRemoteAgentToolSource;
import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.commerce.CheckPaymentStatusToolCallback;
import com.agenticai.core.agents.tools.commerce.CreatePaymentLinkToolCallback;
import com.agenticai.core.agents.tools.e2b.E2bRunCodeToolCallback;
import com.agenticai.core.agents.tools.e2b.SandboxArtifactRetention;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.agentspi.MandatePaymentToolSource;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.sdlc.E2bCredentialService;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.storage.FileStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
final class AgentIntegrationToolResolver {

    private final AgentToolGuard toolGuard;

    private final AgentService agentService;
    // Connected-workspace tools (Google today), contributed through the kernel's
    // WorkspaceToolSource
    // so this class never imports a vendor (ADR-0011 rule a). Empty list = none offered.
    private final List<WorkspaceToolSource> workspaceToolSources;
    // Master Roadmap 2026 item #14 fast-follow (#1071) - ObjectProviders for the same reason
    // every other optional capability in this class uses one: core/sdlc is always on the
    // classpath in the real app (agents now depends on it directly, see core/agents/pom.xml),
    // but this keeps slim test contexts constructible without an E2bCredentialService/
    // SdlcSandboxService bean. Two-gate, explicit-attachment pattern identical to Sarvam's
    // (see resolveE2bTools' javadoc): tenant-level e2bCredentialService.isActive() &&
    // .hasCodeExecutionTemplate() (cheap booleans, not the throwing resolve*() methods) AND
    // this specific agent's agentService.isE2bEnabled(agentId) (off by default).
    private final ObjectProvider<E2bCredentialService> e2bCredentialService;
    private final ObjectProvider<SdlcSandboxService> sdlcSandboxService;
    // Agentic commerce v0 (create_payment_link / check_payment_status, see
    // com.agenticai.core.agents.tools.commerce) - ObjectProvider for the same reason
    // every other cross-module optional capability here uses one: core/billing
    // is always on the classpath in the real app (transitively via tenancy),
    // but this keeps slim test contexts constructible without a
    // CheckoutSessionService bean. Gated on the cheap, non-throwing
    // isPaymentGatewayAvailable() (mirrors sarvamCredentialService.isActive())
    // rather than letting PaymentGatewayRegistry#resolve throw per turn - see
    // resolveCommerceTools' javadoc. NOTE: the approval gate for these tools
    // is NOT here - it is GuardedToolCallback + ApprovalPolicyService's default
    // "*payment*" pattern, exactly as for every other tool guardAndLog wraps.
    private final ObjectProvider<CheckoutSessionService> checkoutSessionService;
    // E1 Agentic UPI Payments - the mandate-aware payment tool surface, through the kernel's
    // MandatePaymentToolSource (ADR-0011 rule a). ObjectProvider for the same slim-test-context
    // reason as checkoutSessionService; absent = legacy commerce tools only.
    private final ObjectProvider<MandatePaymentToolSource> mandatePaymentToolSource;
    // The built-in web tools (fetch_web_page, http_request, web_search, deep_research /
    // verify_citations, web_task) live in core/agents-browser behind this port (ADR-0023). This
    // class still wraps each one in the tool guard and deep_research in the turn's run budget.
    // ObjectProvider so slim test contexts without the bean still construct; no web tools then.
    private final ObjectProvider<WebToolPort> webTools;

    /**
     * Per-turn shared RunBudget source (keyed by the turn's stepCounter); null = no USD gate (still
     * bounded by its own config).
     */
    private final Function<AtomicInteger, RunBudget> runBudgetFactory;

    // Data-analysis sandbox (gap-analysis item 9) - setter-injected via AgentToolProvider so this
    // class's (test-constructed) constructor signature is untouched. Null = classic run_code.
    private ObjectProvider<SandboxFileRegistry> sandboxFileRegistry;
    private ObjectProvider<FileStorageService> sandboxFileStorage;
    private ObjectProvider<SandboxArtifactRetention> sandboxRetention;

    // Outbound A2A client (L3) - setter-injected via AgentToolProvider so this class's
    // test-constructed constructor signature is untouched. Null/absent/disabled = tool not offered.
    // The port is implemented by A2aClientService in core/agents-interop (ADR-0023).
    private ObjectProvider<A2aRemoteAgentToolSource> a2aClient;

    void setA2aClient(ObjectProvider<A2aRemoteAgentToolSource> a2aClient) {
        this.a2aClient = a2aClient;
    }

    /**
     * Whether the agent's "Deep research" capability switch AND the deployment flag are on; false
     * without the web tools. Never throws.
     */
    boolean researchCapabilityEnabled(UUID agentId) {
        WebToolPort port = webTools == null ? null : webTools.getIfAvailable();
        return port != null && port.researchCapabilityEnabled(agentId);
    }

    /** True for the two tool names the "Deep research" capability stands for - and nothing else. */
    static boolean isResearchToolName(String name) {
        return WebToolPort.RESEARCH_TOOL_NAMES.contains(name);
    }

    /**
     * The non-empty allow-list filter, with one exception: an agent whose "Deep research"
     * capability is on keeps deep_research / verify_citations even when its allow-list does not
     * name them (otherwise the switch would silently do nothing for any agent that has an
     * allow-list). The capability is read only when one of those two tools resolved and is not
     * already allowed, so an ordinary turn pays no query.
     */
    static List<ToolCallback> filterAllowed(
            java.util.Set<String> allowed,
            List<ToolCallback> tools,
            java.util.function.BooleanSupplier researchCapability) {
        boolean researchAllowed =
                tools.stream()
                                .anyMatch(
                                        tool ->
                                                isResearchToolName(tool.getToolDefinition().name())
                                                        && !allowed.contains(
                                                                tool.getToolDefinition().name()))
                        && researchCapability.getAsBoolean();
        return tools.stream()
                .filter(
                        tool ->
                                allowed.contains(tool.getToolDefinition().name())
                                        || (researchAllowed
                                                && isResearchToolName(
                                                        tool.getToolDefinition().name())))
                .toList();
    }

    /**
     * Web tools resolved for tool DISCOVERY (listing only): research tools are listed, never gated
     * or stubbed.
     */
    List<ToolCallback> resolveWebToolsForDiscovery(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        return ResearchTurnScope.withMode(
                ResearchTurnScope.Mode.DISCOVERY,
                () ->
                        resolveWebTools(
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
    }

    /**
     * Per-agent opt-in for a2a_call_agent: the tool name must be explicitly in the agent's
     * allow-list.
     */
    private boolean agentOptedIntoA2a(UUID agentId) {
        if (agentService == null || agentId == null) {
            return false;
        }
        try {
            java.util.Set<String> allowed = agentService.getAllowedToolNames(agentId);
            return allowed != null && allowed.contains(A2aRemoteAgentToolSource.TOOL_NAME);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * D1 platform-managed E2B fallback: readiness predicate + per-tenant run quota. Unset =
     * tenant-key only.
     */
    private ObjectProvider<com.agenticai.core.sdlc.E2bAccessResolver> e2bAccessResolver;

    private ObjectProvider<com.agenticai.core.agents.tools.e2b.PlatformSandboxRunQuota>
            platformSandboxRunQuota;

    void setPlatformSandboxSupport(
            ObjectProvider<com.agenticai.core.sdlc.E2bAccessResolver> accessResolver,
            ObjectProvider<com.agenticai.core.agents.tools.e2b.PlatformSandboxRunQuota> quota) {
        this.e2bAccessResolver = accessResolver;
        this.platformSandboxRunQuota = quota;
    }

    void setSandboxDataSupport(
            ObjectProvider<SandboxFileRegistry> registry,
            ObjectProvider<FileStorageService> storage,
            ObjectProvider<SandboxArtifactRetention> retention) {
        this.sandboxFileRegistry = registry;
        this.sandboxFileStorage = storage;
        this.sandboxRetention = retention;
    }

    AgentIntegrationToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            List<WorkspaceToolSource> workspaceToolSources,
            ObjectProvider<E2bCredentialService> e2bCredentialService,
            ObjectProvider<SdlcSandboxService> sdlcSandboxService,
            ObjectProvider<CheckoutSessionService> checkoutSessionService,
            ObjectProvider<WebToolPort> webTools,
            ObjectProvider<MandatePaymentToolSource> mandatePaymentToolSource) {
        this(
                toolGuard,
                agentService,
                workspaceToolSources,
                e2bCredentialService,
                sdlcSandboxService,
                checkoutSessionService,
                webTools,
                mandatePaymentToolSource,
                null);
    }

    AgentIntegrationToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            List<WorkspaceToolSource> workspaceToolSources,
            ObjectProvider<E2bCredentialService> e2bCredentialService,
            ObjectProvider<SdlcSandboxService> sdlcSandboxService,
            ObjectProvider<CheckoutSessionService> checkoutSessionService,
            ObjectProvider<WebToolPort> webTools,
            ObjectProvider<MandatePaymentToolSource> mandatePaymentToolSource,
            Function<AtomicInteger, RunBudget> runBudgetFactory) {
        this.toolGuard = toolGuard;
        this.agentService = agentService;
        this.workspaceToolSources =
                workspaceToolSources == null ? List.of() : List.copyOf(workspaceToolSources);
        this.e2bCredentialService = e2bCredentialService;
        this.sdlcSandboxService = sdlcSandboxService;
        this.checkoutSessionService = checkoutSessionService;
        this.webTools = webTools;
        this.mandatePaymentToolSource = mandatePaymentToolSource;
        this.runBudgetFactory = runBudgetFactory;
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
     * Tools from every connected workspace account ({@link WorkspaceToolSource}; Google Workspace
     * today, see {@code GoogleWorkspaceToolSource} for what it offers and why). Offered to every
     * agent automatically once the TENANT connects an account - there is no per-agent toggle; the
     * per-agent allow-list ({@link #filterByAllowedTools}) still applies. Each tool is wrapped in
     * the tool guard.
     */
    List<ToolCallback> resolveWorkspaceTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<ToolCallback> tools = new java.util.ArrayList<>();
        for (WorkspaceToolSource source : workspaceToolSources) {
            for (ToolCallback raw : source.rawTools()) {
                tools.add(
                        guardAndLog(
                                raw,
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
            }
        }
        return tools;
    }

    /**
     * Master Roadmap 2026 item #14 fast-follow (#1071 - "E2B: agent tool-callback + frontend UI") -
     * {@code run_code} requires the exact same two-gate, explicit-attachment pattern {@link
     * #resolveSarvamTools} established: a tenant-level E2B credential that is both ACTIVE and has a
     * code-execution template configured (gated on {@code e2bCredentialService.isActive() &&
     * e2bCredentialService.hasCodeExecutionTemplate()} - two cheap boolean checks, never {@code
     * resolveActiveApiKey()}/{@code resolveCodeExecutionTemplateId()}, which throw {@code
     * E2bNotConfiguredException}) AND this specific agent having been explicitly attached via
     * {@code agentService.isE2bEnabled(agentId)} (off by default - see {@code Agent.e2bEnabled}'s
     * javadoc). Offering arbitrary code execution to every agent the moment a tenant connects E2B
     * would be a materially bigger blast-radius mistake than Sarvam's original tenant-only gate
     * ever was, so this starts with the explicit-attachment model Sarvam only adopted after direct
     * feedback - see resolveSarvamTools' javadoc for that history.
     */
    List<ToolCallback> resolveE2bTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        // D1: tenant key first, else the platform-managed fallback
        // (app.sandbox.platform-e2b.enabled).
        com.agenticai.core.sdlc.E2bAccessResolver accessResolver =
                e2bAccessResolver == null ? null : e2bAccessResolver.getIfAvailable();
        if (accessResolver != null
                && (!accessResolver.codeExecutionReady() || !agentService.isE2bEnabled(agentId))) {
            return List.of();
        }
        E2bCredentialService credentialService =
                accessResolver != null ? null : e2bCredentialService.getIfAvailable();
        if (accessResolver == null
                && (credentialService == null
                        || !credentialService.isActive()
                        || !credentialService.hasCodeExecutionTemplate()
                        || !agentService.isE2bEnabled(agentId))) {
            return List.of();
        }
        SdlcSandboxService sandboxService = sdlcSandboxService.getIfAvailable();
        if (sandboxService == null) {
            return List.of();
        }
        SandboxFileRegistry fileRegistry =
                sandboxFileRegistry == null ? null : sandboxFileRegistry.getIfAvailable();
        FileStorageService fileStorage =
                sandboxFileStorage == null ? null : sandboxFileStorage.getIfAvailable();
        SandboxArtifactRetention retention =
                sandboxRetention == null ? null : sandboxRetention.getIfAvailable();
        ToolCallback runCode =
                new E2bRunCodeToolCallback(
                        sandboxService,
                        fileRegistry,
                        fileStorage,
                        retention,
                        agentId,
                        sessionId,
                        accessResolver,
                        platformSandboxRunQuota == null
                                ? null
                                : platformSandboxRunQuota.getIfAvailable());
        return List.of(
                guardAndLog(
                        runCode,
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * The built-in web tools, in their original offer order: {@code fetch_web_page}, the {@code
     * http_request} tools, {@code a2a_call_agent}, {@code web_search}, the deep_research pair and
     * {@code web_task}. All but a2a come from {@link WebToolPort} (core/agents-browser), which owns
     * the offer gates; this method wraps each one in {@link #guardAndLog} and gives deep_research
     * the turn's shared RunBudget (the same instance the delegation tools use; it only GATES the
     * tool once the USD cap is spent - deep_research makes no model call). This method is the one
     * both resolveToolsFor and listAvailableToolsFor (via resolveWebToolsForDiscovery) call.
     */
    List<ToolCallback> resolveWebTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        WebToolPort port = webTools == null ? null : webTools.getIfAvailable();
        List<ToolCallback> web = new ArrayList<>();
        if (port != null) {
            for (ToolCallback raw : port.pageAndHttpTools(agentId)) {
                web.add(
                        guardAndLog(
                                raw,
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
            }
        }
        // a2a_call_agent: offered only when app.a2a.client.enabled (default OFF). All policy
        // (host allow-list, SSRF, signed card, timeouts) is enforced at call time in
        // A2aClientService.
        A2aRemoteAgentToolSource a2a = a2aClient == null ? null : a2aClient.getIfAvailable();
        // Offered ONLY when the operator allow-listed at least one host (otherwise every call would
        // be refused and the tool would only cost prompt tokens) AND this agent explicitly opted in
        // by naming a2a_call_agent in its tool allow-list (empty allow-list = "unrestricted", which
        // is deliberately NOT an opt-in for an egress tool).
        if (a2a != null && a2a.isEnabled() && a2a.hasAllowedHosts() && agentOptedIntoA2a(agentId)) {
            ToolCallback a2aCall = a2a.newCallAgentTool();
            web.add(
                    guardAndLog(
                            a2aCall,
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        if (port == null) {
            return web;
        }
        // Turn key: inside a delegation this is the PARENT turn's counter (ResearchTurnScope), so
        // the research session and the RunBudget are one per USER turn.
        AtomicInteger turnKey = ResearchTurnScope.turnKey(stepCounter);
        WebToolPort.SearchTools search =
                port.searchTools(agentId, sessionId, turnKey, userMessage);
        if (search.webSearch() != null) {
            web.add(
                    guardAndLog(
                            search.webSearch(),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        if (search.deepResearch() != null) {
            if (ResearchTurnScope.mode() == ResearchTurnScope.Mode.STATELESS) {
                // Fail-closed stubs (edge / approval replay): offered as they are.
                web.add(search.deepResearch());
                web.add(search.verifyCitations());
            } else {
                RunBudget researchBudget =
                        runBudgetFactory == null ? null : runBudgetFactory.apply(turnKey);
                ToolCallback deepResearch =
                        toolGuard.withRunBudget(search.deepResearch(), researchBudget, false);
                web.add(
                        guardAndLog(
                                deepResearch,
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
                web.add(
                        guardAndLog(
                                search.verifyCitations(),
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
            }
        }
        if (search.webTask() != null) {
            web.add(
                    guardAndLog(
                            search.webTask(),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return web;
    }

    /**
     * Agentic commerce v0 - {@code create_payment_link} / {@code check_payment_status} (see {@code
     * com.agenticai.core.agents.tools.commerce} and core/billing's {@code
     * com.agenticai.core.billing.commerce} package-info for the "ACP-shaped, not ACP-conformant"
     * scope). Offered to every agent once a default payment gateway is registered (the cheap {@code
     * isPaymentGatewayAvailable()} boolean - under the {@code security} profile with Razorpay keys
     * set, always true), the same tenant-level-only gate {@link #resolveWorkspaceTools} uses: there
     * is no per-agent "commerce" flag today, and an agent that should never collect money is kept
     * away from these by the existing per-agent allow-list ({@link #filterByAllowedTools}), exactly
     * like any other tool.
     *
     * <p><b>Money movement is gated by the standard approval path, not by anything here.</b> Both
     * callbacks are wrapped by {@link #guardAndLog} like every other tool, and their {@code
     * mcp_tool.create_payment_link} / {@code mcp_tool.check_payment_status} action types match
     * {@code ApprovalPolicyService}'s default {@code *payment*} sensitive pattern - so on a fresh
     * install, with zero overrides, a SEMI_AUTONOMOUS agent's call is recorded for a tenant-admin's
     * approval and replayed by {@code AgentToolCallApprovalExecutor} (through {@link
     * #resolveToolCandidatesFor}, which is why these must resolve there too - they do, via the
     * shared {@code resolveToolsFor} path). SUPERVISED agents are ALWAYS_GATE regardless;
     * FULLY_AUTONOMOUS is the tenant's explicit opt-out for that agent. Nothing in this method, the
     * callbacks, or {@code CheckoutSessionService} bypasses that gate.
     */
    /**
     * E1 addition: when the agent holds an ACTIVE signed {@code PaymentMandate}, the mandate-aware
     * callbacks (same {@code create_payment_link} / {@code check_payment_status} names plus {@code
     * request_upi_collect}) REPLACE the v0 checkout-session set - one tool name must never point at
     * two different backends. Without a mandate, the v0 tools remain exactly as before (visibility
     * there is the gateway-availability gate + per-agent allow-list, unchanged). The mandate set's
     * own invisibility rule lives behind {@link MandatePaymentToolSource} ({@code
     * PaymentToolService#hasActiveMandate}): no ACTIVE mandate, no tools - an agent that loses its
     * mandate stops seeing payment tools on the very next turn, and a revoked mandate fails any
     * in-flight call at execution re-verification regardless.
     */
    List<ToolCallback> resolveCommerceTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        MandatePaymentToolSource mandateSource =
                mandatePaymentToolSource == null ? null : mandatePaymentToolSource.getIfAvailable();
        java.util.Optional<List<ToolCallback>> mandateTools =
                mandateSource == null
                        ? java.util.Optional.empty()
                        : mandateSource.mandateTools(agentId, sessionId);
        if (mandateTools.isPresent()) {
            return mandateTools.get().stream()
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
        CheckoutSessionService service = checkoutSessionService.getIfAvailable();
        if (service == null || !service.isPaymentGatewayAvailable()) {
            return List.of();
        }
        List<ToolCallback> raw =
                List.of(
                        new CreatePaymentLinkToolCallback(service, agentId, sessionId),
                        new CheckPaymentStatusToolCallback(service));
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
}
