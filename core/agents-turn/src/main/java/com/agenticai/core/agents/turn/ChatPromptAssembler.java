package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.countVisionFrames;
import static com.agenticai.core.agents.turn.ChatAttachments.withAttachmentNote;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.joinMemoryBlocks;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.mediaGenerationHonestyDirective;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.memoryUsageDirective;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.prependMemoryBlock;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.withSkillsBlock;

import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.context.CompactionRun;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.AgentToolSelection;
import com.agenticai.core.agents.tools.ResolvedToolList;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.chat.ArgumentSanitizingToolCallingManager;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds everything the model call needs for one turn - attachments and vision metering, the three
 * concurrent pre-turn reads (history, tools, memory recall), the system prompt and memory block,
 * context compaction, the user-turn append and the RL episode - moved verbatim out of {@link
 * AgentChatService#chat}. Statement order is the original's; see the comments kept with each step
 * for why it matters.
 *
 * <p>ADR-0022 stage 2/3: the tenant comes from the explicit {@link TurnContext} ({@link
 * ChatClientSelection.Clients#context()}); the pre-turn reads re-apply its {@link
 * TurnContext#scope()} on their virtual threads and tool resolution receives the context itself.
 */
final class ChatPromptAssembler {

    /** The assembled turn. {@code tools} is the live list the dispatch lambda captures. */
    record AssembledPrompt(
            String systemPrompt,
            List<Message> messages,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            boolean anyMcpToolsOffered,
            CompactionRun compactionRun,
            ReasoningDepth turnDepth,
            boolean effectiveForceFastDepth,
            String experimentVariants,
            UUID rlEpisodeId) {}

    private final AgentChatService svc;

    ChatPromptAssembler(AgentChatService svc) {
        this.svc = svc;
    }

    AssembledPrompt assemble(
            ChatTurnPreflight.Turn turn,
            RlTurnPlan rl,
            ChatClientSelection.Clients clients,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            String turnRef,
            String visionSessionId,
            boolean forceFastDepth,
            AgentStepEventEmitter stepEvents,
            boolean suppressAgentTools) {
        // Read at call time: the directive flags are @Value field-injected and the compaction /
        // sandbox collaborators are setter-injected after the service is constructed.
        ChatHotpath hotpath = svc.hotpath;
        ChatAttachments attachmentResolver = svc.attachmentResolver;
        VisionUsageService visionUsageService = svc.visionUsageService;
        VisionSessionService visionSessionService = svc.visionSessionService;
        SessionMemoryService sessionMemoryService = svc.sessionMemoryService;
        AgentToolPort agentToolProvider = svc.agentToolProvider;
        ChatMemoryRecall memoryRecall = svc.memoryRecall;
        AgentService agentService = svc.agentService;
        RlEpisodeRecorder rlEpisodes = svc.rlEpisodes;
        ChatContextCompaction contextCompaction = svc.contextCompaction;
        SandboxFileRegistry sandboxFiles = svc.sandboxFiles;
        boolean memoryUsageDirectiveEnabled = svc.memoryUsageDirectiveEnabled;
        boolean toolSearchDirectiveEnabled = svc.toolSearchDirectiveEnabled;
        boolean contextCompactionDirectiveEnabled = svc.contextCompactionDirectiveEnabled;
        boolean browserV2DirectiveEnabled = svc.browserV2DirectiveEnabled;
        boolean artifactsDirectiveEnabled = svc.artifactsDirectiveEnabled;
        boolean artifactsDirectiveAllAgents = svc.artifactsDirectiveAllAgents;
        String artifactsDirectiveAgentIds = svc.artifactsDirectiveAgentIds;
        TurnContext context = clients.context();
        String currentTenantId = context.tenantId();
        TenantPlan currentTenantPlan = turn.currentTenantPlan();
        UUID agentId = turn.agentId();
        String memorySessionId = turn.memorySessionId();
        Agent agent = turn.agent();
        String safeAttachmentKey = turn.safeAttachmentKey();
        com.agenticai.core.agents.hooks.HookRun hookRun = turn.hookRun();
        String rlModelOverride = rl.rlModelOverride;
        String rlPromptOverride = rl.rlPromptOverride;
        ReasoningDepth effReasoningDepth = rl.effReasoningDepth;
        Map<PolicyDimension, UUID> rlSelectedArms = rl.rlSelectedArms;
        ChatClient chatClient = clients.chatClient();
        List<HostedChainLink> hostedChain = clients.hostedChain();

        PendingAttachments pendingAttachments =
                hotpath.startAttachments(
                        attachmentResolver,
                        svc.preTurnExecutor,
                        agent,
                        agentId,
                        safeAttachmentKey,
                        attachmentMimeType,
                        inlineAttachments);

        // Live Vision - camera/screen frames are metered per
        // user per day, HERE: after resolveAttachments (so a malformed turn
        // 400s before spending quota) and before any model work below (so a
        // quota-blocked turn never costs a token). VisionUsageService throws
        // the 429 when the plan's daily cap is exceeded; session attribution
        // is best-effort and never fails the turn (see its javadoc). Skipped
        // without a TenantContext - the omnichannel webhook adapters never
        // send inline attachments, let alone sourced frames.
        int visionFrames = countVisionFrames(inlineAttachments);
        if (visionFrames > 0 && currentTenantId != null) {
            visionUsageService.recordFrames(
                    currentTenantId, subjectKey, currentTenantPlan, visionFrames);
            if (visionSessionId != null && !visionSessionId.isBlank()) {
                visionSessionService.recordFrames(visionSessionId, currentTenantId, visionFrames);
            }
        }

        // forceFastDepth turn-scoped override (2026-08-06): the caller's
        // explicit flag (voice, via the seven-arg chat overload or
        // AgentController#chat's ChatRequest.forceFastDepth) OR this same
        // vision-frame check just above - a camera/screen turn carries an
        // inline attachment (visionFrames > 0) regardless of who called this
        // method, so it is forced fast here unconditionally rather than
        // requiring every future vision call site to remember to pass the
        // flag itself. Plain text/file-attachment turns (visionFrames == 0)
        // are NEVER forced fast unless the caller explicitly asked.
        // Small-model mode (V501): the first link of the hosted chain is the row this turn is
        // served by; when its model is small (<= 8B, or forced), the turn offers at most six
        // tools, uses the compact directives and runs at FAST depth - see docs/local-models.md.
        boolean smallModelTurn =
                hostedChain != null && !hostedChain.isEmpty() && hostedChain.get(0).smallModel();
        boolean effectiveForceFastDepth = forceFastDepth || visionFrames > 0 || smallModelTurn;

        // Master Roadmap 2026 item #13 - resolved once per turn, threaded
        // through every reasoning-depth branch below (same "compute once,
        // pass down" shape currentTenantId itself already uses).
        String experimentVariants =
                turn.activeFlags() != null
                        ? ChatAgentRouting.formatExperimentVariants(turn.activeFlags())
                        : svc.agentRouting.resolveExperimentVariants(currentTenantId);

        // Latency: these three per-turn inputs are independent of each other -
        // session history (Redis), this turn's tool set (incl. any MCP
        // resolution), and long-term memory recall (sidecar) - and each is a
        // blocking I/O round-trip. Run them CONCURRENTLY on virtual threads
        // instead of serially, propagating this request's TenantContext onto
        // each worker (the only request-scoped state these reads depend on -
        // getting it wrong would query the wrong tenant's schema, so this
        // reuses the exact snapshot-and-set posture LlmMetricsAdvisor and the
        // omnichannel async paths already rely on). Each future is joined at
        // its use site below; kicking all three off here is what overlaps them.
        // ADR-0022: the scope is the turn's explicit context, equal to this request thread's.
        TenantScope preTurnTenant = context.scope();
        CompletableFuture<List<Message>> historyFuture =
                supplyPreTurn(
                        preTurnTenant,
                        () ->
                                sessionMemoryService.getHistory(memorySessionId).stream()
                                        .map(ChatPromptAssembler::toSpringAiMessage)
                                        .toList());
        // stepEvents (2026-08-17) is threaded into tool resolution rather than
        // anywhere downstream because resolution is where every tool gets its
        // wrapper chain built - AgentToolProvider#guardAndLog is the ONE point
        // covering all twenty tool sources (MCP and in-process alike). Note this
        // lambda runs on a VIRTUAL THREAD, not the servlet request thread, which
        // is exactly why the emitter is passed as an argument here instead of
        // being stashed in a request-scoped ThreadLocal by the controller.
        //
        // ADR-0022 stage 3: the turn's TurnContext is handed to tool resolution,
        // which resolves inside context.scope(). The step-event branch that used
        // to sit here now sits in that overload and must not be collapsed into
        // "always wrap, a disabled emitter is a no-op anyway": a turn without step
        // events still runs the ORIGINAL five-argument resolution path, so the
        // disabled path executes zero new wrapper code. That is the strongest
        // form of the byte-identity guarantee this feature is required to give
        // the LiveKit voice bridge and every omnichannel adapter.
        // suppressAgentTools (chatTextOnly, structured-output repair turns):
        // resolution is skipped ENTIRELY rather than resolved-then-discarded -
        // resolving builds live MCP wrapper chains and costs I/O, and the whole
        // point of a text-only turn is that no tool can run. An empty list takes
        // the exact code path an agent with no tools configured already takes on
        // every turn, so nothing downstream is new.
        // THIS turn's dispatched depth, not the agent's stored one (2026-09-17 audit) -
        // AgentToolProvider gates RIGOROUS's per-tool verifier call on it, and the two
        // diverge on every force-fast turn (voice, vision) and whenever the RL bandit picked
        // a different arm. Resolved here because both inputs are already final at this point:
        // effectiveForceFastDepth just above, effReasoningDepth further up.
        ReasoningDepth turnDepth =
                effectiveForceFastDepth ? ReasoningDepth.FAST : effReasoningDepth;
        CompletableFuture<List<ToolCallback>> toolsFuture =
                supplyPreTurn(
                        preTurnTenant,
                        () ->
                                suppressAgentTools
                                        ? ResolvedToolList.empty()
                                        : ResolvedToolList.copyOf(
                                                agentToolProvider.resolveToolsFor(
                                                        context,
                                                        agentId,
                                                        sessionId,
                                                        subjectKey,
                                                        userMessage,
                                                        chatClient,
                                                        stepEvents,
                                                        turnDepth)));
        CompletableFuture<String> memoryFuture =
                supplyPreTurn(
                        preTurnTenant,
                        () ->
                                memoryRecall.recallMemoryFactsBlock(
                                        agentId, subjectKey, userMessage, stepEvents));

        // Stage 4: empty unless the "mcp" profile is active and at least one
        // registry entry is enabled - see AgentToolProvider for why.
        // userMessage additionally drives automatic per-turn relevance
        // filtering once the resolved tool count is large enough to matter
        // (see ToolRelevanceSelector). chatClient (Agent Intelligence
        // research doc, July 2026) is this turn's already-built client,
        // reused by RIGOROUS-mode tool-call verification instead of
        // resolving a second set of credentials - see resolveToolsFor's
        // chatClient javadoc.
        List<ToolCallback> tools = joinPreTurn(toolsFuture);
        rl.filterToolSubset(tools);
        // #911 - QuickTaskService's per-run ad-hoc delegation tools (see this
        // overload's own javadoc); empty for every other caller.
        if (extraTools != null && !extraTools.isEmpty()) {
            tools.addAll(extraTools);
        }
        // Defensive MCP hardening (2026-08-05) - see mcpUntrustedDataDirective's
        // javadoc for why this can't be derived from `tools` itself and has to
        // ask AgentToolProvider directly. Not run as its own concurrent future
        // alongside historyFuture/toolsFuture/memoryFuture above: it re-uses
        // toolsFuture's own already-cached MCP discovery (see
        // AgentToolProvider#anyMcpToolsOfferedFor's javadoc), so by the time
        // toolsFuture has been joined just above, this call is a cache hit, not
        // an added blocking round trip worth its own future.
        // Read from the resolution that already ran when it recorded the answer (a
        // ResolvedToolList); otherwise ask again, as before.
        Boolean mcpOfferedByResolution =
                tools instanceof ResolvedToolList resolved ? resolved.anyMcpToolsOffered() : null;
        boolean anyMcpToolsOffered =
                mcpOfferedByResolution != null
                        ? mcpOfferedByResolution
                        : agentToolProvider.anyMcpToolsOfferedFor(
                                agentId, sessionId, userMessage, chatClient);
        if (smallModelTurn) {
            tools =
                    AgentToolSelection.capForSmallModel(
                            tools, userMessage, AgentToolSelection.SMALL_MODEL_TOOL_CAP);
        }

        // "Smarter, more dynamic agents" roadmap - automatic long-term memory
        // recall, always attempted (unlike remember_fact, which the model
        // must choose to call) so this reliably works even if the model never
        // proactively decides to look something up. Best-effort: a recall
        // failure (sidecar down, no rows yet) degrades to "no extra context",
        // never fails the chat turn itself.
        //
        // Prompt caching (see AnthropicAgentChatClientFactory's javadoc):
        // agent.getSystemPrompt() is a stored field that's stable across an
        // entire agent's lifetime, not just one session - it used to get the
        // recalled-memories block concatenated directly onto it every turn,
        // which meant the "system" content Anthropic hashes for its cache
        // breakpoint changed on literally every single message, so a cache
        // entry could never survive past the turn that wrote it. Now the
        // static prompt stays untouched and goes through .system(...) alone;
        // the small per-turn memories block becomes its own SystemMessage,
        // prepended ahead of the rest of the conversation. Spring AI's
        // multiBlockSystemCaching (enabled in the factory) places the cache
        // breakpoint on the second-to-last system block - with exactly two
        // system messages that's this static one, leaving the memories block
        // free to vary every turn without invalidating it.
        // RL PROMPT_VARIANT (2026-08-15 wiring): rlPromptOverride is the
        // literal alternate prompt text (see systemPromptFromArmConfig's
        // javadoc), not a version reference - this is intentionally the same
        // Anthropic-cache-friendly shape as agent.getSystemPrompt() itself
        // (a single stable string per turn, not concatenated with anything
        // per-turn), so PROMPT_VARIANT doesn't reintroduce the cache-breakpoint
        // problem the memories-block split above was written to avoid.
        // Agent Skills, Level 1: the "Available skills" name+description block. Appended to the
        // static prompt (not the per-turn memory block) because it is byte-stable turn to turn -
        // sorted, and it only changes when the workspace's skills do - so it stays inside the
        // cached prefix the comment above protects. Gated on load_skill actually being on THIS
        // turn's tool list (same rule as memoryUsageDirective): never advertise a skill the model
        // has no tool to load.
        // Procedures layer (Tier-3): attached ACTIVE SOPs compose into the
        // same byte-stable static prompt the skills block joins (the block
        // is position/rule-order deterministic - see ProcedurePromptBlock),
        // so it stays inside the cached prefix the comment above protects.
        // Procedures go FIRST: binding business rules outrank the skills
        // advertisement and should read as part of the operating contract.
        String systemPrompt =
                withSkillsBlock(
                        withSkillsBlock(
                                rlPromptOverride != null
                                        ? rlPromptOverride
                                        : agent.getSystemPrompt(),
                                agentToolProvider.proceduresPromptBlock(agentId)),
                        agentToolProvider.skillsPromptBlock(agentId, tools, smallModelTurn));
        // Repairs any non-JSON tool-call arguments PERSISTED into history
        // from an earlier turn - see ArgumentSanitizingToolCallingManager
        // #sanitizeHistory's javadoc for the live incident this closes
        // (ArgumentSanitizingToolCallingManager itself only ever repairs the
        // CURRENT turn's fresh tool-execution output, never history loaded
        // from SessionMemoryService, so a poisoned entry from many turns ago
        // replayed forever - 400ing every later turn regardless of whether
        // THAT turn's own model response has any tool calls at all).
        List<Message> history =
                ArgumentSanitizingToolCallingManager.sanitizeHistory(
                        joinPreTurn(historyFuture), AgentChatService.HISTORY_SANITIZE_MAPPER);
        // Two independently-optional halves of the same per-turn memory system
        // block: the WRITE-side directive (present whenever remember_fact is
        // actually on this turn's tool list) and the READ-side recalled facts
        // (present only once something has been remembered). Either, both, or
        // neither - joinMemoryBlocks returns null when neither applies, which
        // is exactly the pre-existing "no extra SystemMessage" case.
        // Each directive is additionally gated on its env kill-switch (the
        // app.chat.directives.* fields above) - a null contribution is simply
        // skipped by joinMemoryBlocks, so a flag-off directive vanishes from
        // block 2 cleanly.
        // Per-tenant owner overrides (switch a directive off or reword it), resolved once per
        // turn from an in-process cache; no overrides = exactly the built-in text.
        java.time.LocalDate todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        ResolvedDirectives pd = svc.promptDirectiveSettings.forToday(todayUtc);
        String memoryBlock =
                joinMemoryBlocks(
                        pd.resolve(
                                PromptDirectiveRegistry.CURRENT_DATE,
                                ChatPromptDirectives.currentDateDirective(todayUtc)),
                        pd.resolve(
                                PromptDirectiveRegistry.MEMORY_USAGE,
                                memoryUsageDirectiveEnabled ? memoryUsageDirective(tools) : null),
                        pd.resolve(
                                PromptDirectiveRegistry.MEDIA_HONESTY,
                                mediaGenerationHonestyDirective(tools)),
                        svc.directiveComposer.compose(
                                new PromptDirectiveComposer.Context(
                                        tools, anyMcpToolsOffered, smallModelTurn, pd)),
                        pd.resolve(
                                PromptDirectiveRegistry.TOOL_SEARCH,
                                toolSearchDirectiveEnabled
                                        ? ChatPromptDirectives.toolSearchDirective(tools)
                                        : null),
                        hookRun == null ? null : hookRun.contextNote(),
                        pd.resolve(
                                PromptDirectiveRegistry.CONTEXT_COMPACTION,
                                ChatPromptDirectives.contextCompactionDirective(
                                        contextCompactionDirectiveEnabled
                                                && contextCompaction != null
                                                && contextCompaction.isEnabled(),
                                        tools)),
                        pd.resolve(
                                PromptDirectiveRegistry.BROWSER_V2,
                                browserV2DirectiveEnabled
                                        ? ChatPromptDirectives.browserV2Directive(tools)
                                        : null),
                        pd.resolve(
                                PromptDirectiveRegistry.ARTIFACTS,
                                ChatPromptDirectives.artifactsDirective(
                                        artifactsDirectiveEnabled,
                                        artifactsDirectiveAllAgents,
                                        artifactsDirectiveAgentIds,
                                        agentId)),
                        pd.customInstructionsBlock(),
                        joinPreTurn(memoryFuture));
        List<Message> baseMessages =
                memoryBlock == null ? history : prependMemoryBlock(memoryBlock, history);
        // Context compaction (default OFF -> compactionRun is null and `messages` is baseMessages
        // itself): when on,
        // the replayed history is shrunk to the model's window here, and the run is attached to the
        // client in
        // dispatchTurn so the tool loop keeps the prompt inside the window round after round.
        CompactionRun compactionRun =
                contextCompaction == null
                        ? null
                        : contextCompaction.openRunFor(
                                agent,
                                rlModelOverride,
                                hostedChain,
                                agentId,
                                sessionId,
                                tools,
                                stepEvents,
                                agentService::resolveEffectiveModel);
        List<Message> messages =
                compactionRun == null
                        ? baseMessages
                        : compactionRun.prepareMessages(baseMessages, systemPrompt, userMessage);

        // Recorded immediately: the user said this regardless of what happens
        // next. Any attachments become a compact text placeholder alongside the
        // text (see withAttachmentNote) - history replay on later turns stays
        // coherent ("what was attached" is visible to the model) without
        // re-sending multi-MB payloads on every subsequent turn.
        List<ChatAttachmentPayload> allAttachments = pendingAttachments.join();
        sessionMemoryService.appendTurn(
                memorySessionId, ChatTurn.user(withAttachmentNote(userMessage, allAttachments)));
        // Data-analysis sandbox (gap-analysis item 9): data files go to run_code's working dir
        // (only when this agent has code execution on); sandbox-only types (xlsx) never reach the
        // model.
        boolean sandboxActive =
                sandboxFiles != null && sandboxFiles.availableFor(agent.isE2bEnabled());
        if (sandboxActive) {
            ChatAttachments.mountForSandbox(sandboxFiles, agentId, sessionId, allAttachments);
        }
        // A data file the sandbox took is withheld from a model with no native document input
        // (upload accepted it only because of the sandbox); otherwise it goes to both.
        final List<ChatAttachmentPayload> attachments =
                ChatAttachments.withoutSandboxOnly(
                        allAttachments, sandboxActive && !svc.agentRouting.modelTakesDocuments(agent));

        // Open this turn's RL episode (best-effort, Playground-only) BEFORE the
        // LLM call, so RlTrajectoryAdvisor's per-call steps attach to it, and
        // carrying every dimension's selected arm (empty map when none) so a
        // later human reward on this same turnRef folds back into every one
        // of them, not just MODEL_ROUTE's (see EpisodePolicyArm's javadoc).
        UUID rlEpisodeId =
                rlEpisodes.beginRlEpisode(turnRef, agentId, sessionId, userMessage, rlSelectedArms);
        return new AssembledPrompt(
                systemPrompt,
                messages,
                tools,
                attachments,
                anyMcpToolsOffered,
                compactionRun,
                turnDepth,
                effectiveForceFastDepth,
                experimentVariants,
                rlEpisodeId);
    }

    /**
     * Runs one blocking pre-turn read on a virtual thread with this request's {@link TenantContext}
     * re-applied (and cleared afterward, so a reused carrier never leaks tenant scope). See the
     * fan-out in {@link AgentChatService#chat} for why the three reads overlap. {@code
     * recallMemoryFactsBlock} already swallows its own failures; {@code getHistory}/{@code
     * resolveToolsFor} surface theirs through {@link #joinPreTurn}.
     */
    private <T> CompletableFuture<T> supplyPreTurn(TenantScope tenant, Supplier<T> work) {
        // TenantScope saves/restores rather than set/clears, so it stays correct even if a
        // future caller runs this inline on an already-scoped thread.
        return CompletableFuture.supplyAsync(tenant.wrapSupplier(work), svc.preTurnExecutor);
    }

    /**
     * Joins a pre-turn future, unwrapping {@link CompletionException} so callers see the original
     * {@link RuntimeException} (and its existing GlobalExceptionHandler / provider-error mapping)
     * exactly as they did when these reads ran inline on the request thread.
     */
    private static <T> T joinPreTurn(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static Message toSpringAiMessage(ChatTurn turn) {
        return turn.role() == MessageRole.USER
                ? new UserMessage(turn.content())
                : new AssistantMessage(turn.content());
    }
}
