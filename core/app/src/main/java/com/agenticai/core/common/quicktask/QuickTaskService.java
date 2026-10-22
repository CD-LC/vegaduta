package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.decision.AgentRelevanceSelector;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agents.version.AgentVersionResolutionService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.VoiceCallService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * Simple mode's Quick Task feature - "let them type what they want without worrying about the
 * workflow, on button click it should get executed either through whatsapp, sms, or on giving a
 * call." Ties together three things that already exist independently:
 *
 * <ol>
 *   <li>{@link AgentService#listAll()} + {@link AgentRelevanceSelector} - picks whichever of the
 *       tenant's agents best matches the task's own text (#591 follow-up: "quick task should be
 *       very perfect in choosing right agents and mcp tools" - previously this always used {@code
 *       agents.get(0)}, the tenant's first agent, regardless of what the task actually asked for).
 *       A tenant with only one agent, or a task with no lexical signal against any agent, both
 *       still fall back to that same first agent - see {@link AgentRelevanceSelector}'s javadoc for
 *       the full reasoning.
 *   <li>{@link AgentChatService#chat} - runs the free-text prompt through that agent with its
 *       already-automatic tool/MCP selection (see {@code AgentToolProvider}/{@code
 *       ToolRelevanceSelector}); this service just collects the streamed answer into one final
 *       string instead of forwarding the SSE stream, since a WhatsApp message/SMS/ phone call needs
 *       the complete text, not chunks.
 *   <li>One of {@link WhatsAppDispatchService}, {@link SmsDispatchService}, {@link
 *       VoiceCallService} - delivers that final answer to the requesting user's OWN phone number
 *       (never a customer's), one-time, no recurring schedule.
 * </ol>
 *
 * <p>Skips {@code HumanApprovalGateService} (used by {@code WhatsAppSendController} for outbound
 * sends to customers) ONLY for the self-only path above - the requester and the recipient are
 * guaranteed to be the same person there, so there's no second party whose approval would ever make
 * sense to gate. #598 follow-up ("let Quick Task detect & send to a typed number"): when the task's
 * own text names a different phone number, that guarantee no longer holds, so {@link
 * #execute}/{@link QuickTaskDelivery#dispatchToThirdParty} route through the exact same approval
 * gate {@code WhatsAppSendController} uses instead - see that method's javadoc.
 */
@Service
public class QuickTaskService {

    private static final Logger log = LoggerFactory.getLogger(QuickTaskService.class);

    // Live-trace polling cadence (#474 follow-up: "I want to see the live
    // trace log for that task - everything running for that task"). Tool
    // calls land in Postgres the instant they finish (LoggingToolCallback's
    // finally block) regardless of this feature, so this interval is purely
    // "how quickly does the UI notice a row that's already there" - short
    // enough to feel live, long enough not to hammer the DB for a task that
    // usually only makes a handful of tool calls total.
    private static final Duration TRACE_POLL_INTERVAL = Duration.ofMillis(600);

    private final AppUserRepository appUserRepository;
    private final AgentService agentService;
    private final AgentChatService agentChatService;
    private final AgentToolProvider agentToolProvider;
    // Primary agent choice and #911 extra-agent delegation tools - see QuickTaskAgentSelection.
    private final QuickTaskAgentSelection agentSelection;
    private final ToolCallLogService toolCallLogService;
    private final QuickTaskRunService quickTaskRunService;
    private final AuditLogService auditLogService;
    // Self-only vs approval-gated third-party delivery - see QuickTaskDelivery.
    private final QuickTaskDelivery delivery;
    // SSE event serialisation and tool-call polling for runStream.
    private final QuickTaskTraceEvents trace;

    /**
     * C1 version resolver - setter-injected (same posture as {@link
     * AgentChatService#setAgentVersionResolutionService}) so the hand-built test fixtures keep
     * their constructor. Null/absent = run rows carry no {@code agent_version_id} pin, the pre-C1
     * shape.
     */
    private ObjectProvider<AgentVersionResolutionService> versionResolver;

    @Autowired
    public void setAgentVersionResolutionService(
            ObjectProvider<AgentVersionResolutionService> provider) {
        this.versionResolver = provider;
    }

    public QuickTaskService(
            AppUserRepository appUserRepository,
            AgentService agentService,
            AgentChatService agentChatService,
            AgentToolProvider agentToolProvider,
            AgentRelevanceSelector agentRelevanceSelector,
            WhatsAppDispatchService whatsAppDispatchService,
            SmsDispatchService smsDispatchService,
            VoiceCallService voiceCallService,
            HumanApprovalGateService approvalGateService,
            ToolCallLogService toolCallLogService,
            QuickTaskRunService quickTaskRunService,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            ChannelTextSends channelTextSends) {
        this.appUserRepository = appUserRepository;
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.agentToolProvider = agentToolProvider;
        this.agentSelection =
                new QuickTaskAgentSelection(agentService, agentChatService, agentRelevanceSelector);
        this.toolCallLogService = toolCallLogService;
        this.quickTaskRunService = quickTaskRunService;
        this.auditLogService = auditLogService;
        this.delivery =
                new QuickTaskDelivery(
                        whatsAppDispatchService,
                        smsDispatchService,
                        voiceCallService,
                        approvalGateService,
                        objectMapper,
                        channelTextSends);
        this.trace = new QuickTaskTraceEvents(toolCallLogService, objectMapper);
    }

    /**
     * Dry-run preview - "detect the underlying workflow if specified, select defined tools/MCP
     * tools" - BEFORE anything actually executes or dispatches. Deliberately does NOT require a
     * phone number (unlike {@link #run}): checking what would happen shouldn't be blocked on a
     * prerequisite only the real run needs.
     */
    public QuickTaskPreviewResponse preview(
            String keycloakUserId, QuickTaskPreviewRequest request) {
        AgentResponse agent =
                agentSelection.selectAgentOrThrow(request.message(), request.agentId());
        String probeSessionId = "quick-task-preview-" + UUID.randomUUID();
        // No real chat context for a preview probe - null chatClient is safe,
        // see AgentToolProvider#resolveToolsFor's chatClient javadoc (a
        // RIGOROUS agent's verifier simply fails open for this call, same as
        // the tool-discovery probe in listAvailableToolsFor).
        // Trailing null = no dispatched reasoning depth for this turn, so the agent's STORED
        // depth applies (2026-09-17) - correct here: a preview probe is not a turn, nothing is
        // dispatched at any depth, and the verifier fails open for it either way.
        List<ToolCallback> tools =
                agentToolProvider.resolveToolsFor(
                        agent.id(), probeSessionId, keycloakUserId, request.message(), null, null);
        List<ToolDescriptor> detected =
                tools.stream()
                        .map(
                                t ->
                                        new ToolDescriptor(
                                                t.getToolDefinition().name(),
                                                t.getToolDefinition().description()))
                        .toList();

        // #598 follow-up - surface a third-party number at preview time too,
        // not just at execute() time, so the UI can warn "this will need
        // approval" before the person commits. Own number is looked up
        // best-effort here (unlike validate(), preview() must not require
        // one to exist yet); no own number on file just means any number
        // found in the text is treated as third-party, which is the correct,
        // safe default.
        String ownPhoneNumber =
                appUserRepository
                        .findByKeycloakUserId(keycloakUserId)
                        .map(AppUser::getPhoneNumber)
                        .orElse(null);
        String thirdPartyNumber =
                QuickTaskDelivery.extractThirdPartyNumber(request.message(), ownPhoneNumber)
                        .orElse(null);

        // The person confirming this task deserves to know what will power
        // it BEFORE they commit - same resolved agent {@link #run} would
        // pick, so the disclosed engine can never disagree with the real
        // run. Nullable providerName only when the agent record itself is
        // malformed; the UI treats nulls as "not disclosed", never guesses.
        return new QuickTaskPreviewResponse(
                agent.name(),
                detected,
                thirdPartyNumber,
                agent.id(),
                agent.providerName() == null ? null : agent.providerName().name(),
                agent.model());
    }

    public QuickTaskResponse run(String keycloakUserId, QuickTaskRequest request) {
        ValidatedRun validated =
                validate(keycloakUserId, request.message(), request.agentId(), request.channel());
        QuickTaskResponse response =
                execute(
                        validated.agent(),
                        keycloakUserId,
                        validated.phoneNumber(),
                        request,
                        UUID.randomUUID().toString());
        return response;
    }

    /**
     * Same real work as {@link #run}, restructured as a live event stream (#474 follow-up: "I want
     * to see the live trace log for that task - everything running for that task"). Quick Task's
     * actual execution is still one blocking chat call plus one dispatch call - this doesn't change
     * that - but the caller no longer has to wait in silence for it: a {@code started} event fires
     * immediately with the real session id, a {@code tool_call} event fires for every real {@code
     * ToolCallLog} row as it's written (polled from Postgres, not simulated), and a {@code
     * done}/{@code error} event closes out the stream with the same data {@link #run} would have
     * returned synchronously.
     *
     * <p>Validation (account/phone/agent) happens synchronously on the calling thread, exactly like
     * {@link #run} - a caller with no phone number configured still gets a normal 400 {@link
     * QuickTaskNotReadyException}, not a stream that opens and then reports an error event, since
     * nothing has actually started yet at that point.
     *
     * <p>The real work runs on {@link Schedulers#boundedElastic()} (it calls {@code .block()}
     * internally, same as {@link #run}) while a separate polling subscription tails {@link
     * ToolCallLogService} for this run's {@code sessionId}. Both capture this method's
     * calling-thread {@link TenantContext} once and re-apply it explicitly before any tenant-scoped
     * work, the same "capture on a known-correct thread, re-set before any hopped-thread work"
     * pattern {@code TenantContextPropagatingToolCallback} already established for the exact same
     * class of bug (#63) - without this, a schema-per-tenant lookup on a Reactor scheduler thread
     * silently falls back to the wrong schema instead of failing loudly.
     */
    public Flux<String> runStream(String keycloakUserId, QuickTaskRequest request) {
        ValidatedRun validated =
                validate(keycloakUserId, request.message(), request.agentId(), request.channel());
        AgentResponse agent = validated.agent();
        String phoneNumber = validated.phoneNumber();
        String sessionId = UUID.randomUUID().toString();
        TenantContext capturedContext = TenantContext.current().orElse(null);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // providerName/model ride the started+done events so the UI can show
        // "powered by ..." on the live trace and the result, not just on the
        // pre-run preview card.
        String providerName = agent.providerName() == null ? null : agent.providerName().name();
        trace.emit(
                sink,
                QuickTaskTraceEvents.event(
                        "started",
                        "sessionId",
                        sessionId,
                        "agentId",
                        agent.id().toString(),
                        "agentName",
                        agent.name(),
                        "providerName",
                        providerName,
                        "model",
                        agent.model()));

        Set<UUID> seenToolCallIds = ConcurrentHashMap.newKeySet();
        Disposable pollSubscription =
                Flux.interval(TRACE_POLL_INTERVAL)
                        .publishOn(TenantAwareSchedulers.boundedElastic())
                        .subscribe(
                                tick ->
                                        trace.pollNewToolCalls(
                                                capturedContext,
                                                agent.id(),
                                                sessionId,
                                                seenToolCallIds,
                                                sink));

        // #594 follow-up ("same issue" - "No agent found with id ...", even
        // though the preview step immediately before had already resolved
        // and shown that exact agent + its tools correctly). Root cause: a
        // real, pre-existing #63-class bug - execute() runs on {@link
        // Schedulers#boundedElastic()} via subscribeOn below, but unlike
        // pollNewToolCalls (which explicitly re-applies capturedContext on
        // its own hopped thread), this call never did. agentChatService.chat
        // -> agentService.getEntity(agentId) is a synchronous repository
        // read right at the top of chat() - with no TenantContext set on
        // this new thread, it silently resolves against the default/public
        // schema instead of this tenant's, so a perfectly real agent id
        // (just looked up successfully on the request thread by validate()
        // moments earlier) comes back "not found." Single-tenant/no-
        // multi-tenancy local setups never surface this (there's only ever
        // one schema to fall back to), which is exactly why this slipped
        // through #579's verification pass but breaks on a real
        // schema-per-tenant deployment. Same fix shape as pollNewToolCalls:
        // capture-then-reapply on the new thread, restore/clear after.
        Mono.fromCallable(
                        () -> {
                            Optional<TenantContext> previous = TenantContext.current();
                            if (capturedContext != null) {
                                TenantContext.set(capturedContext);
                            }
                            try {
                                return execute(
                                        agent, keycloakUserId, phoneNumber, request, sessionId);
                            } finally {
                                previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
                            }
                        })
                .subscribeOn(TenantAwareSchedulers.boundedElastic())
                .subscribe(
                        response -> {
                            // One last catch-up poll - closes the small race between
                            // the final tool call's DB write and the next interval
                            // tick, so "done" never arrives before every real tool
                            // call it depended on has already been shown.
                            trace.pollNewToolCalls(
                                    capturedContext, agent.id(), sessionId, seenToolCallIds, sink);
                            pollSubscription.dispose();
                            trace.emit(
                                    sink,
                                    QuickTaskTraceEvents.event(
                                            "done",
                                            "resultText",
                                            response.resultText(),
                                            "channel",
                                            response.channel().name(),
                                            "deliveryNote",
                                            response.deliveryNote(),
                                            "providerName",
                                            providerName,
                                            "model",
                                            agent.model()));
                            sink.tryEmitComplete();
                        },
                        error -> {
                            pollSubscription.dispose();
                            // Direct feedback ("display the actual underlying error/reason
                            // returned by the provider... show a user-friendly message
                            // while preserving technical details for troubleshooting") -
                            // this used to emit error.getMessage() straight into the SSE
                            // "error" event, which for an UpstreamProviderException is the
                            // TECHNICAL message (e.g. "The LLM provider call failed: Failed
                            // to generate content" - see ProviderErrorTranslator's class
                            // javadoc for how that specific string leaked here) - the exact
                            // raw text GlobalExceptionHandler#handleUpstreamProvider
                            // deliberately never returns for the equivalent non-streaming
                            // request. Full technical detail (with stack trace) still goes
                            // to the server log line just above and, for anything wired
                            // through LlmMetricsAdvisor, to the durable llm_call_log row -
                            // only the plain-language consumer message reaches this stream.
                            String consumerMessage =
                                    error instanceof UpstreamProviderException upstreamEx
                                            ? upstreamEx.getConsumerMessage()
                                            : "Something went wrong running that. Please try"
                                                    + " again.";
                            trace.emit(
                                    sink,
                                    QuickTaskTraceEvents.event(
                                            "error", "message", consumerMessage));
                            sink.tryEmitComplete();
                        });

        return sink.asFlux();
    }

    /**
     * The actual chat-and-dispatch work {@link #run} and {@link #runStream} both do - factored out
     * so the two never drift. Also the single chokepoint both paths share for persisting {@link
     * QuickTaskRun} (#606 follow-up: "it should generate an ID of a task and we should be able to
     * see the complete log... any point of time") - a RUNNING row is written before the LLM call
     * starts, then updated to COMPLETED or FAILED before this method returns/throws, so {@code GET
     * /api/quick-task/{taskId}} can reconstruct the whole run afterward regardless of which entry
     * point (blocking {@link #run} or streaming {@link #runStream}) drove it, or whether the
     * caller's browser tab is even still open.
     */
    private QuickTaskResponse execute(
            AgentResponse agent,
            String keycloakUserId,
            String phoneNumber,
            QuickTaskRequest request,
            String sessionId) {
        // C1 pin: resolve the version ONCE up front so the durable
        // QuickTaskRun row records the same agent_version.id that
        // AgentChatService will pin for this turn's llm_call_log/
        // tool_call_log rows a few lines below. Fail-open - a resolver
        // hiccup leaves the column NULL, never blocks the task.
        UUID agentVersionId = null;
        try {
            AgentVersionResolutionService resolver =
                    versionResolver == null ? null : versionResolver.getIfAvailable();
            if (resolver != null) {
                agentVersionId =
                        resolver.resolveForRun(agent.id()).map(v -> v.getId()).orElse(null);
            }
        } catch (RuntimeException e) {
            // log-free by design: AgentVersionResolutionService already logged; the run row just
            // stays unpinned
        }
        quickTaskRunService.start(
                sessionId,
                agent.id(),
                keycloakUserId,
                request.channel(),
                request.message(),
                agentVersionId);
        // "all operations should get audited" - the run itself, which agent
        // answered it (explicit pick vs. auto-select) and which channel it's
        // headed for, all in one entry keyed by this run's own sessionId/
        // taskId so it lines up 1:1 with the QuickTaskRun/LlmCallLog rows
        // already keyed the same way (see getLlmUsageForTask's javadoc).
        auditLogService.record(
                keycloakUserId,
                "quicktask.run.started",
                "quick_task_run",
                sessionId,
                AuditOutcome.SUCCESS,
                "Agent "
                        + agent.name()
                        + (request.agentId() != null
                                ? " (explicitly selected)"
                                : " (auto-selected)")
                        + " · destination "
                        + request.channel());
        try {
            // #911 ("in quick tasks, if it requires multiple agents, it should
            // use additionally") - see buildAdditionalAgentTools' javadoc: a
            // best-effort, best-N lexical-overlap pass over every OTHER agent
            // in this tenant, offered to the primary agent as one-off
            // delegation tools for this run only.
            List<ToolCallback> extraAgentTools =
                    buildAdditionalAgentTools(agent, request.message());

            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    agent.id(), sessionId, keycloakUserId, request.message())
                            .extraTools(extraAgentTools)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
            // params to AgentChatService.chat() - null/null here, same reasoning as
            // every other non-Playground caller (see DiscordWebhookController's comment):
            // Quick Task has no attachment-upload UI of its own.
            String resultText =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collect(Collectors.joining())
                            .block();

            // Third-party numbers go through the approval gate; LOG sends nothing - see
            // QuickTaskDelivery.
            String deliveryNote =
                    delivery.deliver(
                            request.channel(),
                            request.message(),
                            phoneNumber,
                            resultText,
                            keycloakUserId);

            quickTaskRunService.complete(sessionId, resultText, deliveryNote);
            auditLogService.record(
                    keycloakUserId,
                    "quicktask.run.completed",
                    "quick_task_run",
                    sessionId,
                    AuditOutcome.SUCCESS,
                    deliveryNote);
            return new QuickTaskResponse(
                    resultText, request.channel(), deliveryNote, sessionId, agent.id());
        } catch (RuntimeException e) {
            quickTaskRunService.fail(sessionId, e.getMessage());
            auditLogService.record(
                    keycloakUserId,
                    "quicktask.run.failed",
                    "quick_task_run",
                    sessionId,
                    AuditOutcome.FAILURE,
                    e.getMessage());
            throw e;
        }
    }

    /**
     * "any point of time - they should be able to view it" - the full persisted run plus every real
     * {@link ToolCallLog} row for it, in the order tools actually fired. Ownership-scoped: a task
     * id that exists but belongs to a different user in this tenant 404s exactly like one that
     * doesn't exist at all (see {@link QuickTaskNotFoundException}).
     */
    public QuickTaskRunDetailResponse getTaskDetail(String keycloakUserId, String taskId) {
        QuickTaskRun run =
                quickTaskRunService
                        .findForOwner(taskId, keycloakUserId)
                        .orElseThrow(() -> new QuickTaskNotFoundException(taskId));
        List<ToolCallLog> toolCalls =
                toolCallLogService.findByAgentIdAndSessionId(run.getAgentId(), taskId);
        // Engine disclosure for the detail view - the run row stores the
        // agent id, so its configured provider/model is a best-effort lookup
        // away. Fail-open: a deleted agent or a cross-schema hiccup leaves
        // the fields null, never blocks the detail.
        AgentResponse agent = null;
        try {
            agent = run.getAgentId() == null ? null : agentService.get(run.getAgentId());
        } catch (RuntimeException ignored) {
            // agent deleted or not resolvable here - detail still returns, engine fields stay null
        }
        return QuickTaskRunDetailResponse.from(run, toolCalls, agent);
    }

    /** Recent-tasks list backing the "pick a past task id to open" view. */
    public List<QuickTaskRunSummaryResponse> recentTasks(String keycloakUserId) {
        return quickTaskRunService.recentForOwner(keycloakUserId).stream()
                .map(QuickTaskRunSummaryResponse::from)
                .toList();
    }

    /** Paged variant: {@code Page.getTotalElements()} is the user's real run count. */
    public Page<QuickTaskRunSummaryResponse> recentTasks(
            String keycloakUserId, long offset, int limit) {
        return quickTaskRunService
                .recentForOwner(keycloakUserId, offset, limit)
                .map(QuickTaskRunSummaryResponse::from);
    }

    /**
     * Shared prerequisite checks {@link #run} and {@link #runStream} both need before anything
     * executes.
     */
    private ValidatedRun validate(
            String keycloakUserId, String message, UUID explicitAgentId, QuickTaskChannel channel) {
        AppUser user =
                appUserRepository
                        .findByKeycloakUserId(keycloakUserId)
                        .orElseThrow(
                                () ->
                                        new QuickTaskNotReadyException(
                                                "No account found for this session - please sign in"
                                                        + " again."));

        // LOG never dispatches anywhere (see QuickTaskChannel#LOG's javadoc),
        // so it's the one destination that shouldn't be gated on having a
        // phone number on file - a person picking "log only" specifically to
        // avoid a phone call/text going out shouldn't be told to add a phone
        // number first in order to do that.
        String phoneNumber = user.getPhoneNumber();
        if (channel != QuickTaskChannel.LOG && (phoneNumber == null || phoneNumber.isBlank())) {
            throw new QuickTaskNotReadyException(
                    "Add your phone number first so a result has somewhere to go - PUT"
                            + " /api/account/phone.");
        }

        return new ValidatedRun(
                agentSelection.selectAgentOrThrow(message, explicitAgentId), phoneNumber);
    }

    private record ValidatedRun(AgentResponse agent, String phoneNumber) {}

    /**
     * #911 extra-agent delegation tools for one run - see {@link
     * QuickTaskAgentSelection#additionalAgentTools}. Each tool is wrapped so the delegation carries
     * this thread's tenant.
     */
    List<ToolCallback> buildAdditionalAgentTools(AgentResponse primary, String message) {
        return agentSelection.additionalAgentTools(primary, message);
    }
}
