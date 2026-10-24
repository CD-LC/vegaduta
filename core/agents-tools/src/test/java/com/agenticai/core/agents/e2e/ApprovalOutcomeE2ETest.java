package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.tools.workspacefiles.WorkspaceFileToolSource;
import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestAlreadyDecidedException;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.codeintel.ApplyCodeChangesToolCallback;
import com.agenticai.core.codeintel.ApplyCodeWorkspaceApprovalExecutor;
import com.agenticai.core.codeintel.CodeIntelClient;
import com.agenticai.core.codeintel.RunCodeWorkspaceApprovalExecutor;
import com.agenticai.core.codeintel.RunInCodeWorkspaceToolCallback;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.ToolOutputProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * "An action the agent asked for needs a human; the human decides; does the agent carry on?" for every
 * KIND of approval executor, not only the replayed MCP tool: the code-workspace run and apply (tools that
 * hold their own approval inside {@code call()}), a channel-style send (a tool plus an executor that
 * records no output), and the Assistant's folder tools (a guarded tool, gated by policy).
 *
 * <p>Same rig as {@link ApprovalContinuationE2ETest}: the real turn pipeline, tool guard chain, approval
 * service, outcome port, continuation launcher and scoped session memory; only the LLM is scripted, and
 * the code-intel service behind the workspace tools is a mock the test can count calls on. The client
 * sends nothing after the decision.
 */
class ApprovalOutcomeE2ETest {

    private static final String RUN_GOAL = "Run the billing tests in workspace ws-1 and tell me how many passed.";
    private static final String RUN_OUTPUT_MARKER = "BUILD OK 12 tests";
    private static final String RUN_RESULT_JSON =
            "{\"success\":true,\"attempts_used\":1,\"final\":{\"exit_code\":0,\"duration_seconds\":3.2,"
                    + "\"timed_out\":false,\"stdout\":\""
                    + RUN_OUTPUT_MARKER
                    + "\",\"stderr\":\"\"}}";
    private static final String RUN_ARGS =
            "{\"repo_id\":\"billing\",\"workspace_id\":\"ws-1\",\"command\":\"mvn -q test\"}";
    private static final String APPLY_GOAL = "Fix the typo in README.md of workspace ws-1.";
    private static final String APPLY_ARGS =
            "{\"repo_id\":\"billing\",\"workspace_id\":\"ws-1\",\"files\":[{\"path\":\"README.md\",\"content\":\"hello\"}]}";

    private ApprovalTurnHarness harness;
    private CodeIntelClient client;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    /** What a sensible model does: finish once it has {@code marker} in its context, call the tool otherwise. */
    private static ScriptedLlm.Responder model(String tool, String args, String marker, String finalAnswer) {
        return request -> {
            if (request.mentions(marker)) {
                return ScriptedLlm.text(finalAnswer);
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("I'm waiting for your approval before that can run.");
            }
            return ScriptedLlm.callTool("call-" + request.number(), tool, args);
        };
    }

