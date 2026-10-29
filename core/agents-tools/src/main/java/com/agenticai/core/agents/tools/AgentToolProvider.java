package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.tools.LoadSkillToolCallback;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Resolves the full tool list an agent's chat call should get, from two independent sources, each
 * wrapped in GuardedToolCallback (Autonomy/ Guardrails Strategy Stage A - per-turn step ceiling +
 * output sanitization) and then LoggingToolCallback, so every call - regardless of origin - gets
 * identical guardrail enforcement and identical logging:
 *
 * <p>1. MCP tools (Stage 4): empty unless the "mcp" Spring profile is active - which is when the
 * ToolCallbackProvider bean even exists, since that bean comes from spring-ai-starter-mcp-client
 * autoconfiguration - and at least one MCP server is enabled in the DB registry.
 *
 * <p>2. The knowledge-base search tool (Stage 6): empty unless the "knowledge" profile is active -
 * which is when KnowledgeSearchService even exists - and this specific agent has
 * knowledgeBaseEnabled = true. Unlike MCP tools, this isn't gated by McpRegistryService - it's a
 * native in-process tool, not an external MCP server, so the per-agent flag on Agent itself is its
 * "registry".
 *
 * <p>Since the composite refactor this class is the facade only: each origin of tools is a {@link
 * ToolSource} bean (see {@link ToolSourceConfiguration}) and {@link ToolResolver} merges, filters
 * and caps what they contribute. Optional capabilities stay ObjectProviders inside the sources, so
 * AgentChatService, which depends on this bean, is constructible even when no optional profile is
 * active, in which case agents simply get no tools (Stage 3's behavior).
 *
 * <p>{@code stepCounter} is created fresh, once, at the top of {@link #resolveToolsFor} - one
 * counter per chat turn, shared across every tool in that turn's list, since the guardrail ceiling
 * is "N tool calls total this turn," not per-tool. See {@link GuardedToolCallback}'s javadoc.
 */
@Service
public class AgentToolProvider implements AgentToolPort {

    private static final Logger log = LoggerFactory.getLogger(AgentToolProvider.class);

    private final AgentService agentService;
    private final ToolResolver toolResolver;
    private final TurnRunBudgets turnRunBudgets;
    private final AgentMcpToolResolver mcpToolResolver;
    private final AgentCrewBrowserToolResolver crewBrowserToolResolver;
    private final AgentToolDiscovery toolDiscovery;
    // Agent Skills (com.agenticai.core.agents.skills, migration V235).
    private final SkillService skillService;
    private final ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedurePromptService>
            procedurePromptService;

    public AgentToolProvider(
            AgentService agentService,
            ToolResolver toolResolver,
            TurnRunBudgets turnRunBudgets,
            AgentMcpToolResolver mcpToolResolver,
            AgentCrewBrowserToolResolver crewBrowserToolResolver,
            AgentToolDiscovery toolDiscovery,
            SkillService skillService,
            ObjectProvider<com.agenticai.core.guardrails.procedures.ProcedurePromptService>
                    procedurePromptService) {
        this.agentService = agentService;
        this.toolResolver = toolResolver;
        this.turnRunBudgets = turnRunBudgets;
        this.mcpToolResolver = mcpToolResolver;
        this.crewBrowserToolResolver = crewBrowserToolResolver;
        this.toolDiscovery = toolDiscovery;
        this.skillService = skillService;
        this.procedurePromptService = procedurePromptService;
    }

    /**
     * Optional: the untrusted-content taint layer ({@code app.agents.taint.mode}). Absent bean
     * (slim/test contexts) leaves every resolution as it was before the layer existed. Setter
     * injection, like {@code AgentToolGovernance#setTaintPolicy}: the policy is itself a tool-layer
     * bean, so a constructor parameter would make this provider unstartable wherever tools run
     * without the guardrails surface.
     */
    private volatile AgentTaintPolicy taintPolicy;

    /**
     * The in-flight turn's untrusted-content record (from {@link
     * com.agenticai.core.agents.turn.TurnContext#taint()}), visible while tools resolve on this
     * thread. {@link #resolveToolsFor(UUID, ...)} binds it to the turn's step counter so gates see
     * marks from inbound messages and auto-injected retrieval.
     */
    static final ThreadLocal<com.agenticai.core.guardrails.taint.TurnTaint> TURN_TAINT_SCOPE =
            new ThreadLocal<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTaintPolicy(AgentTaintPolicy taintPolicy) {
        this.taintPolicy = taintPolicy;
    }

    /**
     * Whether {@code toolName} is one the relevance selector never trims - see {@link
     * AgentToolSelection#ALWAYS_RELEVANT_TOOL_NAMES}. For callers in other packages
     * (ChatTurnExecutor's stable tool ordering).
     */
    public static boolean isAlwaysRelevantTool(String toolName) {
        return AgentToolSelection.isAlwaysRelevantTool(toolName);
    }

    /**
     * Bug fix (2026-08-06, direct report: "once tools enabled in mcp, agents still unable to access
     * it... soon after enabling, it should be available"). Root cause: {@link
     * #staticMcpToolCache}/{@link #dynamicMcpToolCache} above are populated lazily and read on
     * every turn, but NOTHING ever evicted them when a tenant's MCP registry actually changed -
     * {@code McpRegistryController#toggle}/{@code #register}/{@code #connectNow} and {@code
     * McpRegistryReviewService#approve} all correctly (re)connect the LIVE MCP client immediately
     * (see {@code DynamicMcpConnectionManager#connect}), but this class kept serving its own
     * previously-cached (possibly empty, pre-existence) tool list for up to {@link
     * #mcpDiscoveryCacheTtl} (default 20s) afterward - for BOTH hosted turns ({@code
     * AgentChatService}) and edge sessions ({@code EdgeToolExecutionController}), since both
     * resolve through this one singleton. Call this from every action that changes which MCP tools
     * a tenant can see - {@code DynamicMcpConnectionManager#connect}/{@code #disconnect} now do,
     * and {@code McpRegistryController#toggle} calls it directly for a STATIC registration's
     * enable/disable (which has no live connection to reconnect, so nothing else would evict this
     * cache for that case). Must be called from a request/background thread with {@link
     * TenantContext} already set to the affected tenant - same requirement {@link
     * #tenantCacheKey()} always had.
     */
    public void evictMcpToolCache() {
        mcpToolResolver.evictCache();
    }

    /**
     * @param subjectKey stable cross-session identity for the person on the other end of this
     *     conversation (see {@code AgentMemoryService}'s javadoc) - only used to scope the {@code
     *     remember_fact} tool; everything else here is unchanged and still keyed by {@code
     *     sessionId}.
     * @param userMessage this turn's message - feeds {@link ToolRelevanceSelector} so a large tool
     *     catalog is automatically narrowed to what's actually relevant to what was just asked
     *     ("Automatic tool/MCP selection (no manual picking)" - see that class's javadoc). {@code
     *     null}/blank is safe and simply disables narrowing for that call (falls back to the full
     *     resolved list).
     * @param chatClient Agent Intelligence research doc (July 2026) - this turn's already-built
     *     {@code ChatClient} (same provider/model/ credentials {@code AgentChatService} is about to
     *     stream the response with), threaded down to {@link GuardedToolCallback} for {@code
     *     RIGOROUS}-mode tool-call verification so the verifier never has to resolve its own
     *     credentials. Null is safe (verifier simply fails open - see {@code
     *     ToolCallVerifierService}'s javadoc) for any caller with no real chat context, e.g. {@link
     *     #listAvailableToolsFor}'s discovery probe.
     */
    public List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            ReasoningDepth effectiveDepth) {
        // Deliberately NOT routed through the step-event overload below with a
        // disabled emitter: this line is exactly what it was before that
        // overload existed, so every caller that never heard of step events
        // executes zero new code rather than new code that behaves the same.
        return resolveToolsFor(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                chatClient,
                true,
                true,
                effectiveDepth,
                true);
    }

    /**
     * Opt-in step-event streaming (2026-08-17). Identical to the five-argument {@link
     * #resolveToolsFor} above EXCEPT that every resolved tool is additionally wrapped in {@link
     * StepEventToolCallback}, so each invocation pushes a {@code tool_call}/{@code tool_result}
     * pair onto {@code emitter}'s per-turn side channel. Only {@code AgentChatService#chat} calls
     * this overload, and only with a live emitter when {@code ChatRequest#stepEvents} asked for one
     * - see {@link AgentStepEventEmitter}'s javadoc for why this had to be opt-in per request
     * rather than a change to the stream format (short version: the LiveKit voice bridge would
     * speak the events out loud).
     *
     * <p>When {@code emitter} is {@link AgentStepEventEmitter#disabled()} (or null - treated
     * identically, defensively) this method is not merely equivalent to the five-arg overload, it
     * produces an object-identical wrap chain: {@link #guardAndLog} skips the wrapper entirely
     * rather than adding a pass-through one. That identity is the whole safety argument for this
     * feature, so do not "simplify" it into an always-wrap-but-no-op emitter.
     *
     * <p>The emitter travels via {@link #STEP_EVENT_SCOPE} rather than as a parameter on every one
     * of the twenty {@code resolveXxxTools} helpers and {@link #guardAndLog} itself (~45 call
     * sites). That is safe for exactly the same reason {@code guardAndLog}'s {@code
     * TenantContext.current()} read is: this whole method runs synchronously, on one thread, before
     * anything reactive is subscribed, and {@code guardAndLog} reads the scope on that same thread.
     * Note that thread is NOT the servlet request thread - {@code AgentChatService#chat} runs tool
     * resolution on a virtual thread via its {@code supplyPreTurn} concurrency block - which is
     * precisely why the scope is opened HERE, inside the call, instead of by the caller.
     */
    public List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            AgentStepEventEmitter emitter,
            ReasoningDepth effectiveDepth) {
        return resolveToolsFor(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                chatClient,
                emitter,
                effectiveDepth,
                false,
                false);
    }

    /**
     * As above; {@code stableToolSet} offers the model the agent's whole allowed set, unchanged
     * from one message to the next (no per-message relevance trimming; the hard cap and on-demand
     * tool loading still apply).
     */
    private List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            AgentStepEventEmitter emitter,
            ReasoningDepth effectiveDepth,
            boolean stableToolSet,
            boolean forceToolDeferral) {
        if (emitter == null || !emitter.isEnabled()) {
            return resolveToolsFor(
                    agentId,
                    sessionId,
                    subjectKey,
                    userMessage,
                    chatClient,
                    true,
                    true,
                    effectiveDepth,
                    true,
                    stableToolSet,
                    forceToolDeferral);
        }
        AgentToolGuard.STEP_EVENT_SCOPE.set(emitter);
        try {
            return resolveToolsFor(
                    agentId,
                    sessionId,
                    subjectKey,
                    userMessage,
                    chatClient,
                    true,
                    true,
                    effectiveDepth,
                    true,
                    stableToolSet,
                    forceToolDeferral);
        } finally {
            // remove(), not set(null): resolution can run on a pooled/virtual
            // carrier thread, and a stale emitter left behind here would attach
            // a finished turn's trace to an unrelated later turn.
            AgentToolGuard.STEP_EVENT_SCOPE.remove();
        }
    }

    /**
     * The chat turn's entry (ADR-0022 stage 3): resolves under the turn's explicit {@link
     * TurnContext} rather than whatever the calling thread holds. {@code context.scope()} is
     * applied for the whole resolution, so every tool's wrap chain - {@code guardAndLog}'s capture
     * for {@link TenantContextPropagatingToolCallback}, the MCP grant id, the output redactor - and
     * every tenant-schema read during resolution see the turn's tenant. With a disabled (or null)
     * {@code emitter} this runs exactly the five-argument path; with a live one, the step-event
     * path. A null {@code context} behaves like the overloads without one.
     */
    public List<ToolCallback> resolveToolsFor(
            TurnContext context,
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            AgentStepEventEmitter emitter,
            ReasoningDepth effectiveDepth) {
        // A turn on a model served from the person's own machine keeps its tool set unchanged from
        // one message to
        // the next (TurnModelTraits#stableToolSet), so the local server's prompt cache holds across
        // turns.
        boolean stableToolSet =
                context != null
                        && context.modelTraits() != null
                        && context.modelTraits().stableToolSet();
        // The serving row's own tool-deferral switch (V674): a slow local model's turns hide most
        // of the catalog behind search_tools/call_tool even when app.tools.search.enabled is off.
        boolean forceToolDeferral =
                context != null
                        && context.modelTraits() != null
                        && context.modelTraits().toolDeferral();
        java.util.function.Supplier<List<ToolCallback>> resolve =
                () ->
                        resolveToolsFor(
                                agentId,
                                sessionId,
                                subjectKey,
                                userMessage,
                                chatClient,
                                emitter,
                                effectiveDepth,
                                stableToolSet,
                                forceToolDeferral);
        if (context == null) {
            return resolve.get();
        }
        // The turn's approval state rides to AgentToolGuard#guardAndLog on this thread (see
        // TURN_APPROVAL_SCOPE) so every guard built for the turn shares the one instance the turn
        // loop also holds; removed in the finally so a pooled thread never leaks it to another
        // turn.
        return context.scope()
                .get(
                        () -> {
                            AgentToolGuard.TURN_APPROVAL_SCOPE.set(context.approval());
                            AgentToolGuard.TURN_ACTIVITY_SCOPE.set(context.toolActivity());
                            AgentToolGuard.TURN_RUNTIME_SCOPE.set(
                                    new AgentToolGuard.TurnRuntimeScope(
                                            agentId, context.runtimeSettings()));
                            TURN_TAINT_SCOPE.set(context.taint());
                            try {
                                return resolve.get();
                            } finally {
                                AgentToolGuard.TURN_APPROVAL_SCOPE.remove();
                                AgentToolGuard.TURN_ACTIVITY_SCOPE.remove();
                                AgentToolGuard.TURN_RUNTIME_SCOPE.remove();
                                TURN_TAINT_SCOPE.remove();
                            }
                        });
    }

    /**
     * Resolves the same merged, allow-list-filtered tool candidate set {@link #resolveToolsFor}
     * would, but WITHOUT {@link ToolRelevanceSelector}'s per-turn relevance cap - for a caller that
     * already knows the exact tool name it wants and is never handing this list to an LLM provider,
     * so the "keep only the N most relevant tools for this message" trimming has nothing to do and
     * can only hurt: {@code EdgeToolExecutionController} (the ONE caller) used to pass a {@code
     * null} userMessage into {@link #resolveToolsFor} for exactly this reason ("no relevance
     * trimming"), which relied on {@link ToolRelevanceSelector#select}'s own empty-query bypass to
     * let everything through - the same bypass that, for a REAL chat turn with a
     * blank/attachment-only message, was letting an unbounded tool catalog reach the LLM provider
     * whole (see that class's javadoc for the live incident this caused). Now that the selector's
     * empty-query path is capped like every other turn, a null-message caller that genuinely needs
     * the untrimmed set must ask for it explicitly here instead of depending on that bypass - so
     * Edge's lookup-by-name behavior keeps working (every currently-resolvable tool, not just the
     * top {@code maxToolsPerTurn}) without reopening the same hole.
     */
    public List<ToolCallback> resolveToolCandidatesFor(
            UUID agentId, String sessionId, String subjectKey) {
        // STATELESS: edge execution and approval replay look one tool up by name in a FRESH
        // resolution, so a
        // turn's deep_research evidence is not there - those tools resolve as fail-closed stubs
        // (see
        // ResearchTurnScope.Mode). The result is never sent to a model, so no prompt tokens are
        // involved.
        return ResearchTurnScope.withMode(
                ResearchTurnScope.Mode.STATELESS,
                () ->
                        resolveToolsFor(
                                agentId,
                                sessionId,
                                subjectKey,
                                null,
                                null,
                                true,
                                false,
                                null,
                                false));
    }

    /**
     * The delegation-depth cap. Resolves the exact same tool list {@link #resolveToolsFor} would
     * EXCEPT collaborator (delegation) tools. Called only by {@code AgentChatService#delegateCall}
     * - never by a normal chat turn - so a collaborator agent invoked THROUGH a delegation is never
     * itself offered a delegation tool. That makes agent A delegating to B delegating back to A (or
     * anywhere else) structurally impossible rather than merely policy-discouraged: there is no
     * tool call B could even attempt that would delegate further. {@code subjectKey} is passed as
     * {@code sessionId} itself (a delegated call has no independent subject identity of its own -
     * see {@code AgentChatService#delegateCall}'s javadoc for why it uses a fresh, throwaway
     * session with no long-term memory), matching {@code remember_fact}'s tool being effectively a
     * no-op for a one-shot delegated exchange rather than needing a separate parameter just for it.
     */
    public List<ToolCallback> resolveToolsForDelegatedCall(
            UUID agentId, String sessionId, String userMessage, ChatClient chatClient) {
        return resolveToolsFor(
                agentId, sessionId, sessionId, userMessage, chatClient, false, true, null, false);
    }

    /**
     * "Crew" specialist-handoff counterpart of {@link #resolveToolsForDelegatedCall}: the
     * specialist's FULL tool set plus, while {@code chain} has depth left, {@code
     * delegate_to_specialist} bound to {@code chain}. See {@link
     * AgentCrewBrowserToolResolver#resolveSpecialistDelegationTools}.
     */
    public List<ToolCallback> resolveToolsForSpecialistHandoff(
            UUID agentId,
            String sessionId,
            String userMessage,
            ChatClient chatClient,
            HandoffChain chain) {
        return crewBrowserToolResolver.withHandoffChain(
                chain,
                () ->
                        resolveToolsFor(
                                agentId,
                                sessionId,
                                sessionId,
                                userMessage,
                                chatClient,
                                true,
                                true,
                                null,
                                false));
    }

    private List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            boolean allowDelegation,
            boolean applyRelevanceSelection,
            ReasoningDepth effectiveDepth,
            boolean mainChatTurn) {
        return resolveToolsFor(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                chatClient,
                allowDelegation,
                applyRelevanceSelection,
                effectiveDepth,
                mainChatTurn,
                false,
                false);
    }

    private List<ToolCallback> resolveToolsFor(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            ChatClient chatClient,
            boolean allowDelegation,
            boolean applyRelevanceSelection,
            ReasoningDepth effectiveDepth,
            boolean mainChatTurn,
            boolean stableToolSet,
            boolean forceToolDeferral) {
        AtomicInteger stepCounter = new AtomicInteger(0);
        turnRunBudgets.bindAgent(stepCounter, agentId);
        // Untrusted-content taint (app.agents.taint.mode): when this resolution runs for a chat
        // turn, adopt the TurnContext's own taint record as the turn's state - an entry that marked
        // an inbound channel message, or the pipeline that marks auto-injected retrieval, writes to
        // that instance and the gate below reads it. Null scope (non-turn resolutions: edge,
        // replay,
        // delegated calls) leaves the counter-keyed default in place. Read on this thread for the
        // same reason TURN_APPROVAL_SCOPE is.
        AgentTaintPolicy taint = taintPolicy;
        if (taint != null) {
            taint.bind(stepCounter, TURN_TAINT_SCOPE.get());
        }
        // 2026-08-16 fix: RIGOROUS's tool-call verifier judges each call ONLY against this turn's
        // raw userMessage. A workflow-triggered turn with no run input gets the generic
        // placeholder ("Workflow started manually.") as userMessage, which nothing can match - a
        // RIGOROUS agent as a workflow's first node rejected every call and blew the Temporal
        // ExecuteNode timeout. Skipping verification when there is no real user-authored ask
        // leaves approval gates, step limit and output sanitization intact (GuardedToolCallback
        // #call; none of those read `rigorous`).
        // effectiveDepth is THIS turn's dispatched depth when the caller knows it
        // (AgentChatService#chat does - forceFastDepth and the RL arm are both resolved there
        // before tools are resolved); the stored depth is only the fallback for callers with
        // no turn context.
        ReasoningDepth depth =
                effectiveDepth != null ? effectiveDepth : agentService.getReasoningDepth(agentId);
        boolean rigorous = depth == ReasoningDepth.RIGOROUS && hasVerifiableUserIntent(userMessage);
        // The continuation turn after an approval carries the tools its conversation already used
        // (see ToolResolver#alwaysRelevant); the turn's approval state is on this thread only for
        // the duration of the context overload of resolveToolsFor.
        TurnApprovalState turnApproval = AgentToolGuard.TURN_APPROVAL_SCOPE.get();
        return toolResolver.resolve(
                new ToolResolutionContext(
                        agentId,
                        sessionId,
                        subjectKey,
                        stepCounter,
                        userMessage,
                        chatClient,
                        rigorous,
                        allowDelegation,
                        mainChatTurn,
                        List.of(),
                        mainChatTurn && turnApproval != null
                                ? keptOnOffer(turnApproval)
                                : Set.of(),
                        forceToolDeferral),
                applyRelevanceSelection,
                stableToolSet);
    }

    /**
     * The tools relevance selection must not trim on a main chat turn: the tools the conversation
     * already used (the continuation after an approval, a short follow-up) and the tools the person
     * picked for this run ({@code ChatRequest.toolNames}) - a tool they switched on is not dropped
     * for failing to match the message. Keeping a tool from being trimmed only keeps it in the
     * list; the run's own narrowing still removes whatever it did not name, and nothing here adds a
     * tool the agent does not resolve.
     */
    private static Set<String> keptOnOffer(TurnApprovalState state) {
        if (state.runToolNames() == null || state.runToolNames().isEmpty()) {
            return state.stickyTools();
        }
        Set<String> names = new java.util.HashSet<>(state.stickyTools());
        names.addAll(state.runToolNames());
        return names;
    }

    // Duplicated literal, not shared via a constant: core/agents cannot depend
    // on core/workflow (dependency runs the other way - workflow calls
    // AgentChatService.chat, not vice versa), and this module boundary is the
    // right one to keep. See the placeholder's real definitions at
    // WorkflowExecutionEngine#execute and WorkflowRunWorkflowImpl#run.
    private static final String WORKFLOW_NO_INPUT_PLACEHOLDER = "Workflow started manually.";

    private static boolean hasVerifiableUserIntent(String userMessage) {
        return userMessage != null
                && !userMessage.isBlank()
                && !WORKFLOW_NO_INPUT_PLACEHOLDER.equals(userMessage.trim());
    }

    /**
     * The dropdown tool picker {@code
     * com.agenticai.core.agents.core.AgentController#setToolAccess}'s javadoc calls out as future
     * work, now built: every tool this specific agent would currently be offered, BEFORE the
     * allow-list filter is applied (i.e. {@link #resolveToolsFor} minus {@link
     * #filterByAllowedTools}) - so an agent that already has an allow-list saved still sees every
     * tool it COULD add back, not just the ones currently selected.
     *
     * <p>MCP tool names are only known once their server actually responds (Spring AI's
     * ToolCallbackProvider queries them live), so this can't be a static list without risking drift
     * from whatever MCP servers are actually enabled right now - it reuses the exact same
     * resolution path a real chat turn takes, just with a throwaway session/subject key since
     * nothing here is actually invoked, only each callback's own {@code ToolDefinition} (name +
     * description) is read.
     */
    public List<ToolDescriptor> listAvailableToolsFor(UUID agentId) {
        return toolDiscovery.listAvailableTools(agentId);
    }

    /**
     * Whether this turn would be offered at least one MCP-sourced tool (static YAML-wired or
     * dynamic, #424) - used by {@code AgentChatService} to scope its MCP-untrusted-data standing
     * system directive (Defensive MCP hardening, 2026-08-05) to turns that actually offer an MCP
     * tool, so a pure built-in-tool turn never pays for, or shows the model, the extra text. Tool
     * origin (MCP vs. platform built-in) isn't otherwise recoverable from the final, merged,
     * guardrail/logging-wrapped {@code List<ToolCallback>} {@link #resolveToolsFor} returns -
     * {@code GuardedToolCallback}/{@code LoggingToolCallback} wrap every tool the same way
     * regardless of origin, and even though both static and dynamic MCP tools now carry the same
     * {@code mcp__<server>__} structural marker (see {@link NamespacedMcpToolCallback}), a built-in
     * whose own name happens to contain that substring can't be ruled out by string inspection
     * alone - so this re-resolves {@link #resolveMcpTools} directly rather than trying to
     * reverse-engineer origin from the already-resolved list.
     *
     * <p>A main chat turn does not need to call this: the list {@link #resolveToolsFor} returns is
     * a {@link ResolvedToolList} that already carries the answer, so the assembler reads it there
     * and only falls back to this re-resolution (a discovery-cache hit, not a live MCP round trip)
     * for a list that does not. The fresh, throwaway {@code AtomicInteger} step counter and {@code
     * rigorous=false} are safe: these wrapped callbacks are never invoked, only their names read.
     *
     * <p><b>Known imprecision, accepted on purpose.</b> This checks the pre-{@link
     * ToolRelevanceSelector} MCP CANDIDATE set, not whichever tools survive that selector's
     * per-message relevance trimming into the final list the LLM actually sees - so on a turn where
     * an MCP tool exists but gets filtered out as irrelevant to this specific message, the standing
     * directive still gets added even though no MCP tool ends up offered. That is a false POSITIVE
     * (harmless extra system text), never a false negative, so it does not weaken the defense;
     * recomputing this from the post-selection list would need threading {@code mcpToolNames} out
     * of {@link #resolveToolsFor} instead (see the "not folded into" note above) - deferred as
     * unnecessary precision for what this directive is for.
     */
    public boolean anyMcpToolsOfferedFor(
            UUID agentId, String sessionId, String userMessage, ChatClient chatClient) {
        return !toolResolver
                .mcpCandidates(
                        new ToolResolutionContext(
                                agentId,
                                sessionId,
                                sessionId,
                                new AtomicInteger(0),
                                userMessage,
                                chatClient,
                                false,
                                true,
                                false,
                                List.of()))
                .isEmpty();
    }

    /**
     * Agent Skills, Level 1 - the "## Skills" block for this agent's system prompt, or "" when
     * {@code load_skill} is not on {@code tools} (which, given the per-turn skill gate in {@code
     * resolveSkillTools}, is the case on turns where no skill is in play). Lives here rather than
     * in {@code AgentChatService} so the prompt block and the tool that serves it are decided from
     * one place: whatever removed the tool from this turn (skills off, hard cap, a failed read)
     * removes the advertisement with it. Never throws.
     */
    public String skillsPromptBlock(UUID agentId, List<ToolCallback> tools) {
        return skillsPromptBlock(agentId, tools, false);
    }

    /**
     * {@link #skillsPromptBlock(UUID, List)}; {@code compact} lists skills by name only
     * (small-model mode, V501).
     */
    public String skillsPromptBlock(UUID agentId, List<ToolCallback> tools, boolean compact) {
        if (tools == null
                || tools.stream()
                        .noneMatch(
                                t ->
                                        LoadSkillToolCallback.NAME.equals(
                                                t.getToolDefinition().name()))) {
            return "";
        }
        try {
            var skills = skillService.metadataForTurn(agentId);
            return compact
                    ? com.agenticai.core.agents.skills.SkillPromptBlock.renderCompact(skills)
                    : com.agenticai.core.agents.skills.SkillPromptBlock.render(skills);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not build the skills prompt block for agent {}: {}",
                    agentId,
                    e.getMessage());
            return "";
        }
    }

    /**
     * Procedures layer (Tier-3) - the "## Operating procedures" block for this agent's system
     * prompt, or "" when it has no attached ACTIVE procedures. Unlike {@link #skillsPromptBlock}
     * this is NOT gated on a tool being present: SOP rules bind every turn, tools or no tools.
     * Never throws.
     */
    public String proceduresPromptBlock(UUID agentId) {
        com.agenticai.core.guardrails.procedures.ProcedurePromptService service =
                procedurePromptService.getIfAvailable();
        return service == null ? "" : service.promptBlockForAgent(agentId);
    }
}
