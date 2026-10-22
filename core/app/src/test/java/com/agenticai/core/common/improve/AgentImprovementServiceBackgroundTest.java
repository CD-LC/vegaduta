package com.agenticai.core.common.improve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import com.agenticai.core.guardrails.procedures.ProcedureService;
import com.agenticai.core.providers.ProviderCoolingDownException;
import com.agenticai.core.providers.ProviderName;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prod 2026-10-05 (R1): the hourly sweep re-asked a refused provider for every unresolved issue
 * cluster, forever. The sweep's path uses the background completion; a cooling-down provider ends
 * the scan instead of failing once per cluster; the manual "generate" path is unchanged.
 */
class AgentImprovementServiceBackgroundTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentImprovementSuggestionRepository suggestions;
    private PlatformIssueRepository issues;
    private AssistService assist;
    private AgentImprovementService service;

    @BeforeEach
    void setUp() {
        suggestions = mock(AgentImprovementSuggestionRepository.class);
        issues = mock(PlatformIssueRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        assist = mock(AssistService.class);
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agent.getName()).thenReturn("Bot");
        when(agent.getSystemPrompt()).thenReturn("Be helpful.");
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        service =
                new AgentImprovementService(
                        suggestions,
                        issues,
                        agents,
                        mock(AgentService.class),
                        mock(ProcedureService.class),
                        assist,
                        new ObjectMapper());
    }

    private PlatformIssue cluster() {
        PlatformIssue issue = mock(PlatformIssue.class);
        when(issue.getId()).thenReturn(UUID.randomUUID());
        when(issue.getSource()).thenReturn(IssueSource.TOOL_CALL);
        when(issue.getSourceId()).thenReturn(AGENT_ID.toString());
        when(issue.getOccurrenceCount()).thenReturn(5);
        when(issue.getTitle()).thenReturn("t");
        when(issue.getMessage()).thenReturn("m");
        return issue;
    }

    private void twoOpenClusters() {
        PlatformIssue a = cluster();
        PlatformIssue b = cluster();
        when(issues.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
                .thenReturn(List.of(a, b));
    }

    @Test
    void theSweepPathUsesTheBackgroundCompletionNotThePersonDrivenOne() {
        twoOpenClusters();
        when(assist.completeInBackground(anyString(), anyString()))
                .thenReturn("{\"title\":\"NO_CLEAR_FIX\"}");

        service.generateForAgent(AGENT_ID, true);

        verify(assist, times(2)).completeInBackground(anyString(), anyString());
        verify(assist, never()).complete(anyString(), anyString());
    }

    @Test
    void theManualGeneratePathIsUnchanged() {
        twoOpenClusters();
        when(assist.complete(anyString(), anyString())).thenReturn("{\"title\":\"NO_CLEAR_FIX\"}");

        service.generateForAgent(AGENT_ID);

        verify(assist, times(2)).complete(anyString(), anyString());
        verify(assist, never()).completeInBackground(anyString(), anyString());
    }

    @Test
    void aCoolingDownProviderEndsTheScanAfterTheFirstClusterInsteadOfFailingOncePerCluster() {
        twoOpenClusters();
        when(assist.completeInBackground(anyString(), anyString()))
                .thenThrow(
                        new ProviderCoolingDownException(
                                ProviderName.GOOGLE_VERTEX, Instant.now(), Duration.ofHours(6)));

        assertThrows(
                ProviderCoolingDownException.class, () -> service.generateForAgent(AGENT_ID, true));

        verify(assist, times(1)).completeInBackground(anyString(), anyString());
        verify(suggestions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anOrdinaryFailureStillOnlySkipsThatCluster() {
        twoOpenClusters();
        when(assist.completeInBackground(anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(0, service.generateForAgent(AGENT_ID, true).size());

        verify(assist, times(2)).completeInBackground(anyString(), anyString());
    }
}
