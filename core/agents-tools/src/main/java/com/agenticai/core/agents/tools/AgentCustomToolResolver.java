package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.collab.CollaboratorToolFactory;
import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.tools.ImproveSkillToolCallback;
import com.agenticai.core.agents.skills.tools.LoadSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ProposeSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ReadSkillFileToolCallback;
import com.agenticai.core.agents.skills.tools.RunSkillScriptToolCallback;
import com.agenticai.core.agents.tools.code.CreateCustomToolToolCallback;
import com.agenticai.core.agents.tools.code.CustomCodeTool;
import com.agenticai.core.agents.tools.code.CustomCodeToolCallback;
import com.agenticai.core.agents.tools.code.CustomCodeToolService;
import com.agenticai.core.agents.tools.code.CustomCodeToolSnapshot;
import com.agenticai.core.agents.tools.code.ToolSandboxClient;
import com.agenticai.core.agents.tools.code.ToolSandboxProperties;
import com.agenticai.core.agents.tools.openapi.OpenApiToolCallback;
import com.agenticai.core.agents.tools.openapi.OpenApiToolExecutor;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSnapshot;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceService;
import com.agenticai.core.agents.tools.script.UserToolScript;
import com.agenticai.core.agents.tools.script.UserToolScriptCallback;
import com.agenticai.core.agents.tools.script.UserToolScriptEvaluator;
import com.agenticai.core.agents.tools.script.UserToolScriptService;
import com.agenticai.core.agents.tools.script.UserToolScriptSnapshot;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group extracted from {@link AgentToolProvider} (extract-only, no behavior
 * change). Not a Spring bean: constructed by AgentToolProvider.
 */
final class AgentCustomToolResolver {

    /**
     * Mirrors app.research.deep.enabled; false = delegation tools are not wrapped (flag-off
     * parity).
     */
    private volatile boolean researchEnabled;

    void setResearchEnabled(boolean researchEnabled) {
        this.researchEnabled = researchEnabled;
    }

    /** Mirrors app.tools.run-subtasks.enabled: offer the ad hoc {@code run_subtasks} tool. */
    private volatile boolean runSubtasksEnabled = true;

    void setRunSubtasksEnabled(boolean runSubtasksEnabled) {
        this.runSubtasksEnabled = runSubtasksEnabled;
    }

    private static final Logger log = LoggerFactory.getLogger(AgentCustomToolResolver.class);

    private final AgentToolGuard toolGuard;
    private final Function<AtomicInteger, RunBudget> runBudgetFactory;

    private final AgentService agentService;
    // ObjectProvider (not a
    // plain constructor dependency) specifically to break a circular bean dependency:
    // AgentChatService already depends on THIS class (to resolve every tool an agent gets), so a
    // direct AgentChatService field here would be a genuine cycle. ObjectProvider defers the
    // actual lookup to first use inside resolveCollaboratorTools - by then both beans are fully
    // constructed - so Spring never has to resolve the cycle eagerly at startup. See
    // resolveCollaboratorTools' javadoc.
    private final ObjectProvider<AgentChatService> agentChatService;
    // #424 - "user-created tools (generated scripts)". Direct dependencies,
    // not ObjectProvider - unlike every cross-module optional capability
    // above, UserToolScriptService/UserToolScriptEvaluator live in THIS
    // module (com.agenticai.core.agents.tools.script), so there's no slim-test-
    // context absence to guard against - the same reason toolCallLogRepository
    // and toolRelevanceSelector above are plain fields too.
    private final UserToolScriptService userToolScriptService;
    private final UserToolScriptEvaluator userToolScriptEvaluator;
    // "Custom Code Tools" - the real-code sibling of the SpEL feature above.
    // Plain field for the same reason userToolScriptService is one:
    // CustomCodeToolService lives in THIS module
    // (com.agenticai.core.agents.tools.code), so there is no slim-test-context
    // absence to guard against. (The optional part of that feature - the
    // sandbox HTTP client, which only exists when tool-sandbox.enabled=true -
    // is held as an ObjectProvider inside CustomCodeToolService itself, so the
    // conditionality never leaks out here.)
    private final CustomCodeToolService customCodeToolService;
    // "Import from OpenAPI" - the no-code sibling of the two tenant-tool
    // features above: every operation of an imported OpenAPI document is an
    // agent tool. Plain fields for the same in-this-module reason
    // (com.agenticai.core.agents.tools.openapi); the optional pieces (encryption,
    // vault) are ObjectProviders inside the executor itself.
    private final OpenApiToolSourceService openApiToolSourceService;
    private final OpenApiToolExecutor openApiToolExecutor;
    private final SkillService skillService;
    private final SkillPromptBlock.SessionGate skillGate = new SkillPromptBlock.SessionGate();
    private final ObjectProvider<ToolSandboxClient> skillSandboxClient;
    private final ObjectProvider<ToolSandboxProperties> skillSandboxProperties;
    // ObjectProvider - core/approval's HumanApprovalGateService may be absent in slim contexts;
    // create_custom_tool then creates the draft but leaves it disabled rather than crashing.
    private final ObjectProvider<com.agenticai.core.approval.HumanApprovalGateService>
            approvalGateService;

