package com.agenticai.core.workflow.stream;

/**
 * Binds the {@link NodeStreamSink} of the node currently executing to the
 * executing thread, so {@code WorkflowNodeExecutor} can stream an agent's
 * tokens without every node handler's signature growing a sink parameter.
 * Same shape and lifecycle as {@code WorkflowTriggerContextHolder}: the
 * engine binds around each {@code executeNode} call (on whichever thread runs
 * it - main traversal, a parallel branch, a fan-out worker) and always clears
 * in {@code finally}.
 *
 * <p>Readers must capture {@link #current()} on the CALLING thread before
 * subscribing to a reactive stream: Reactor delivers tokens on its own
 * threads, where this ThreadLocal is empty.
 */
public final class NodeStreamContext {

    private static final ThreadLocal<NodeStreamSink> CURRENT = new ThreadLocal<>();

    private NodeStreamContext() {
    }

    public static void bind(NodeStreamSink sink) {
        CURRENT.set(sink);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** The bound sink, or {@link NodeStreamSink#NOOP} - never null. */
    public static NodeStreamSink current() {
        NodeStreamSink sink = CURRENT.get();
        return sink == null ? NodeStreamSink.NOOP : sink;
    }
}
