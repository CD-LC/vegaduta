package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCancelResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCapabilitiesResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** The lab routes are a thin shell: they pass the principal's name along and answer 202 for the two signals. */
class AgentLabControllerTest {

    private final AgentLabRunService runService = mock(AgentLabRunService.class);
    private final AgentLabController controller = new AgentLabController(runService);
    private final UUID agentId = UUID.randomUUID();
    private final Principal principal = () -> "u1";

    private static LabRunResponse run(String runId, String status) {
        return new LabRunResponse(
            runId, UUID.randomUUID().toString(), "CONSISTENCY", status, 3, 0, false, null, 1L, 1L, null,
            List.of(), null);
    }

    @Test
    void startAnswers202WithTheQueuedRun() {
        LabRunRequest request = new LabRunRequest(
            "CONSISTENCY", List.of(), List.of(new LabCellRequest("k0", null, "Q", null, null)), "NONE", "JUDGE");
        LabRunResponse queued = run("run-1", "QUEUED");
        when(runService.start(agentId, request, "u1")).thenReturn(queued);

        ResponseEntity<LabRunResponse> response = controller.startRun(agentId, request, principal);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertSame(queued, response.getBody());
    }

    @Test
    void cancelAnswers202() {
        when(runService.cancel(agentId, "run-1", "u1")).thenReturn(new LabCancelResponse("run-1", true));

        ResponseEntity<LabCancelResponse> response = controller.cancelRun(agentId, "run-1", principal);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(new LabCancelResponse("run-1", true), response.getBody());
    }

    @Test
    void getReturnsTheCallersRun() {
        LabRunResponse running = run("run-1", "RUNNING");
        when(runService.get(agentId, "run-1", "u1")).thenReturn(running);

        assertSame(running, controller.getRun(agentId, "run-1", principal));
    }

    @Test
    void capabilitiesDelegate() {
        LabCapabilitiesResponse caps = new LabCapabilitiesResponse(false, "PLAN", 60, 120, 16, 8, 60, false);
        when(runService.capabilities(agentId)).thenReturn(caps);

        assertSame(caps, controller.capabilities(agentId));
    }

    @Test
    void serviceStatusesPassThroughUntouched() {
        when(runService.get(agentId, "gone", "u1"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Lab run not found or expired."));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> controller.getRun(agentId, "gone", principal));

        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void aMissingPrincipalIsPassedAsNullForTheServiceToResolve() {
        controller.getRun(agentId, "run-1", null);

        verify(runService).get(agentId, "run-1", null);
    }
}
