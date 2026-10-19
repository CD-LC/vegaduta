package com.agenticai.core.agents.lab;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCancelResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCapabilitiesResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Lab routes: capabilities, start a run, poll it, cancel it. The Run
 * Graph instrument needs none of these - it reads the playground's own step
 * events and forks through the existing session rewind route.
 *
 * <p>Thin on purpose. Access (RUNNER on the agent, the same bar as chatting
 * with it), the plan gate, validation and every status code are decided in
 * {@link AgentLabRunService}, which throws only types {@code
 * GlobalExceptionHandler} already maps: {@code IllegalArgumentException}
 * (400), {@code TenantLimitExceededException} (403), {@code
 * ResourceAccessDeniedException} (404) and {@code ResponseStatusException}
 * (404, 409, 429, 503). A new exception type would fall into that handler's
 * catch-all and come back as a 500 with an Issues-portal record.
 *
 * <p>No {@code SecurityConfig} entry: the path falls through to {@code
 * anyRequest().authenticated()}, like the quality and experiment routes.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/lab")
public class AgentLabController {

    private final AgentLabRunService runService;

    public AgentLabController(AgentLabRunService runService) {
        this.runService = runService;
    }

    @GetMapping("/capabilities")
    public LabCapabilitiesResponse capabilities(@PathVariable UUID agentId) {
        return runService.capabilities(agentId);
    }

    /** 202, not 200: the run is queued, and the body is its first poll result. */
    @PostMapping("/runs")
    public ResponseEntity<LabRunResponse> startRun(
        @PathVariable UUID agentId,
        @RequestBody LabRunRequest request,
        Principal principal
    ) {
        return ResponseEntity.accepted().body(runService.start(agentId, request, subjectOf(principal)));
    }

    @GetMapping("/runs/{runId}")
    public LabRunResponse getRun(@PathVariable UUID agentId, @PathVariable String runId, Principal principal) {
        return runService.get(agentId, runId, subjectOf(principal));
    }

    /** 202: this records a signal; the run stops before its next model call. */
    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<LabCancelResponse> cancelRun(
        @PathVariable UUID agentId, @PathVariable String runId, Principal principal
    ) {
        return ResponseEntity.accepted().body(runService.cancel(agentId, runId, subjectOf(principal)));
    }

    /** Null when there is no principal; the service then falls back to the tenant context's subject. */
    private static String subjectOf(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
