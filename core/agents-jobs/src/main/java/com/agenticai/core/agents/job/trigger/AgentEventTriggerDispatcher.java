package com.agenticai.core.agents.job.trigger;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.eventbus.WorkflowEvent;
import com.agenticai.core.eventbus.WorkflowEventSignal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a published platform event into agent jobs - the third and last way an agent can start,
 * after a human asking (PR-1) and a clock (PR-2).
 *
 * <p><b>Why an in-JVM {@link WorkflowEventSignal} and not the Kafka pipe.</b> The durable outbox
 * -&gt; relay -&gt; Kafka path is gated on {@code app.workflow-events.enabled}, which is {@code
 * false} IN PRODUCTION ({@code deploy/docker-compose.gcp.yml} carries neither the flag nor the
 * {@code workflow} profile), and its consumers live behind that same inactive profile. A dispatcher
 * hanging off it would have been dead code on {@code .ai} the day it shipped, and untestable in CI
 * for the same reason. The relay also permanently dead-letters a row after its retry cap with no
 * replay path, which for a trigger means the job simply never happens. {@code
 * WorkflowEventPublisher} therefore fans out in-JVM BEFORE it consults that flag, and this class
 * listens to that.
 *
 * <p><b>This runs on the PUBLISHING thread and that is deliberate.</b> A webhook request thread, or
 * document generation's async thread - and that thread already holds the correct {@code
 * TenantContext}, which is how {@code publish} resolved {@code event.tenantId()} in the first
 * place. It must NOT be {@code @Async} and must not hop threads: {@code TenantContext} is a plain
 * {@code ThreadLocal}, and a hop is exactly the 2026-08-10 document-generation incident, where work
 * on a fresh async thread read the empty {@code public} schema and "successfully" found nothing for
 * three days.
 *
 * <p>Two consequences of running there, both enforced below:
 *
 * <ul>
 *   <li><b>It must never throw.</b> Everything is wrapped. The publisher already swallows
 *       exceptions to honour its own "never break the calling business flow" contract, so a
 *       throwing listener would be silently useless rather than loudly broken - which is worse than
 *       useless.
 *   <li><b>It must be cheap and bounded</b> (the standing "no new blocking calls on the hot path"
 *       rule). The budget per event is: one allow-list check with no I/O at all, then at most one
 *       indexed {@code SELECT}, then at most {@code max-triggers-per-event} short transactions.
 *       <b>No LLM call, ever</b> - whatever the agent then does happens afterwards on {@code
 *       AgentJobExecutor}'s own bounded pool.
 * </ul>
 *
 * <p><b>Two honesty items an operator has to know</b> (a third - channel events not firing when a
 * workflow binding exists - was fixed in {@code core/omnichannel}: the {@code publish} call in
 * every one of the nine channel controllers/jobs now runs unconditionally, before the
 * workflow-trigger-binding check, instead of being skipped by its early {@code return}. An agent
 * event trigger and a workflow channel binding on the same channel can now both fire for the same
 * inbound message):
 *
 * <ol>
 *   <li><b>Channel payloads are content-free by design</b> - {@code {channel, fromMasked,
 *       bodyLength}}, no message body, no unmasked sender, no session id. See {@link
 *       AgentTriggerEventTypes}.
 *   <li><b>"An event produces exactly one job" is exact for ONE event type and time-windowed for
 *       the other eight.</b> {@code document.generation.completed} carries a stable {@code runId}
 *       (the document job's id), so {@link #eventKeyOf} plus V210's {@code UNIQUE (trigger_id,
 *       event_key)} make a redelivery of it impossible to double-fire. The eight channel types are
 *       published through {@code WorkflowEventPublisher#publishNewRun}, which mints a FRESH RANDOM
 *       UUID per call, so a provider retry produces a different key and the unique index cannot see
 *       it. {@link #fingerprintOf} plus {@code app.agent-jobs.trigger.duplicate-window} covers
 *       those instead - durably, which matters because the upstream guard ({@code
 *       InboundMessageDedupService}) is Redis and documented fail-open, and {@code
 *       XDmPollingJob}/{@code EmailInboundPollingJob} never call it at all. Outside that window, a
 *       channel redelivery can still produce a second job.
 * </ol>
 */
@Component
public class AgentEventTriggerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventTriggerDispatcher.class);

    /**
     * {@code agent_event_trigger_fire.event_key} and {@code agent_job.origin_ref} are both
     * VARCHAR(255).
     */
    private static final int MAX_EVENT_KEY_CHARS = 255;

    private final AgentEventTriggerRepository repository;
    private final AgentEventTriggerFiring firing;
    private final AgentJobProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final NotificationService notificationService;
    private final AgentEventTriggerPresenceCache presenceCache;

    public AgentEventTriggerDispatcher(
            AgentEventTriggerRepository repository,
            AgentEventTriggerFiring firing,
            AgentJobProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            NotificationService notificationService,
            AgentEventTriggerPresenceCache presenceCache) {
        this.repository = repository;
        this.firing = firing;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.notificationService = notificationService;
        this.presenceCache = presenceCache;
    }

    /**
     * The whole listener, wrapped so nothing escapes onto the publishing thread.
     *
     * <p>Synchronous and NOT {@code @Async} - see the class javadoc. The outer catch is a {@code
     * Throwable}-free {@code RuntimeException} catch on purpose: an {@code Error} (OOM, a linkage
     * failure) is not something a webhook handler should continue through pretending nothing
     * happened.
     */
    @EventListener
    public void onWorkflowEvent(WorkflowEventSignal signal) {
        try {
            dispatch(signal.event());
        } catch (RuntimeException e) {
            log.error(
                    "Agent event trigger dispatch failed for {} - the publishing flow is unaffected",
                    signal.event() == null ? "(no event)" : signal.event().eventType(),
                    e);
            meterRegistry.counter("agent.job.trigger.dispatch_error").increment();
        }
    }

    private void dispatch(WorkflowEvent event) {
        if (event == null || !properties.getTrigger().isEnabled()) {
            return;
        }
        // FIRST, and with no I/O: the ~7 workflow.* literals the execution engine
        // also publishes must not cost a query each. See AgentTriggerEventTypes
        // for the two reasons the set is an allow-list rather than any string.
        if (!AgentTriggerEventTypes.isSubscribable(event.eventType())) {
            return;
        }
        if (event.runId() == null || event.runId().isBlank()) {
            // The event key IS the idempotency guarantee. Without a runId there
            // is nothing to deduplicate on, and firing anyway would mean an
            // at-least-once redelivery bills the tenant twice.
            log.debug(
                    "Workflow event {} carries no runId - not dispatching to agent triggers",
                    event.eventType());
            return;
        }
        if (TenantSchemaResolver.DEFAULT_SCHEMA.equals(event.tenantId())) {
            // The publishing thread had no TenantContext, so publish() fell back
            // to 'public'. Dispatching there would read the empty public schema
            // at best and cross tenants at worst - the exact failure mode
            // TenantSchemaIterator exists to prevent.
            log.debug(
                    "Workflow event {} was published without a tenant context (schema '{}') - not dispatching to agent triggers",
                    event.eventType(),
                    event.tenantId());
            return;
        }
        // The guard and the ROUTING must agree, and until this check they were
        // two different sources of truth: the refusal above reads
        // event.tenantId(), while the repository read below and every write
        // downstream are routed by THIS THREAD's TenantContext through
        // Hibernate's schema resolver. They agree today only because
        // WorkflowEventPublisher#publish derived event.tenantId() from this same
        // thread-local moments earlier - an incidental invariant, not an
        // enforced one. WorkflowEventSignal is a public record, so any future
        // replay or relay consumer that re-published a CARRIED event would read
        // tenant B's triggers and enqueue tenant B's jobs for tenant A's event.
        // One equality check makes it enforced.
        String threadTenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (threadTenant == null) {
            log.warn(
                    "Workflow event {} reached the agent trigger dispatcher with no TenantContext on this thread - "
                            + "refusing to dispatch rather than reading the public schema",
                    event.eventType());
            return;
        }
        if (!threadTenant.equals(event.tenantId())) {
            log.error(
                    "Workflow event {} carries tenant '{}' but the dispatching thread is in tenant '{}' - refusing to "
                            + "dispatch. A carried event must never be re-published onto another tenant's thread.",
                    event.eventType(),
                    event.tenantId(),
                    threadTenant);
            meterRegistry.counter("agent.job.trigger.dispatch_error").increment();
            return;
        }

        // The "no new blocking calls on the hot path" concession, minimised:
        // most tenants have never configured a trigger, and their every inbound
        // message was still paying the indexed SELECT below. The cache stores
        // ONLY empty answers (a present trigger always gets the live query) and
        // the configuration side invalidates on every write - see the cache's
        // javadoc for the bounded multi-replica staleness this buys that with.
        Instant now = Instant.now();
        if (presenceCache.isKnownEmpty(threadTenant, event.eventType(), now)) {
            return;
        }
        List<AgentEventTrigger> candidates =
                repository.findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(event.eventType());
        if (candidates.isEmpty()) {
            presenceCache.recordEmpty(threadTenant, event.eventType(), now);
            return;
        }

        int max = Math.max(1, properties.getTrigger().getMaxTriggersPerEvent());
        if (candidates.size() > max) {
            log.warn(
                    "{} enabled agent triggers match {} but app.agent-jobs.trigger.max-triggers-per-event is {} - "
                            + "only the {} oldest will fire, so this event bounds the work it can do on the publishing thread",
                    candidates.size(),
                    event.eventType(),
                    max,
                    max);
            candidates = candidates.subList(0, max);
        }

        String eventKey = eventKeyOf(event);
        String fingerprint = fingerprintOf(event);
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        for (AgentEventTrigger trigger : candidates) {
            try {
                if (!AgentEventTriggerFilter.matches(
                        AgentEventTriggerFilter.parse(trigger.getFilterJson(), objectMapper),
                        payload)) {
                    continue;
                }
                recordOutcome(
                        trigger,
                        event,
                        firing.fire(trigger.getId(), event, eventKey, fingerprint),
                        eventKey);
            } catch (DataIntegrityViolationException duplicate) {
                // V210's UNIQUE (trigger_id, event_key) absorbed an at-least-once
                // redelivery. The guard of record working, not an error - and
                // because the firing ran in its own REQUIRES_NEW transaction, the
                // webhook transaction that published this event is untouched.
                meterRegistry
                        .counter(
                                "agent.job.trigger.duplicate",
                                "eventType",
                                event.eventType(),
                                "by",
                                "EVENT_KEY")
                        .increment();
                log.debug(
                        "Agent event trigger {} has already handled event {} - the unique index absorbed the redelivery",
                        trigger.getId(),
                        eventKey);
            } catch (RuntimeException e) {
                // Per TRIGGER, not per event: one broken trigger (an unparseable
                // stored filter, a transient DB blip) must not stop the other
                // triggers subscribed to the same event from firing.
                meterRegistry.counter("agent.job.trigger.dispatch_error").increment();
                log.error(
                        "Agent event trigger {} (agent {}) failed on event {}",
                        trigger.getId(),
                        trigger.getAgentId(),
                        eventKey,
                        e);
            }
        }
    }

    private void recordOutcome(
            AgentEventTrigger trigger,
            WorkflowEvent event,
            AgentEventTriggerFiring.FireResult result,
            String eventKey) {
        switch (result.outcome()) {
            case FIRED -> {
                meterRegistry
                        .counter("agent.job.trigger.fired", "eventType", event.eventType())
                        .increment();
                log.info(
                        "Agent event trigger {} (agent {}) fired job {} for event {}",
                        trigger.getId(),
                        trigger.getAgentId(),
                        result.jobId(),
                        eventKey);
            }
            case LOOP_GUARD_TRIPPED -> {
                meterRegistry
                        .counter(
                                "agent.job.trigger.loop_guard_tripped",
                                "eventType",
                                event.eventType(),
                                "scope",
                                result.trippedScope() == null
                                        ? "unknown"
                                        : result.trippedScope().name())
                        .increment();
                log.warn(
                        "LOOP GUARD ({}): agent event trigger {} (agent {}, event type {}) tripped at {} firings in the "
                                + "last {} against a limit of {} (this trigger: {} in {}, {} in {}; whole tenant: {} in {}, "
                                + "{} in {}). The trigger has been DISABLED with disabled_reason=LOOP_GUARD_TRIPPED and will "
                                + "create no further jobs. A per-trigger trip usually means the agent's own actions publish "
                                + "the event it subscribes to; a TENANT-scope trip means the tenant's triggers are "
                                + "COLLECTIVELY over the tenant-wide spend ceiling (a fan-out, or triggers looping through "
                                + "each other). Break the cycle before re-saving the trigger.",
                        result.trippedScope(),
                        trigger.getId(),
                        trigger.getAgentId(),
                        event.eventType(),
                        firesForScope(result),
                        windowForScope(result),
                        trippedLimit(result),
                        result.counts().burst(),
                        properties.getTrigger().getWindow(),
                        result.counts().sustained(),
                        properties.getTrigger().getSustainedWindow(),
                        result.counts().tenantBurst(),
                        properties.getTrigger().getWindow(),
                        result.counts().tenantSustained(),
                        properties.getTrigger().getSustainedWindow());
                notifyLoopGuard(trigger, event, result);
            }
            case SKIPPED_DUPLICATE_PAYLOAD ->
                    meterRegistry
                            .counter(
                                    "agent.job.trigger.duplicate",
                                    "eventType",
                                    event.eventType(),
                                    "by",
                                    "PAYLOAD")
                            .increment();
            // Both skip arms are spelled out rather than left to a `default`, so
            // that adding an Outcome constant shows up here as an arm somebody
            // has to write instead of silently falling into "meter nothing".
            case SKIPPED_NO_IDENTITY ->
                    meterRegistry
                            .counter(
                                    "agent.job.trigger.skipped",
                                    "eventType",
                                    event.eventType(),
                                    "reason",
                                    "NO_IDENTITY")
                            .increment();
            case SKIPPED_DISABLED ->
                    meterRegistry
                            .counter(
                                    "agent.job.trigger.skipped",
                                    "eventType",
                                    event.eventType(),
                                    "reason",
                                    "DISABLED")
                            .increment();
        }
    }

    /**
     * Puts the loop guard in front of a human. A WARN log and a counter are what an operator finds
     * AFTER they go looking; this is what tells them to go looking, and the acceptance criterion
     * for this feature is specifically "a clear diagnostic rather than a bill".
     *
     * <p>Broadcast to tenant-admins ({@code null} recipient) and best-effort by contract - the same
     * posture {@code AgentScheduleService#notify} takes. A notification-write failure must never
     * become a second failure on top of a trigger that has already been safely disabled; the
     * disable is committed by the time this runs, so losing the notification costs visibility, not
     * money.
     */
    private void notifyLoopGuard(
            AgentEventTrigger trigger,
            WorkflowEvent event,
            AgentEventTriggerFiring.FireResult result) {
        boolean tenantScope =
                scopeOf(result) == AgentEventTriggerFiring.LoopGuardScope.TENANT_BURST
                        || scopeOf(result)
                                == AgentEventTriggerFiring.LoopGuardScope.TENANT_SUSTAINED;
        try {
            notificationService.create(
                    null,
                    NotificationCategory.SYSTEM,
                    "Agent event trigger disabled: firing loop detected",
                    (tenantScope
                                    ? "Across ALL of this workspace's agent triggers, "
                                            + firesForScope(result)
                                            + " jobs fired within "
                                            + windowForScope(result)
                                            + ", over the workspace-wide limit of "
                                            + trippedLimit(result)
                                            + ". This agent's trigger for '"
                                            + event.eventType()
                                            + "' is the one that crossed the line "
                                            + "and has been disabled; others will be disabled too if they keep firing. The usual cause is "
                                            + "several triggers feeding each other, or one event fanning out to many triggers."
                                    : "This agent's trigger for '"
                                            + event.eventType()
                                            + "' fired "
                                            + firesForScope(result)
                                            + " times within "
                                            + windowForScope(result)
                                            + ", over the limit of "
                                            + trippedLimit(result)
                                            + ". It has been disabled so it stops creating jobs and stops spending. The usual cause is "
                                            + "that the agent's own actions publish the event it is subscribed to, so each run triggers "
                                            + "the next.")
                            + " Break the cycle first - re-saving the trigger re-enables it and clears the counter.",
                    NotificationSeverity.ERROR,
                    "/agents/" + trigger.getAgentId(),
                    null);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record a loop-guard notification for agent event trigger {}",
                    trigger.getId(),
                    e);
        }
    }

    private long firesForScope(AgentEventTriggerFiring.FireResult result) {
        return switch (scopeOf(result)) {
            case BURST -> result.counts().burst();
            case SUSTAINED -> result.counts().sustained();
            case TENANT_BURST -> result.counts().tenantBurst();
            case TENANT_SUSTAINED -> result.counts().tenantSustained();
        };
    }

    private Object windowForScope(AgentEventTriggerFiring.FireResult result) {
        return switch (scopeOf(result)) {
            case BURST, TENANT_BURST -> properties.getTrigger().getWindow();
            case SUSTAINED, TENANT_SUSTAINED -> properties.getTrigger().getSustainedWindow();
        };
    }

    private int trippedLimit(AgentEventTriggerFiring.FireResult result) {
        return switch (scopeOf(result)) {
            case BURST -> properties.getTrigger().getMaxFiresPerWindow();
            case SUSTAINED -> properties.getTrigger().getMaxFiresPerSustainedWindow();
            case TENANT_BURST -> properties.getTrigger().getMaxTenantFiresPerWindow();
            case TENANT_SUSTAINED -> properties.getTrigger().getMaxTenantFiresPerSustainedWindow();
        };
    }

    /** Null-safe scope: BURST is the least-surprising default for a malformed result. */
    private static AgentEventTriggerFiring.LoopGuardScope scopeOf(
            AgentEventTriggerFiring.FireResult result) {
        return result.trippedScope() == null
                ? AgentEventTriggerFiring.LoopGuardScope.BURST
                : result.trippedScope();
    }

    /**
     * The EXACT idempotency key: {@code eventType|runId}.
     *
     * <p>{@code runId} alone would be enough for today's publishers, but the event type is included
     * so that two different event types sharing a run id - which the workflow engine's own events
     * do, and which a future allow-listed type might - can never collide on one trigger's ledger.
     *
     * <p><b>This key only deduplicates an event whose {@code runId} SURVIVES re-publication</b>,
     * and of the nine subscribable types exactly one does: {@code document.generation.completed},
     * which passes the document job's own id. The eight channel types go through {@code
     * WorkflowEventPublisher#publishNewRun}, which mints a fresh random UUID per call - so a
     * provider redelivery re-enters the controller and produces a DIFFERENT key that the unique
     * index cannot recognise. {@link #fingerprintOf} is what covers those; see its javadoc.
     */
    static String eventKeyOf(WorkflowEvent event) {
        String key = event.eventType() + "|" + event.runId();
        return key.length() <= MAX_EVENT_KEY_CHARS ? key : key.substring(0, MAX_EVENT_KEY_CHARS);
    }

    /**
     * The WINDOWED dedupe key: SHA-256 over the event type and the payload's canonicalised entries.
     * Deterministic across JVMs and replicas, which a {@code Map#hashCode} would not be.
     *
     * <p>This exists because the exact key above cannot see a channel redelivery (fresh {@code
     * runId} per delivery), the upstream Redis guard {@code InboundMessageDedupService} is
     * documented FAIL-OPEN, and two inbound paths - {@code XDmPollingJob} and {@code
     * EmailInboundPollingJob} - never call it at all. Without this, a retry after a Redis blip buys
     * a second billed agent job.
     *
     * <p>Deduplicating on payload equality loses nothing HERE, which is the only reason it is
     * acceptable: a channel payload is {@code {channel, fromMasked, bodyLength}} and that is the
     * entire information content a trigger can act on - the goal template can interpolate nothing
     * else - so two events with an identical payload would produce two jobs identical in every
     * observable respect. Returns null if hashing is unavailable, in which case only the exact key
     * applies.
     */
    static String fingerprintOf(WorkflowEvent event) {
        try {
            StringBuilder canonical = new StringBuilder(event.eventType()).append('\0');
            Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
            // Sorted, because a Map's iteration order is not part of its
            // identity and two deliveries must fingerprint the same.
            new TreeMap<>(payload)
                    .forEach(
                            (key, value) ->
                                    canonical.append(key).append('=').append(value).append('\0'));
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            log.warn(
                    "Could not fingerprint workflow event {} - falling back to the exact event key alone",
                    event.eventType(),
                    e);
            return null;
        }
    }
}
