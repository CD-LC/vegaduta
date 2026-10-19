package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.workflowdraft.WorkflowDraftToolFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/** {@code create_workflow} is offered to an agent that lists it, and to the admin picker whenever on. */
class AgentWorkflowDraftToolResolverTest {

    private final AgentToolGuard guard = mock(AgentToolGuard.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkflowDraftToolFactory factory = mock(WorkflowDraftToolFactory.class);
    private final UUID agentId = UUID.randomUUID();
    private AgentWorkflowDraftToolResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(guard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name("create_workflow")
                        .description("d")
                        .inputSchema("{}")
                        .build();
        ToolCallback tool =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return definition;
                    }

                    @Override
                    public String call(String toolInput) {
                        return "";
                    }
                };
        when(factory.createIfEnabled()).thenReturn(tool);
        ObjectProvider<WorkflowDraftToolFactory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(factory);
        resolver = new AgentWorkflowDraftToolResolver(guard, agentService);
        resolver.setFactory(provider);
    }

    private List<ToolCallback> turn() {
        return resolver.resolveWorkflowDraftTools(
                agentId, "chat-1", new AtomicInteger(), "hi", null, false);
    }

    @Test
    void anAgentThatListsTheToolGetsIt() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of("create_workflow"));
        assertEquals(1, turn().size());
    }

    @Test
    void anUnrestrictedOrOtherwiseRestrictedAgentDoesNot() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of());
        assertTrue(turn().isEmpty());
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of("web_search"));
        assertTrue(turn().isEmpty());
    }

    @Test
    void discoveryListsItWheneverTheFlagIsOn() {
        when(agentService.getAllowedToolNames(agentId)).thenReturn(Set.of());
        assertEquals(
                1,
                resolver
                        .resolveWorkflowDraftToolsForDiscovery(
                                agentId, "probe", new AtomicInteger(), null, null, false)
                        .size());
        when(factory.createIfEnabled()).thenReturn(null);
        assertTrue(
                resolver
                        .resolveWorkflowDraftToolsForDiscovery(
                                agentId, "probe", new AtomicInteger(), null, null, false)
                        .isEmpty());
    }
}
