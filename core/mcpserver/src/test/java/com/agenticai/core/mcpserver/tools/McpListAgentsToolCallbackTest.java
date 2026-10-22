package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code list_my_agents} is the external caller's discovery surface for what its key can do - the
 * danger case is not a wrong name but a LEAK: because the advertised MCP tool list is JVM-wide,
 * this callback's own read of {@link com.agenticai.core.mcpserver.McpCallerContext} is the only
 * thing keeping one grant's listing from including agents that grant (or tenant) was never given.
 * Also pinned: a dangling exposed id (agent deleted after the grant was configured) degrades to
 * omission, not a failed listing - the grant editor has no FK onto the agent table, so this state
 * is fully reachable in production.
 */
class McpListAgentsToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private AgentService agentService;
    private McpListAgentsToolCallback callback;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        callback = new McpListAgentsToolCallback(agentService);
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    /**
     * Real entity + reflection-backfilled id (JPA assigns it), then the production mapper - keeps
     * the test honest about what AgentResponse actually exposes.
     */
    private AgentResponse agentNamed(UUID id, String name) {
        Agent agent =
                new Agent(
                        name,
                        "prompt",
                        ProviderName.OPENAI,
                        "gpt-4o",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.SIMPLE);
        ReflectionTestUtils.setField(agent, "id", id);
        return AgentResponse.from(agent);
    }

    @Test
    void listsExactlyTheGrantsExposedAgentsWithIdAndName() throws Exception {
        UUID agentId = UUID.randomUUID();
        McpCallerContextTestAccess.set(UUID.randomUUID(), Set.of(agentId), Set.of(), Set.of());
        when(agentService.getGrantedAgent(agentId)).thenReturn(agentNamed(agentId, "Support Bot"));

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertEquals(1, result.size());
        assertEquals(agentId.toString(), result.get(0).get("id").asString());
        assertEquals("Support Bot", result.get(0).get("name").asString());
        // Only id + name cross the boundary - never the system prompt,
        // model, or tool config AgentResponse also carries.
        assertEquals(2, result.get(0).size());
    }

    @Test
    void anEmptyExposedSetListsNothingAndNeverQueriesTheAgentService() throws Exception {
        // Deny-by-default made visible: no exposed agents means an empty
        // list, not "list whatever the tenant has".
        McpCallerContextTestAccess.set(UUID.randomUUID(), Set.of(), Set.of(), Set.of());

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertTrue(result.isArray());
        assertEquals(0, result.size());
        verify(agentService, never()).get(any(UUID.class));
    }

    @Test
    void missingCallerContextDegradesToAnEmptyListNotAnException() throws Exception {
        // Unlike call_agent (which throws), listing chose the softer
        // degrade - there is nothing to leak from an empty list.
        JsonNode result = MAPPER.readTree(callback.call("{}"));
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void aDeletedAgentIsOmittedWhileTheRestOfTheListingSurvives() throws Exception {
        UUID liveAgentId = UUID.randomUUID();
        UUID deletedAgentId = UUID.randomUUID();
        McpCallerContextTestAccess.set(
                UUID.randomUUID(),
                new HashSet<>(Set.of(liveAgentId, deletedAgentId)),
                Set.of(),
                Set.of());
        when(agentService.getGrantedAgent(liveAgentId))
                .thenReturn(agentNamed(liveAgentId, "Still Here"));
        when(agentService.getGrantedAgent(deletedAgentId))
                .thenThrow(new RuntimeException("agent not found"));

        JsonNode result = MAPPER.readTree(callback.call("{}"));

        assertEquals(1, result.size());
        assertEquals("Still Here", result.get(0).get("name").asString());
    }
}
