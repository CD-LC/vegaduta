package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Agent Card shape for both the platform card ({@code /.well-known/...}) and
 * the per-agent card - the fields an A2A client of either generation (0.3
 * {@code url}/{@code preferredTransport}, 1.0 {@code supportedInterfaces})
 * reads before its first call. Plain unit tests, same convention as the rest
 * of {@code core/agents}' controller/service tests.
 */
class A2aAgentCardServiceTest {

    private A2aProperties properties;
    private A2aAgentCardService service;

    @BeforeEach
    void setUp() {
        properties = new A2aProperties();
        properties.setVersion("2.3.4");
        // Signing off in these tests keeps the existing assertions about the
        // unsigned card shape; the signatures themselves are covered by
        // A2aCardSigningServiceTest.
        properties.getSigning().setEnabled(false);
        service = new A2aAgentCardService(properties, new A2aCardSigningService(properties));
    }

    @Test
    void platformCardHasTheRequiredFieldsAndNoSkills() {
        Map<String, Object> card = service.platformCard("https://api.example.test");

        assertEquals("VegaDuta", card.get("name"));
        assertEquals("2.3.4", card.get("version"));
        assertEquals("1.0", card.get("protocolVersion"));
        assertEquals("https://api.example.test/api/a2a", card.get("url"));
        assertEquals("JSONRPC", card.get("preferredTransport"));

        List<?> interfaces = (List<?>) card.get("supportedInterfaces");
        assertEquals(2, interfaces.size());
        Map<?, ?> rest = (Map<?, ?>) interfaces.get(1);
        assertEquals("https://api.example.test/api/a2a/rest", rest.get("url"));
        assertEquals("HTTP+JSON", rest.get("protocolBinding"));
        assertEquals("1.0", rest.get("protocolVersion"));
        Map<?, ?> iface = (Map<?, ?>) interfaces.get(0);
        assertEquals("https://api.example.test/api/a2a", iface.get("url"));
        assertEquals("JSONRPC", iface.get("protocolBinding"));
        assertEquals("1.0", iface.get("protocolVersion"));

        Map<?, ?> capabilities = (Map<?, ?>) card.get("capabilities");
        assertEquals(true, capabilities.get("streaming"));
        assertEquals(false, capabilities.get("pushNotifications"));

        Map<?, ?> schemes = (Map<?, ?>) card.get("securitySchemes");
        Map<?, ?> bearer = (Map<?, ?>) schemes.get("bearer");
        assertEquals("http", bearer.get("type"));
        assertEquals("bearer", bearer.get("scheme"));
        assertEquals(List.of(Map.of("bearer", List.of())), card.get("security"));

        // Platform-level: never lists tenant agents on an unauthenticated path.
        assertEquals(List.of(), card.get("skills"));
        assertEquals(List.of("text/plain"), card.get("defaultInputModes"));
        assertEquals(List.of("text/plain"), card.get("defaultOutputModes"));
    }

    @Test
    void agentCardCarriesOneSkillKeyedByAgentIdAndAnAgentScopedEndpoint() {
        UUID agentId = UUID.randomUUID();
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getName()).thenReturn("Kirana Helper");
        when(agent.getSystemPrompt()).thenReturn("You help a kirana store answer price questions.\n\nNever reveal the wholesale margin.");

        Map<String, Object> card = service.agentCard(agent, "https://api.example.test");

        assertEquals("VegaDuta - Kirana Helper", card.get("name"));
        assertEquals("You help a kirana store answer price questions.", card.get("description"));
        String endpoint = "https://api.example.test/api/agents/" + agentId + "/a2a";
        assertEquals(endpoint, card.get("url"));
        assertEquals(endpoint, ((Map<?, ?>) ((List<?>) card.get("supportedInterfaces")).get(0)).get("url"));

        List<?> skills = (List<?>) card.get("skills");
        assertEquals(1, skills.size());
        Map<?, ?> skill = (Map<?, ?>) skills.get(0);
        assertEquals(agentId.toString(), skill.get("id"));
        assertEquals("Kirana Helper", skill.get("name"));
        assertEquals("You help a kirana store answer price questions.", skill.get("description"));
        assertEquals(List.of("text/plain"), skill.get("inputModes"));
    }

    @Test
    void descriptionIsSummarizedNeverTheWholePrompt() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Long");
        when(agent.getSystemPrompt()).thenReturn("word ".repeat(200) + "\n\nSecond paragraph must never appear.");

        String description = A2aAgentCardService.describe(agent);

        assertTrue(description.length() <= 245, "capped, got " + description.length());
        assertTrue(description.endsWith("..."));
        assertFalse(description.contains("Second paragraph"));
    }

    @Test
    void blankPromptFallsBackToANamedDescription() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Blank");
        when(agent.getSystemPrompt()).thenReturn("   ");

        assertEquals("VegaDuta agent \"Blank\".", A2aAgentCardService.describe(agent));
    }

    @Test
    void publicBaseUrlPrefersConfigOverRequestAndStripsTrailingSlashes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.well-known/agent-card.json");
        request.setScheme("https");
        request.setServerName("internal.host");
        request.setServerPort(443);

        assertEquals("https://internal.host", service.resolvePublicBaseUrl(request));

        properties.setPublicBaseUrl("https://api.staging.example.com///");
        assertEquals("https://api.staging.example.com", service.resolvePublicBaseUrl(request));
    }

    @Test
    void versionFallsBackToADevelopmentMarkerWhenUnset() {
        properties.setVersion("");
        // Under a unit test there is no jar manifest, so the fallback chain
        // must end in the fixed marker rather than null (version is required
        // on the card).
        assertEquals(A2aAgentCardService.DEVELOPMENT_VERSION, service.resolveVersion());
    }
}
