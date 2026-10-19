package com.agenticai.core.agents.hooks;

/**
 * A thread-bound "did the raw tool actually run" flag shared by the two halves of a tool's hook wrappers.
 *
 * <p>The inner {@link HookingToolCallback} sits directly around the raw tool and calls {@link #ran()} once the
 * tool returned; the outer {@link HookingResultToolCallback} sits around the guard, brackets the guarded call with
 * {@link #begin()} / {@link #end(boolean)}, and reads whether the tool ran. That distinguishes a real result
 * (hooks apply) from a guard's synthetic one - "requires approval", "blocked", "step limit" - that must pass
 * through untouched.
 *
 * <p>A thread-local is safe here because the whole chain is synchronous on one thread. It nests correctly: a
 * delegation tool runs a collaborator's tools on the same thread, inside the outer call, and each bracket saves
 * and restores the enclosing call's flag.
 */
final class ToolRunMarker {

    private static final ThreadLocal<boolean[]> FLAG = ThreadLocal.withInitial(() -> new boolean[1]);

    private ToolRunMarker() {
    }

    /** Opens a bracket. @return the enclosing bracket's flag, to hand back to {@link #end}. */
    static boolean begin() {
        boolean[] flag = FLAG.get();
        boolean previous = flag[0];
        flag[0] = false;
        return previous;
    }

    /** The raw tool returned. */
    static void ran() {
        FLAG.get()[0] = true;
    }

    /** Closes the bracket. @return whether the raw tool ran inside it. */
    static boolean end(boolean previous) {
        boolean[] flag = FLAG.get();
        boolean ran = flag[0];
        flag[0] = previous;
        return ran;
    }
}