    private ApprovalTurnHarness codeAgent(ScriptedLlm.Responder responder) {
        client = mock(CodeIntelClient.class);
        when(client.runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(RUN_RESULT_JSON);
        when(client.applyToWorkspace(anyString(), anyString(), anyList(), any()))
                .thenReturn("{\"written\":[\"README.md\"],\"deleted\":[]}");
        harness =
                ApprovalTurnHarness.builder()
                        .selfGatingTool(gate -> new RunInCodeWorkspaceToolCallback(gate, Set.of("billing")))
                        .selfGatingTool(gate -> new ApplyCodeChangesToolCallback(gate, Set.of("billing")))
                        .executor(
                                approvals ->
                                        new RunCodeWorkspaceApprovalExecutor(
                                                client, approvals.requestRepository(), approvals.eventPublisher()))
                        .executor(
                                approvals ->
                                        new ApplyCodeWorkspaceApprovalExecutor(
                                                client,
                                                new com.agenticai.core.approval.ApprovalExecutionResultWriter(
                                                        approvals.requestRepository(), approvals.eventPublisher())))
                        .gateAction(RunCodeWorkspaceApprovalExecutor.ACTION_TYPE)
                        .gateAction(ApplyCodeWorkspaceApprovalExecutor.ACTION_TYPE)
                        .responder(responder)
                        .build();
        return harness;
    }

    private static long continuationTurns(ApprovalTurnHarness h) {
        return h.storedTranscript().stream()
                .filter(t -> t.role() == MessageRole.USER && t.content() != null)
                .filter(t -> t.content().startsWith(ApprovalTurnHarness.CONTINUATION_MARKER))
                .count();
    }

    private static boolean hasNote(ApprovalTurnHarness h, String fragment) {
        return h.historyTheNextTurnWillSee().stream()
                .anyMatch(t -> t.role() == MessageRole.ASSISTANT && t.content() != null && t.content().contains(fragment));
    }

    // ------------------------------------------------------------------- code-workspace run

    @Test
    void anApprovedCodeWorkspaceRunResumesTheAgentWithTheRealOutput() {
        ApprovalTurnHarness h =
                codeAgent(model("run_in_code_workspace", RUN_ARGS, RUN_OUTPUT_MARKER, "All 12 tests passed."));

        h.chat(RUN_GOAL);
        ApprovalRequest parked = h.pendingApproval();
        int modelCallsBefore = h.llm().modelCalls();

        assertAll(
                () -> assertEquals(RunCodeWorkspaceApprovalExecutor.ACTION_TYPE, parked.getActionType()),
                () ->
                        assertTrue(
                                parked.getPayloadJson().contains("\"goal\""),
                                "the request carries the conversation envelope: " + parked.getPayloadJson()),
                () ->
                        assertTrue(
                                parked.getPayloadJson().contains("\"adminOnly\":true"),
                                "the admin-only rule still reads the action's own payload"),
                () -> assertEquals(DEFAULT_SUBJECT(h), parked.getRequestedBy(), "who may decide it is unchanged"),
                () ->
                        verify(client, never())
                                .runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()));

        h.approvePending();
        String reply = h.awaitContinuation();

        ScriptedLlm.Request next = h.llm().requests().get(modelCallsBefore);
        assertAll(
                () ->
                        verify(client, times(1))
                                .runInWorkspace(
                                        org.mockito.ArgumentMatchers.eq("billing"),
                                        org.mockito.ArgumentMatchers.eq("ws-1"),
                                        org.mockito.ArgumentMatchers.eq("mvn -q test"),
                                        any(),
                                        anyInt(),
                                        any()),
                () -> assertEquals("All 12 tests passed.", reply),
                () ->
                        assertTrue(
                                next.mentions(RUN_OUTPUT_MARKER),
                                "the run's real stdout reaches the model in the continuation:\n" + next.conversation()),
                () ->
                        assertTrue(
                                next.userTexts().stream()
                                        .anyMatch(
                                                t ->
                                                        t.startsWith(ApprovalTurnHarness.CONTINUATION_MARKER)
                                                                && t.contains(RUN_GOAL)
                                                                && t.contains("mvn -q test")),
                                "the continuation restates the goal and the call: " + next.userTexts()),
                () -> assertEquals(1, continuationTurns(h)),
                () ->
                        assertTrue(
                                hasNote(h, "[approved action 'run_in_code_workspace' completed:"),
                                "the outcome note is in the history the next turn reads: "
                                        + h.historyTheNextTurnWillSee()),
                () ->
                        assertTrue(
                                h.historyUnderRawSessionId().isEmpty(),
                                "nothing is written under the raw client session id"),
                () ->
                        assertEquals(
                                1,
                                h.approvalRequests().size(),
                                "the continuation did not ask for the run again"));
    }

