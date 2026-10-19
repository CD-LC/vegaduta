package com.agenticai.core.workflow;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imperative shell for ending one traversal leg of an in-process run. Every way a leg can end is a
 * {@link RunOutcome} state object ({@link Cancelled}, {@link Paused}, {@link Waiting}, {@link Halted},
 * {@link Completed}, {@link Failed}) that knows how to finalise itself against this finalizer's collaborators
 * (State pattern), so {@link WorkflowExecutionEngine} only decides WHICH state it reached and stays free of the
 * persistence / event / issue bookkeeping that used to be pasted at six terminal sites.
 */
final class RunFinalizer {

    private static final Logger log = LoggerFactory.getLogger(RunFinalizer.class);

    /** Attaches sanitized run data (and live-stream publication) to a synthetic trace entry. */
    @FunctionalInterface
    interface TraceStamper {
        NodeTraceEntry stamp(
            List<NodeTraceEntry> trace, NodeTraceEntry entry, WorkflowNodeDto node,
            String input, String output, int perFieldCap, UUID usageRunId);
    }

    /** The mutable facts of the leg being ended; {@code fileOutput} is {@code {fileName, fileData}}. */
    record Leg(WorkflowRun run, WorkflowDefinition definition, List<NodeTraceEntry> trace, String[] fileOutput) {}

    /** One terminal state of a traversal leg. */
    sealed interface RunOutcome permits Cancelled, Paused, Waiting, Halted, Completed, Failed {
        WorkflowRun finalizeWith(RunFinalizer f, Leg leg);
    }

