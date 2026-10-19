package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wait node: a durable park (status PAUSED + a workflow_wait row the scheduled sweep or the signed resume URL
 * resolves), see {@link WorkflowWaitService}. Special-cased in the traversal, not in {@link WorkflowNodeExecutor},
 * because only the traversal knows the node the run must continue at once the wait is over. The park itself is
 * {@link RunFinalizer.Waiting}.
 */
final class WaitStep implements ContainerStep {

    private final TraversalKit kit;
    private final WorkflowNodeExecutor nodeExecutor;
    /** The wait service is field-injected after construction, and absent in hand-built test engines. */
    private final Supplier<WorkflowWaitService> waitService;

    WaitStep(TraversalKit kit, WorkflowNodeExecutor nodeExecutor, Supplier<WorkflowWaitService> waitService) {
        this.kit = kit;
        this.nodeExecutor = nodeExecutor;
        this.waitService = waitService;
    }

    @Override
    public String nodeType() {
        return "wait";
    }

    @Override
    public Outcome run(RunTraversalState s, WorkflowNodeDto node, String currentOutput) {
        WorkflowWaitService waits = waitService.get();
        if (waits == null || !waits.isEnabled()) {
            throw new WorkflowValidationException(List.of("Wait node '" + node.label()
                + "' needs app.workflow.wait-node.enabled=true on this deployment."));
        }
        String waitNext = kit.graphSupport.nextNodeId(node, s.edgesOf(node.id()), null);
        Instant waitStarted = Instant.now();
        WorkflowNodeDto waitNode = nodeExecutor.resolveConfig(
            s.definition.getId().toString(), s.definition.getName(), s.runIdText(), node, currentOutput, s.requestedBy);
        WorkflowWaitService.Plan waitPlan = waits.plan(waitNode, waitStarted, s.mode);
        if (waitPlan.immediate() || waitNext == null) {
            kit.recorder.add(s, new NodeTraceEntry(node.id(), node.type(), node.label(), "OK",
                waitNext == null && !waitPlan.immediate()
                    ? "Nothing follows this Wait node, so the run ends without waiting."
                    : waitPlan.summary(),
                waitStarted, Instant.now(), List.of()), node, currentOutput, currentOutput, NodeTracePayloads.MAX_FIELD_CHARS, null);
            kit.recorder.persist(s);
            return Outcome.next(waitNext, currentOutput);
        }
        return Outcome.finished(kit.finalizer.finalizeLeg(s.legWithoutFile(),
            new RunFinalizer.Waiting(node, waitNext, currentOutput, waitPlan)));
    }
}
