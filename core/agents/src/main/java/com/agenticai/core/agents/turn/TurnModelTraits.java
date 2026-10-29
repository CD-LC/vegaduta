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

    private volatile boolean stableToolSet;

    private volatile boolean toolDeferral;

    private volatile java.time.Duration streamIdleTimeout;

    /** True for a model whose tool-less reply may be an announced step it never took. */
    public boolean weakToolCaller() {
        return weakToolCaller;
    }

    public void weakToolCaller(boolean weak) {
        this.weakToolCaller = weak;
    }

    /**
     * True when the turn offers the agent's whole tool set in a fixed order instead of a per-message selection:
     * a local model server keeps one prompt cache and renders tools ahead of the system prompt, so a tool set that
     * changes with the message re-reads the whole prompt (seconds per thousand tokens on a laptop) where a stable
     * one is answered from cache. Set from {@code app.chat.local-model.stable-tools}.
     */
    public boolean stableToolSet() {
        return stableToolSet;
    }

    public void stableToolSet(boolean stable) {
        this.stableToolSet = stable;
    }

    /**
     * True when this turn's model forces on-demand tool loading (the local model's {@code
     * toolDeferral} row setting, V674), regardless of {@code app.tools.search.enabled} - a model
     * that re-reads its prompt at a few hundred tokens a second should not carry every tool
     * schema on every turn.
     */
    public boolean toolDeferral() {
        return toolDeferral;
    }

    public void toolDeferral(boolean deferral) {
        this.toolDeferral = deferral;
    }

    /**
     * The model's own stream idle-timeout (the local model's {@code responseTimeoutMs} row
     * setting, V674), or null for {@code app.llm.http.stream-idle-timeout}. Set per attempt - a
     * hosted-chain failover re-points it at the next link's row, so each model's own patience
     * applies to its own stream.
     */
    public java.time.Duration streamIdleTimeout() {
        return streamIdleTimeout;
    }

    public void streamIdleTimeout(java.time.Duration timeout) {
        this.streamIdleTimeout = timeout;
    }
}
