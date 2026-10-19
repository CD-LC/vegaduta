package com.agenticai.core.agents.job.trigger;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerRequest;
import com.agenticai.core.agents.job.trigger.dto.AgentEventTriggerResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for an agent's event subscriptions, shaped deliberately like
 * {@code /api/agents/{agentId}/schedule} so an operator who has configured one
 * has already learned the other: PUT upserts, DELETE disables (keeping the
 * configuration), GET reads.
 *
 * <p>The one shape difference is that an agent has ONE schedule but may have
 * several triggers - one per event type - so this is a collection: GET returns a
 * list and DELETE names a trigger id.
 *
 * <p>Thin by design - every access check, every validation and the whole firing
 * decision live in {@link AgentEventTriggerService} and {@link
 * AgentEventTriggerFiring}, so the event dispatcher enforces exactly the same
 * rules without going through HTTP.
 *
 * <p>The two write verbs additionally require {@code ROLE_tenant-admin} in
 * {@code SecurityConfig} ({@code PUT}/{@code DELETE
 * /api/agents/*}{@code /triggers/**}); GET deliberately falls through to
 * {@code anyRequest().authenticated()} plus the service's own VIEWER check,
 * because the response carries configuration and counters and never a secret.
 * The split is the same one schedules use, and for the same reason: configuring
 * unattended execution is a bigger grant than running an agent once.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/triggers")
public class AgentEventTriggerController {

    private final AgentEventTriggerService triggerService;

    public AgentEventTriggerController(AgentEventTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    /** Upsert, keyed on {@code (agentId, eventType)} - so re-binding the same event type edits rather than duplicating. */
    @PutMapping
    public AgentEventTriggerResponse upsert(
        @PathVariable UUID agentId,
        @Valid @RequestBody AgentEventTriggerRequest request
    ) {
        return triggerService.upsert(agentId, request);
    }

    @GetMapping
    public ResponseEntity<List<AgentEventTriggerResponse>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            triggerService.list(agentId),
            limit, offset, 50, 500);
    }

    /**
     * Disables the trigger and returns the resulting row rather than 204: the
     * console re-renders the (still fully configured, merely inactive) trigger
     * from this response instead of issuing a follow-up GET.
     */
    @DeleteMapping("/{triggerId}")
    public AgentEventTriggerResponse disable(@PathVariable UUID agentId, @PathVariable UUID triggerId) {
        return triggerService.disable(agentId, triggerId);
    }

    /**
     * The subscribable event types and, per type, the placeholders a goal
     * template may use.
     *
     * <p>Served rather than left to documentation so the console's trigger form
     * can populate its event-type picker and its template help from the same
     * source the validator uses. A hard-coded client-side copy of the nine
     * payload shapes drifts the first time a publisher adds a field, and the
     * operator's first sign of the drift is a 400 they cannot explain.
     */
    @GetMapping("/event-types")
    public Map<String, Set<String>> eventTypes(@PathVariable UUID agentId) {
        // No access check and no agentId use: this is a static description of the
        // platform's own event catalogue, identical for every agent and every
        // tenant, and it contains nothing about this agent at all. The path
        // variable exists only so the console can keep one base URL.
        return AgentTriggerEventTypes.subscribable().stream()
            .collect(Collectors.toMap(
                type -> type,
                AgentEventTriggerGoal::placeholdersFor,
                (a, b) -> a,
                LinkedHashMap::new));
    }
}
