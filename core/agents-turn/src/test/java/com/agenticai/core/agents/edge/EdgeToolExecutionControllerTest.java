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
 * The edge propose/execute tool bridge (including device-token support and the
 * full-tool-parity change - see {@link EdgeToolExecutionController}'s class
 * javadoc), and the D-9 follow-up bug fix (listTools' resolution path capped - see that method's
 * javadoc). {@code GET /api/edge/tools} and {@code POST /api/edge/tools/execute}'s authorization
 * layers and result contract. Plain unit tests calling the controller method directly (the module's
 * established convention); the decorator stack itself (guardrails/logging) is {@code
 * AgentToolProvider}'s own tested concern - here the resolved callback is faked, and what's under
 * test is that the controller (a) executes ANY tool {@code resolveToolCandidatesFor} resolves and
 * lists the same tools {@code resolveToolsFor}'s relevance-selected/capped set resolves, subject
 * ONLY to the agent's own allow-list (the old media-generation/MCP whitelist is gone - full parity
 * with hosted), with consumer-safe 4xx messages for what IS still refused, (b) executes through the
 * resolved callback under an {@code "edge:"}-prefixed session id (the tool_call_log origin marker),
 * (c) lets the tenancy/sharing 404s ({@code AgentNotFoundException}/{@code
 * ResourceAccessDeniedException}) propagate to {@code GlobalExceptionHandler} untouched, and (d)
 * accepts EITHER a Keycloak JWT (unchanged) or a valid {@code X-Device-Token} as the authenticating
 * credential, rejecting a request that presents neither (or an invalid device token) before either
 * agent lookup runs.
 */
class EdgeToolExecutionControllerTest extends EdgeToolExecutionControllerTestBase {

    // ------------------------------------------------------------------
    // (a) Existing Keycloak-JWT browser callers still work unchanged

    @Test
    void whitelistedToolExecutesThroughResolvedCallbackUnderEdgeSessionId() {
        ToolCallback tool =
                fakeTool("search_knowledge_base", input -> "Found 2 matching documents.");
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of(tool));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(
                                AGENT_ID, "search_knowledge_base", "{\"query\":\"invoices\"}"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        EdgeToolExecuteResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.ok());
        assertEquals("Found 2 matching documents.", body.resultJson());
        assertNull(body.error());

        // The origin marker: resolution (and therefore LoggingToolCallback's
        // persisted session_id) runs under an "edge:"-prefixed session, and
        // the subject key is the authenticated principal (stable memory
        // identity), same rule as AgentController#chat.
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectKey = ArgumentCaptor.forClass(String.class);
        verify(agentToolProvider)
                .resolveToolCandidatesFor(eq(AGENT_ID), sessionId.capture(), subjectKey.capture());
        assertTrue(sessionId.getValue().startsWith("edge:"));
        assertEquals("keycloak-user-123", subjectKey.getValue());

