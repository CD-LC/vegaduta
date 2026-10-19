package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.job.schedule.dto.AgentScheduleRequest;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleResponse;
import jakarta.validation.Valid;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleRunResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for an agent's cron trigger, shaped deliberately like {@code
 * /api/workflows/{id}/schedule} so an operator who has configured one has
 * already learned the other: PUT upserts, DELETE pauses (keeping the
 * configuration; {@code ?remove=true} deletes it), GET reads.
 *
 * <p>Thin by design - every access check, every validation and the whole firing
 * decision live in {@link AgentScheduleService}, so the background tick enforces
 * exactly the same rules without going through HTTP.
 *
 * <p>The two write verbs additionally require {@code ROLE_tenant-admin} in
 * {@code SecurityConfig}; GET deliberately falls through to
 * {@code anyRequest().authenticated()} plus the service's own VIEWER check,
 * because the response carries configuration and counters and never a secret -
 * the same read-open split {@code GET /api/workflows/*}{@code /schedule} uses.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/schedule")
public class AgentScheduleController {

    private final AgentScheduleService scheduleService;

    public AgentScheduleController(AgentScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /** Upsert: one schedule per agent, so there is no separate create verb to choose between. */
    @PutMapping
    public AgentScheduleResponse upsert(
        @PathVariable UUID agentId,
        @Valid @RequestBody AgentScheduleRequest request
    ) {
        return scheduleService.upsert(agentId, request);
    }

    /**
     * Without parameters this is a PAUSE, not a removal: it disables the trigger, keeps the
     * configuration and returns the row rather than 204 so the console re-renders the (still
     * fully configured, merely inactive) schedule from this response. With {@code ?remove=true}
     * the schedule and its run history are deleted for good and the answer is 204 (a later GET
     * is 404).
     */
    @DeleteMapping
    public ResponseEntity<AgentScheduleResponse> disable(
        @PathVariable UUID agentId,
        @RequestParam(defaultValue = "false") boolean remove
    ) {
        if (remove) {
            scheduleService.remove(agentId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(scheduleService.disable(agentId));
    }

    /** Re-enables a paused schedule with its stored configuration. */
    @PostMapping("/resume")
    public AgentScheduleResponse resume(@PathVariable UUID agentId) {
        return scheduleService.resume(agentId);
    }

    /**
     * Fires the schedule once now, through the same guards as the tick. A refused
     * run comes back 200 with {@code status: "SKIPPED"} and the reason in {@code error}.
     */
    @PostMapping("/run-now")
    public AgentScheduleRunResponse runNow(@PathVariable UUID agentId) {
        return scheduleService.runNow(agentId);
    }

    /** Recent runs, newest first (at most 50). */
    @GetMapping("/runs")
    public List<AgentScheduleRunResponse> runs(
        @PathVariable UUID agentId,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return scheduleService.listRuns(agentId, limit);
    }

    /**
     * 404 when the agent has no schedule at all - distinct from a schedule that
     * exists but is disabled, which returns 200 with {@code enabled: false}. The
     * console needs the difference: one offers "add a schedule", the other
     * "re-enable this one".
     */
    @GetMapping
    public ResponseEntity<AgentScheduleResponse> get(@PathVariable UUID agentId) {
        return scheduleService.get(agentId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
