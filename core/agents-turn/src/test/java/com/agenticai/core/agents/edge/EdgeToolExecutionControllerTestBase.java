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

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
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
 * Shared fixture for the {@link EdgeToolExecutionController} unit tests: a controller over mocked
 * {@link AgentService}, {@code AgentToolProvider} and {@link DeviceAuthResolver}, plus fake tools and
 * device-token stubs. See {@link EdgeToolExecutionControllerTest} for what the suite covers.
 */
abstract class EdgeToolExecutionControllerTestBase {

    // NOTE: #execute resolves through AgentToolProvider#resolveToolCandidatesFor (the
    // untrimmed-candidate-set lookup, not the relevance-capped #resolveToolsFor a real chat
    // turn uses) - see that method's javadoc for why: it always looks up one exact tool by
    // name and never hands the list to an LLM provider, so the per-turn relevance cap has
    // nothing to contribute and would risk arbitrarily hiding the requested tool depending on
    // internal resolution order. #listTools is the opposite case (bug fix, D-9 follow-up): its
    // output becomes the on-device model's system prompt, so it resolves through the SAME
    // capped #resolveToolsFor a hosted turn uses (see EdgeToolExecutionController#listTools's
    // javadoc) - stubbed/verified separately below.
    static final UUID AGENT_ID = UUID.randomUUID();
    static final Principal PRINCIPAL = () -> "keycloak-user-123";

    AgentService agentService;
    AgentToolPort agentToolProvider;
    DeviceAuthResolver deviceAuthResolver;
    EdgeToolExecutionController controller;
    Agent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentService = mock(AgentService.class);
        agentToolProvider = mock(AgentToolPort.class);
        deviceAuthResolver = mock(DeviceAuthResolver.class);
        // f7c16d23 (edge amnesia / E-6) added this fifth constructor argument without
        // updating this test, which broke test compilation for the WHOLE agents module.
        // An unstubbed provider returns null from getIfAvailable(), i.e. exactly the
        // "no AgentMemoryService bean" shape the controller already handles, so every
        // assertion below is unchanged.
        ObjectProvider<AgentMemoryService> agentMemoryService = mock(ObjectProvider.class);
        controller =
                new EdgeToolExecutionController(
                        agentService,
                        agentToolProvider,
                        new ObjectMapper(),
                        deviceAuthResolver,
                        agentMemoryService);

        agent = mock(Agent.class);
        when(agent.getAllowedToolNames()).thenReturn(Set.of());
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(agent);
    }

    static ToolCallback fakeTool(String name, Function<String, String> call) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test tool " + name)
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return call.apply(toolInput);
            }
        };
    }

    /**
     * Wires {@link #deviceAuthResolver}'s mock so that, for {@code deviceToken}, it behaves like
     * the real {@link DeviceAuthResolver#run} - invoking the supplied action with {@code device}
     * and returning whatever the action returns - without needing a real tenant schema/DB. Mirrors
     * the real resolver's contract closely enough for these controller-level tests: real
     * TenantContext population is that class's own tested concern (see its own test), not this
     * controller's.
     */
    @SuppressWarnings("unchecked")
    void stubValidDeviceToken(String deviceToken, MobileDevice device) {
        when(deviceAuthResolver.run(eq(deviceToken), any()))
                .thenAnswer(
                        invocation -> {
                            Function<MobileDevice, Object> action = invocation.getArgument(1);
                            return action.apply(device);
                        });
    }

    static MobileDevice fakeDevice(UUID id) {
        MobileDevice device = mock(MobileDevice.class);
        when(device.getId()).thenReturn(id);
        return device;
    }
}
