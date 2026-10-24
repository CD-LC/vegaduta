package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The lab's with-tools mode at the run level: a run that asks for the agent's tools is refused up front, with a
 * sentence, where the mode is off (as shipped); where it is on every cell's completion carries the request for
 * tools; and a run that does not ask is the tool-less run it always was.
 */
class AgentLabRunServiceWithToolsTest extends AgentLabRunServiceTestSupport {

    private LabRunRequest withTools(Boolean flag) {
        return new LabRunRequest("CONSISTENCY", java.util.List.of(), cells(2), "NONE", "NONE", flag);
    }

    @Test
    void capabilitiesReportWhetherTheToolsModeIsAvailable() {
        when(completion.withToolsAvailable()).thenReturn(false);
        assertFalse(service.capabilities(agentId).withToolsAvailable());

        when(completion.withToolsAvailable()).thenReturn(true);
        assertTrue(service.capabilities(agentId).withToolsAvailable());
    }

    @Test
    void aRunForToolsIsRefusedBeforeAnythingIsStoredWhenTheModeIsOff() {
        when(completion.withToolsAvailable()).thenReturn(false);

        IllegalArgumentException refused =
            assertThrows(IllegalArgumentException.class, () -> service.start(agentId, withTools(true), USER));

        assertTrue(refused.getMessage().contains("app.agent-lab.with-tools.enabled"), refused.getMessage());
        assertTrue(store.runs.isEmpty());
        assertTrue(store.locks.isEmpty(), "a refused run must not hold the caller's run lock");
    }

    @Test
    void whenTheModeIsOnEveryCellAsksTheCompletionForTools() {
        when(completion.withToolsAvailable()).thenReturn(true);

        LabRunResponse run = startAndRead(withTools(true));

        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, org.mockito.Mockito.times(2)).complete(sent.capture());
        assertTrue(sent.getAllValues().stream().allMatch(LabCompletionRequest::withTools));
    }

    @Test
    void aRunThatDoesNotAskStaysToollessWhateverTheModeIs() {
        when(completion.withToolsAvailable()).thenReturn(true);

        LabRunResponse run = startAndRead(withTools(null));

        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, org.mockito.Mockito.times(2)).complete(sent.capture());
        assertFalse(sent.getAllValues().stream().anyMatch(LabCompletionRequest::withTools));
    }

    @Test
    void anExplicitFalseIsTheSameAsNotAsking() {
        LabRunResponse run = startAndRead(withTools(false));

        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, org.mockito.Mockito.times(2)).complete(sent.capture());
        assertFalse(sent.getAllValues().stream().anyMatch(LabCompletionRequest::withTools));
    }
}
