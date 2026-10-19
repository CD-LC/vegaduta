package com.agenticai.core.agents.collab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.AgentChatService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/** The ADR-0023 port implementation builds the same tool classes the resolver used to build. */
class DefaultCollaboratorToolFactoryTest {

    private final DefaultCollaboratorToolFactory factory = new DefaultCollaboratorToolFactory();
    private final AgentChatService chat = mock(AgentChatService.class);

    @Test
    void delegateToAgentBuildsTheAskAgentTool() {
        ToolCallback tool =
                factory.delegateToAgent(chat, UUID.randomUUID(), "ask_agent_billing", "Billing", "");
        assertInstanceOf(DelegateToAgentToolCallback.class, tool);
        assertEquals("ask_agent_billing", tool.getToolDefinition().name());
    }

    @Test
    void delegateToTeamBuildsTheTeamToolWithOrWithoutAVerifier() {
        List<CollaboratorToolFactory.TeamMember> members =
                List.of(
                        new CollaboratorToolFactory.TeamMember(UUID.randomUUID(), "A", "a"),
                        new CollaboratorToolFactory.TeamMember(UUID.randomUUID(), "B", "b"));
        RunBudget budget = new RunBudget(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1000);
        ToolCallback plain =
                factory.delegateToTeam(
                        chat, members, null, budget, null, "s1", SwarmVerifierConfig.DISABLED,
                        null, null, "goal");
        assertInstanceOf(DelegateToTeamToolCallback.class, plain);
        ToolCallback verified =
                factory.delegateToTeam(
                        chat, members, null, budget, null, "s1",
                        new SwarmVerifierConfig(true, 1, 500, 500), (s, u) -> "{}", null, "goal");
        assertInstanceOf(DelegateToTeamToolCallback.class, verified);
        assertEquals(plain.getToolDefinition().name(), verified.getToolDefinition().name());
    }

    @Test
    void runSubtasksBuildsTheSubtaskTool() {
        RunBudget budget = new RunBudget(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1000);
        ToolCallback tool = factory.runSubtasks(chat, UUID.randomUUID(), null, budget, null, "s1");
        assertInstanceOf(RunSubtasksToolCallback.class, tool);
        assertEquals("run_subtasks", tool.getToolDefinition().name());
    }
}
