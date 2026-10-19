package com.agenticai.core.agents.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.devices.DeviceAuthResolver;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.tenancy.EdgeAgentPolicyLookup.EdgeAgentPolicyView;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Edge Wave 2 (EDGE-A/EDGE-B) — the per-agent policy/guardrail lookup behind
 * {@code GET /api/edge/config?agentId=...}: credential gating mirrors the edge tool
 * endpoints' safe subset, every failure collapses to null (anti-enumeration on a permitAll
 * path), and the pattern-representability verdict matches the client guardrail module's
 * scope. Plain Mockito unit tests, same convention as
 * {@code EdgeTurnTelemetryControllerTest} — no web layer, no DB.
 */
class EdgeAgentPolicyLookupServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final Principal PRINCIPAL = () -> "keycloak-user-123";

    private AgentService agentService;
    private DeviceAuthResolver deviceAuthResolver;
    private EdgeAgentPolicyLookupService service;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        deviceAuthResolver = mock(DeviceAuthResolver.class);
        service = new EdgeAgentPolicyLookupService(agentService, deviceAuthResolver);
        agent = mock(Agent.class);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of());
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(agent);
    }

    @Test
    void keycloakCallerGetsPolicyAndGuardrailFacts() {
        when(agent.getEdgeRoutingPolicy()).thenReturn(EdgeRoutingPolicy.EDGE_ONLY);
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("pii", "jailbreak"));

        EdgeAgentPolicyView view = service.lookup(AGENT_ID, PRINCIPAL, null);

        assertNotNull(view);
        assertEquals("EDGE_ONLY", view.edgeRoutingPolicy());
        assertTrue(view.guardrailsEnabled());
        // pii + jailbreak are exactly the pattern-portable pair.
        assertTrue(view.guardrailsPatternRepresentable());
    }

    @Test
    void semanticGuardrailsAreReportedAsNotPatternRepresentable() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("pii", "toxicity"));

        EdgeAgentPolicyView view = service.lookup(AGENT_ID, PRINCIPAL, null);

        assertNotNull(view);
        assertTrue(view.guardrailsEnabled());
        assertFalse(view.guardrailsPatternRepresentable());
    }

    @Test
    void noPolicyAndNoGuardrailsIsTheHonestDefaultView() {
        EdgeAgentPolicyView view = service.lookup(AGENT_ID, PRINCIPAL, null);

        assertNotNull(view);
        assertNull(view.edgeRoutingPolicy());
        assertFalse(view.guardrailsEnabled());
        // Vacuously representable — nothing to represent.
        assertTrue(view.guardrailsPatternRepresentable());
    }

    @Test
    void anonymousPrincipalWithoutDeviceTokenResolvesNullWithoutAnyAgentLookup() {
        Principal anonymous = new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertNull(service.lookup(AGENT_ID, anonymous, null));
        assertNull(service.lookup(AGENT_ID, null, null));
        verify(agentService, never()).requireAccess(any(), any());
    }

    @Test
    void accessDeniedCollapsesToNullNotAnException() {
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER))
            .thenThrow(new ResourceAccessDeniedException());

        assertNull(service.lookup(AGENT_ID, PRINCIPAL, null));
    }

    @Test
    void nullAgentIdIsANoOp() {
        assertNull(service.lookup(null, PRINCIPAL, null));
        verify(agentService, never()).requireAccess(any(), any());
    }
}
