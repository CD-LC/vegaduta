package com.agenticai.core.agents.job.trigger;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerRequest;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The CONFIGURATION half of event triggers. Firing lives in {@link
 * AgentEventTriggerDispatcher}/{@link AgentEventTriggerFiring} and shares no
 * code with this class on purpose: this one runs on an HTTP thread with a human
 * behind it and may throw 400s, the other runs on a webhook thread and must
 * never throw at all.
 *
 * <p><b>Everything that can be rejected is rejected HERE</b> - the event type
 * against the nine-item allow-list, every filter key and every goal-template
 * placeholder against the fields that event type actually carries. A trigger
 * that could never match, or a template that renders {@code (not provided)}, is
 * a support ticket weeks later with no evidence attached; a 400 while the
 * operator is still looking at the form costs nothing. This is the deliberate
 * difference from {@code AgentScheduleService#renderGoal}, which leaves an
 * unknown placeholder literal - a schedule has three possible placeholders and
 * fires within a day, a trigger has a per-event-type set and may not fire for
 * weeks.
 *
 * <p>EDITOR for writes, not RUNNER: configuring an unattended reaction to
 * external events is a change to what the agent IS, not a single invocation of
 * it. The {@code ROLE_tenant-admin} half of the gate lives in {@code
 * SecurityConfig}, exactly parallel to the agent schedule PUT/DELETE.
 */
@Service
public class AgentEventTriggerService {

    private static final Logger log = LoggerFactory.getLogger(AgentEventTriggerService.class);

    private final AgentEventTriggerRepository repository;
    private final AgentService agentService;
    private final AgentJobProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentEventTriggerPresenceCache presenceCache;
    private final TenantLimits tenantLimits;

    public AgentEventTriggerService(
        AgentEventTriggerRepository repository,
        AgentService agentService,
        AgentJobProperties properties,
        ObjectMapper objectMapper,
        AgentEventTriggerPresenceCache presenceCache,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.agentService = agentService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.presenceCache = presenceCache;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Creates or re-configures this agent's subscription to one event type.
     *
     * <p>Re-saving is also the ONLY way back from a loop-guard disable, and it
     * deliberately clears the window counters (see {@link
     * AgentEventTrigger#reconfigure}) - a trigger the guard just disabled sits at
     * the cap, so carrying the count forward would re-disable it on the very next
     * event and make the feature look broken.
     *
     * <p><b>A SUPERVISED agent is NOT refused here</b>, unlike a schedule. That
     * refusal exists because a parked scheduled run holds the overlap guard shut
     * and mutes the schedule entirely while everything reports itself healthy;
     * triggers have no overlap guard, so a triggered job that parks on its first
     * tool call is just a job waiting for a human, which is what SUPERVISED
     * means. It is worth knowing that a busy channel plus a SUPERVISED agent
     * produces one approval request per event, bounded by the firing cap.
     */
    public AgentEventTriggerResponse upsert(UUID agentId, AgentEventTriggerRequest request) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        // Plan gate - an event trigger is unattended automation (the agent
        // works with no human watching), which is a paid-tier entitlement
        // (plan_limits.automation_allowed). Enforced at the config boundary
        // so a downgraded tenant cannot keep authoring new unattended
        // reactions; an existing trigger that is merely disabled counts for
        // nothing until re-enabled by an upsert.
        requireAutomationAllowed();

        String eventType = requireSubscribable(request.eventType());
        String filterJson = blankToNull(request.filterJson());
        AgentEventTriggerFilter.requireUsable(filterJson, eventType, objectMapper);
        AgentEventTriggerGoal.requireRenderable(request.goalTemplate(), eventType);
        requireGoalFits(request.goalTemplate());

        Instant now = Instant.now();
        Optional<AgentEventTrigger> existing = repository.findByAgentIdAndEventType(agentId, eventType);
        AgentEventTrigger trigger;
        if (existing.isPresent()) {
            trigger = existing.get();
            trigger.reconfigure(filterJson, request.goalTemplate(), now);
        } else {
            requireAgentCapacity(agentId);
            trigger = AgentEventTrigger.create(
                UUID.randomUUID(), agentId, eventType, filterJson, request.goalTemplate(), currentSubject(), now);
        }
        repository.save(trigger);
        // A just-created trigger must fire on the very next event: without this,
        // the dispatcher's negative presence cache could sit on a stale "this
        // tenant has none" for up to its TTL.
        invalidatePresenceCache();
        log.info("Agent {} subscribed to event type '{}' (trigger {})", agentId, eventType, trigger.getId());
        return respond(trigger);
    }

    public List<AgentEventTriggerResponse> list(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return repository.findByAgentIdOrderByEventTypeAsc(agentId).stream().map(this::respond).toList();
    }

    /**
     * Turns one trigger off. Deliberately KEEPS the event type, filter and goal
     * template - re-enabling is then one PUT with the body the operator already
     * had, rather than a re-authoring exercise - and returns the row instead of
     * 204 so the console can render the now-disabled state without a follow-up
     * GET. Same shape, same reasoning, as {@code DELETE
     * /api/agents/{agentId}/schedule}.
     *
     * <p>A disabled trigger does not count against {@code
     * max-triggers-per-agent}, so keeping it costs the agent nothing.
     */
    public AgentEventTriggerResponse disable(UUID agentId, UUID triggerId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        AgentEventTrigger trigger = repository.findById(triggerId)
            .filter(candidate -> candidate.getAgentId().equals(agentId))
            .orElseThrow(() -> new NoSuchElementException(
                "No event trigger " + triggerId + " is configured for agent " + agentId));
        trigger.disable(AgentEventTriggerDisableReason.OPERATOR, Instant.now());
        repository.save(trigger);
        // Symmetry with upsert - a disable can only ADD empty answers, but
        // invalidating keeps the cache's contents trivially reasoned about:
        // every trigger write clears the tenant's entries, no exceptions.
        invalidatePresenceCache();
        return respond(trigger);
    }

    // --------------------------------------------------------------- internals

    private void requireAutomationAllowed() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.automationAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include unattended automation - upgrade your plan to configure event triggers."
            );
        }
    }

    private void invalidatePresenceCache() {
        TenantContext.current().map(TenantContext::getTenantId)
            .ifPresent(presenceCache::invalidateTenant);
    }

    /**
     * @throws IllegalArgumentException listing the nine. "Unknown event type"
     *     without the list is a 400 that tells the operator nothing they did not
     *     already know, and the correct value is not guessable.
     */
    private static String requireSubscribable(String eventType) {
        String trimmed = eventType == null ? "" : eventType.trim();
        if (!AgentTriggerEventTypes.isSubscribable(trimmed)) {
            throw new IllegalArgumentException(
                "Invalid trigger: '" + trimmed + "' is not an event this platform publishes. Subscribable event "
                    + "types are " + AgentTriggerEventTypes.subscribable() + ".");
        }
        return trimmed;
    }

    /**
     * The template must fit within the rendered-goal ceiling BEFORE anything is
     * interpolated - otherwise the operator's own words are what gets truncated
     * away at fire time, and they find out from a job whose goal stops
     * mid-sentence.
     */
    private void requireGoalFits(String goalTemplate) {
        int max = properties.getTrigger().getMaxGoalChars();
        if (goalTemplate.length() > max) {
            throw new IllegalArgumentException(
                "Invalid trigger: the goal template is " + goalTemplate.length() + " characters, over the "
                    + max + "-character limit (app.agent-jobs.trigger.max-goal-chars). Interpolated event values are "
                    + "added on top of this, so the template itself has to fit.");
        }
    }

    private void requireAgentCapacity(UUID agentId) {
        int max = properties.getTrigger().getMaxTriggersPerAgent();
        if (repository.countByAgentIdAndEnabledTrue(agentId) >= max) {
            throw new IllegalArgumentException(
                "This agent already has the maximum of " + max + " enabled event triggers. "
                    + "Disable one before adding another.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String currentSubject() {
        return TenantContext.current()
            .map(TenantContext::getSubject)
            .orElseThrow(() -> new IllegalStateException(
                "A trigger cannot be configured without an authenticated tenant context - every firing runs with "
                    + "the configuring human's identity, so there has to be one"));
    }

    private AgentEventTriggerResponse respond(AgentEventTrigger trigger) {
        return AgentEventTriggerResponse.from(
            trigger, AgentEventTriggerGoal.placeholdersFor(trigger.getEventType()));
    }
}
