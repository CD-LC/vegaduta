package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowSyncTriggerResult;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implements the omnichannel module's {@link WorkflowTriggerPort} seam - see
 * that interface's javadoc, and {@code WorkflowTriggerChannel}'s, for why
 * this indirection exists (breaking a module dependency cycle between
 * omnichannel's inbound controllers and this module's {@link WorkflowService}).
 */
@Service
public class WorkflowTriggerPortImpl implements WorkflowTriggerPort {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerPortImpl.class);

    private final WorkflowService workflowService;
    private final WorkflowVoiceMenuRequestService voiceMenuRequestService;
    /** Shared bounded virtual-thread executor (JAVA-008) - was the common ForkJoinPool. */
    private final java.util.concurrent.Executor asyncExecutor;

    public WorkflowTriggerPortImpl(
        WorkflowService workflowService,
        WorkflowVoiceMenuRequestService voiceMenuRequestService,
        @org.springframework.beans.factory.annotation.Qualifier(com.agenticai.core.security.concurrent.PlatformExecutorConfiguration.PLATFORM_EXECUTOR) java.util.concurrent.Executor asyncExecutor
    ) {
        this.workflowService = workflowService;
        this.voiceMenuRequestService = voiceMenuRequestService;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public void triggerAsync(UUID workflowId, String startNodeId, String input, String requestedBy, InboundTriggerContext context) {
        // Tenant context + MDC are carried (and restored) by the propagating wrapper.
        CompletableFuture.runAsync(com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(() -> {
            // Reply-to-sender: make the inbound message's origin (e.g. the
            // WhatsApp sender's number) visible to this run's Output node,
            // scoped to this background thread the way TenantContext is above.
            // Cleared in the finally so a pooled thread never leaks it into the
            // next run. See WorkflowTriggerContextHolder's javadoc.
            WorkflowTriggerContextHolder.set(context);
            try {
                workflowService.run(workflowId, startNodeId, input, requestedBy);
            } catch (RuntimeException e) {
                log.warn("Channel-triggered workflow {} failed: {}", workflowId, e.getMessage());
            } finally {
                WorkflowTriggerContextHolder.clear();
            }
        }), asyncExecutor);
    }

    @Override
    public WorkflowSyncTriggerResult triggerSync(UUID workflowId, String startNodeId, String input, String requestedBy, InboundTriggerContext context) {
        WorkflowTriggerContextHolder.set(context);
        try {
            WorkflowRunResponse run = workflowService.run(workflowId, startNodeId, input, requestedBy);
            // Voice menu (V308): the run isn't necessarily FINISHED - a
            // voice_menu node may have halted it AWAITING_VOICE_INPUT mid-graph,
            // waiting on the caller to press digits. That is not a failure and
            // not a finished result; see WorkflowSyncTriggerResult.pendingVoiceMenu.
            if (run.status() == WorkflowRunStatus.AWAITING_VOICE_INPUT) {
                return voiceMenuRequestService.pendingResultForRun(run.id());
            }
            boolean succeeded = run.status() == WorkflowRunStatus.COMPLETED;
            String outputText = run.errorMessage() != null && !run.errorMessage().isBlank()
                ? run.errorMessage()
                : lastStepSummary(run);
            return new WorkflowSyncTriggerResult(succeeded, outputText);
        } catch (RuntimeException e) {
            log.warn("Synchronously-triggered workflow {} failed: {}", workflowId, e.getMessage());
            return new WorkflowSyncTriggerResult(false, e.getMessage() == null ? "The workflow failed to run." : e.getMessage());
        } finally {
            WorkflowTriggerContextHolder.clear();
        }
    }

    /**
     * Thin pass-through to {@link WorkflowVoiceMenuRequestService#collectDigits} -
     * see that method's javadoc for the halt/resume/classification logic. Lives
     * here (rather than the service being called directly by omnichannel) for
     * the same module-cycle reason every other {@link WorkflowTriggerPort}
     * method does.
     */
    @Override
    public WorkflowSyncTriggerResult collectVoiceMenuDigits(String voiceMenuRequestId, String digits, String requestedBy) {
        try {
            return voiceMenuRequestService.collectDigits(UUID.fromString(voiceMenuRequestId), digits, requestedBy);
        } catch (RuntimeException e) {
            log.warn("Voice menu digit collection for request {} failed: {}", voiceMenuRequestId, e.getMessage());
            return new WorkflowSyncTriggerResult(false, e.getMessage() == null ? "The workflow failed to run." : e.getMessage());
        }
    }

    private String lastStepSummary(WorkflowRunResponse run) {
        if (run.trace() == null || run.trace().isEmpty()) {
            return "This workflow ran but produced no output.";
        }
        NodeTraceEntry last = run.trace().get(run.trace().size() - 1);
        return last.summary() != null && !last.summary().isBlank() ? last.summary() : "This workflow ran but produced no output.";
    }
}
