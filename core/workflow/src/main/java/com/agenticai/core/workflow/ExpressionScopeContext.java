package com.agenticai.core.workflow;

/**
 * Thread-bound {@link ExpressionScope} for the node about to execute, read by {@link WorkflowNodeExecutor}.
 * A ThreadLocal rather than one more {@code executeNode} overload on purpose: the engines call
 * {@code executeNode} from loop, fan-out and Temporal activity threads, several tests stub its existing
 * signatures, and this is the same shape {@code NodeStreamContext} and {@code ApprovalReplayGrant} already
 * use for per-execution context. Callers must pair {@link #bind} with {@link #clear} in a finally block.
 */
public final class ExpressionScopeContext {

    private static final ThreadLocal<ExpressionScope> CURRENT = new ThreadLocal<>();

    private ExpressionScopeContext() {
    }

    public static void bind(ExpressionScope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }

    /** The bound scope, or null for an ordinary node execution. */
    public static ExpressionScope current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
