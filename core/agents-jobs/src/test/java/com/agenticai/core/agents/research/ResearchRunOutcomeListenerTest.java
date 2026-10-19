package com.agenticai.core.agents.research;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A research run only keeps its daily slot when it produced a report. */
class ResearchRunOutcomeListenerTest {

    private ResearchRunUsageService usage;
    private ResearchRunOutcomeListener listener;

    @BeforeEach
    void setUp() {
        usage = mock(ResearchRunUsageService.class);
        AgentCapabilityStateRepository states = mock(AgentCapabilityStateRepository.class);
        AgentCapabilityState enabled = mock(AgentCapabilityState.class);
        when(enabled.isEnabled()).thenReturn(true);
        when(states.findById(any())).thenReturn(Optional.of(enabled));
        listener = new ResearchRunOutcomeListener(states, usage, new ObjectMapper());
    }

    private static AgentJob job(AgentJobStatus status, String resultJson) {
        AgentJob job = mock(AgentJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.getAgentId()).thenReturn(UUID.randomUUID());
        when(job.getTenantId()).thenReturn("t1");
        when(job.getRequestedBy()).thenReturn("alice");
        when(job.getOrigin()).thenReturn(AgentJobOrigin.API);
        when(job.getCreatedAt()).thenReturn(Instant.now());
        when(job.getStatus()).thenReturn(status);
        when(job.getResultJson()).thenReturn(resultJson);
        return job;
    }

    @Test
    void failedRunGivesItsSlotBack() {
        listener.onTerminal(job(AgentJobStatus.FAILED, null));
        verify(usage).release("t1", "alice");
    }

    @Test
    void cancelledRunGivesItsSlotBack() {
        listener.onTerminal(job(AgentJobStatus.CANCELLED, null));
        verify(usage).release("t1", "alice");
    }

    @Test
    void successWithoutAReportGivesItsSlotBack() {
        listener.onTerminal(job(AgentJobStatus.SUCCEEDED, "{\"title\":\"t\",\"reportMarkdown\":\"  \",\"sources\":[]}"));
        verify(usage).release("t1", "alice");
    }

    @Test
    void successWithAReportKeepsItsSlot() {
        listener.onTerminal(job(AgentJobStatus.SUCCEEDED, "{\"title\":\"t\",\"reportMarkdown\":\"# Hi\",\"sources\":[]}"));
        verify(usage, never()).release(any(), any());
    }

    @Test
    void runOlderThanTheWindowIsNotRefunded() {
        AgentJob old = job(AgentJobStatus.FAILED, null);
        when(old.getCreatedAt()).thenReturn(Instant.now().minusSeconds(25 * 3600));
        listener.onTerminal(old);
        verify(usage, never()).release(any(), any());
    }
}
