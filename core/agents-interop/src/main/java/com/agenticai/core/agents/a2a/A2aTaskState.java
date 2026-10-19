package com.agenticai.core.agents.a2a;

/**
 * The subset of A2A task states this server can actually reach. Deliberately
 * omits {@code INPUT_REQUIRED}/{@code AUTH_REQUIRED}/{@code REJECTED}: an agent
 * chat turn here either produces a reply, fails, or is canceled - there is no
 * mid-task "ask the caller a question" hop today (a follow-up message on the
 * same {@code contextId} is how a conversation continues), and advertising a
 * state the server never emits would only mislead a client's state machine.
 */
public enum A2aTaskState {
    SUBMITTED("TASK_STATE_SUBMITTED", "submitted"),
    WORKING("TASK_STATE_WORKING", "working"),
    COMPLETED("TASK_STATE_COMPLETED", "completed"),
    FAILED("TASK_STATE_FAILED", "failed"),
    CANCELED("TASK_STATE_CANCELED", "canceled");

    private final String v1;
    private final String v03;

    A2aTaskState(String v1, String v03) {
        this.v1 = v1;
        this.v03 = v03;
    }

    public String wire(A2aVersion version) {
        return version == A2aVersion.V1_0 ? v1 : v03;
    }

    /** COMPLETED/FAILED/CANCELED - no further transition is possible, cancel is refused. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
