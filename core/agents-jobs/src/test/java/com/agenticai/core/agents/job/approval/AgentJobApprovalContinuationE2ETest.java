package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.e2e.ApprovalTurnHarness;
import com.agenticai.core.agents.e2e.RecordingTool;
import com.agenticai.core.agents.e2e.ScriptedLlm;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobApprovalPark;
import com.agenticai.core.agents.job.AgentJobExecution;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobOutputContract;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The agent-JOB twin of {@code ApprovalContinuationE2ETest}: a background job whose turn hits an
 * approval gate parks, a human approves, and the job must resume and finish the work.
 *
 * <p>Real: {@link AgentJobExecution} (claim, turns, park detection, finish), {@link
 * AgentJobExecutor} (the worker pool), {@link AgentJobApprovalSweep} (it receives the "execution
 * recorded" event the approval executor publishes, exactly the early-resume path production has),
 * the park detector, and everything the shared {@link ApprovalTurnHarness} runs for real (turn
 * pipeline, guard chain, approval service and replay executor, session memory). Fake: the LLM
 * ({@link ScriptedLlm}), the job and approval repositories (in-memory), the sweep's scheduler
 * collaborators (never invoked here; the resume is driven by the event, not the timer).
 *
 * <p>Not covered here: the sweep's timer tick, the {@code REPLAY_GRACE} wait, and job durability
 * features (step journal, ownership leases); each has its own tests.
 *
 * <p>The resumed turn is built by the same continuation builder a chat session uses ({@code
 * ApprovalContinuationPrompt}), so the model gets the goal restated and the approved tool's full
 * result, not only the history note.
 */
class AgentJobApprovalContinuationE2ETest {

    private static final String TENANT = "t_test";
    private static final String REQUESTED_BY = "alice@example.com";
    private static final String GOAL =
            "Send invoice INV-7731 to billing@acme.test and report the confirmation number.";
    private static final String CONFIRMATION = "CONF-9042";
    private static final String TOOL = "send_invoice";
    private static final String TOOL_OUTPUT =
            "Invoice INV-7731 sent to billing@acme.test (confirmation " + CONFIRMATION + ")";

    private ApprovalTurnHarness harness;
    private AgentJobExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            AgentJobApprovalTestSupport.shutdown(executor);
        }
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * Finishes when the approved tool's output is anywhere in its context, else asks for the tool.
     */
    private static ScriptedLlm.Responder sensibleModel() {
        return request -> {
            if (request.mentions(CONFIRMATION)) {
                return ScriptedLlm.text(
                        "Done. Invoice INV-7731 was sent; the confirmation number is "
                                + CONFIRMATION
                                + ".");
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("I'm waiting for approval before the invoice can be sent.");
            }
            return ScriptedLlm.callTool(
                    "call-" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
        };
    }

    @Test
    void aParkedJobResumesAfterApprovalSeesTheApprovedOutputAndSucceeds() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentJob job =
                AgentJob.queued(
                        jobId,
                        TENANT,
                        agentId,
                        GOAL,
                        REQUESTED_BY,
                        REQUESTED_BY,
                        Set.of("ROLE_user"),
                        AgentJobOrigin.API,
                        null,
                        600,
                        1,
                        15,
                        null);
        RecordingTool sendInvoice =
                RecordingTool.returning(
                        TOOL,
                        "Sends an invoice by email and returns its confirmation.",
                        TOOL_OUTPUT);
        harness =
                ApprovalTurnHarness.builder()
                        .agentId(agentId)
                        .tenant(TENANT, REQUESTED_BY)
                        .sessionId(job.getSessionId())
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .responder(sensibleModel())
                        .build();

        AgentJobRepository repository = AgentJobApprovalTestSupport.repositoryFor(job);
        AgentJobApprovalLookup lookup = lookupOver(harness);
        AgentJobProperties properties = AgentJobApprovalTestSupport.properties();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        harness.chatService(),
                        mock(ToolCallLogRepository.class),
                        properties,
                        meters,
                        mock(AgentJobProgressService.class),
                        provider(AgentJobOutputContract.PASS_THROUGH),
                        provider(
                                (AgentJobApprovalPark)
                                        new AgentJobApprovalParkDetector(
                                                lookup, properties, meters)));
        // The resumed turn is built by the same continuation builder a chat session uses.
        execution.setApprovalResume(
                new AgentJobApprovalResume(
                        lookup,
                        harness.continuationFactory(),
                        harness.continuationPrompt(),
                        harness.continuationClaims(),
                        harness.continuationProperties()));
        executor = new AgentJobExecutor(repository, execution, properties);
        AgentJobApprovalSweep sweep =
                new AgentJobApprovalSweep(
                        repository,
                        lookup,
                        properties,
                        mock(TenantSchemaIterator.class),
                        mock(PgAdvisoryJobLock.class),
                        meters,
                        provider(executor),
                        provider(harness.approvalService()));
        // Spring would deliver the "execution recorded" event to the sweep's listener; so do we.
        harness.onApprovalEvent(
                event -> {
                    if (event instanceof ApprovalRequestExecutionRecordedEvent recorded) {
                        sweep.onApprovalExecutionRecorded(recorded);
                    }
                });

        // Run 1: the job's turn calls the gated tool and the job parks.
        execution.run(jobId, TENANT);
        assertEquals(
                AgentJobStatus.AWAITING_APPROVAL,
                job.getStatus(),
                "precondition: the gated job parked");
        assertEquals(0, sendInvoice.executions(), "precondition: nothing ran before approval");
        ApprovalRequest parked = harness.pendingApproval();
        int modelCallsBeforeApproval = harness.llm().modelCalls();

        // The human approves; the executor replays the tool and publishes "execution recorded",
        // which the sweep turns into an immediate dispatch of a fresh turn on the worker pool.
        harness.approvePending();
        assertEquals(1, sendInvoice.executions(), "precondition: approval ran the tool");
        int turnsUnderRawIdAfterApproval = harness.historyUnderRawSessionId().size();
        int turnsUnderScopedKeyAfterApproval = harness.historyTheNextTurnWillSee().size();

        assertTrue(
                AgentJobApprovalTestSupport.awaitTrue(
                        () -> job.getTurnsUsed() >= 2 && executor.activeCount() == 0, 20_000L),
                "the sweep never resumed the approved job on a fresh turn (status "
                        + job.getStatus()
                        + ")");
        // Let any status write after the run settle before reading it.
        AgentJobApprovalTestSupport.awaitTrue(
                () -> job.getStatus() != AgentJobStatus.RUNNING, 5_000L);

        List<ScriptedLlm.Request> afterApproval =
                harness.llm()
                        .requests()
                        .subList(modelCallsBeforeApproval, harness.llm().requests().size());
        assertFalse(afterApproval.isEmpty(), "the resumed job made no model call");
        ScriptedLlm.Request nextRequest = afterApproval.get(0);
        long approvalRequestsForTheTool =
                harness.approvalRequests().stream()
                        .filter(r -> r.getActionType().equals("mcp_tool." + TOOL))
                        .count();

        assertAll(
                () ->
                        assertEquals(
                                1,
                                sendInvoice.executions(),
                                "(a) the approved tool must have executed exactly once in total"),
                () ->
                        assertTrue(
                                nextRequest.mentions(CONFIRMATION),
                                "(b) the approved tool's output ("
                                        + CONFIRMATION
                                        + ") must be visible to the model in the"
                                        + " resumed job's first model request. It was not. After approval the history under"
                                        + " the raw session id '"
                                        + job.getSessionId()
                                        + "' held "
                                        + turnsUnderRawIdAfterApproval
                                        + " turn(s) and the history under the scoped key the turn reads held "
                                        + turnsUnderScopedKeyAfterApproval
                                        + ". The model was sent (system messages omitted):\n"
                                        + nextRequest.conversation()),
                () ->
                        assertTrue(
                                nextRequest.mentions(GOAL),
                                "(c) the job's goal must be present in the resumed job's first model request"),
                () ->
                        assertTrue(
                                nextRequest.userTexts().stream()
                                        .anyMatch(
                                                t ->
                                                        t.startsWith(
                                                                        ApprovalTurnHarness
                                                                                .CONTINUATION_MARKER)
                                                                && t.contains(GOAL)
                                                                && t.contains(TOOL_OUTPUT)),
                                "(c2) the resumed turn is the same continuation message a chat session gets (goal restated,"
                                        + " approved tool's full result): "
                                        + nextRequest.userTexts()),
                () ->
                        assertEquals(
                                AgentJobStatus.SUCCEEDED,
                                job.getStatus(),
                                "(d) the resumed job must finish the work and SUCCEED; it is "
                                        + job.getStatus()
                                        + " (park count "
                                        + job.getParkCount()
                                        + ") because the model, never shown the approved"
                                        + " output, asked for the gated tool again"),
                () ->
                        assertNotNull(
                                job.getResultText(),
                                "(d) a succeeded job must carry the agent's final answer"),
                () ->
                        assertTrue(
                                job.getResultText() != null
                                        && job.getResultText().contains(CONFIRMATION),
                                "(d) the job's result must contain the confirmation number; it is: "
                                        + job.getResultText()),
                () ->
                        assertEquals(
                                1L,
                                approvalRequestsForTheTool,
                                "(e) no duplicate side effect: the resumed job must not have to ask for the same tool"
                                        + " again; it did, so "
                                        + approvalRequestsForTheTool
                                        + " approval requests exist"
                                        + " for it (the first was "
                                        + parked.getId()
                                        + ")"),
                () ->
                        assertFalse(
                                harness.approvalRequests().stream()
                                        .anyMatch(r -> r.getStatus() == ApprovalStatus.PENDING),
                                "(e) no approval request may be left PENDING by a job whose approval was already granted"));
    }

    // ------------------------------------------------------------------------ fixtures

    /** The job-approval lookup, answered from the approval requests the real gate recorded. */
    private static AgentJobApprovalLookup lookupOver(ApprovalTurnHarness harness) {
        AgentJobApprovalLookup lookup = mock(AgentJobApprovalLookup.class);
        when(lookup.findByRequestedByAndStatusOrderByCreatedAtAsc(
                        anyString(), any(ApprovalStatus.class)))
                .thenAnswer(
                        inv ->
                                harness.approvalRequests().stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getRequestedBy()))
                                        .filter(
                                                r ->
                                                        r.getStatus()
                                                                == inv.getArgument(
                                                                        1, ApprovalStatus.class))
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                        ApprovalRequest::getCreatedAt))
                                        .toList());
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(anyString()))
                .thenAnswer(
                        inv ->
                                harness.approvalRequests().stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getRequestedBy()))
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                                ApprovalRequest::getCreatedAt)
                                                        .reversed())
                                        .limit(20)
                                        .toList());
        return lookup;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(ArgumentMatchers.<Supplier<T>>any())).thenReturn(instance);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
