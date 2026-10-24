package com.agenticai.core.agents.job.approval;

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
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A background job whose turn hits an approval gate, wired over the real turn pipeline and approval
 * stack ({@link ApprovalTurnHarness}), the real {@link AgentJobExecution}, worker pool, park
 * detector, resume builder and approval sweep; only the LLM, the repositories and the sweep's
 * scheduler collaborators are fakes. The approval sweep receives the executor's "execution
 * recorded" event exactly as in production; a DENIAL publishes no such event, so {@link
 * #sweepAfterDecision} delivers the one the sweep's tick would act on.
 */
final class AgentJobApprovalE2ERig {

    static final String TENANT = "t_test";
    static final String REQUESTED_BY = "alice@example.com";
    static final String GOAL =
            "Send invoice INV-7731 to billing@acme.test and report the confirmation number.";
    static final String TOOL = "send_invoice";

    final AgentJob job;
    final RecordingTool tool;
    final ApprovalTurnHarness harness;
    final AgentJobExecution execution;
    final AgentJobExecutor executor;
    final AgentJobApprovalSweep sweep;

    private AgentJobApprovalE2ERig(
            AgentJob job,
            RecordingTool tool,
            ApprovalTurnHarness harness,
            AgentJobExecution execution,
            AgentJobExecutor executor,
            AgentJobApprovalSweep sweep) {
        this.job = job;
        this.tool = tool;
        this.harness = harness;
        this.execution = execution;
        this.executor = executor;
        this.sweep = sweep;
    }

    /**
     * @param jobsContinueAfterDenial {@code app.approvals.continuation.jobs-continue-after-denial}
     */
    static AgentJobApprovalE2ERig build(
            String toolOutput,
            ScriptedLlm.Responder model,
            boolean jobsContinueAfterDenial,
            Consumer<ApprovalContinuationProperties> tuning) {
        UUID agentId = UUID.randomUUID();
        AgentJob job =
                AgentJob.queued(
                        UUID.randomUUID(),
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
                RecordingTool.returning(TOOL, "Sends an invoice by email.", toolOutput);
        ApprovalTurnHarness harness =
                ApprovalTurnHarness.builder()
                        .agentId(agentId)
                        .tenant(TENANT, REQUESTED_BY)
                        .sessionId(job.getSessionId())
                        .tool(sendInvoice)
                        .gateTool(TOOL)
                        .responder(model)
                        .continuation(
                                p -> {
                                    p.setJobsContinueAfterDenial(jobsContinueAfterDenial);
                                    tuning.accept(p);
                                })
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
        execution.setApprovalResume(
                new AgentJobApprovalResume(
                        lookup,
                        harness.continuationFactory(),
                        harness.continuationPrompt(),
                        harness.continuationClaims(),
                        harness.continuationProperties()));
        AgentJobExecutor executor = new AgentJobExecutor(repository, execution, properties);
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
        sweep.setContinuationProperties(harness.continuationProperties());
        harness.onApprovalEvent(
                event -> {
                    if (event instanceof ApprovalRequestExecutionRecordedEvent recorded) {
                        sweep.onApprovalExecutionRecorded(recorded);
                    }
                });
        return new AgentJobApprovalE2ERig(job, sendInvoice, harness, execution, executor, sweep);
    }

    /** Runs the job's first turn: it calls the gated tool and parks. */
    void runUntilParked() {
        execution.run(job.getId(), TENANT);
    }

    /**
     * What the sweep's tick does for a decided request that published no event (a denial): settle
     * the parked job.
     */
    void sweepAfterDecision(ApprovalRequest decided) {
        sweep.onApprovalExecutionRecorded(
                new ApprovalRequestExecutionRecordedEvent(
                        this, decided.getId(), decided.getRequestedBy()));
    }

    void close() {
        AgentJobApprovalTestSupport.shutdown(executor);
        harness.close();
    }

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
                                        .sorted(Comparator.comparing(ApprovalRequest::getCreatedAt))
                                        .toList());
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(anyString()))
                .thenAnswer(
                        inv ->
                                harness.approvalRequests().stream()
                                        .filter(r -> inv.getArgument(0).equals(r.getRequestedBy()))
                                        .sorted(
                                                Comparator.comparing(ApprovalRequest::getCreatedAt)
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
