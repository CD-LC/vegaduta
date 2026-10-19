package com.agenticai.core.common.improve;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Improvement suggestions act on one agent, so every route checks access to that agent first:
 * VIEWER to read, EDITOR to generate (spends LLM budget), accept or dismiss. A denied caller must
 * trigger no work at all.
 */
class AgentImprovementControllerTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID SUGGESTION = UUID.randomUUID();

    private AgentImprovementService improvementService;
    private AgentHealthScoreService healthScoreService;
    private AgentService agentService;
    private AgentImprovementController controller;

    @BeforeEach
    void setUp() {
        improvementService = mock(AgentImprovementService.class);
        healthScoreService = mock(AgentHealthScoreService.class);
        agentService = mock(AgentService.class);
        controller =
                new AgentImprovementController(
                        improvementService, healthScoreService, agentService);
    }

    private void denied(PermissionLevel level) {
        doThrow(new ResourceAccessDeniedException()).when(agentService).requireAccess(AGENT, level);
    }

    @Test
    void generateChecksEditorAccessBeforeSpendingAnything() {
        when(improvementService.generateForAgent(AGENT)).thenReturn(List.of());

        controller.generate(AGENT);

        InOrder order = inOrder(agentService, improvementService);
        order.verify(agentService).requireAccess(AGENT, PermissionLevel.EDITOR);
        order.verify(improvementService).generateForAgent(AGENT);
    }

    @Test
    void generateDeniedDoesNoWork() {
        denied(PermissionLevel.EDITOR);

        assertThatThrownBy(() -> controller.generate(AGENT))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(improvementService, never()).generateForAgent(any());
        verify(healthScoreService, never()).recompute(any(), any());
    }

    @Test
    void acceptAndDismissDeniedDoNothing() {
        denied(PermissionLevel.EDITOR);

        assertThatThrownBy(() -> controller.accept(AGENT, SUGGESTION, null))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.dismiss(AGENT, SUGGESTION, null))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(improvementService, never()).listForAgent(any());
        verify(improvementService, never()).accept(any(), any());
        verify(improvementService, never()).dismiss(any(), any());
    }

    @Test
    void readsNeedViewerAccess() {
        denied(PermissionLevel.VIEWER);

        assertThatThrownBy(() -> controller.list(AGENT, null, null))
                .isInstanceOf(ResourceAccessDeniedException.class);
        assertThatThrownBy(() -> controller.health(AGENT))
                .isInstanceOf(ResourceAccessDeniedException.class);
        verify(improvementService, never()).listForAgent(any());
        verify(healthScoreService, never()).getForAgent(any());
    }
}
