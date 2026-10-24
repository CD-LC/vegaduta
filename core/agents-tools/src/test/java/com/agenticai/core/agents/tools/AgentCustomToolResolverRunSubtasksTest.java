package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.collab.CollaboratorToolFactory;
import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code run_subtasks} is offered to an agent that may delegate even when it has no collaborators
 * (the Assistant's case), and only when the flag is on and the turn is not itself a delegated call.
 */
class AgentCustomToolResolverRunSubtasksTest {

    private final AgentToolGuard guard = mock(AgentToolGuard.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentChatService chatService = mock(AgentChatService.class);
    private final CollaboratorToolFactory factory = mock(CollaboratorToolFactory.class);
    private final UUID agentId = UUID.randomUUID();
    private AgentCustomToolResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(guard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(guard.withRunBudget(any(), any(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(agentService.getCollaboratorAgentIds(agentId)).thenReturn(Set.of());
        ToolCallback subtasks = namedTool("run_subtasks");
        when(factory.runSubtasks(any(), eq(agentId), any(), any(), any(), any()))
                .thenReturn(subtasks);
        ObjectProvider<AgentChatService> chat = mock(ObjectProvider.class);
        when(chat.getIfAvailable()).thenReturn(chatService);
        ObjectProvider<CollaboratorToolFactory> factories = mock(ObjectProvider.class);
        when(factories.getIfAvailable()).thenReturn(factory);
        resolver =
                new AgentCustomToolResolver(
                        guard,
                        agentService,
                        chat,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        counter ->
                                new RunBudget(
                                        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1000));
        resolver.setCollaboratorToolFactory(factories);
    }

    private static ToolCallback namedTool(String name) {
        ToolDefinition definition =
                ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private List<ToolCallback> resolve(boolean allowDelegation) {
        return resolver.resolveCollaboratorTools(
                agentId, "chat-1", new AtomicInteger(), "hi", null, false, allowDelegation);
    }

    @Test
    void anAgentWithNoCollaboratorsStillGetsRunSubtasks() {
        List<ToolCallback> tools = resolve(true);
        assertEquals(1, tools.size());
        assertEquals("run_subtasks", tools.get(0).getToolDefinition().name());
    }

    @Test
    void aDelegatedCallGetsNothing() {
        assertTrue(resolve(false).isEmpty());
    }

    @Test
    void theFlagRemovesTheTool() {
        resolver.setRunSubtasksEnabled(false);
        assertTrue(resolve(true).isEmpty());
    }
}
