package com.agenticai.core.quality.simulation;

import com.agenticai.core.quality.simulation.SimulationDtos.CreatePersonaRequest;
import com.agenticai.core.quality.simulation.SimulationDtos.CreateSessionRequest;
import com.agenticai.core.quality.simulation.SimulationDtos.PersonaResponse;
import com.agenticai.core.quality.simulation.SimulationDtos.RunDetailResponse;
import com.agenticai.core.quality.simulation.SimulationDtos.RunSummaryResponse;
import com.agenticai.core.quality.simulation.SimulationDtos.SessionResponse;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for simulation / adversarial persona testing, nested under
 * the same {@code .../quality} URL family as the eval gate and datasets:
 *
 * <ul>
 *   <li>GET    /api/agents/{agentId}/quality/simulations/personas - built-in + tenant custom</li>
 *   <li>POST   /api/agents/{agentId}/quality/simulations/personas - create a custom persona</li>
 *   <li>POST   /api/agents/{agentId}/quality/simulations/sessions - trigger a run</li>
 *   <li>GET    /api/agents/{agentId}/quality/simulations/sessions - list, newest first</li>
 *   <li>GET    /api/agents/{agentId}/quality/simulations/sessions/{id} - one session</li>
 *   <li>GET    /api/agents/{agentId}/quality/simulations/sessions/{id}/runs - that session's runs (summary)</li>
 *   <li>GET    /api/agents/{agentId}/quality/simulations/runs/{id} - one run, full detail incl. transcript</li>
 * </ul>
 *
 * <p>Follows {@code EvalGateController}'s conventions: no extra security
 * annotations, no {@code SecurityConfig} change - {@code /api/**} already
 * falls through to authenticated. Custom personas are tenant-wide (not
 * scoped to {@code agentId}), matching {@link SimulationPersonaService}'s
 * own scope - the {@code agentId} in the path is kept for URL consistency
 * with the rest of this feature and because {@code POST .../personas} sits
 * naturally beside the session-trigger endpoint a caller will use it from.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality/simulations")
public class SimulationController {

    private final SimulationPersonaService personaService;
    private final SimulationRunService runService;

    public SimulationController(SimulationPersonaService personaService, SimulationRunService runService) {
        this.personaService = personaService;
        this.runService = runService;
    }

    /** Setter-injected so the two-arg constructor stays as it was; absent = the report answers 404. */
    private com.agenticai.core.quality.simulation.attack.AttackReportService attackReportService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAttackReportService(com.agenticai.core.quality.simulation.attack.AttackReportService attackReportService) {
        this.attackReportService = attackReportService;
    }

    /**
     * GET /sessions/{sessionId}/attack-report - attack success rate over the session's built-in
     * attack-scenario runs. 404 when app.quality.simulation.attack-scenarios.enabled=false or the
     * session is missing/mismatched.
     */
    @GetMapping("/sessions/{sessionId}/attack-report")
    public SimulationDtos.AttackReportResponse attackReport(@PathVariable UUID agentId, @PathVariable UUID sessionId) {
        if (attackReportService == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.NOT_FOUND, "Attack scenarios are not enabled");
        }
        return attackReportService.report(agentId, sessionId);
    }

    @GetMapping("/personas")
    public List<PersonaResponse> listPersonas(@PathVariable UUID agentId) {
        return personaService.listAll().stream().map(PersonaResponse::of).toList();
    }

    @PostMapping("/personas")
    public ResponseEntity<PersonaResponse> createPersona(
        @PathVariable UUID agentId, @RequestBody CreatePersonaRequest request
    ) {
        SimulationPersona created = personaService.createCustom(
            request.key(), request.name(), request.description(), request.objective());
        return ResponseEntity.status(HttpStatus.CREATED).body(
            new PersonaResponse(created.getKey(), created.getName(), created.getDescription(), created.getObjective(), false));
    }

    /**
     * DELETE /personas/{key} - removes a CUSTOM persona (V344). Built-ins
     * are code, not rows (V266 header), so deleting a built-in key is a
     * 400 rather than a silent no-op; an unknown key is 404-via-400 the
     * same way (keys are opaque - no existence oracle worth preserving).
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/personas/{key}")
    public ResponseEntity<Void> deletePersona(@PathVariable UUID agentId, @PathVariable String key) {
        personaService.deleteCustom(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> createSession(
        @PathVariable UUID agentId, @RequestBody CreateSessionRequest request
    ) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        SimulationSession session = runService.createSession(
            agentId, request.personaKeys(), request.maxTurns() == null ? 6 : request.maxTurns(), actor,
            request.budgetUsd());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.of(session));
    }

    /**
     * POST /sessions/{sessionId}/cancel - cooperative stop (V344): live
     * runs observe the flag between turns and end CANCELLED; a session
     * that drained on a cancel reports CANCELLED, not COMPLETED.
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public SessionResponse cancelSession(@PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return SessionResponse.of(runService.cancelSession(agentId, sessionId));
    }

    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(@PathVariable UUID agentId) {
        return runService.listSessions(agentId).stream().map(SessionResponse::of).toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return SessionResponse.of(runService.getSession(agentId, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/runs")
    public List<RunSummaryResponse> listRuns(@PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return runService.listRuns(agentId, sessionId).stream().map(RunSummaryResponse::of).toList();
    }

    @GetMapping("/runs/{runId}")
    public RunDetailResponse getRun(@PathVariable UUID agentId, @PathVariable UUID runId) {
        return RunDetailResponse.of(runService.getRun(agentId, runId));
    }
}
