package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.runtime.TurnRuntime;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.security.RequestCorrelationFilter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Who and what one chat turn is for, as an immutable value passed explicitly down the turn
 * pipeline. ADR-0022 ({@code docs/architecture/ADR-0022-explicit-turn-context.md}) describes the
 * target and the staged migration from the {@link TenantContext} ThreadLocal to this record.
 *
 * <p>Stage 4: each chat entry (the HTTP controllers, channel adapters, workflow, jobs, interop)
 * builds it with {@link #forEntry} from the tenant it already knows and calls {@code
 * AgentChatService#chat(TurnContext, ChatTurnRequest)}, which runs the synchronous part under
 * {@link #onEntryThread} and subscribes the reply inside {@link #scope()}. Stages 1-3 made the turn
 * pipeline read the tenant from here instead of the ThreadLocal. The ThreadLocal is not removed: it
 * stays the source the JDBC boundary reads to pick the tenant schema ({@code SET search_path}), and
 * {@link #scope()} is the one bridge from this record back to it for a hop that still needs it.
 *
 * @param tenant the caller's tenant principal, exactly the instance the request thread carried;
 *     {@code null} for a tenant-less turn (the pipeline already handles a null tenant id)
 * @param requestedAgentId the agent the caller asked for, before experiment routing
 * @param sessionId the raw client session id (not the SEC-004 scoped memory id)
 * @param requestId correlation id; the {@code X-Request-Id} MDC value when present, otherwise a
 *     fresh one; never null
 * @param startedAt wall-clock start of the turn; never null
 * @param deadline instant after which the turn should give up; {@code null} means no deadline is
 *     set (stage 1 sets none, the existing per-depth timeouts still apply)
 * @param budget the hosted turn's reservation guards once client selection has made them; {@code
 *     null} before that and for BYOK turns
 * @param approval the turn's approval state (parked on a human approval or not, and the SEC-004
 *     scoped memory id the turn reads its history under), shared by reference with the tool guards
 *     built for the turn; {@code null} until preflight has derived the scoped id. The one mutable
 *     member: it is how the turn loop learns that a tool call parked the turn
 * @param continuation set only for the turn the platform starts after a human decided a gated tool
 *     call (see {@link ChatTurnRequest#continuation()}); {@code null} on every ordinary turn
 * @param reply how the turn's reply ended (a finished answer, cut off, refused, ...), shared by
 *     reference with the stage that produces the reply and the stages that cache or store it; never
 *     null (a fresh one is created when the caller passes none). The second mutable member
 * @param toolActivity what the turn's tools have done so far (running, finished, whether their effect
 *     was journaled), shared by reference with the tool wrapper that reports it and the turn loop that
 *     decides on it (idle timeout, whether a failover may replay the turn); never null (a fresh one is
 *     created when the caller passes none). The third mutable member
 * @param modelTraits what is known about the model the turn runs on (whether it is a weak tool
 *     caller), set by client selection and read by the stages that adapt to the model; never null (a
 *     fresh one is created when the caller passes none). The fourth mutable member
 * @param runtime the turn's resolved runtime limits (tool steps, timeouts, output cap, retrieval,
 *     continuation passes), set by client selection once the agent is loaded and read by every stage
 *     that enforces one; never null (a fresh, still empty one is created when the caller passes none).
 *     The fifth mutable member
 * @param turnId the turn's own id, made when the context is built and carried unchanged through every copy:
 *     the id of its turn journal record ({@code X-Journal-Turn-Id} on the response, {@code
 *     GET /api/agents/{agentId}/turns/{turnId}/journal}); never null. Unlike {@code requestId} it is never taken
 *     from the caller, so two turns can never share it
 * @param stepEvents the turn's step-event emitter (the request's, or {@link AgentStepEventEmitter#disabled()}
 *     when the caller did not ask for step events); never null. What a stage that is not handed the emitter
 *     directly (a buffered pass, a continuation) uses to tell the client it is retrying, failing over or
 *     continuing
 */
public record TurnContext(
        TenantContext tenant,
        UUID requestedAgentId,
        String sessionId,
        String requestId,
        Instant startedAt,
        Instant deadline,
        TurnReservationGuards budget,
        TurnApprovalState approval,
        ApprovalContinuation continuation,
        TurnReplyOutcome reply,
        TurnToolActivity toolActivity,
        TurnModelTraits modelTraits,
        TurnRuntime runtime,
        UUID turnId,
        AgentStepEventEmitter stepEvents) {

    /** Every field but the step-event emitter, which a fresh context starts without (the disabled one). */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval,
            ApprovalContinuation continuation,
            TurnReplyOutcome reply,
            TurnToolActivity toolActivity,
            TurnModelTraits modelTraits,
            TurnRuntime runtime,
            UUID turnId) {
        this(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                null);
    }

    /** Every field but the runtime settings, which a fresh context starts without. */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval,
            ApprovalContinuation continuation,
            TurnReplyOutcome reply,
            TurnToolActivity toolActivity,
            TurnModelTraits modelTraits) {
        this(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                null,
                null);
    }

    /** Every field but the model traits, which a fresh context starts with unknown ones. */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval,
            ApprovalContinuation continuation,
            TurnReplyOutcome reply,
            TurnToolActivity toolActivity) {
        this(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                reply,
                toolActivity,
                null);
    }

    /** Every field but the tool activity, which a fresh context starts with an empty record. */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval,
            ApprovalContinuation continuation,
            TurnReplyOutcome reply) {
        this(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                reply,
                null);
    }

    /** Every field but the reply outcome, which a fresh context starts with a new, complete one. */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval,
            ApprovalContinuation continuation) {
        this(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                null);
    }

    /** A context with no approval state yet (every entry builds one; preflight adds the state). */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget) {
        this(tenant, requestedAgentId, sessionId, requestId, startedAt, deadline, budget, null, null);
    }

    /** A context for an ordinary turn (no approval continuation). */
    public TurnContext(
            TenantContext tenant,
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Instant deadline,
            TurnReservationGuards budget,
            TurnApprovalState approval) {
        this(tenant, requestedAgentId, sessionId, requestId, startedAt, deadline, budget, approval, null);
    }

    public TurnContext {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(startedAt, "startedAt");
        reply = reply == null ? new TurnReplyOutcome() : reply;
        toolActivity = toolActivity == null ? new TurnToolActivity() : toolActivity;
        modelTraits = modelTraits == null ? new TurnModelTraits() : modelTraits;
        runtime = runtime == null ? new TurnRuntime() : runtime;
        turnId = turnId == null ? UUID.randomUUID() : turnId;
        stepEvents = stepEvents == null ? AgentStepEventEmitter.disabled() : stepEvents;
        if (deadline != null && deadline.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "deadline " + deadline + " is before start " + startedAt);
        }
    }

    /**
     * Captures the calling thread's {@link TenantContext} into a new turn context. {@code
     * requestId} may be null or blank, in which case a random one is generated; {@code maxDuration}
     * null means no deadline.
     */
    public static TurnContext fromCurrentThread(
            UUID requestedAgentId,
            String sessionId,
            String requestId,
            Instant startedAt,
            Duration maxDuration) {
        Instant start = Objects.requireNonNull(startedAt, "startedAt");
        return new TurnContext(
                TenantContext.current().orElse(null),
                requestedAgentId,
                sessionId,
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                start,
                maxDuration == null ? null : start.plus(maxDuration),
                null);
    }

    /**
     * The turn context a chat entry builds for {@code request} (ADR-0022 stage 4): {@code tenant}
     * is the principal the entry already knows (the one its filter or channel adapter put on the
     * request thread, or {@code null} for a tenant-less turn), the {@link RequestCorrelationFilter}
     * request id from MDC when present, now as the start, no deadline and no budget yet. Pass the
     * result to {@code AgentChatService#chat(TurnContext, ChatTurnRequest)}.
     */
    public static TurnContext forEntry(TenantContext tenant, ChatTurnRequest request) {
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        return new TurnContext(
                        tenant,
                        request.agentId(),
                        request.sessionId(),
                        requestId == null || requestId.isBlank()
                                ? UUID.randomUUID().toString()
                                : requestId,
                        Instant.now(),
                        null,
                        null)
                .withContinuation(request.continuation())
                .withStepEvents(request.stepEvents());
    }

    /**
     * Legacy entry (ADR-0022 stages 1-3): {@link #forEntry} with the calling thread's tenant. Only
     * the deprecated {@code chat(ChatTurnRequest)} overload and tests use it; an entry in
     * production code builds its context with {@link #forEntry}.
     */
    static TurnContext atEntry(ChatTurnRequest request) {
        return forEntry(TenantContext.current().orElse(null), request);
    }

    /**
     * Runs the synchronous part of a turn with this context's tenant in the calling thread's {@link
     * TenantContext}, so the context is the source of truth at the entry (ADR-0022 stage 4).
     *
     * <p>When the thread already carries exactly this tenant instance (the stage-1 invariant: the
     * entry built the context from the value its filter or adapter set) the work runs untouched,
     * which is what every entry does today. Otherwise only the tenant is applied and restored
     * afterwards; unlike {@link TenantScope} the agent-version pin is deliberately left alone, so a
     * pin the turn's preflight sets on the request thread stays there for the subscription, as it
     * did before stage 4. A tenant-less context runs the work untouched (as {@link #scope()}).
     */
    public <T> T onEntryThread(Supplier<T> work) {
        TenantContext previous = TenantContext.current().orElse(null);
        if (tenant == null || previous == tenant) {
            return work.get();
        }
        TenantContext.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * The text the turn's DECISIONS are made on: input screening, tool selection, cost routing and
     * memory recall. On an ordinary turn that is {@code userMessage}; on the continuation after a
     * human decided a gated tool call it is the original goal, because the continuation message the
     * model is sent is platform-written text wrapped around tool output - screening or matching
     * tools against that would judge the tool's output as if the person had typed it, and a message
     * with no words of the task in it matches no tool.
     */
    public String decisionMessage(String userMessage) {
        return continuation != null && continuation.goal() != null && !continuation.goal().isBlank()
                ? continuation.goal()
                : userMessage;
    }

    /** The turn's resolved runtime settings, or null before client selection resolved them. */
    public AgentRuntimeSettings runtimeSettings() {
        return runtime.settings();
    }

    /** The tenant id, or {@code null} for a tenant-less turn. */
    public String tenantId() {
        return tenant == null ? null : tenant.getTenantId();
    }

    /** The authenticated subject (user or channel principal), or {@code null}. */
    public String subject() {
        return tenant == null ? null : tenant.getSubject();
    }

    public Optional<TenantContext> tenantContext() {
        return Optional.ofNullable(tenant);
    }

    /**
     * The bridge back to the ThreadLocal: a {@link TenantScope} that applies this turn's tenant for
     * the duration of some work (on any thread) and restores the previous value afterwards. Empty
     * (pass-through) for a tenant-less turn.
     */
    public TenantScope scope() {
        return TenantScope.of(tenant);
    }

    /**
     * As {@link #scope()}, additionally applying {@code agentVersionPin} on the executing thread -
     * the same pair {@link TenantScope#capture()} carries. The C1 version pin is not (yet) part of
     * this record, so a hop that must stamp {@code agent_version_id} passes the pin it read on the
     * originating thread.
     */
    public TenantScope scope(UUID agentVersionPin) {
        return TenantScope.of(tenant, agentVersionPin);
    }

    /** Time left before {@link #deadline()}, never negative; empty when no deadline is set. */
    public Optional<Duration> remaining(Instant now) {
        if (deadline == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(now, deadline);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    /** True when a deadline is set and {@code now} is at or past it. */
    public boolean isExpired(Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }

    /** A copy carrying the turn's reservation guards (set once client selection has made them). */
    public TurnContext withBudget(TurnReservationGuards guards) {
        return new TurnContext(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                guards,
                approval,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                stepEvents);
    }

    /**
     * A copy carrying the turn's approval state (set once preflight has derived the scoped memory
     * id). Pass the copy to tool resolution and to the turn executor so both see the same instance.
     */
    public TurnContext withApproval(TurnApprovalState state) {
        return new TurnContext(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                state,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                stepEvents);
    }

    /**
     * A copy marked as the continuation turn after a human decided a gated tool call; {@code null}
     * returns this context unchanged.
     */
    public TurnContext withContinuation(ApprovalContinuation value) {
        if (value == null) {
            return this;
        }
        return new TurnContext(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                value,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                stepEvents);
    }

    /**
     * A copy that should give up at {@code limit}: the instant an enclosing deadline (an agent job's
     * own timeout) ends. {@code null} returns this context unchanged. A limit before the start is
     * clamped to the start rather than rejected, since the caller computed it from a clock that may
     * be a moment ahead of this context's.
     */
    public TurnContext withDeadline(Instant limit) {
        if (limit == null) {
            return this;
        }
        return new TurnContext(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                limit.isBefore(startedAt) ? startedAt : limit,
                budget,
                approval,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                stepEvents);
    }

    /** A copy carrying the turn's step-event emitter; {@code null} returns this context unchanged. */
    public TurnContext withStepEvents(AgentStepEventEmitter emitter) {
        if (emitter == null) {
            return this;
        }
        return new TurnContext(
                tenant,
                requestedAgentId,
                sessionId,
                requestId,
                startedAt,
                deadline,
                budget,
                approval,
                continuation,
                reply,
                toolActivity,
                modelTraits,
                runtime,
                turnId,
                emitter);
    }
}
