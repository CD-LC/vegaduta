package com.agenticai.core.agents.tools.backgroundjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code continue_in_background} turns "this needs more calls than one reply allows" into a queued
 * {@code agent_job} - the handoff that keeps a long task going under the normal per-turn ceilings.
 */
class BackgroundJobToolCallbackTest {

    private AgentJobService jobService;
    private UUID agentId;
    private BackgroundJobToolCallback tool;

    @BeforeEach
    void setUp() {
        jobService = mock(AgentJobService.class);
        agentId = UUID.randomUUID();
        tool = new BackgroundJobToolCallback(jobService, agentId);
    }

    private static AgentJobResponse queuedResponse(int maxTurns) {
        AgentJobResponse response = mock(AgentJobResponse.class);
        when(response.id()).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        when(response.status()).thenReturn(AgentJobStatus.QUEUED);
        when(response.maxTurns()).thenReturn(maxTurns);
        return response;
    }

    @Test
    void aGoalSubmitsTheJobAndHandsBackItsId() throws Exception {
        AgentJobResponse response = queuedResponse(8);
        when(jobService.submit(eq(agentId), any())).thenReturn(response);

        JsonNode out =
                new ObjectMapper()
                        .readTree(
                                tool.call(
                                        "{\"goal\":\"collect all 20 router IPs and list them\"}"));

        assertTrue(out.get("submitted").asBoolean());
        assertEquals("11111111-1111-1111-1111-111111111111", out.get("jobId").asText());
        assertEquals("QUEUED", out.get("status").asText());
        assertEquals(8, out.get("maxTurns").asInt());
        assertTrue(out.get("note").asText().contains("Jobs"));

        ArgumentCaptor<AgentJobStartRequest> captor =
                ArgumentCaptor.forClass(AgentJobStartRequest.class);
        verify(jobService).submit(eq(agentId), captor.capture());
        assertEquals("collect all 20 router IPs and list them", captor.getValue().goal());
    }

    @Test
    void aTurnsRequestReachesTheServiceForClamping() {
        AgentJobResponse response = queuedResponse(8);
        when(jobService.submit(eq(agentId), any())).thenReturn(response);

        tool.call("{\"goal\":\"keep scanning until done\",\"turns\":12}");

        ArgumentCaptor<AgentJobStartRequest> captor =
                ArgumentCaptor.forClass(AgentJobStartRequest.class);
        verify(jobService).submit(eq(agentId), captor.capture());
        assertEquals(12, captor.getValue().maxTurns());
    }

    @Test
    void aMissingGoalIsCleanTextNotAThrow() {
        String out = tool.call("{}");

        assertTrue(out.startsWith("Could not start a background job"));
        assertTrue(out.contains("goal"));
    }

    @Test
    void aServiceFailureIsCleanTextNotAThrow() {
        when(jobService.submit(eq(agentId), any()))
                .thenThrow(new IllegalStateException("the job queue is full"));

        String out = tool.call("{\"goal\":\"x\"}");

        assertTrue(out.contains("queue is full"));
        assertFalse(out.contains("submitted"));
    }

    @Test
    void theToolDefinitionTeachesTheStandaloneGoalContract() {
        String description = tool.getToolDefinition().description();

        assertTrue(description.contains("cannot see this conversation"));
        assertTrue(tool.getToolDefinition().inputSchema().contains("\"goal\""));
    }
}
