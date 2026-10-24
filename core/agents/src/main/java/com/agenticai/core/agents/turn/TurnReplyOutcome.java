package com.agenticai.core.agents.turn;

import java.util.concurrent.atomic.AtomicReference;

/**
 * How the reply of ONE chat turn ended, shared by reference between the stage that produces the
 * reply (the FAST stream, a buffered depth) and the stages that decide what to do with it afterwards
 * (what is stored in the conversation, whether it is cached, whether the quality judge sees it). It
 * is the one mutable member of {@link TurnContext}, like the approval state: a turn that completes
 * its stream normally is not necessarily a turn that produced an answer.
 *
 * <p>Only {@link Kind#COMPLETE} is an answer. A reply cut off by the output limit, stopped by the
 * provider's safety system, closed after a failure, or replaced by the "no response" line must not be
 * served to the next person who asks the same question, and must not be recorded as something the
 * agent said.
 *
 * <p>{@link #begin()} runs when a reply attempt starts, so a turn that is re-run on the next
 * provider of the hosted chain reports the attempt that produced the reply it keeps.
 */
public final class TurnReplyOutcome {

    /** How a reply ended. */
    public enum Kind {
        /** The model finished its answer. */
        COMPLETE,
        /** The reply is cut off: the model hit its output limit and the continuation passes did not finish it. */
        TRUNCATED,
        /** The provider's safety system stopped the reply. */
        REFUSED,
        /** The model's tool call was unusable and retrying did not fix it. */
        MALFORMED_TOOL_CALL,
        /** The model returned nothing and the "no response" line was shown instead. */
        EMPTY,
        /** A failure ended the reply after some text had been sent. */
        INTERRUPTED
    }

    /** How the reply ended and, where there is one, the provider's own word for it. */
    public record State(Kind kind, String detail) {
        static final State COMPLETE = new State(Kind.COMPLETE, null);
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.COMPLETE);

    /** Set when the streamed reply is held back to be checked against the output guardrails (see below). */
    private volatile boolean heldForOutputCheck;

    /** What a held reply was replaced by, when its check withheld it; {@code null} = the reply stands. */
    private final AtomicReference<String> replacement = new AtomicReference<>();

    /** Starts a reply attempt: whatever the attempt before it ended with no longer applies. */
    public void begin() {
        state.set(State.COMPLETE);
        replacement.set(null);
    }

    /**
     * Marks the streamed (FAST) reply of this turn as held back for its output-guardrail check:
     * {@code app.chat.parity.fast-buffering-triggers} contains {@code OUTPUT_GUARDRAILS} and the agent has
     * output guardrails. The stage that completes the reply then checks it and, if it must be withheld,
     * records what replaces it ({@link #replaceWith}); the stage that sends it reads {@link #replacement()}.
     */
    public void holdForOutputCheck(boolean held) {
        this.heldForOutputCheck = held;
    }

    public boolean isHeldForOutputCheck() {
        return heldForOutputCheck;
    }

    /** Records that the held reply is withheld and {@code text} is what the person gets (and what is stored). */
    public void replaceWith(String text) {
        replacement.set(text);
    }

    /** What replaces the held reply, or {@code null} when it stands as written. */
    public String replacement() {
        return replacement.get();
    }

    /** Records that the reply ended as {@code outcome}. */
    public void mark(Kind outcome) {
        mark(outcome, null);
    }

    /**
     * Records that the reply ended as {@code outcome}; {@code detail} is the provider's finish
     * reason for a {@link Kind#REFUSED} reply, so the message shown can name it.
     */
    public void mark(Kind outcome, String detail) {
        state.set(outcome == null || outcome == Kind.COMPLETE ? State.COMPLETE : new State(outcome, detail));
    }

    public Kind kind() {
        return state.get().kind();
    }

    /** The provider's reason recorded with the outcome, or {@code null}. */
    public String detail() {
        return state.get().detail();
    }

    /** True when the reply is a finished answer: safe to cache, to store as said, to judge. */
    public boolean isComplete() {
        return state.get().kind() == Kind.COMPLETE;
    }

    /** The current state, to put back with {@link #restore} when a later attempt's reply is not kept. */
    public State snapshot() {
        return state.get();
    }

    /** Puts back a state taken with {@link #snapshot}. */
    public void restore(State previous) {
        state.set(previous == null ? State.COMPLETE : previous);
    }
}