    AgentCustomToolResolver(
            AgentToolGuard toolGuard,
            AgentService agentService,
            ObjectProvider<AgentChatService> agentChatService,
            UserToolScriptService userToolScriptService,
            UserToolScriptEvaluator userToolScriptEvaluator,
            CustomCodeToolService customCodeToolService,
            OpenApiToolSourceService openApiToolSourceService,
            OpenApiToolExecutor openApiToolExecutor,
            SkillService skillService,
            ObjectProvider<ToolSandboxClient> skillSandboxClient,
            ObjectProvider<ToolSandboxProperties> skillSandboxProperties,
            ObjectProvider<com.agenticai.core.approval.HumanApprovalGateService>
                    approvalGateService,
            Function<AtomicInteger, RunBudget> runBudgetFactory) {
        this.runBudgetFactory = runBudgetFactory;
        this.toolGuard = toolGuard;
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.userToolScriptService = userToolScriptService;
        this.userToolScriptEvaluator = userToolScriptEvaluator;
        this.customCodeToolService = customCodeToolService;
        this.openApiToolSourceService = openApiToolSourceService;
        this.openApiToolExecutor = openApiToolExecutor;
        this.skillService = skillService;
        this.skillSandboxClient = skillSandboxClient;
        this.skillSandboxProperties = skillSandboxProperties;
        this.approvalGateService = approvalGateService;
    }

    // Swarm goal verifier (app.swarm.verifier.*): setter-injected by AgentToolProvider; DISABLED =
    // legacy.
    private volatile com.agenticai.core.agents.collab.SwarmVerifierConfig swarmVerifierConfig =
            com.agenticai.core.agents.collab.SwarmVerifierConfig.DISABLED;
    private volatile ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
            swarmVerifierMeters;

    // Builds the delegation tools; implemented in core/agents-interop (ADR-0023). Absent = no
    // collaborator tools, the same fail-open posture as an absent AgentChatService.
    private volatile ObjectProvider<CollaboratorToolFactory> collaboratorToolFactory;

    void setCollaboratorToolFactory(ObjectProvider<CollaboratorToolFactory> factory) {
        this.collaboratorToolFactory = factory;
    }

    void setSwarmVerifier(
            com.agenticai.core.agents.collab.SwarmVerifierConfig config,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        this.swarmVerifierConfig =
                config == null
                        ? com.agenticai.core.agents.collab.SwarmVerifierConfig.DISABLED
                        : config;
        this.swarmVerifierMeters = meters;
    }

