package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.countVisionFrames;
import static com.agenticai.core.agents.turn.ChatAttachments.withAttachmentNote;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.prependMemoryBlock;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ChatPromptAssembler.class);

    /**
     * The conversation replayed to the model, and whether any of its assistant turns starts with
     * the platform's tool record ({@link ToolLedger}) - the cue to tell the model the record is
     * reliable.
     */
    private record ReplayedHistory(List<Message> messages, boolean hasToolRecord) {}

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
            boolean suppressAgentTools,
            List<String> runToolNames,
            String taintSource) {
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
        TurnContext context = clients.context();
        // What tool selection, the RIGOROUS tool-call verifier and memory recall match against: the
        // person's message, or the original goal on the continuation after an approval (a generic
        // "carry on" message with the tool output in it matches none of the tools the next step
        // needs).
        String decisionMessage = context.decisionMessage(userMessage);
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
        // One deadline for all three reads: they were kicked off together and run in parallel, so
        // joinPreTurnOrDegrade counts the wait from HERE - a stalled read never adds its own full
        // wait after the others already consumed it.
        long preTurnDeadlineNanos =
                svc.turns.preTurnMaxWait == null
                        ? Long.MIN_VALUE
                        : System.nanoTime() + svc.turns.preTurnMaxWait.toNanos();
        CompletableFuture<ReplayedHistory> historyFuture =
                supplyPreTurn(
                        preTurnTenant,
                        () -> {
                            List<ChatTurn> replayed =
                                    sessionMemoryService.getHistory(memorySessionId);
                            return new ReplayedHistory(
                                    replayed.stream()
                                            .map(ChatPromptAssembler::toSpringAiMessage)
                                            .toList(),
                                    replayed.stream().anyMatch(t -> t.tools() != null));
                        });
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
        // The turn's shared step counter (one per turn, created HERE so the SAME identity reaches
        // both the tool wrappers and the pre-turn reads that must seed taint on this turn - see
        // AgentToolPort#resolveToolsFor(...stepCounter) and #seedTurnTaint).
        AtomicInteger stepCounter = new AtomicInteger(0);
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
                                                        decisionMessage,
                                                        chatClient,
                                                        stepEvents,
                                                        turnDepth,
                                                        stepCounter)));
        CompletableFuture<String> memoryFuture =
                supplyPreTurn(
                        preTurnTenant,
                        () ->
                                memoryRecall.recallMemoryFactsBlock(
                                        agentId,
                                        subjectKey,
                                        decisionMessage,
                                        stepEvents,
                                        stepCounter,
                                        agentToolProvider::seedTurnTaint));
        // A workflow Agent node whose input carries a knowledge node's spotlighted output rides the
        // taint on the request (the workflow module has no turn of its own to mark) - seed it with
        // the SAME counter here, before any tool can run.
        if (taintSource != null && !taintSource.isBlank()) {
            agentToolProvider.seedTurnTaint(stepCounter, taintSource);
        }

        // Stage 4: empty unless the "mcp" profile is active and at least one
        // registry entry is enabled - see AgentToolProvider for why.
        // userMessage additionally drives automatic per-turn relevance
        // filtering once the resolved tool count is large enough to matter
        // (see ToolRelevanceSelector). chatClient (Agent Intelligence
        // research doc, July 2026) is this turn's already-built client,
        // reused by RIGOROUS-mode tool-call verification instead of
        // resolving a second set of credentials - see resolveToolsFor's
        // chatClient javadoc.
        List<ToolCallback> tools =
                joinPreTurnOrDegrade(
                        toolsFuture, ResolvedToolList.empty(), "tools", preTurnDeadlineNanos);
        if (runToolNames != null && !suppressAgentTools) {
            // The run-scoped tool selection (ChatRequest.toolNames): narrows the agent's own
            // resolved tools -
            // already behind their approval, autonomy and guardrail wrappers, which stay as they
            // are - to the
            // named ones, and refuses a name the agent does not offer. The person's choice replaces
            // the RL
            // TOOL_SUBSET bandit's for this run (a bandit arm silently removing a tool the person
            // switched on
            // would be a second, hidden selection); the arm is dropped from the episode so it is
            // not credited.
            // A continuation after an approval narrows leniently (a tool that disappeared since is
            // dropped).
            RunToolSelection.narrow(tools, runToolNames, context.continuation() == null);
            rl.dropToolSubsetArm();
        } else {
            rl.filterToolSubset(tools);
        }
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
                                agentId, sessionId, decisionMessage, chatClient);
        if (smallModelTurn) {
            tools =
                    AgentToolSelection.capForSmallModel(
                            tools,
                            decisionMessage,
                            // The tools the conversation already used, for a short follow-up.
                            context.approval() == null
                                    ? Set.of()
                                    : context.approval().stickyTools(),
                            svc.smallModel);
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
        String basePrompt = rlPromptOverride != null ? rlPromptOverride : agent.getSystemPrompt();
        String proceduresBlock = agentToolProvider.proceduresPromptBlock(agentId);
        String skillsBlock = agentToolProvider.skillsPromptBlock(agentId, tools, smallModelTurn);
        // Repairs any non-JSON tool-call arguments PERSISTED into history
        // from an earlier turn - see ArgumentSanitizingToolCallingManager
        // #sanitizeHistory's javadoc for the live incident this closes
        // (ArgumentSanitizingToolCallingManager itself only ever repairs the
        // CURRENT turn's fresh tool-execution output, never history loaded
        // from SessionMemoryService, so a poisoned entry from many turns ago
        // replayed forever - 400ing every later turn regardless of whether
        // THAT turn's own model response has any tool calls at all).
        ReplayedHistory replayedHistory =
                joinPreTurnOrDegrade(
                        historyFuture,
                        new ReplayedHistory(List.of(), false),
                        "history",
                        preTurnDeadlineNanos);
        List<Message> history =
                ArgumentSanitizingToolCallingManager.sanitizeHistory(
                        replayedHistory.messages(), AgentChatService.HISTORY_SANITIZE_MAPPER);
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
        // Per-agent, per-tenant and platform overrides of the standing directives (switch one off
        // or
        // reword it), resolved through the prompt registry (cached in process); no overrides =
        // exactly
        // the built-in text. ChatSystemPromptComposer is the single place that orders the blocks -
        // the
        // prompt preview calls the same method, so what it shows is what a turn sends.
        java.time.LocalDate todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        ChatSystemPromptComposer.Composed composed =
                ChatSystemPromptComposer.compose(
                        new ChatSystemPromptComposer.Input(
                                basePrompt,
                                rlPromptOverride != null,
                                proceduresBlock,
                                skillsBlock,
                                todayUtc,
                                svc.promptDirectiveSettings.forTurn(todayUtc, agentId),
                                svc.directiveComposer,
                                tools,
                                anyMcpToolsOffered,
                                smallModelTurn,
                                ChatSystemPromptComposer.Switches.of(svc),
                                agentId,
                                hookRun == null ? null : hookRun.contextNote(),
                                replayedHistory.hasToolRecord()
                                        ? svc.turns.toolLedger.getTrustNote()
                                        : null,
                                joinPreTurnOrDegrade(
                                        memoryFuture, null, "memory", preTurnDeadlineNanos)));
        String systemPrompt = composed.systemPrompt();
        String memoryBlock = composed.memoryBlock();
        // Tells the request inspector which registry key / scope / version each block of the system
        // text came from.
        PromptBlockProvenanceRecorder.record(sessionId, composed);
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
                        allAttachments,
                        sandboxActive && !svc.agentRouting.modelTakesDocuments(agent));

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

    /**
     * First-token latency: a pre-turn read that stalls (a dead MCP connect, a sidecar that stopped
     * answering) used to hold the model call open indefinitely - on a laptop install the console
     * showed silence for minutes. Every read shares one deadline measured from the fan-out point,
     * so three parallel reads never sum past it; past it the read hands back {@code fallback} and
     * the turn runs with a reduced prompt (e.g. no memory block, no tools) instead of not running
     * at all. The abandoned future is cancelled so a wedged virtual thread does not linger. 0 =
     * unbounded, the original behaviour.
     */
    private <T> T joinPreTurnOrDegrade(
            CompletableFuture<T> future, T fallback, String what, long deadlineNanos) {
        if (deadlineNanos == Long.MIN_VALUE
                || svc.turns.preTurnMaxWait == null
                || svc.turns.preTurnMaxWait.isZero()
                || svc.turns.preTurnMaxWait.isNegative()) {
            return joinPreTurn(future);
        }
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0) {
            future.cancel(true);
            return fallback;
        }
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(
                    "Pre-turn read '{}' exceeded {}ms - continuing without it so the model call starts",
                    what,
                    svc.turns.preTurnMaxWait.toMillis());
            future.cancel(true);
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return fallback;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new CompletionException(e.getCause());
        }
    }

    private static Message toSpringAiMessage(ChatTurn turn) {
        return turn.role() == MessageRole.USER
                ? new UserMessage(turn.content())
                : new AssistantMessage(turn.content());
    }
}
