package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.TurnReservationGuards;
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
 */
public record TurnContext(
        TenantContext tenant,
        UUID requestedAgentId,
        String sessionId,
        String requestId,
        Instant startedAt,
        Instant deadline,
        TurnReservationGuards budget) {

    public TurnContext {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(startedAt, "startedAt");
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
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                Instant.now(),
                null,
                null);
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
                tenant, requestedAgentId, sessionId, requestId, startedAt, deadline, guards);
    }
}