    private static String gateKey(UUID agentId, String sessionId) {
        return sessionId == null ? null : agentId + ":" + sessionId;
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
     * One delegation tool per agent in this agent's opt-in {@code collaboratorAgentIds} list (see
     * that field's javadoc on {@code Agent}). Empty unless this agent has at least one collaborator
     * configured - today's unchanged behavior (no delegation tool offered at all) for every agent
     * that hasn't opted in.
     *
     * <p>{@code allowDelegation=false} (see {@link #resolveToolsForDelegatedCall}) short-circuits
     * to empty regardless of how many collaborators are configured - that single check is the
     * entire depth-1 delegation cap for the whole feature.
     *
     * <p>A collaborator id that no longer resolves (deleted since being added) is skipped with a
     * warning rather than failing the whole turn - same fail-open posture every other optional tool
     * source in this class already has.
     */
    List<ToolCallback> resolveCollaboratorTools(
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
        AgentChatService chatService = agentChatService.getIfAvailable();
        ObjectProvider<CollaboratorToolFactory> factoryProvider = collaboratorToolFactory;
        CollaboratorToolFactory factory =
                factoryProvider == null ? null : factoryProvider.getIfAvailable();
        if (chatService == null || factory == null) {
            return List.of();
        }
        List<ToolCallback> tools = new ArrayList<>();
        // One budget per resolution (one resolveToolsFor call per chat turn), shared by every
        // delegate tool below. Disabled (usd = 0) unless app.guardrails.run-budget.usd is set.
        RunBudget runBudget = runBudgetFactory.apply(stepCounter);
        // run_subtasks needs no collaborators: its branches are the agent itself.
        if (runSubtasksEnabled) {
            tools.add(
                    guardAndLog(
                            toolGuard.withRunBudget(
                                    ResearchTurnScope.bindIfEnabled(
                                            researchEnabled,
                                            factory.runSubtasks(
                                                    chatService,
                                                    agentId,
                                                    TenantContext.current().orElse(null),
                                                    runBudget,
                                                    toolGuard.stepJournal(),
                                                    sessionId),
                                            stepCounter),
                                    runBudget,
                                    false),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        Set<UUID> collaboratorIds = agentService.getCollaboratorAgentIds(agentId);
        if (collaboratorIds.isEmpty()) {
            return tools;
        }
        List<CollaboratorToolFactory.TeamMember> members = new ArrayList<>();
        for (UUID collaboratorId : collaboratorIds) {
            try {
                AgentResponse collaborator = agentService.get(collaboratorId);
                String slug = slugify(collaborator.name());
                String toolName = "ask_agent_" + slug;
                String prompt = collaborator.systemPrompt();
                String shortDescription =
                        prompt == null ? "" : prompt.substring(0, Math.min(200, prompt.length()));
                // bind(): the delegate's own deep_research ceilings/budget resolve to THIS turn's,
                // not a fresh set.
                ToolCallback delegate =
                        ResearchTurnScope.bindIfEnabled(
                                researchEnabled,
                                factory.delegateToAgent(
                                        chatService,
                                        collaboratorId,
                                        toolName,
                                        collaborator.name(),
                                        shortDescription),
                                stepCounter);
                tools.add(
                        guardAndLog(
                                toolGuard.withRunBudget(delegate, runBudget, true),
                                agentId,
                                sessionId,
                                stepCounter,
                                userMessage,
                                chatClient,
                                rigorous));
                members.add(
                        new CollaboratorToolFactory.TeamMember(
                                collaboratorId, collaborator.name(), slug));
            } catch (Exception e) {
                log.warn(
                        "Skipping collaborator {} for agent {} - could not resolve its details",
                        collaboratorId,
                        agentId,
                        e);
            }
        }
        // Swarm fan-out: a parallel team tool exists only once there are at
        // least two resolved collaborators to address - a "team" of one is
        // just the single-agent delegate tool above. Same wrap chain, same
        // allowDelegation gate (this whole method short-circuits on it), same
        // TenantContext capture semantics guardAndLog itself uses.
        if (members.size() >= 2) {
            CollaboratorToolFactory.VerifierModel verifierModel = null;
            io.micrometer.core.instrument.MeterRegistry verifierMeters = null;
            if (swarmVerifierConfig.enabled() && chatClient != null) {
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> mp =
                        swarmVerifierMeters;
                verifierMeters = mp == null ? null : mp.getIfAvailable();
                // Prefer the delegating agent's economy model (cost routing config); fall back to
                // the turn's own
                // client when none applies. Resolved lazily (only if a swarm call actually
                // verifies) and with the
                // request thread's TenantContext re-applied, since the tool may run on another
                // thread.
                TenantContext verifierCtx = TenantContext.current().orElse(null);
                verifierModel =
                        (system, user) -> {
                            var previous = TenantContext.current();
                            if (verifierCtx != null) {
                                TenantContext.set(verifierCtx);
                            }
                            try {
                                ChatClient economy = chatService.economyClientFor(agentId);
                                ChatClient c = economy != null ? economy : chatClient;
                                return c.prompt().system(system).user(user).call().content();
                            } finally {
                                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
                            }
                        };
            }
            tools.add(
                    guardAndLog(
                            toolGuard.withRunBudget(
                                    ResearchTurnScope.bindIfEnabled(
                                            researchEnabled,
                                            factory.delegateToTeam(
                                                    chatService,
                                                    members,
                                                    TenantContext.current().orElse(null),
                                                    runBudget,
                                                    toolGuard.stepJournal(),
                                                    sessionId,
                                                    swarmVerifierConfig,
                                                    verifierModel,
                                                    verifierMeters,
                                                    userMessage,
                                                    agentId),
                                            stepCounter),
                                    runBudget,
                                    false),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return tools;
    }

    /**
     * {@code ask_agent_<slug>} tool names need to be stable, readable, and schema-safe -
     * lowercased, non-alphanumeric runs collapsed to a single underscore, leading/trailing
     * underscores trimmed.
     */
    private static String slugify(String name) {
        if (name == null) {
            return "agent";
        }
        String slug =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "agent" : slug;
    }

    /**
     * #424 - "user-created tools (generated scripts)", scoped to tenant-authored SpEL expressions
     * rather than arbitrary-language script execution - see {@link UserToolScript}'s javadoc for
     * why. Offered to every agent automatically once a tenant has at least one enabled script - no
     * separate per-agent opt-in flag, same posture as {@code
     * AgentIntegrationToolResolver#resolveWorkspaceTools} and {@link #resolveTelegramTools} (an
     * agent's own {@code allowedToolNames} allow-list, applied below via {@link
     * #filterByAllowedTools}, is still the per-agent restriction mechanism for these like every
     * other tool here).
     */
    List<ToolCallback> resolveScriptTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<UserToolScriptSnapshot> scripts = userToolScriptService.listEnabledSnapshots();
        if (scripts.isEmpty()) {
            return List.of();
        }
        return scripts.stream()
                .map(
                        script ->
                                guardAndLog(
                                        new UserToolScriptCallback(
                                                script,
                                                userToolScriptEvaluator,
                                                userToolScriptService),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * The "write me a tool" entry point - {@code create_custom_tool}, offered whenever the
     * tool-sandbox service is enabled on this deployment (off: the bean exists but there is no
     * sandbox behind it, so the tool is simply absent rather than present-but-dead). A call only
     * writes a disabled draft - the workspace-wide enable is a {@code custom_code_tool.enable}
     * approval a person grants - see {@link CreateCustomToolToolCallback}'s javadoc for why
     * AI-authored code is never agent-visible unreviewed, under any autonomy tier.
     */
    List<ToolCallback> resolveToolAuthoringTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        if (!customCodeToolService.isSandboxEnabled()) {
            return List.of();
        }
        return List.of(
                guardAndLog(
                        new CreateCustomToolToolCallback(
                                customCodeToolService, approvalGateService),
                        agentId,
                        sessionId,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous));
    }

    /**
     * "Custom Code Tools" - the real-code sibling of {@link #resolveScriptTools} above: an actual
     * tenant-authored Java/Python program, compiled and run by the standalone tool-sandbox
     * microservice rather than evaluated in this JVM (see {@link CustomCodeTool}'s javadoc for the
     * full security model, and for why the SpEL feature above had to be scoped down where this one
     * didn't). Same auto-offer posture as every sibling resolver here - available to every agent
     * once a tenant has at least one VALIDATED, enabled tool, with an agent's own {@code
     * allowedToolNames} allow-list still the per-agent restriction mechanism.
     *
     * <p>{@code listRunnable()} is a repository-level filter (enabled AND VALIDATED), so a
     * workspace full of rejected drafts costs one indexed query returning nothing, and this method
     * short-circuits before building any callback.
     */
    List<ToolCallback> resolveCustomCodeTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<CustomCodeToolSnapshot> codeTools = customCodeToolService.listRunnableSnapshots();
        if (codeTools.isEmpty()) {
            return List.of();
        }
        return codeTools.stream()
                .map(
                        tool ->
                                guardAndLog(
                                        new CustomCodeToolCallback(tool, customCodeToolService),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * "Import from OpenAPI" - the no-code sibling of {@link #resolveScriptTools} and {@link
     * #resolveCustomCodeTools}: every enabled operation of every enabled imported OpenAPI source
     * (see {@link com.agenticai.core.agents.tools.openapi.OpenApiToolSource}) is offered as a tool
     * through the exact same in-process callback path, so an agent can call it with zero further
     * wiring. Same auto-offer posture as every sibling resolver - an agent's own {@code
     * allowedToolNames} allow-list ({@link #filterByAllowedTools}) is still the per-agent
     * restriction mechanism. {@code listRunnable()} is one join-fetched query (enabled tool AND
     * enabled source), so a tenant with no imports costs one indexed query returning nothing and
     * this short-circuits before building any callback.
     */
    List<ToolCallback> resolveOpenApiTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        List<OpenApiToolSnapshot> openApiTools = openApiToolSourceService.listRunnableSnapshots();
        if (openApiTools.isEmpty()) {
            return List.of();
        }
        return openApiTools.stream()
                .map(
                        tool ->
                                guardAndLog(
                                        new OpenApiToolCallback(
                                                tool,
                                                openApiToolExecutor,
                                                openApiToolSourceService),
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }

    /**
     * Agent Skills (see {@link SkillService}) - the three tools that implement progressive
     * disclosure: {@code load_skill} (Level 2), {@code read_skill_file} and {@code
     * run_skill_script} (Level 3), plus propose/improve. Offered only when this agent has at least
     * one skill in scope AND a skill is in play this turn: the agent has an explicit skill scope,
     * or the message / one of the last few messages of this session matches a skill (see {@link
     * SkillPromptBlock#anyMatch}), or load_skill already ran in this session ({@link
     * SkillPromptBlock.SessionGate}). A plain turn pays zero skill tokens.
     *
     * <p><b>Why these bypass {@code allowedToolNames}:</b> skills have their own per-agent scope
     * ({@code agent_skill_config}), and {@code AgentChatService} advertises the in-scope skills in
     * the system prompt only on turns where {@code load_skill} is among the tools. If the tool
     * allow-list could strip {@code load_skill}, every tool-restricted agent would be told "call
     * load_skill" with no such tool - the exact instructed-but-not-given failure {@link
     * #capToHardLimit}'s javadoc describes for MCP families. Both read tools only ever return this
     * workspace's own skill text, so granting them widens nothing. {@code run_skill_script}
     * EXECUTES code, so it alone still honours the allow-list: a restricted agent gets it only if
     * its list names it.
     *
     * <p>A name collision with an already-resolved tool (an MCP server exposing its own {@code
     * load_skill}) drops the skill tool's rival rather than failing the turn - see {@link
     * #mergeFavoringBuiltins} for why a duplicate name is fatal in Spring AI.
     */
    List<ToolCallback> resolveSkillTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous,
            List<ToolCallback> alreadyResolved) {
        try {
            // Token gate: a turn where no skill is in play (no match in this or the last few
            // messages, nothing loaded yet this session, no explicit skill scope) gets no skill
            // tools and, downstream, no skills prompt block. Sticky per session so the tool set
            // and prompt prefix do not flap mid-conversation.
            List<SkillService.SkillMetadata> metadata = skillService.metadataForTurn(agentId);
            if (metadata.isEmpty()) {
                return List.of();
            }
            boolean inPlay =
                    skillGate.shouldOffer(gateKey(agentId, sessionId), metadata, userMessage);
            if (!inPlay && skillService.settingsForTurn(agentId).allowedSkillNames().isEmpty()) {
                return List.of();
            }
        } catch (RuntimeException e) {
            // Skills are an enhancement; a failure reading them must never cost the agent its turn.
            log.warn(
                    "Could not resolve skills for agent {} - continuing without skill tools: {}",
                    agentId,
                    e.getMessage());
            return List.of();
        }
        ToolSandboxClient sandbox = skillSandboxClient.getIfAvailable();
        ToolSandboxProperties sandboxProperties = skillSandboxProperties.getIfAvailable();
        Set<String> toolAllowList = agentService.getAllowedToolNames(agentId);
        boolean scriptsRunnable =
                sandbox != null
                        && sandboxProperties != null
                        && (toolAllowList.isEmpty()
                                || toolAllowList.contains(RunSkillScriptToolCallback.NAME));

        List<ToolCallback> skillTools = new ArrayList<>();
        final String loadedKey = gateKey(agentId, sessionId);
        skillTools.add(
                new LoadSkillToolCallback(skillService, agentId, scriptsRunnable) {
                    @Override
                    public String call(String toolInput) {
                        skillGate.markLoaded(loadedKey);
                        return super.call(toolInput);
                    }
                }.withPrompts(toolGuard.promptResolver()));
        skillTools.add(new ReadSkillFileToolCallback(skillService, agentId));
        skillTools.add(new ProposeSkillToolCallback(skillService, agentId));
        // Self-improvement loop (V243): propose_skill can only ADD; this is
        // the verb that fixes a skill that already exists, staged for admin
        // review the same way.
        skillTools.add(new ImproveSkillToolCallback(skillService, agentId));
        if (scriptsRunnable) {
            skillTools.add(
                    new RunSkillScriptToolCallback(
                            skillService, sandbox, sandboxProperties, agentId));
        }
        // alreadyResolved is a read-only snapshot (ToolResolver passes List.copyOf); the caller
        // drops
        // same-named earlier tools when it merges what this returns.
        return skillTools.stream()
                .map(
                        t ->
                                guardAndLog(
                                        t,
                                        agentId,
                                        sessionId,
                                        stepCounter,
                                        userMessage,
                                        chatClient,
                                        rigorous))
                .toList();
    }
}
