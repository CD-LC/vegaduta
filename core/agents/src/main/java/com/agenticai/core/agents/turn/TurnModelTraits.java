package com.agenticai.core.agents.turn;

/**
 * What is known about the model ONE chat turn runs on, set once client selection has chosen it and
 * read by the stages that adapt to the model, shared by reference like {@link TurnReplyOutcome} and
 * {@link TurnToolActivity}.
 *
 * <p>Today that is one fact: whether the model is a <em>weak tool caller</em>, the kind that
 * announces a step ("Let me create the file:") and then ends its reply without making the call. A
 * platform-hosted pool row is one when it runs in small-model mode or was measured to make tool calls
 * only as text; a bring-your-own provider is one when it is listed in {@code
 * app.chat.continuation.heuristic-providers} (local servers). A frontier model's text-only reply is an
 * answer, so the wording-based "unfinished reply" check is applied only to these.
 */
public final class TurnModelTraits {

    private volatile boolean weakToolCaller;

    /** True for a model whose tool-less reply may be an announced step it never took. */
    public boolean weakToolCaller() {
        return weakToolCaller;
    }

    public void weakToolCaller(boolean weak) {
        this.weakToolCaller = weak;
    }
}
