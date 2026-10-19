package com.agenticai.core.agents.turnjournal;

/** The stage a {@link TurnEvent} records; see ADR-0020. */
public enum TurnEventType {
    INTAKE,
    GUARD,
    MODEL_CALL,
    TOOL_CALL,
    APPROVAL,
    VERIFY,
    COMMIT
}