        // The JWT path never touches the device-token resolver.
        verify(deviceAuthResolver, never()).run(any(), any());
    }

    @Test
    void keycloakPrincipalTakesPriorityOverAnyDeviceTokenAlsoPresent() {
        // A caller presenting BOTH a valid session and a device token (should
        // never happen from a real client, but must not become a downgrade
        // path) is authenticated via the JWT - the device resolver is never
        // consulted.
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of(fakeTool("search_knowledge_base", i -> "x")));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                        "some-device-token",
                        PRINCIPAL);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deviceAuthResolver, never()).run(any(), any());
    }

    // ------------------------------------------------------------------
    // Full tool parity with hosted (2026-08-06): a formerly-excluded name
    // (media generation, an MCP-shaped tool, a Google-workspace write) now
    // executes/lists exactly like any other resolvable tool, subject only to
    // the agent's own allow-list.

    @Test
    void formerlyExcludedToolsNowExecuteWhenTheProviderResolvesThem() {
        for (String name :
                List.of(
                        "generate_image",
                        "generate_video",
                        "speak",
                        "google_gmail",
                        "mcp__brave__search")) {
            ToolCallback tool = fakeTool(name, input -> "ok:" + name);
            when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                    .thenReturn(List.of(tool));

            ResponseEntity<EdgeToolExecuteResponse> response =
                    controller.execute(
                            new EdgeToolExecuteRequest(AGENT_ID, name, "{}"), null, PRINCIPAL);

            assertEquals(HttpStatus.OK, response.getStatusCode(), name);
            assertTrue(response.getBody().ok(), name);
            assertEquals("ok:" + name, response.getBody().resultJson(), name);
        }
    }

    @Test
    void aToolTheProviderNeverResolvesForThisAgentIsAConflictNotAForbidden() {
        // Not "this tool is on-device-forbidden" (that whole gate is gone) -
        // "this agent's own configuration doesn't currently offer it," the
        // same 409 every other unresolvable-but-allowed tool gets.
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of());

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "google_gmail", "{}"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                "That tool isn't available for this agent right now.", response.getBody().error());
    }

    // ------------------------------------------------------------------
    // Agent allow-list (the ONLY remaining tool-scope gate)

    @Test
    void agentAllowListIsTheOnlyRemainingToolScopeGate() {
        when(agent.getAllowedToolNames()).thenReturn(Set.of("remember_fact"));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(
                                AGENT_ID, "search_knowledge_base", "{\"query\":\"x\"}"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("This agent doesn't have access to that tool.", response.getBody().error());
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Cross-tenant / missing / unshared agent

    @Test
    void crossTenantOrMissingAgentPropagatesThe404Exceptions() {
        UUID foreignAgentId = UUID.randomUUID();
        when(agentService.requireAccess(foreignAgentId, PermissionLevel.RUNNER))
                .thenThrow(new AgentNotFoundException(foreignAgentId));

        // Propagates untouched - GlobalExceptionHandler maps it to 404
        // (anti-enumeration: indistinguishable from a genuinely absent id).
        assertThrows(
                AgentNotFoundException.class,
                () ->
                        controller.execute(
                                new EdgeToolExecuteRequest(
                                        foreignAgentId, "search_knowledge_base", "{}"),
                                null,
                                PRINCIPAL));
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }

    @Test
    void unsharedPrivateAgentPropagatesResourceAccessDenied() {
        UUID privateAgentId = UUID.randomUUID();
        when(agentService.requireAccess(privateAgentId, PermissionLevel.RUNNER))
                .thenThrow(new ResourceAccessDeniedException());

        assertThrows(
                ResourceAccessDeniedException.class,
                () ->
                        controller.execute(
                                new EdgeToolExecuteRequest(
                                        privateAgentId, "search_knowledge_base", "{}"),
                                null,
                                PRINCIPAL));
    }

    // ------------------------------------------------------------------
    // Input validation + degraded states

    @Test
    void missingAgentIdOrToolNameIs400() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                controller
                        .execute(
                                new EdgeToolExecuteRequest(null, "search_knowledge_base", "{}"),
                                null,
                                PRINCIPAL)
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                controller
                        .execute(new EdgeToolExecuteRequest(AGENT_ID, "  ", "{}"), null, PRINCIPAL)
                        .getStatusCode());
    }

    @Test
    void malformedArgumentsJsonIs400() {
        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{not json"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("argumentsJson must be a valid JSON document.", response.getBody().error());
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }

    @Test
    void whitelistedButCurrentlyUnresolvableToolIs409() {
        // Whitelisted and allow-listed, but this agent's configuration doesn't
        // currently offer it (e.g. knowledge base disabled).
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of());

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                "That tool isn't available for this agent right now.", response.getBody().error());
    }

    @Test
    void toolExecutionFailureReturnsOkFalseWithConsumerSafeMessage() {
        ToolCallback failing =
                fakeTool(
                        "search_knowledge_base",
                        input -> {
                            throw new IllegalStateException(
                                    "pgvector connection refused at 10.0.0.7:5432");
                        });
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of(failing));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        EdgeToolExecuteResponse body = response.getBody();
        assertFalse(body.ok());
        assertNull(body.resultJson());
        // Consumer-safe: no internal details leak into the client-facing error.
        assertFalse(body.error().contains("pgvector"));
        assertFalse(body.error().contains("10.0.0.7"));
    }


    @Test
    void blankArgumentsDefaultToEmptyObject() {
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("recall_related_facts")
                                .description("d")
                                .inputSchema("{\"type\":\"object\"}")
                                .build());
        when(tool.call(input.capture())).thenReturn("[]");
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of(tool));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "recall_related_facts", null),
                        null,
                        PRINCIPAL);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().ok());
        assertEquals("{}", input.getValue());
    }

    // ------------------------------------------------------------------
    // (b) A valid X-Device-Token can now authenticate both endpoints

    @Test
    void validDeviceTokenCanExecuteAWhitelistedTool() {
        UUID deviceId = UUID.randomUUID();
        stubValidDeviceToken("device-token-abc", fakeDevice(deviceId));

        ToolCallback tool =
                fakeTool("search_knowledge_base", input -> "Found 1 matching document.");
        when(agentToolProvider.resolveToolCandidatesFor(eq(AGENT_ID), any(), any()))
                .thenReturn(List.of(tool));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(
                                AGENT_ID, "search_knowledge_base", "{\"query\":\"invoices\"}"),
                        "device-token-abc",
                        null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        EdgeToolExecuteResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.ok());
        assertEquals("Found 1 matching document.", body.resultJson());

        // Same "edge:" origin marker as the JWT path; subject key is now the
        // stable per-device identity, not a Keycloak subject.
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectKey = ArgumentCaptor.forClass(String.class);
        verify(agentToolProvider)
                .resolveToolCandidatesFor(eq(AGENT_ID), sessionId.capture(), subjectKey.capture());
        assertTrue(sessionId.getValue().startsWith("edge:"));
        assertEquals("device:" + deviceId, subjectKey.getValue());
    }


    @Test
    void deviceTokenCallerStillGoesThroughTheSameAllowListLayer() {
        // The device-token path is not a shortcut around layer 3 (the agent's
        // own allow-list) - it hits exactly the same check a JWT-authenticated
        // call does.
        stubValidDeviceToken("device-token-abc", fakeDevice(UUID.randomUUID()));
        when(agent.getAllowedToolNames()).thenReturn(Set.of("remember_fact"));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "google_gmail", "{}"),
                        "device-token-abc",
                        null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("This agent doesn't have access to that tool.", response.getBody().error());
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // (c) Invalid/missing credentials of either kind are rejected

    @Test
    void missingCredentialOfEitherKindIsUnauthorizedForExecute() {
        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                        null,
                        null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(agentService, never()).requireAccess(any(), any());
        verify(agentToolProvider, never()).resolveToolCandidatesFor(any(), any(), any());
    }


    @Test
    void anonymousAuthenticationTokenIsNotTreatedAsAKeycloakSession() {
        // Now that this path is permitAll() at the Spring Security layer (see
        // SecurityConfig), Spring's default AnonymousAuthenticationFilter
        // still populates a non-null Principal (getName() == "anonymousUser")
        // for a request presenting no credential at all. That must NOT be
        // treated as an authenticated Keycloak session.
        Principal anonymous =
                new AnonymousAuthenticationToken(
                        "key",
                        "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        ResponseEntity<EdgeToolExecuteResponse> response =
                controller.execute(
                        new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                        null,
                        anonymous);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(agentService, never()).requireAccess(any(), any());
        verify(deviceAuthResolver, never()).run(any(), any());
    }

    @Test
    void invalidDeviceTokenPropagatesDeviceExceptionForExecute() {
        when(deviceAuthResolver.run(eq("bad-token"), any()))
                .thenThrow(new DeviceException("Unknown or revoked device token."));

        assertThrows(
                DeviceException.class,
                () ->
                        controller.execute(
                                new EdgeToolExecuteRequest(AGENT_ID, "search_knowledge_base", "{}"),
                                "bad-token",
                                null));
        verify(agentService, never()).requireAccess(any(), any());
    }
}