    @Test
    void deniedCodeWorkspaceRunTellsTheAgentAndNeverRuns() {
        ApprovalTurnHarness h =
                codeAgent(
                        request -> {
                            if (request.mentions("DENIED")) {
                                return ScriptedLlm.text("Understood, I will not run the tests.");
                            }
                            if (request.hasToolResult()) {
                                return ScriptedLlm.text("Waiting for approval.");
                            }
                            return ScriptedLlm.callTool("c" + request.number(), "run_in_code_workspace", RUN_ARGS);
                        });
        h.chat(RUN_GOAL);
        int before = h.llm().modelCalls();

        h.rejectPending("not on production data");
        String reply = h.awaitContinuation();

        ScriptedLlm.Request next = h.llm().requests().get(before);
        assertAll(
                () ->
                        verify(client, never())
                                .runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()),
                () -> assertEquals("Understood, I will not run the tests.", reply),
                () -> assertTrue(next.mentions("not on production data"), next.conversation()),
                () -> assertTrue(next.mentions(RUN_GOAL), "the goal is restated: " + next.conversation()),
                () -> assertTrue(hasNote(h, "DENIED"), h.historyTheNextTurnWillSee().toString()),
                () -> assertEquals(ApprovalStatus.REJECTED, h.approvalRequests().get(0).getStatus()));
    }

    @Test
    void anExpiredCodeWorkspaceRunIsReportedAsRefusedWithTheExpiryReason() {
        ApprovalTurnHarness h =
                codeAgent(
                        request -> {
                            if (request.mentions("nobody decided in time")) {
                                return ScriptedLlm.text("The run timed out waiting for approval.");
                            }
                            if (request.hasToolResult()) {
                                return ScriptedLlm.text("Waiting for approval.");
                            }
                            return ScriptedLlm.callTool("c" + request.number(), "run_in_code_workspace", RUN_ARGS);
                        });
        h.chat(RUN_GOAL);

        h.approvalService().expire(h.pendingApproval().getId(), "nobody decided in time");
        String reply = h.awaitContinuation();

        assertAll(
                () -> assertEquals("The run timed out waiting for approval.", reply),
                () -> assertEquals(ApprovalStatus.REJECTED, h.approvalRequests().get(0).getStatus()),
                () -> assertEquals(ApprovalRequest.EXPIRY_ACTOR, h.approvalRequests().get(0).getDecidedBy()),
                () ->
                        verify(client, never())
                                .runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()));
    }

    @Test
    void aRunCodeIntelRefusesIsAFailureTheAgentIsToldAbout() {
        ApprovalTurnHarness h =
                codeAgent(
                        request -> {
                            if (request.mentions("rejected by code-intel")) {
                                return ScriptedLlm.text("Code-intel refused the command; I will report that.");
                            }
                            if (request.hasToolResult()) {
                                return ScriptedLlm.text("Waiting for approval.");
                            }
                            return ScriptedLlm.callTool("c" + request.number(), "run_in_code_workspace", RUN_ARGS);
                        });
        when(client.runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn("{\"status\":403,\"error\":\"command not allowed by policy\"}");
        h.chat(RUN_GOAL);

        assertThrows(RuntimeException.class, h::approvePending);
        String reply = h.awaitContinuation();

        assertAll(
                () -> assertEquals("Code-intel refused the command; I will report that.", reply),
                () -> assertEquals(ApprovalStatus.EXECUTION_FAILED, h.approvalRequests().get(0).getStatus()),
                () -> assertTrue(hasNote(h, "failed:"), h.historyTheNextTurnWillSee().toString()));
    }

    @Test
    void approvingTwiceRunsOnceAndContinuesOnce() throws Exception {
        ApprovalTurnHarness h =
                codeAgent(model("run_in_code_workspace", RUN_ARGS, RUN_OUTPUT_MARKER, "All 12 tests passed."));
        h.chat(RUN_GOAL);
        ApprovalRequest parked = h.pendingApproval();
        h.approvePending();
        h.awaitContinuation();

        assertThrows(
                ApprovalRequestAlreadyDecidedException.class,
                () -> h.approvalService().approve(parked.getId(), ApprovalTurnHarness.APPROVER));
        Thread.sleep(300);

        assertAll(
                () ->
                        verify(client, times(1))
                                .runInWorkspace(anyString(), anyString(), anyString(), any(), anyInt(), any()),
                () -> assertEquals(1, continuationTurns(h), "a second approve starts no second continuation"));
    }

    // ------------------------------------------------------------------ code-workspace apply

    @Test
    void anApprovedCodeWorkspaceApplyResumesTheAgentWithWhatWasApplied() {
        ApprovalTurnHarness h =
                codeAgent(model("apply_code_changes", APPLY_ARGS, "applied 1 file change(s)", "README fixed."));
        h.chat(APPLY_GOAL);
        ApprovalRequest parked = h.pendingApproval();
        int before = h.llm().modelCalls();

        h.approvePending();
        String reply = h.awaitContinuation();

        ScriptedLlm.Request next = h.llm().requests().get(before);
        assertAll(
                () -> assertEquals(ApplyCodeWorkspaceApprovalExecutor.ACTION_TYPE, parked.getActionType()),
                () -> verify(client, times(1)).applyToWorkspace(anyString(), anyString(), anyList(), any()),
                () -> assertEquals("README fixed.", reply),
                () -> assertTrue(next.mentions("applied 1 file change(s)"), next.conversation()),
                () -> assertTrue(next.mentions("README.md"), "the written path is in the result: " + next.conversation()),
                () ->
                        assertTrue(
                                h.approvalRequests().get(0).getExecutionResult() != null
                                        && h.approvalRequests().get(0).getExecutionResult().contains("applied 1"),
                                "the apply now records what it did on the request"),
                () ->
                        assertTrue(
                                h.approvals().publishedEvents().stream()
                                        .anyMatch(ApprovalRequestExecutionRecordedEvent.class::isInstance),
                                "and announces that the execution became durable"));
    }

    // ---------------------------------------------------------- a send that records no output

    /** A tool that gates itself (like a channel send) over an executor that records no output. */
    private static ToolCallback sendTool(HumanApprovalGateService gate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("channel_send_message")
                        .description("Sends a message to a channel.")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                var evaluation =
                        gate.evaluateInTurn(
                                "channel.test.send",
                                "channel_send_message",
                                "Send test message to ops",
                                "{\"to\":\"ops\",\"body\":\"deploy done\"}",
                                "user-1");
                return evaluation.approved()
                        ? "{\"status\":\"sent\"}"
                        : "{\"status\":\"pending_approval\",\"approval_request_id\":\"" + evaluation.pendingRequestId() + "\"}";
            }
        };
    }

    private ApprovalTurnHarness sendAgent(AtomicInteger sends, boolean gated, ScriptedLlm.Responder responder) {
        ApprovalActionExecutor executor =
                new ApprovalActionExecutor() {
                    @Override
                    public String actionType() {
                        return "channel.test.send";
                    }

                    @Override
                    public void execute(ApprovalRequest request) {
                        sends.incrementAndGet();
                    }
                };
        ApprovalTurnHarness.Builder b =
                ApprovalTurnHarness.builder()
                        .selfGatingTool(ApprovalOutcomeE2ETest::sendTool)
                        .executor(approvals -> executor)
                        .responder(responder);
        if (gated) {
            b.gateAction("channel.test.send");
        } else {
            b.allowWithoutApproval("channel.test.send");
        }
        harness = b.build();
        return harness;
    }

    @Test
    void anApprovedSendWithNoOutputStillResumesTheAgentAndSaysItRanWithoutOutput() {
        AtomicInteger sends = new AtomicInteger();
        ApprovalTurnHarness h =
                sendAgent(
                        sends,
                        true,
                        request -> {
                            if (request.mentions("returned no output")) {
                                return ScriptedLlm.text("The message was sent.");
                            }
                            if (request.hasToolResult()) {
                                return ScriptedLlm.text("Waiting for approval.");
                            }
                            return ScriptedLlm.callTool("c" + request.number(), "channel_send_message", "{\"to\":\"ops\"}");
                        });
        h.chat("Tell ops the deploy is done.");

        h.approvePending();
        String reply = h.awaitContinuation();

        assertAll(
                () -> assertEquals(1, sends.get()),
                () -> assertEquals("The message was sent.", reply),
                () -> assertTrue(hasNote(h, "completed: The action ran and returned no output."), h.historyTheNextTurnWillSee().toString()));
    }

    @Test
    void anActionPolicyAutoApprovesRunsInsideTheTurnAndStartsNoContinuation() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        ApprovalTurnHarness h =
                sendAgent(
                        sends,
                        false,
                        request -> {
                            if (request.hasToolResult()) {
                                return ScriptedLlm.text("Sent it.");
                            }
                            return ScriptedLlm.callTool("c" + request.number(), "channel_send_message", "{\"to\":\"ops\"}");
                        });

        String reply = h.chat("Tell ops the deploy is done.");
        Thread.sleep(300);

        assertAll(
                () -> assertEquals("Sent it.", reply),
                () -> assertEquals(1, sends.get(), "it ran during the turn that asked"),
                () -> assertEquals(0, continuationTurns(h), "there was nothing to wait for, so nothing to continue"),
                () ->
                        assertFalse(
                                h.approvalRequests().get(0).getPayloadJson().contains("\"replay\""),
                                "an auto-approved request carries no conversation envelope"));
    }

    @Test
    void anActionRaisedOutsideAnAgentTurnIsRecordedPlainAndNotContinued() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        ApprovalTurnHarness h = sendAgent(sends, true, request -> ScriptedLlm.text("ok"));

        // The admin screen / a webhook calls the gate with no tool running: no scope, no envelope.
        var evaluation =
                h.approvalService()
                        .evaluateInTurn("channel.test.send", "channel_send_message", "Send from the screen", "{\"to\":\"ops\"}", "user-1");
        ApprovalRequest request = h.approvalRequests().get(0);
        h.approvalService().approve(evaluation.pendingRequestId(), ApprovalTurnHarness.APPROVER);
        Thread.sleep(300);

        assertAll(
                () -> assertEquals("{\"to\":\"ops\"}", request.getPayloadJson()),
                () -> assertEquals(1, sends.get()),
                () -> assertEquals(0, continuationTurns(h)),
                () -> assertEquals(0, h.llm().modelCalls(), "no turn was started"));
    }

    // --------------------------------------------------------- the Assistant's folder tools

    @Test
    void anApprovedFolderWriteResumesTheAssistantWithTheRealResult(@TempDir Path projects) throws Exception {
        WorkspaceFileToolSource source =
                new WorkspaceFileToolSource(
                        new MockEnvironment(),
                        true,
                        true,
                        projects.toString(),
                        new StaticListableBeanFactory().getBeanProvider(ToolOutputProperties.class));
        ToolCallback write =
                source.rawTools().stream()
                        .filter(t -> t.getToolDefinition().name().equals("workspace_write"))
                        .findFirst()
                        .orElseThrow();
        String args = "{\"path\":\"notes/todo.txt\",\"content\":\"ship it\"}";
        harness =
                ApprovalTurnHarness.builder()
                        .selfGatingTool(gate -> write)
                        .gateTool("workspace_write")
                        .responder(
                                request -> {
                                    if (request.mentions("notes/todo.txt") && request.userTexts().stream().anyMatch(t -> t.startsWith(ApprovalTurnHarness.CONTINUATION_MARKER))) {
                                        return ScriptedLlm.text("I saved notes/todo.txt in your folder.");
                                    }
                                    if (request.hasToolResult()) {
                                        return ScriptedLlm.text("Waiting for your approval to write the file.");
                                    }
                                    return ScriptedLlm.callTool("c" + request.number(), "workspace_write", args);
                                })
                        .build();

        harness.chat("Save a todo note in my folder.");
        assertFalse(Files.exists(projects.resolve("notes/todo.txt")), "nothing is written before the decision");
        ApprovalRequest parked = harness.pendingApproval();
        harness.approvePending();
        String reply = harness.awaitContinuation();

        assertAll(
                () -> assertEquals("mcp_tool.workspace_write", parked.getActionType()),
                () -> assertEquals("ship it", Files.readString(projects.resolve("notes/todo.txt"))),
                () -> assertEquals("I saved notes/todo.txt in your folder.", reply),
                () -> assertEquals(1, continuationTurns(harness)),
                () ->
                        assertTrue(
                                hasNote(harness, "[approved action 'workspace_write' completed:"),
                                harness.historyTheNextTurnWillSee().toString()));
    }

    private static String DEFAULT_SUBJECT(ApprovalTurnHarness h) {
        return h.subject();
    }
}
