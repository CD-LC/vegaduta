package com.agenticai.core.agents.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.edge.EdgeToolExecutionController.EdgeToolExecuteRequest;
import com.agenticai.core.agents.edge.EdgeToolExecutionController.EdgeToolExecuteResponse;
import com.agenticai.core.agents.edge.EdgeToolExecutionController.EdgeToolListResponse;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.devices.DeviceAuthResolver;
import com.agenticai.core.devices.DeviceException;
import com.agenticai.core.devices.MobileDevice;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * {@code GET /api/edge/tools} (the discovery half of {@link EdgeToolExecutionController}): the
 * capped {@code resolveToolsFor} path, the agent allow-list filter, the 404 propagation and both
 * credential kinds. Split out of {@link EdgeToolExecutionControllerTest}, which covers execute.
 */
class EdgeToolListingControllerTest extends EdgeToolExecutionControllerTestBase {

    // ------------------------------------------------------------------
    // GET /api/edge/tools - the discovery half

    @Test
    void listToolsReturnsEveryResolvedToolWithSchemaAsJsonObjectFullParity() {
        // Bug fix (D-9 follow-up): listTools now resolves through
        // resolveToolsFor (relevance-selected/hard-capped, same as a hosted
        // chat turn) rather than the uncapped resolveToolCandidatesFor - see
        // that method's javadoc for why. A short list like this one is well
        // under the cap, so nothing here is trimmed.
        when(agentToolProvider.resolveToolsFor(eq(AGENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                fakeTool("search_knowledge_base", i -> "x"),
                                fakeTool("remember_fact", i -> "x"),
                                // Full parity (2026-08-06): these now appear too - resolvable
                                // for a hosted turn means resolvable for an edge session.
                                fakeTool("google_gmail", i -> "x"),
                                fakeTool("run_code", i -> "x")));

        ResponseEntity<EdgeToolListResponse> httpResponse =
                controller.listTools(AGENT_ID, null, PRINCIPAL);
        assertEquals(HttpStatus.OK, httpResponse.getStatusCode());
        EdgeToolListResponse response = httpResponse.getBody();
        assertNotNull(response);

        assertEquals(4, response.tools().size());
        EdgeToolExecutionController.EdgeToolDescriptor first = response.tools().get(0);
        assertEquals("search_knowledge_base", first.name());
        assertEquals("test tool search_knowledge_base", first.description());
        // inputSchema is a real JSON object on the wire, never a double-encoded string.
        assertTrue(first.inputSchema().isObject());
        assertEquals("object", first.inputSchema().get("type").asText());
        assertEquals(
                List.of("search_knowledge_base", "remember_fact", "google_gmail", "run_code"),
                response.tools().stream()
                        .map(EdgeToolExecutionController.EdgeToolDescriptor::name)
                        .toList());

        // Same discovery-probe path as execute for the session/subject-key
        // shape: "edge:"-prefixed session id, principal as the subject key,
        // no userMessage/chatClient (nothing to score relevance against yet -
        // see listTools's javadoc).
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        verify(agentToolProvider)
                .resolveToolsFor(
                        eq(AGENT_ID),
                        sessionId.capture(),
                        eq("keycloak-user-123"),
                        eq(null),
                        eq(null),
                        eq(null));
        assertTrue(sessionId.getValue().startsWith("edge:"));
    }

    @Test
    void listToolsAppliesTheAgentAllowListAsTheOnlyRemainingFilter() {
        when(agent.getAllowedToolNames()).thenReturn(Set.of("remember_fact"));
        when(agentToolProvider.resolveToolsFor(eq(AGENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                fakeTool("search_knowledge_base", i -> "x"),
                                fakeTool("remember_fact", i -> "x")));

        EdgeToolListResponse response = controller.listTools(AGENT_ID, null, PRINCIPAL).getBody();

        assertNotNull(response);
        assertEquals(1, response.tools().size());
        assertEquals("remember_fact", response.tools().get(0).name());
    }

    @Test
    void listToolsNowIncludesMediaGenerationToolsWhenResolvable() {
        // Full parity (2026-08-06): media generation is no longer excluded by
        // construction from the edge listing.
        when(agentToolProvider.resolveToolsFor(eq(AGENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                fakeTool("generate_image", i -> "x"),
                                fakeTool("generate_video", i -> "x"),
                                fakeTool("speak", i -> "x"),
                                fakeTool("sarvam_translate", i -> "x")));

        EdgeToolListResponse response = controller.listTools(AGENT_ID, null, PRINCIPAL).getBody();

        assertNotNull(response);
        assertEquals(4, response.tools().size());
    }

    @Test
    void listToolsCapsAnUnboundedCandidateSetInsteadOfSendingItAllToTheOnDeviceModel() {
        // The actual bug this fix addresses: a freshly-created agent has an
        // empty allowedToolNames ("unrestricted"), so on a tenant with a big
        // tool catalog (many MCP connectors/custom tools) the OLD
        // resolveToolCandidatesFor call handed the on-device model's system
        // prompt every single one, full JSON schema included - blowing the
        // D-9 context budget on essentially every turn, new agent or old.
        // resolveToolsFor's relevance selector + hard cap (default 12) is
        // exactly what a real hosted turn already goes through - this proves
        // listTools now goes through the same bounded path rather than
        // returning an oversized list itself (the cap's own math is
        // ToolRelevanceSelector's tested concern, not this controller's).
        List<ToolCallback> cappedTwelve =
                java.util.stream.IntStream.range(0, 12)
                        .<ToolCallback>mapToObj(i -> fakeTool("tool_" + i, x -> "x"))
                        .toList();
        when(agentToolProvider.resolveToolsFor(eq(AGENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(cappedTwelve);

        EdgeToolListResponse response = controller.listTools(AGENT_ID, null, PRINCIPAL).getBody();

        assertNotNull(response);
        assertEquals(12, response.tools().size());
        // The uncapped lookup is never even consulted by listTools.
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }

    @Test
    void listToolsPropagatesThe404ExceptionsForMissingOrCrossTenantAgents() {
        UUID foreignAgentId = UUID.randomUUID();
        when(agentService.requireAccess(foreignAgentId, PermissionLevel.RUNNER))
                .thenThrow(new AgentNotFoundException(foreignAgentId));

        assertThrows(
                AgentNotFoundException.class,
                () -> controller.listTools(foreignAgentId, null, PRINCIPAL));
        verify(agentToolProvider, never())
                .resolveToolsFor(any(), any(), any(), any(), any(), any());

        UUID privateAgentId = UUID.randomUUID();
        when(agentService.requireAccess(privateAgentId, PermissionLevel.RUNNER))
                .thenThrow(new ResourceAccessDeniedException());

        assertThrows(
                ResourceAccessDeniedException.class,
                () -> controller.listTools(privateAgentId, null, PRINCIPAL));
    }
    @Test
    void validDeviceTokenCanListTools() {
        UUID deviceId = UUID.randomUUID();
        stubValidDeviceToken("device-token-abc", fakeDevice(deviceId));

        when(agentToolProvider.resolveToolsFor(eq(AGENT_ID), any(), any(), any(), any(), any()))
                .thenReturn(List.of(fakeTool("search_knowledge_base", i -> "x")));

        ResponseEntity<EdgeToolListResponse> response =
                controller.listTools(AGENT_ID, "device-token-abc", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().tools().size());
        assertEquals("search_knowledge_base", response.getBody().tools().get(0).name());

        ArgumentCaptor<String> subjectKey = ArgumentCaptor.forClass(String.class);
        verify(agentToolProvider)
                .resolveToolsFor(
                        eq(AGENT_ID), any(), subjectKey.capture(), eq(null), eq(null), eq(null));
        assertEquals("device:" + deviceId, subjectKey.getValue());
    }
    @Test
    void missingCredentialOfEitherKindIsUnauthorizedForListTools() {
        ResponseEntity<EdgeToolListResponse> response = controller.listTools(AGENT_ID, null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(agentService, never()).requireAccess(any(), any());
        verify(agentToolProvider, never())
                .resolveToolsFor(any(), any(), any(), any(), any(), any());
    }
    @Test
    void invalidDeviceTokenPropagatesDeviceExceptionForListTools() {
        when(deviceAuthResolver.run(eq("bad-token"), any()))
                .thenThrow(new DeviceException("Unknown or revoked device token."));

        assertThrows(
                DeviceException.class, () -> controller.listTools(AGENT_ID, "bad-token", null));
        verify(agentService, never()).requireAccess(any(), any());
    }
}
