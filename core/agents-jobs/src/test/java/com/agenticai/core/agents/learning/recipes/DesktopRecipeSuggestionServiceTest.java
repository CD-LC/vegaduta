package com.agenticai.core.agents.learning.recipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.skills.DesktopRunSuggestionGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DesktopRecipeSuggestionServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-26T10:00:00Z");

    private final LearningProposalService proposals = mock(LearningProposalService.class);
    private final ToolCallLogService toolCalls = mock(ToolCallLogService.class);
    private DesktopRecipeSuggestionService service;

    @BeforeEach
    void setUp() {
        when(proposals.propose(any()))
                .thenReturn(new LearningProposalService.Result(LearningProposalService.Outcome.CREATED, null));
        ObjectProvider<LearningProposalService> svcProvider = mock(ObjectProvider.class);
        when(svcProvider.getIfAvailable()).thenReturn(proposals);
        service = new DesktopRecipeSuggestionService(svcProvider, toolCalls);
    }

    @AfterEach
    void tearDown() {
        DesktopRunSuggestionGateway.install(null);
    }

    private static ToolCallLog call(String tool, String error, int sec) {
        Instant at = T0.plusSeconds(sec);
        return new ToolCallLog(AGENT, "s1", tool, "{}", "ok", error, at, at.plusMillis(100));
    }

    private static TurnOutcome turn() {
        return new TurnOutcome(
                AGENT, "s1", "Move the report to the archive folder", "", T0, true, false,
                TenantScope.of(TenantContext.of("acme", "alice", Set.of())));
    }

    private void toolLog(List<ToolCallLog> calls) {
        when(toolCalls.findSince(eq(AGENT), eq("s1"), any())).thenReturn(calls);
    }

    @Test
    void aTurnWithSeveralDesktopActionsIsProposedAsARecipe() {
        toolLog(
                List.of(
                        call("local_desktop_open", null, 1),
                        call("local_desktop_click", null, 2),
                        call("local_desktop_type", null, 3)));

        service.onTurn(turn());

        ArgumentCaptor<Proposal> captor = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).propose(captor.capture());
        Proposal p = captor.getValue();
        assertThat(p.kind()).isEqualTo(LearningProposalKind.DESKTOP_RECIPE);
        assertThat(p.subject()).isEqualTo("alice");
        assertThat(p.agentId()).isEqualTo(AGENT);
        assertThat(p.payload()).containsEntry("sessionId", "s1");
        // The person's own request line names the recipe; no tool input or output is copied.
        assertThat(p.body()).isEqualTo("Move the report to the archive folder");
    }

    @Test
    void aSingleDesktopGestureIsNotARecipe() {
        toolLog(List.of(call("local_desktop_click", null, 1)));
        service.onTurn(turn());
        verify(proposals, never()).propose(any());
    }

    @Test
    void failedAndReadOnlyDesktopCallsDoNotCount() {
        toolLog(
                List.of(
                        call("local_desktop_click", "no element", 1),
                        call("local_desktop_type", null, 2),
                        call("local_desktop_screen", null, 3)));
        service.onTurn(turn());
        verify(proposals, never()).propose(any());
    }

    @Test
    void recipeReplayStepsNeverTriggerASuggestion() {
        toolLog(
                List.of(
                        call("desktop_recipe_step/click", null, 1),
                        call("desktop_recipe_step/type", null, 2),
                        call("desktop_recipe_step/open", null, 3)));
        service.onTurn(turn());
        verify(proposals, never()).propose(any());
    }

    @Test
    void anUnfinishedTurnIsNeverRead() {
        TurnOutcome cancelled =
                new TurnOutcome(AGENT, "s1", "go", "", T0, false, true,
                        TenantScope.of(TenantContext.of("acme", "alice", Set.of())));
        service.onTurn(cancelled);
        verify(toolCalls, never()).findSince(any(), anyString(), any());
    }
}