    /** Stop Run: {@code nextNode} is what the traversal was about to start, attributed as "cancelled before start". */
    record Cancelled(WorkflowNodeDto nextNode, String contextSuffix) implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            UUID runId = leg.run().getId();
            log.info("Workflow run {} for '{}' cancelled by user request{}.", runId, leg.definition().getName(), contextSuffix);
            leg.trace().add(new NodeTraceEntry(nextNode.id(), nextNode.type(), nextNode.label(), "CANCELLED",
                "Run cancelled by user request" + contextSuffix + ".", Instant.now(), Instant.now(), List.of()));
            leg.run().complete(WorkflowRunStatus.CANCELLED, f.graphSupport.writeTrace(leg.trace()),
                "Cancelled by user request.", leg.fileOutput()[0], leg.fileOutput()[1]);
            f.runRepository.save(leg.run());
            f.cancellationService.clearCancellationFlag(runId);
            f.publishTerminal("workflow.run.cancelled", leg, Map.of());
            return leg.run();
        }
    }

    /** Operator Pause: parks the run at {@code nextNode} with the exact input it was about to receive. */
    record Paused(WorkflowNodeDto nextNode, String nextNodeInput) implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            log.info("Workflow run {} for '{}' paused by operator request before node '{}'.",
                leg.run().getId(), leg.definition().getName(), nextNode.label());
            Instant now = Instant.now();
            leg.trace().add(f.stamper.stamp(leg.trace(), new NodeTraceEntry(nextNode.id(), nextNode.type(), nextNode.label(),
                "PAUSED", "Run paused by operator request - resume continues from this node.", now, now, List.of()),
                nextNode, nextNodeInput, null, NodeTracePayloads.MAX_FIELD_CHARS, null));
            leg.run().pause(f.graphSupport.writeTrace(leg.trace()), nextNode.id(), nextNodeInput);
            f.runRepository.save(leg.run());
            f.publishPaused(leg, nextNode.id());
            return leg.run();
        }
    }

    /**
     * Wait node: the durable wake-up row is registered BEFORE the park so a crash in between leaves a harmless
     * orphan (the wake sweep cancels it) instead of a parked run nothing will ever resume.
     */
    record Waiting(WorkflowNodeDto waitNode, String nextNodeId, String waitInput, WorkflowWaitService.Plan plan)
        implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            log.info("Workflow run {} for '{}' is waiting at node '{}': {}",
                leg.run().getId(), leg.definition().getName(), waitNode.label(), plan.summary());
            f.waitService.get().register(leg.run(), leg.definition(), waitNode, nextNodeId, plan);
            Instant now = Instant.now();
            leg.trace().add(f.stamper.stamp(leg.trace(), new NodeTraceEntry(waitNode.id(), waitNode.type(), waitNode.label(),
                "AWAITING_WAIT", plan.summary(), now, now, plan.logLines()),
                waitNode, waitInput, null, NodeTracePayloads.MAX_FIELD_CHARS, null));
            leg.run().pause(f.graphSupport.writeTrace(leg.trace()), nextNodeId, waitInput);
            f.runRepository.save(leg.run());
            f.publishPaused(leg, waitNode.id());
            return leg.run();
        }
    }

    /** A node reported a terminal status (FAILED / REJECTED / ...) instead of an output. */
    record Halted(WorkflowRunStatus status, String summary) implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            leg.run().complete(status, f.graphSupport.writeTrace(leg.trace()), summary, leg.fileOutput()[0], leg.fileOutput()[1]);
            f.runRepository.save(leg.run());
            f.cancellationService.clearCancellationFlag(leg.run().getId());
            if (status == WorkflowRunStatus.FAILED) {
                f.failureRecorder.accept(leg.run(), summary);
            }
            f.publishTerminal("workflow.run." + status.name().toLowerCase(Locale.ROOT), leg, Map.of());
            return leg.run();
        }
    }

    /** Traversal ran off the end of the graph. */
    record Completed() implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            leg.run().complete(WorkflowRunStatus.COMPLETED, f.graphSupport.writeTrace(leg.trace()), null,
                leg.fileOutput()[0], leg.fileOutput()[1]);
            f.runRepository.save(leg.run());
            f.cancellationService.clearCancellationFlag(leg.run().getId());
            f.publishTerminal("workflow.run.completed", leg, Map.of());
            return leg.run();
        }
    }

    /** An exception escaped the traversal; {@code failedNode} may be null (failure before the trigger resolved). */
    record Failed(String failedNodeId, WorkflowNodeDto failedNode, String activeInput, RuntimeException error)
        implements RunOutcome {
        @Override
        public WorkflowRun finalizeWith(RunFinalizer f, Leg leg) {
            String type = failedNode != null ? failedNode.type() : "engine";
            String label = failedNode != null ? failedNode.label() : "Engine";
            leg.trace().add(f.stamper.stamp(leg.trace(), new NodeTraceEntry(failedNodeId, type, label, "ERROR",
                error.getMessage(), Instant.now(), Instant.now(), List.of("Run failed: " + error.getMessage())),
                failedNode, activeInput, null, NodeTracePayloads.MAX_FIELD_CHARS, null));
            leg.run().complete(WorkflowRunStatus.FAILED, f.graphSupport.writeTrace(leg.trace()), error.getMessage(),
                leg.fileOutput()[0], leg.fileOutput()[1]);
            f.runRepository.save(leg.run());
            f.cancellationService.clearCancellationFlag(leg.run().getId());
            f.failureRecorder.accept(leg.run(), error.getMessage());
            f.publishTerminal("workflow.run.failed", leg, Map.of("error", String.valueOf(error.getMessage())));
            return leg.run();
        }
    }

    private final WorkflowRunRepository runRepository;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowGraphSupport graphSupport;
    private final Supplier<WorkflowWaitService> waitService;
    private final BiConsumer<WorkflowRun, String> failureRecorder;
    private final TraceStamper stamper;

    RunFinalizer(
        WorkflowRunRepository runRepository, WorkflowRunCancellationService cancellationService,
        WorkflowEventPublisher eventPublisher, WorkflowGraphSupport graphSupport,
        Supplier<WorkflowWaitService> waitService, BiConsumer<WorkflowRun, String> failureRecorder,
        TraceStamper stamper
    ) {
        this.runRepository = runRepository;
        this.cancellationService = cancellationService;
        this.eventPublisher = eventPublisher;
        this.graphSupport = graphSupport;
        this.waitService = waitService;
        this.failureRecorder = failureRecorder;
        this.stamper = stamper;
    }

    WorkflowRun finalizeLeg(Leg leg, RunOutcome outcome) {
        return outcome.finalizeWith(this, leg);
    }

    private void publishTerminal(String event, Leg leg, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>(extra);
        payload.put("workflowId", leg.definition().getId().toString());
        eventPublisher.publish(event, leg.run().getId().toString(), payload);
    }

    private void publishPaused(Leg leg, String nodeId) {
        eventPublisher.publish("workflow.run.paused", leg.run().getId().toString(), Map.of(
            "workflowId", leg.definition().getId().toString(), "nodeId", nodeId));
    }
}
