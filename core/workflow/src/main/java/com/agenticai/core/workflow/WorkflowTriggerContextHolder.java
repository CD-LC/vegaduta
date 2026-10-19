package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import java.util.Optional;

/**
 * Run-scoped carrier for the {@link InboundTriggerContext} of a channel-
 * triggered workflow run - i.e. "who do I reply to?" for an inbound message
 * that kicked off this run. Set by {@code WorkflowTriggerPortImpl} on the
 * background thread it runs the workflow on, read by {@code
 * WorkflowNodeExecutor#executeOutputNode} when a {@code
 * Output(channel=whatsapp)} node has no explicit destination and should reply
 * to the sender instead.
 *
 * <p><b>Why a {@link ThreadLocal} and not a threaded-through parameter</b>: this
 * follows the exact pattern this module already uses for the same problem - the
 * synchronous {@link WorkflowExecutionEngine} walks a run's whole graph on one
 * thread (the one {@code WorkflowTriggerPortImpl} starts), so a {@code
 * ThreadLocal} set there is visible to every node's execution without changing
 * {@code WorkflowService#run}, both execution engines' {@code execute(...)}
 * signatures, and {@code WorkflowNodeExecutor#executeNode}. It mirrors how
 * {@code TenantContext} is already snapshotted across this module's one async
 * boundary and how {@code WorkflowNodeExecutor}'s {@code WORKFLOW_CALL_STACK}
 * is scoped.
 *
 * <p><b>The two thread boundaries this must be propagated across</b>, exactly as
 * {@code TenantContext} is:
 * <ul>
 *   <li>The initial trigger hop - {@code WorkflowTriggerPortImpl.triggerAsync}
 *       sets it inside its {@code CompletableFuture.runAsync} body.
 *   <li>Parallel fan-out - {@link WorkflowExecutionEngine} runs each branch node
 *       on its own {@code CompletableFuture}, so it snapshots this and re-sets it
 *       inside each branch (see that class's parallel-node handling).
 * </ul>
 * The Loop and normal linear paths stay on the trigger thread, so they need no
 * extra handling.
 *
 * <p><b>Temporal caveat</b>: the opt-in Temporal execution path
 * ({@code app.workflow.execution-engine=temporal}) runs each node as a separate
 * Activity, potentially on a different worker thread/process, so this {@code
 * ThreadLocal} does not reach it - a channel-triggered workflow executed under
 * Temporal falls back to the Output node's explicit number and, if none is set,
 * the same "missing destination" error as before. This is the identical,
 * documented limitation that {@code WORKFLOW_CALL_STACK} already carries; the
 * default in-process engine (which every channel trigger uses today) is fully
 * covered.
 */
public final class WorkflowTriggerContextHolder {

    private static final ThreadLocal<InboundTriggerContext> CURRENT = new ThreadLocal<>();

    private WorkflowTriggerContextHolder() {
    }

    /** Sets (or, with {@code null}, clears) the current thread's trigger context. */
    public static void set(InboundTriggerContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static Optional<InboundTriggerContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
