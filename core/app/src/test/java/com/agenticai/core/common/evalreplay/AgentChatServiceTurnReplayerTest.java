package com.agenticai.core.common.evalreplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.replay.ReplayTurnRunner;
import com.agenticai.core.quality.dataset.AgentTurnReplayer;
import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayMode;
import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayRequest;
import com.agenticai.core.quality.dataset.AgentTurnReplayer.ToolStatus;
import com.agenticai.core.quality.dataset.EvalSandboxPolicy;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The typed bridge between the eval runner and the real turn pipeline: it maps the three replay modes onto the
 * runner's two switches, hands out the sandbox auto-approval only to a tenant the operator declared
 * non-production, and reports the tool calls with the status the turn itself recorded.
 */
class AgentChatServiceTurnReplayerTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ReplayTurnRunner runner;
    private ArgumentCaptor<ReplayTurnRunner.Request> sent;

    @BeforeEach
    void setUp() {
        runner = mock(ReplayTurnRunner.class);
        sent = ArgumentCaptor.forClass(ReplayTurnRunner.Request.class);
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentChatServiceTurnReplayer replayer(Set<String> nonProductionTenants) {
        return new AgentChatServiceTurnReplayer(
            runner, EvalSandboxPolicy.of(
                com.agenticai.core.quality.dataset.EvalMode.FULL, nonProductionTenants, "eval-auto-approve",
                List.of("local_shell", "*payment*"), List.of("crm_lookup_*")));
    }

    private ReplayRequest request(ReplayMode mode) {
        return new ReplayRequest(AGENT, "eval:run:0", "alice", "hello", mode, TIMEOUT);
    }

    private void runnerAnswers(ReplayTurnRunner.Result result) {
        when(runner.run(any(ReplayTurnRunner.Request.class))).thenReturn(result);
    }

    @Test
    void textOnlyAsksTheRunnerForNoToolsAndNoApproval() {
        runnerAnswers(new ReplayTurnRunner.Result("hi there", List.of(), 0, 0));

        AgentTurnReplayer.ReplayedTurn turn = replayer(Set.of("tenant-1")).replay(request(ReplayMode.TEXT_ONLY));

        verify(runner).run(sent.capture());
        assertThat(sent.getValue().withTools()).isFalse();
        assertThat(sent.getValue().autoApproveActor()).isNull();
        assertThat(sent.getValue().agentId()).isEqualTo(AGENT);
        assertThat(sent.getValue().sessionId()).isEqualTo("eval:run:0");
        assertThat(sent.getValue().subjectKey()).isEqualTo("alice");
        assertThat(sent.getValue().timeout()).isEqualTo(TIMEOUT);
        assertThat(turn.responseText()).isEqualTo("hi there");
        assertThat(turn.toolCalls()).isEmpty();
    }

    @Test
    void theSixArgumentFormKeepsItsTwoOriginalMeanings() {
        runnerAnswers(new ReplayTurnRunner.Result("x", List.of(), 0, 0));
        AgentChatServiceTurnReplayer replayer = replayer(Set.of("tenant-1"));

        replayer.replay(AGENT, "s", "alice", "hello", false, TIMEOUT);
        replayer.replay(AGENT, "s", "alice", "hello", true, TIMEOUT);

        verify(runner, org.mockito.Mockito.times(2)).run(sent.capture());
        assertThat(sent.getAllValues().get(0).withTools()).isFalse();
        assertThat(sent.getAllValues().get(1).withTools()).isTrue();
        assertThat(sent.getAllValues()).allSatisfy(r -> assertThat(r.autoApproveActor()).isNull());
    }

    @Test
    void toolsModeRunsThePipelineWithoutAnyAutoApproval() {
        runnerAnswers(new ReplayTurnRunner.Result("done", List.of(), 0, 0));

        replayer(Set.of("tenant-1")).replay(request(ReplayMode.TOOLS));

        verify(runner).run(sent.capture());
        assertThat(sent.getValue().withTools()).isTrue();
        assertThat(sent.getValue().autoApproveActor()).isNull();
    }

    @Test
    void autoApproveForADeclaredTenantPassesTheActorAndTheExcludedTools() {
        runnerAnswers(new ReplayTurnRunner.Result("done", List.of(), 2, 0));

        AgentTurnReplayer.ReplayedTurn turn =
            replayer(Set.of("tenant-1")).replay(request(ReplayMode.TOOLS_AUTO_APPROVE));

        verify(runner).run(sent.capture());
        assertThat(sent.getValue().withTools()).isTrue();
        assertThat(sent.getValue().autoApproveActor()).isEqualTo("eval-auto-approve");
        assertThat(sent.getValue().autoApproveExcludedTools()).containsExactly("local_shell", "*payment*");
        assertThat(turn.autoApprovedCalls()).isEqualTo(2);
    }

    @Test
    void theOperatorsVouchedForReadersGoToTheRunnerSoItsSandboxCanLetThemRun() {
        runnerAnswers(new ReplayTurnRunner.Result("done", List.of(), 0, 0));

        replayer(Set.of()).replay(request(ReplayMode.TOOLS));

        verify(runner).run(sent.capture());
        assertThat(sent.getValue().readOnlyTools()).containsExactly("crm_lookup_*");
    }

    @Test
    void aCallTheSandboxSimulatedIsReportedAsSimulated() {
        runnerAnswers(new ReplayTurnRunner.Result("done", List.of(
            new ReplayTurnRunner.ToolEvidence("remember_fact", "{}", "simulated", ReplayTurnRunner.Status.SIMULATED)),
            0, 0, List.of("remember_fact"), List.of()));

        AgentTurnReplayer.ReplayedTurn turn = replayer(Set.of()).replay(request(ReplayMode.TOOLS));

        assertThat(turn.toolCalls()).extracting(AgentTurnReplayer.ReplayedToolCall::status)
            .containsExactly(ToolStatus.SIMULATED);
    }

    @Test
    void autoApproveForAnUndeclaredTenantIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> replayer(Set.of("some-other-tenant")).replay(request(ReplayMode.TOOLS_AUTO_APPROVE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.quality.eval.auto-approve.tenant-ids");

        verify(runner, never()).run(any(ReplayTurnRunner.Request.class));
    }

    @Test
    void autoApproveWithNoTenantOnTheThreadIsRefused() {
        TenantContext.clear();

        assertThatThrownBy(() -> replayer(Set.of("tenant-1")).replay(request(ReplayMode.TOOLS_AUTO_APPROVE)))
            .isInstanceOf(IllegalArgumentException.class);

        verify(runner, never()).run(any(ReplayTurnRunner.Request.class));
    }

    @Test
    void toolCallsKeepTheStatusTheTurnRecordedForEach() {
        runnerAnswers(new ReplayTurnRunner.Result("done", List.of(
            new ReplayTurnRunner.ToolEvidence("search", "{\"q\":1}", "found", ReplayTurnRunner.Status.OK),
            new ReplayTurnRunner.ToolEvidence("send", "{}", "queued", ReplayTurnRunner.Status.AWAITING_APPROVAL),
            new ReplayTurnRunner.ToolEvidence("lookup", "{}", "x", ReplayTurnRunner.Status.UNKNOWN)), 0, 0));

        AgentTurnReplayer.ReplayedTurn turn = replayer(Set.of()).replay(request(ReplayMode.TOOLS));

        assertThat(turn.toolCalls()).extracting(AgentTurnReplayer.ReplayedToolCall::toolName)
            .containsExactly("search", "send", "lookup");
        assertThat(turn.toolCalls()).extracting(AgentTurnReplayer.ReplayedToolCall::status)
            .containsExactly(ToolStatus.OK, ToolStatus.AWAITING_APPROVAL, ToolStatus.UNKNOWN);
        assertThat(turn.toolCalls().get(0).input()).isEqualTo("{\"q\":1}");
    }
}
