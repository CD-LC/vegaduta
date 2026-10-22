package com.agenticai.core.eventbus;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Interactive Workflow Designer PRD, Phase 0 / Section 9 - the single entry point any part of this
 * codebase calls to emit a workflow event. Callers never touch Kafka or the outbox table directly.
 *
 * <p><b>Flag-gated, not profile-gated</b>: unlike {@link WorkflowEventOutboxRelay} and {@code
 * WorkflowEventLogger} (both {@code @Profile("workflow")}), this class is always a live bean, but
 * {@link #publish} is a no-op unless {@code app.workflow-events.enabled} is {@code true}. This is
 * deliberate: call sites like {@code TwilioWebhookController} call this unconditionally, and the
 * flag check happening inside this class (rather than the caller having to know whether the feature
 * is on) is what makes wiring a new call site later a one-line addition with zero risk to existing
 * behavior when the flag is off - which is the default.
 *
 * <p><b>One thing is NOT flag-gated: the in-JVM {@link WorkflowEventSignal} fan-out</b>, published
 * at the top of {@link #publish} before the flag is consulted. It reaches in-process
 * {@code @EventListener} subscribers with no outbox row, no relay and no broker involved. See that
 * record's javadoc for why gating it would make it dead code in production, and {@link #publish}
 * for the ordering guarantee.
 *
 * <p><b>Honest scope limit on the outbox pattern for Phase 0</b>: the ideal outbox guarantee is
 * "the outbox row is written in the exact same transaction as the business fact it represents," so
 * a rolled-back business write can never leave behind a stray event. Phase 0's only call site
 * ({@code TwilioWebhookController}) does not wrap its handling in a single {@code @Transactional}
 * method today, so this publisher runs in its own transaction (default {@code REQUIRED}
 * propagation) rather than truly joining one. This is fine for Phase 0's stated goal - prove
 * trigger -> outbox -> Kafka -> consumer works end to end - but is called out here so it is not
 * mistaken for a production-hardened guarantee; true same- transaction atomicity is a Phase 1+
 * item, once a node's real state- changing write and its event both need to be atomic together.
 */
@Service
public class WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventPublisher.class);

    private final WorkflowEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /**
     * The in-JVM fan-out described on {@link WorkflowEventSignal}. Separate from the outbox on
     * purpose and NOT gated on {@link #enabled} - see {@link #publish}.
     */
    private final ApplicationEventPublisher applicationEvents;

    @Autowired
    public WorkflowEventPublisher(
            WorkflowEventOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Value("${app.workflow-events.enabled:false}") boolean enabled,
            ApplicationEventPublisher applicationEvents) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.applicationEvents = applicationEvents;
    }

    /**
     * Compatibility constructor for callers that predate the in-JVM signal (the existing unit suite
     * constructs this class directly). Kept rather than updating those call sites because this
     * class lives in a module several parallel workstreams read, and a constructor-signature break
     * there is a compile failure with no relationship to what the caller was testing. The signal is
     * a no-op under this constructor; the outbox path is identical.
     */
    public WorkflowEventPublisher(
            WorkflowEventOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            boolean enabled) {
        this(outboxRepository, objectMapper, enabled, event -> {});
    }

    /**
     * Writes an outbox row for a brand-new run (a fresh {@code runId} is generated). Returns the
     * generated run id so a caller that will emit further events for the same run later can reuse
     * it - unused by Phase 0's single-event call site, but this is the shape Phase 1's
     * WorkflowExecutionEngine needs (trigger event, then N node-level events, all sharing one
     * runId).
     */
    public String publishNewRun(String eventType, Map<String, Object> payload) {
        String runId = UUID.randomUUID().toString();
        publish(eventType, runId, payload);
        return runId;
    }

    /**
     * <b>Ordering here is load-bearing.</b> The envelope is built and fanned out IN-JVM ({@link
     * WorkflowEventSignal}) BEFORE the {@code app.workflow-events.enabled} check, and only then
     * does the outbox path run. An in-process subscriber gated on that flag would be dead in
     * production, where the flag is off and the {@code workflow} profile is inactive; it would also
     * be untestable in CI for the same reason. The outbox -&gt; relay -&gt; Kafka path below is
     * completely unchanged, flag and all.
     *
     * <p>The fan-out is wrapped in its own try/catch because every caller treats event publishing
     * as best-effort and this class's javadoc commits to never failing the business flow - a broken
     * subscriber must not stop an inbound WhatsApp message from reaching its agent.
     */
    public void publish(String eventType, String runId, Map<String, Object> payload) {
        String tenantId =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);
        WorkflowEvent event = WorkflowEvent.of(eventType, tenantId, runId, payload);

        try {
            applicationEvents.publishEvent(new WorkflowEventSignal(event));
        } catch (RuntimeException e) {
            log.error(
                    "In-JVM workflow event fan-out failed for {} (run {}) - continuing",
                    eventType,
                    runId,
                    e);
        }

        if (!enabled) {
            log.debug(
                    "Workflow events disabled (app.workflow-events.enabled=false) - not publishing {}",
                    eventType);
            return;
        }
        try {
            String envelopeJson = objectMapper.writeValueAsString(event);
            outboxRepository.save(new WorkflowEventOutbox(eventType, runId, envelopeJson));
            log.debug(
                    "Queued workflow event {} (run {}) in the outbox for schema '{}'",
                    eventType,
                    runId,
                    tenantId);
        } catch (JacksonException e) {
            // A serialization failure here means a caller passed a payload
            // Jackson can't handle - fail loudly in logs but never let this
            // take down the actual business flow that called us (e.g. an
            // inbound WhatsApp message should still reach the agent even if
            // event publishing itself is broken).
            log.error(
                    "Failed to serialize workflow event {} (run {}) for schema '{}' - not queued",
                    eventType,
                    runId,
                    tenantId,
                    e);
        }
    }

    /**
     * Explicit-tenant variant for callers running on a thread where {@link TenantContext} is
     * already gone - e.g. {@code AgentChatService}'s stream-completion callback, which runs on a
     * reactor/HTTP-client worker (the same reason {@code
     * AgentChatService#auditStreamedOutboundViolation} threads a captured {@code TenantContext}
     * snapshot into its check rather than reading the ThreadLocal there). Emitting through {@link
     * #publish} on such a thread would stamp the event with {@code
     * TenantSchemaResolver.DEFAULT_SCHEMA}, and a tenant webhook subscription would then silently
     * never match - the event exists but is addressed to nobody. {@code tenantId} here is the same
     * value {@link TenantContext#getTenantId} returns on the request thread - the tenant's schema
     * name. A null/blank value is treated exactly like a missing context (DEFAULT_SCHEMA) rather
     * than rejected: best-effort emission must never fail the business flow.
     */
    public void publish(
            String eventType, String runId, Map<String, Object> payload, String tenantId) {
        String effectiveTenant =
                (tenantId == null || tenantId.isBlank())
                        ? TenantContext.current()
                                .map(TenantContext::getTenantId)
                                .orElse(TenantSchemaResolver.DEFAULT_SCHEMA)
                        : tenantId;
        WorkflowEvent event = WorkflowEvent.of(eventType, effectiveTenant, runId, payload);

        // The in-JVM listeners (WebhookDispatchListener, AgentEventTriggerDispatcher, ...) and the
        // outbox write resolve their schema from THIS thread's TenantContext. A caller using this
        // overload has none (that is why it passes the tenant as data), so the fan-out would query
        // the default schema and fail closed. Apply the tenant for the duration of the dispatch and
        // restore whatever the thread had (TenantScope clears it if there was none), so a pooled
        // thread never carries one tenant's context into its next task.
        scopeFor(effectiveTenant).run(() -> dispatch(event, eventType, runId, effectiveTenant));
    }

    /**
     * A scope holding {@code tenantId} unless the calling thread already holds exactly that tenant
     * (then its own richer context, with the real subject and roles, stays in force) or the tenant
     * is the default-schema fallback (no tenant was known: apply nothing).
     */
    private static TenantScope scopeFor(String tenantId) {
        if (tenantId == null
                || tenantId.isBlank()
                || TenantSchemaResolver.DEFAULT_SCHEMA.equals(tenantId)) {
            return TenantScope.of((TenantContext) null);
        }
        boolean alreadyHeld =
                TenantContext.current().map(c -> tenantId.equals(c.getTenantId())).orElse(false);
        if (alreadyHeld) {
            return TenantScope.of((TenantContext) null);
        }
        return TenantScope.of(TenantContext.of(tenantId, "system:workflow-event-fanout", Set.of()));
    }

    private void dispatch(
            WorkflowEvent event, String eventType, String runId, String effectiveTenant) {
        try {
            applicationEvents.publishEvent(new WorkflowEventSignal(event));
        } catch (RuntimeException e) {
            log.error(
                    "In-JVM workflow event fan-out failed for {} (run {}) - continuing",
                    LogSafe.clean(eventType),
                    LogSafe.clean(runId),
                    e);
        }

        if (!enabled) {
            log.debug(
                    "Workflow events disabled (app.workflow-events.enabled=false) - not publishing {}",
                    eventType);
            return;
        }
        try {
            String envelopeJson = objectMapper.writeValueAsString(event);
            outboxRepository.save(new WorkflowEventOutbox(eventType, runId, envelopeJson));
            log.debug(
                    "Queued workflow event {} (run {}) in the outbox for schema '{}'",
                    eventType,
                    runId,
                    effectiveTenant);
        } catch (JacksonException e) {
            // A serialization failure here means a caller passed a payload
            // Jackson can't handle - fail loudly in logs but never let this
            // take down the actual business flow that called us (e.g. an
            // inbound WhatsApp message should still reach the agent even if
            // event publishing itself is broken).
            log.error(
                    "Failed to serialize workflow event {} (run {}) for schema '{}' - not queued",
                    LogSafe.clean(eventType),
                    LogSafe.clean(runId),
                    LogSafe.clean(effectiveTenant),
                    e);
        }
    }
}
